package dev.yu.mwi.storage;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Small weighted LRU with one short internal lock. Callers must never perform I/O while invoking
 * this class, and this class never calls back into persistence code.
 */
final class WeightedLruCache<K, V> {
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(64, 0.75f, true);
    private final int maxEntries;
    private final long maxBytes;
    private long bytes;
    private long hits;
    private long misses;
    private long evictions;
    private long oversizedRejected;

    WeightedLruCache(int maxEntries, long maxBytes) {
        if (maxEntries < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("cache limits must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            misses++;
            return null;
        }
        hits++;
        return entry.value();
    }

    /** Installs a value unless the current value wins according to {@code currentWins}. */
    synchronized V put(K key, V value, long weight, BiPredicate<V, V> currentWins) {
        if (weight < 0) {
            throw new IllegalArgumentException("cache weight must not be negative");
        }
        Entry<V> current = entries.get(key);
        if (current != null && currentWins.test(current.value(), value)) {
            return current.value();
        }
        removeInternal(key);
        if (weight > maxBytes) {
            oversizedRejected++;
            return value;
        }
        entries.put(key, new Entry<>(value, weight));
        try {
            bytes = Math.addExact(bytes, weight);
        } catch (ArithmeticException overflow) {
            entries.remove(key);
            throw new IllegalStateException("Profile cache invariant failure: byte weight overflow", overflow);
        }
        evictToLimits();
        return value;
    }

    synchronized void remove(K key) {
        removeInternal(key);
    }

    synchronized void removeIf(Predicate<K> predicate) {
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, Entry<V>> entry = iterator.next();
            if (predicate.test(entry.getKey())) {
                bytes -= entry.getValue().weight();
                iterator.remove();
            }
        }
    }

    synchronized CacheStats stats() {
        if (bytes < 0 || entries.size() > maxEntries || bytes > maxBytes) {
            throw new IllegalStateException("Profile cache invariant failure: entries=" + entries.size()
                    + ", bytes=" + bytes + ", maxEntries=" + maxEntries + ", maxBytes=" + maxBytes);
        }
        return new CacheStats(hits, misses, entries.size(), bytes, maxBytes,
                evictions, oversizedRejected, maxEntries);
    }

    synchronized Invariant invariant() {
        long summed = entries.values().stream().mapToLong(Entry::weight).sum();
        boolean valid = bytes >= 0 && bytes == summed && entries.size() <= maxEntries && bytes <= maxBytes;
        String description = valid ? "valid" : "entries=" + entries.size() + ", bytes=" + bytes
                + ", summedWeights=" + summed + ", maxEntries=" + maxEntries + ", maxBytes=" + maxBytes;
        return new Invariant(valid, description, entries.size(), bytes, maxBytes);
    }

    private void evictToLimits() {
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || bytes > maxBytes) && iterator.hasNext()) {
            Map.Entry<K, Entry<V>> eldest = iterator.next();
            bytes -= eldest.getValue().weight();
            iterator.remove();
            evictions++;
        }
    }

    private void removeInternal(K key) {
        Entry<V> removed = entries.remove(key);
        if (removed != null) {
            bytes -= removed.weight();
        }
    }

    private record Entry<V>(V value, long weight) {
    }

    record Invariant(boolean valid, String description, int entries, long bytes, long maxBytes) {
    }
}
