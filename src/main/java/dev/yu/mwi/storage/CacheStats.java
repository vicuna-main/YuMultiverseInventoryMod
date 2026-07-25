package dev.yu.mwi.storage;

/** Immutable profile-cache metrics. */
public record CacheStats(long hits, long misses, int entries, long bytes, long maxBytes) {
}
