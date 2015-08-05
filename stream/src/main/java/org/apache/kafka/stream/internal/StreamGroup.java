package org.apache.kafka.stream.internal;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.processor.Processor;
import org.apache.kafka.clients.processor.ProcessorContext;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.stream.Chooser;
import org.apache.kafka.stream.TimestampExtractor;
import org.apache.kafka.stream.topology.internal.KStreamSource;
import org.apache.kafka.stream.util.MinTimestampTracker;
import org.apache.kafka.stream.util.ParallelExecutor;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A StreamGroup is composed of multiple streams from different topics that need to be synchronized.
 */
<<<<<<< HEAD:src/main/java/io/confluent/streaming/internal/StreamSynchronizer.java
<<<<<<< HEAD
public class StreamSynchronizer<K, V> implements ParallelExecutor.Task {

  public final String name;
  private final Ingestor ingestor;
  private final Chooser<K, V> chooser;
  private final TimestampExtractor<K, V> timestampExtractor;
  private final Map<TopicPartition, RecordQueue<K, V>> stash = new HashMap<>();
=======
public class StreamSynchronizer implements SyncGroup {
=======
public class StreamGroup implements ParallelExecutor.Task {
>>>>>>> remove SyncGroup from user facing APIs:src/main/java/io/confluent/streaming/internal/StreamGroup.java

  private final ProcessorContext context;
  private final Ingestor ingestor;
  private final Chooser chooser;
  private final TimestampExtractor timestampExtractor;
  private final Map<TopicPartition, RecordQueue> stash = new HashMap<>();
>>>>>>> removed some generics
  private final int desiredUnprocessed;

  // TODO: merge stash, consumedOffset, and newRecordBuffer into sth. like partition metadata
  private final Map<TopicPartition, Long> consumedOffsets;
  private final PunctuationQueue punctuationQueue = new PunctuationQueue();
  private final ArrayDeque<NewRecords<K, V>> newRecordBuffer = new ArrayDeque<>();

  private long streamTime = -1;
  private boolean commitRequested = false;
  private StampedRecord currRecord = null;
  private volatile int buffered = 0;

  /**
   * Creates StreamGroup
   * @param context the task context
   * @param ingestor the instance of {@link Ingestor}
   * @param chooser the instance of {@link Chooser}
   * @param timestampExtractor the instance of {@link TimestampExtractor}
   * @param desiredUnprocessedPerPartition the target number of records kept in a queue for each topic
   */
  StreamGroup(ProcessorContext context,
              Ingestor ingestor,
              Chooser chooser,
              TimestampExtractor timestampExtractor,
              int desiredUnprocessedPerPartition) {
    this.context = context;
    this.ingestor = ingestor;
    this.chooser = chooser;
    this.timestampExtractor = timestampExtractor;
    this.desiredUnprocessed = desiredUnprocessedPerPartition;
    this.consumedOffsets = new HashMap<>();
<<<<<<< HEAD
=======
  }

<<<<<<< HEAD
<<<<<<< HEAD
  public String name() {
    return name;
>>>>>>> removed some generics
  }

=======
>>>>>>> new api model
=======
>>>>>>> new api model
  public StampedRecord record() { return currRecord; }

  /**
   * Merges a stream group into this group
   */
  public void mergeStreamGroup(StreamGroup other) {
    // check these groups have the same ingestor
    if (ingestor != other.ingestor)
      throw new IllegalArgumentException("groups with different ingestors cannot be merged");

    // check these group have the same chooser and time extractor types
    if (!this.chooser.getClass().equals(other.chooser.getClass()))
      throw new IllegalArgumentException("groups with different type of choosers cannot be merged");

    if (!this.timestampExtractor.getClass().equals(other.timestampExtractor.getClass()))
      throw new IllegalArgumentException("groups with different type of time extractors cannot be merged");

    // add all the other's groups partitions
    this.stash.putAll(other.stash);
  }

  /**
   * Adds a partition and its receiver to this stream synchronizer
   * @param partition the partition
   * @param stream the instance of KStreamImpl
   */
  @SuppressWarnings("unchecked")
  public void addPartition(TopicPartition partition, KStreamSource stream) {
    synchronized (this) {
      RecordQueue recordQueue = stash.get(partition);

      if (recordQueue == null) {
        stash.put(partition, createRecordQueue(partition, stream));
      } else {
        throw new IllegalStateException("duplicate partition");
      }
    }
  }

<<<<<<< HEAD
<<<<<<< HEAD
  public void addRecords(TopicPartition partition, Iterator<ConsumerRecord<K, V>> iterator) {
=======
=======
  /**
   * Adds records
   * @param partition the partition
   * @param iterator the iterator of records
   */
>>>>>>> javadoc
  @SuppressWarnings("unchecked")
<<<<<<< HEAD
  public void addRecords(TopicPartition partition, Iterator<ConsumerRecord<Object, Object>> iterator) {
>>>>>>> removed some generics
=======
  public void addRecords(TopicPartition partition, Iterator<ConsumerRecord<byte[], byte[]>> iterator) {
>>>>>>> allow deserializer override at KStream construction
    synchronized (this) {
      newRecordBuffer.addLast(new NewRecords(partition, iterator));
    }
  }

  @SuppressWarnings("unchecked")
  private void ingestNewRecords() {
    for (NewRecords<K, V> newRecords : newRecordBuffer) {
      TopicPartition partition = newRecords.partition;
<<<<<<< HEAD
      Iterator<ConsumerRecord<K, V>> iterator = newRecords.iterator;
=======
      Iterator<ConsumerRecord<byte[], byte[]>> iterator = newRecords.iterator;
>>>>>>> allow deserializer override at KStream construction

      RecordQueue recordQueue = stash.get(partition);
      if (recordQueue != null) {
        boolean wasEmpty = recordQueue.isEmpty();

        while (iterator.hasNext()) {
<<<<<<< HEAD
          ConsumerRecord<Object, Object> record = iterator.next();
          long timestamp = timestampExtractor.extract(record.topic(), record.key(), record.value());
<<<<<<< HEAD
          recordQueue.add(new StampedRecord<>(record, timestamp));
=======
          recordQueue.add(new StampedRecord(record, timestamp));
>>>>>>> removed some generics
=======
          ConsumerRecord<byte[], byte[]> record = iterator.next();

          // deserialize the raw record, extract the timestamp and put into the queue
          Deserializer<?> keyDeserializer = recordQueue.stream.keyDeserializer();
          Deserializer<?> valDeserializer = recordQueue.stream.valueDeserializer();

          Object key = keyDeserializer.deserialize(record.topic(), record.key());
          Object value = valDeserializer.deserialize(record.topic(), record.value());
          ConsumerRecord deserializedRecord = new ConsumerRecord<>(record.topic(), record.partition(), record.offset(), key, value);

          long timestamp = timestampExtractor.extract(record.topic(), key, value);
          recordQueue.add(new StampedRecord(deserializedRecord, timestamp));
>>>>>>> allow deserializer override at KStream construction
          buffered++;
        }

        int queueSize = recordQueue.size();
        if (wasEmpty && queueSize > 0) chooser.add(recordQueue);

        // if we have buffered enough for this partition, pause
        if (queueSize >= this.desiredUnprocessed) {
          ingestor.pause(partition);
        }
      }
    }
    newRecordBuffer.clear();
  }

  /**
   * Schedules a punctuation for the processor
   * @param processor the processor requesting scheduler
   * @param interval the interval in milliseconds
   */
  public void schedule(Processor<?, ?> processor, long interval) {
    punctuationQueue.schedule(new PunctuationSchedule(processor, interval));
  }

  /**
   * Processes one record
   */
  @SuppressWarnings("unchecked")
  @Override
  public boolean process() {
    synchronized (this) {
      boolean readyForNextExecution = false;
      ingestNewRecords();

      RecordQueue recordQueue = chooser.next();
      if (recordQueue == null) {
        return false;
      }

      if (recordQueue.size() == 0) throw new IllegalStateException("empty record queue");

      if (recordQueue.size() == this.desiredUnprocessed) {
        ingestor.unpause(recordQueue.partition(), recordQueue.offset());
      }

      long trackedTimestamp = recordQueue.trackedTimestamp();
      currRecord = recordQueue.next();

      if (streamTime < trackedTimestamp) streamTime = trackedTimestamp;

      recordQueue.stream.receive(currRecord.key(), currRecord.value(), currRecord.timestamp);
      consumedOffsets.put(recordQueue.partition(), currRecord.offset());

      // TODO: local state flush and downstream producer flush
      // need to be done altogether with offset commit atomically
      if (commitRequested) {
        // flush local state
        context.flush();

        // flush produced records in the downstream
        context.recordCollector().flush();

        // commit consumed offsets
        ingestor.commit(consumedOffsets());
      }

      if (commitRequested) ingestor.commit(Collections.singletonMap(
          new TopicPartition(currRecord.topic(), currRecord.partition()),
          currRecord.offset()));

      if (recordQueue.size() > 0) {
        readyForNextExecution = true;
        chooser.add(recordQueue);
      }


      buffered--;
      currRecord = null;

      punctuationQueue.mayPunctuate(streamTime);

      return readyForNextExecution;
    }
  }

  /**
   * Returns consumed offsets
   * @return the map of partition to consumed offset
   */
  public Map<TopicPartition, Long> consumedOffsets() {
    return this.consumedOffsets;
  }

  /**
   * Request committing the current record's offset
   */
  public void commitOffset() {
    this.commitRequested = true;
  }

  public int buffered() {
    return buffered;
  }

  public void close() {
    chooser.close();
    stash.clear();
  }

  protected RecordQueue createRecordQueue(TopicPartition partition, KStreamSource stream) {
    return new RecordQueue(partition, stream, new MinTimestampTracker<>());
  }

  private static class NewRecords {
    final TopicPartition partition;
    final Iterator<ConsumerRecord<byte[], byte[]>> iterator;

    NewRecords(TopicPartition partition, Iterator<ConsumerRecord<byte[], byte[]>> iterator) {
      this.partition = partition;
      this.iterator = iterator;
    }
  }
}