package dev.yu.mwi.config;

import java.util.Locale;
import java.util.Objects;

public record RuntimeConfig(
        int schemaVersion,
        MwiConfig model,
        Storage storage,
        MvinvMode mvinvMode,
        String mvinvDataDirectory
) {
    public RuntimeConfig {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported configuration schema: " + schemaVersion);
        }
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(mvinvMode, "mvinvMode");
        mvinvDataDirectory = Objects.requireNonNull(mvinvDataDirectory, "mvinvDataDirectory").strip();
        if (mvinvDataDirectory.isEmpty() || mvinvDataDirectory.length() > 4_096
                || mvinvDataDirectory.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("mvinv_compat.data_directory must be a non-empty valid path");
        }
    }

    public static RuntimeConfig defaults() {
        return new RuntimeConfig(1, MwiConfig.defaults(), Storage.defaults(), MvinvMode.IMPORT_AND_REPLACE,
                "plugins/Multiverse-Inventories");
    }

    public record Storage(
            int maxRevisions,
            int cacheEntries,
            int cacheMegabytes,
            int ioConcurrency,
            int queueCapacity,
            int flushTimeoutSeconds,
            boolean automaticRecovery,
            int recoveryRetrySeconds,
            String restoreTimeZone) {
        public Storage(int maxRevisions, int cacheEntries, int cacheMegabytes, int ioConcurrency,
                int queueCapacity, int flushTimeoutSeconds, boolean automaticRecovery, int recoveryRetrySeconds) {
            this(maxRevisions, cacheEntries, cacheMegabytes, ioConcurrency, queueCapacity, flushTimeoutSeconds,
                    automaticRecovery, recoveryRetrySeconds, "Asia/Shanghai");
        }
        public Storage(int maxRevisions, int cacheEntries, int cacheMegabytes, int ioConcurrency,
                int queueCapacity, int flushTimeoutSeconds) {
            this(maxRevisions, cacheEntries, cacheMegabytes, ioConcurrency, queueCapacity, flushTimeoutSeconds, true, 30);
        }
        public Storage(int maxRevisions, int cacheEntries, int cacheMegabytes) {
            this(maxRevisions, cacheEntries, cacheMegabytes, 2, 256, 30);
        }

        public Storage {
            java.time.ZoneId.of(Objects.requireNonNull(restoreTimeZone));
            if (maxRevisions < 1 || maxRevisions > 1_000) {
                throw new IllegalArgumentException("storage.max_revisions must be between 1 and 1000");
            }
            if (cacheEntries < 16 || cacheEntries > 100_000) {
                throw new IllegalArgumentException("storage.cache_entries must be between 16 and 100000");
            }
            if (cacheMegabytes < 16 || cacheMegabytes > 4_096) {
                throw new IllegalArgumentException("storage.cache_megabytes must be between 16 and 4096");
            }
            if (ioConcurrency < 1 || ioConcurrency > 8) {
                throw new IllegalArgumentException("storage.io_concurrency must be between 1 and 8");
            }
            if (queueCapacity < ioConcurrency || queueCapacity > 100_000) {
                throw new IllegalArgumentException(
                        "storage.queue_capacity must be between io_concurrency and 100000");
            }
            if (flushTimeoutSeconds < 1 || flushTimeoutSeconds > 300) {
                throw new IllegalArgumentException("storage.flush_timeout_seconds must be between 1 and 300");
            }
            if (recoveryRetrySeconds < 5 || recoveryRetrySeconds > 3600)
                throw new IllegalArgumentException("storage.recovery_retry_seconds must be between 5 and 3600");
        }

        public static Storage defaults() {
            return new Storage(20, 1_024, 128, 2, 256, 30);
        }
    }

    public enum MvinvMode {
        IMPORT_AND_REPLACE,
        DELEGATE_VANILLA;

        public static MvinvMode parse(String value) {
            Objects.requireNonNull(value, "mvinv mode");
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "mvinv_compat.mode must be import_and_replace or delegate_vanilla", invalid);
            }
        }
    }
}
