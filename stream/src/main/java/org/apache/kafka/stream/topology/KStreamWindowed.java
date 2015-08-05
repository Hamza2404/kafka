package org.apache.kafka.stream.topology;

import org.apache.kafka.stream.KStream;

/**
 * KStreamWindowed is an abstraction of a stream of key-value pairs with a window.
 */
public interface KStreamWindowed<K, V> extends KStream<K, V> {

  /**
   * Creates a new stream by joining this windowed stream with the other windowed stream.
   * Each element arrived from either of the streams is joined with elements in a window of each other.
   * The resulting values are computed by applying a joiner.
   *
   * @param other the other windowed stream
   * @param joiner ValueJoiner
   * @param <V1> the value type of the other stream
   * @param <V2> the value type of the new stream
   * @return KStream
   */
  <V1, V2> KStream<K, V2> join(KStreamWindowed<K, V1> other, ValueJoiner<V2, V, V1> joiner);

  /**
   * Creates a new stream by joining this windowed stream with the other windowed stream.
   * Each element arrived from either of the streams is joined with elements in a window of each other if
   * the element from the other stream has an older timestamp.
   * The resulting values are computed by applying a joiner.
   *
   * @param other the other windowed stream
   * @param joiner the instance ValueJoiner
   * @param <V1> the value type of the other stream
   * @param <V2> the value type of the new stream
   * @return KStream
   */
  <V1, V2> KStream<K, V2> joinPrior(KStreamWindowed<K, V1> other, ValueJoiner<V2, V, V1> joiner);

}