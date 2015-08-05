package org.apache.kafka.stream.kv;

import org.apache.kafka.clients.processor.ProcessorContext;
import org.apache.kafka.common.utils.SystemTime;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.stream.kv.internal.MeteredKeyValueStore;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * An in-memory key-value store based on a TreeMap
 *
 * @param <K> The key type
 * @param <V> The value type
 */
public class InMemoryKeyValueStore<K, V> extends MeteredKeyValueStore<K, V> {

    public InMemoryKeyValueStore(String name, ProcessorContext context) {
        this(name, context, new SystemTime());
    }

    public InMemoryKeyValueStore(String name, ProcessorContext context, Time time) {
        super(name, new MemoryStore<K, V>(name, context), context, "kafka-streams", time);
    }

    private static class MemoryStore<K, V> implements KeyValueStore<K, V> {

        private final String name;
        private final NavigableMap<K, V> map;
        private final ProcessorContext context;

        @SuppressWarnings("unchecked")
        public MemoryStore(String name, ProcessorContext context) {
            super();
            this.name = name;
            this.map = new TreeMap<>();
            this.context = context;
        }

        @Override
        public String name() {
            return this.name;
        }

        @Override
        public boolean persistent() { return false; }

        @Override
        public V get(K key) {
            return this.map.get(key);
        }

        @Override
        public void put(K key, V value) {
            this.map.put(key, value);
        }

        @Override
        public void putAll(List<Entry<K, V>> entries) {
            for (Entry<K, V> entry : entries)
                put(entry.key(), entry.value());
        }

        @Override
        public void delete(K key) {
            put(key, null);
        }

        @Override
        public KeyValueIterator<K, V> range(K from, K to) {
            return new MemoryStoreIterator<K, V>(this.map.subMap(from, true, to, false).entrySet().iterator());
        }

        @Override
        public KeyValueIterator<K, V> all() {
            return new MemoryStoreIterator<K, V>(this.map.entrySet().iterator());
        }

        @Override
        public void flush() {
            // do-nothing since it is in-memory
        }

        public void restore() {
            // this should not happen since it is in-memory, hence no state to load from disk
            throw new IllegalStateException("This should not happen");
        }

        @Override
        public void close() {
            // do-nothing
        }

        private static class MemoryStoreIterator<K, V> implements KeyValueIterator<K, V> {
            private final Iterator<Map.Entry<K, V>> iter;

            public MemoryStoreIterator(Iterator<Map.Entry<K, V>> iter) {
                this.iter = iter;
            }

            @Override
            public boolean hasNext() {
                return iter.hasNext();
            }

            @Override
            public Entry<K, V> next() {
                Map.Entry<K, V> entry = iter.next();
                return new Entry<>(entry.getKey(), entry.getValue());
            }

            @Override
            public void remove() {
                iter.remove();
            }

            @Override
            public void close() {}

        }
    }
}