package dev.yu.mwi.storage;

/** Immutable profile-cache metrics. */
public record CacheStats(
        long hits,
        long misses,
        int entries,
        long bytes,
        long maxBytes,
        long evictions,
        long oversizedRejected,
        int maxEntries) {

    public CacheStats(long hits, long misses, int entries, long bytes, long maxBytes) {
        this(hits, misses, entries, bytes, maxBytes, 0L, 0L, Integer.MAX_VALUE);
    }
}
