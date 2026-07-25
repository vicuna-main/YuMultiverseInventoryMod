package dev.yu.mwi.storage;

/** Outcome of an idempotent profile write. */
public enum SaveResult {
    WRITTEN,
    UNCHANGED
}
