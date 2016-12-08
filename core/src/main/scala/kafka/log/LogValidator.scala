/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.log

import java.nio.ByteBuffer

import kafka.common.LongRef
import kafka.message.{CompressionCodec, InvalidMessageException, Message, NoCompressionCodec}
import kafka.utils.Logging
import org.apache.kafka.common.errors.InvalidTimestampException
import org.apache.kafka.common.record._

import scala.collection.mutable
import scala.collection.JavaConverters._

private[kafka] object LogValidator extends Logging {

  /**
   * Update the offsets for this message set and do further validation on messages including:
   * 1. Messages for compacted topics must have keys
   * 2. When magic value = 1, inner messages of a compressed message set must have monotonically increasing offsets
   *    starting from 0.
   * 3. When magic value = 1, validate and maybe overwrite timestamps of messages.
   *
   * This method will convert the messages in the following scenarios:
   * A. Magic value of a message = 0 and messageFormatVersion is 1
   * B. Magic value of a message = 1 and messageFormatVersion is 0
   *
   * If no format conversion or value overwriting is required for messages, this method will perform in-place
   * operations and avoid re-compression.
   *
   * Returns a ValidationAndOffsetAssignResult containing the validated message set, maximum timestamp, the offset
   * of the shallow message with the max timestamp and a boolean indicating whether the message sizes may have changed.
   */
  private[kafka] def validateMessagesAndAssignOffsets(logBuffer: MemoryLogBuffer,
                                                      offsetCounter: LongRef,
                                                      now: Long,
                                                      sourceCodec: CompressionCodec,
                                                      targetCodec: CompressionCodec,
                                                      compactedTopic: Boolean = false,
                                                      messageFormatVersion: Byte = Record.CURRENT_MAGIC_VALUE,
                                                      messageTimestampType: TimestampType,
                                                      messageTimestampDiffMaxMs: Long): ValidationAndOffsetAssignResult = {
    if (sourceCodec == NoCompressionCodec && targetCodec == NoCompressionCodec) {
      // check the magic value
      if (!logBuffer.hasMatchingShallowMagic(messageFormatVersion))
        convertAndAssignOffsetsNonCompressed(logBuffer, offsetCounter, compactedTopic, now, messageTimestampType,
          messageTimestampDiffMaxMs, messageFormatVersion)
      else
        // Do in-place validation, offset assignment and maybe set timestamp
        assignOffsetsNonCompressed(logBuffer, offsetCounter, now, compactedTopic, messageTimestampType,
          messageTimestampDiffMaxMs)
    } else {
      // Deal with compressed messages
      // We cannot do in place assignment in one of the following situations:
      // 1. Source and target compression codec are different
      // 2. When magic value to use is 0 because offsets need to be overwritten
      // 3. When magic value to use is above 0, but some fields of inner messages need to be overwritten.
      // 4. Message format conversion is needed.

      // No in place assignment situation 1 and 2
      var inPlaceAssignment = sourceCodec == targetCodec && messageFormatVersion > Record.MAGIC_VALUE_V0

      var maxTimestamp = Record.NO_TIMESTAMP
      val expectedInnerOffset = new LongRef(0)
      val validatedRecords = new mutable.ArrayBuffer[LogRecord]

      for (entry <- logBuffer.asScala) {
        // TODO: Do message set validation?
        for (record <- entry.asScala) {
          if (!record.hasMagic(entry.magic))
            throw new InvalidRecordException(s"Log record magic does not match outer magic ${entry.magic}")

          record.ensureValid()
          validateKey(record, compactedTopic)

          if (!record.hasMagic(Record.MAGIC_VALUE_V0) && messageFormatVersion > Record.MAGIC_VALUE_V0) {
            // No in place assignment situation 3
            // Validate the timestamp
            validateTimestamp(entry, record, now, messageTimestampType, messageTimestampDiffMaxMs)
            // Check if we need to overwrite offset
            if (record.offset != expectedInnerOffset.getAndIncrement())
              inPlaceAssignment = false
            if (record.timestamp > maxTimestamp)
              maxTimestamp = record.timestamp
          }

          if (sourceCodec != NoCompressionCodec && record.isCompressed)
            throw new InvalidMessageException("Compressed outer record should not have an inner record with a " +
              s"compression attribute set: $record")

          // No in place assignment situation 4
          if (!record.hasMagic(messageFormatVersion))
            inPlaceAssignment = false

          validatedRecords += record
        }
      }

      if (!inPlaceAssignment) {
        val builder = MemoryLogBuffer.builderWithRecords(true, messageFormatVersion, offsetCounter.value,
          messageTimestampType, CompressionType.forId(targetCodec.codec), now, validatedRecords.asJava)
        builder.close()
        offsetCounter.addAndGet(validatedRecords.size)
        val info = builder.info

        ValidationAndOffsetAssignResult(
          validatedEntries = builder.build(),
          maxTimestamp = info.maxTimestamp,
          offsetOfMaxTimestamp = info.offsetOfMaxTimestamp,
          messageSizeMaybeChanged = true)
      } else {
        // ensure the inner messages are valid
        validatedRecords.foreach(_.ensureValid)

        // we can update the wrapper message only and write the compressed payload as is
        val entry = logBuffer.shallowIterator.next()
        val firstOffset = offsetCounter.value
        val lastOffset = offsetCounter.addAndGet(validatedRecords.size) - 1

        if (messageFormatVersion > Record.MAGIC_VALUE_V1)
          entry.setOffset(firstOffset)
        else
          entry.setOffset(lastOffset)

        if (messageTimestampType == TimestampType.CREATE_TIME)
          entry.setCreateTime(maxTimestamp)
        else if (messageTimestampType == TimestampType.LOG_APPEND_TIME)
          entry.setLogAppendTime(now)

        ValidationAndOffsetAssignResult(validatedEntries = logBuffer,
          maxTimestamp = if (messageTimestampType == TimestampType.LOG_APPEND_TIME) now else maxTimestamp,
          offsetOfMaxTimestamp = lastOffset,
          messageSizeMaybeChanged = false)
      }
    }
  }

  private def convertAndAssignOffsetsNonCompressed(logBuffer: MemoryLogBuffer,
                                                   offsetCounter: LongRef,
                                                   compactedTopic: Boolean,
                                                   now: Long,
                                                   timestampType: TimestampType,
                                                   messageTimestampDiffMaxMs: Long,
                                                   toMagicValue: Byte): ValidationAndOffsetAssignResult = {
    val sizeInBytesAfterConversion = if (toMagicValue > 1)
      EosLogEntry.LOG_ENTRY_OVERHEAD + logBuffer.records.asScala.map(record => EosLogRecord.sizeOf(record.key, record.value)).sum
    else
      logBuffer.shallowIterator.asScala.map { logEntry =>
      if (logEntry.magic > 1)
        logEntry.sizeInBytes() // FIXME: Can we get a better estimate?
      else
        logEntry.sizeInBytes() + Message.headerSizeDiff(logEntry.magic(), toMagicValue)
      }.sum

    val newBuffer = ByteBuffer.allocate(sizeInBytesAfterConversion)
    val builder = MemoryLogBuffer.builder(newBuffer, toMagicValue, CompressionType.NONE, timestampType,
      offsetCounter.value, now)

    for (entry <- logBuffer.asScala) {
      for (record <- entry.asScala) {
        validateKey(record, compactedTopic)
        validateTimestamp(entry, record, now, timestampType, messageTimestampDiffMaxMs)
        builder.append(offsetCounter.getAndIncrement(), record)
      }
    }

    builder.close()
    val info = builder.info

    ValidationAndOffsetAssignResult(
      validatedEntries = builder.build(),
      maxTimestamp = info.maxTimestamp,
      offsetOfMaxTimestamp = info.offsetOfMaxTimestamp,
      messageSizeMaybeChanged = true)
  }

  private def assignOffsetsNonCompressed(logBuffer: MemoryLogBuffer,
                                         offsetCounter: LongRef,
                                         now: Long,
                                         compactedTopic: Boolean,
                                         timestampType: TimestampType,
                                         timestampDiffMaxMs: Long): ValidationAndOffsetAssignResult = {
    var maxTimestamp = Record.NO_TIMESTAMP
    var offsetOfMaxTimestamp = -1L
    val initialOffset = offsetCounter.value

    for (entry <- logBuffer.shallowIterator.asScala) {
      val baseOffset = offsetCounter.value
      for (record <- entry.asScala) {
        record.ensureValid()

        validateKey(record, compactedTopic)
        val offset = offsetCounter.getAndIncrement()

        if (entry.magic > 0) {
          validateTimestamp(entry, record, now, timestampType, timestampDiffMaxMs)

          if (record.timestamp > maxTimestamp) {
            maxTimestamp = record.timestamp
            offsetOfMaxTimestamp = offset
          }
        }
      }

      if (entry.magic > Record.MAGIC_VALUE_V1)
        entry.setOffset(baseOffset)
      else
        entry.setOffset(offsetCounter.value - 1)

      if (entry.magic > 0 && timestampType == TimestampType.LOG_APPEND_TIME)
        entry.setLogAppendTime(now)
    }

    if (timestampType == TimestampType.LOG_APPEND_TIME) {
      maxTimestamp = now
      offsetOfMaxTimestamp = initialOffset
    }

    ValidationAndOffsetAssignResult(
      validatedEntries = logBuffer,
      maxTimestamp = maxTimestamp,
      offsetOfMaxTimestamp = offsetOfMaxTimestamp,
      messageSizeMaybeChanged = false)
  }

  private def validateKey(record: Record, compactedTopic: Boolean) {
    if (compactedTopic && !record.hasKey)
      throw new InvalidMessageException("Compacted topic cannot accept message without key.")
  }

  private def validateKey(record: LogRecord, compactedTopic: Boolean) {
    if (compactedTopic && !record.hasKey)
      throw new InvalidMessageException("Compacted topic cannot accept message without key.")
  }


  /**
   * This method validates the timestamps of a message.
   * If the message is using create time, this method checks if it is within acceptable range.
   */
  private def validateTimestamp(record: Record,
                                now: Long,
                                timestampType: TimestampType,
                                timestampDiffMaxMs: Long) {
    if (timestampType == TimestampType.CREATE_TIME && math.abs(record.timestamp - now) > timestampDiffMaxMs)
      throw new InvalidTimestampException(s"Timestamp ${record.timestamp} of message is out of range. " +
        s"The timestamp should be within [${now - timestampDiffMaxMs}, ${now + timestampDiffMaxMs}")
    if (record.timestampType == TimestampType.LOG_APPEND_TIME)
      throw new InvalidTimestampException(s"Invalid timestamp type in message $record. Producer should not set " +
        s"timestamp type to LogAppendTime.")
  }

  /**
   * This method validates the timestamps of a message.
   * If the message is using create time, this method checks if it is within acceptable range.
   */
  private def validateTimestamp(entry: LogEntry,
                                record: LogRecord,
                                now: Long,
                                timestampType: TimestampType,
                                timestampDiffMaxMs: Long) {
    if (timestampType == TimestampType.CREATE_TIME && math.abs(record.timestamp - now) > timestampDiffMaxMs)
      throw new InvalidTimestampException(s"Timestamp ${record.timestamp} of message is out of range. " +
        s"The timestamp should be within [${now - timestampDiffMaxMs}, ${now + timestampDiffMaxMs}")
    if (entry.timestampType == TimestampType.LOG_APPEND_TIME || record.hasTimestampType(TimestampType.LOG_APPEND_TIME))
      throw new InvalidTimestampException(s"Invalid timestamp type in message $record. Producer should not set " +
        s"timestamp type to LogAppendTime.")
  }

  case class ValidationAndOffsetAssignResult(validatedEntries: MemoryLogBuffer,
                                             maxTimestamp: Long,
                                             offsetOfMaxTimestamp: Long,
                                             messageSizeMaybeChanged: Boolean)

}