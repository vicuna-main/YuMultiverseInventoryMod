package dev.yu.mwi.service;

import dev.yu.mwi.api.SharableAdapter;
import dev.yu.mwi.config.RuntimeConfig;
import dev.yu.mwi.domain.ProfileKey;
import dev.yu.mwi.domain.ScopeKey;
import dev.yu.mwi.domain.WorldIdentity;
import dev.yu.mwi.storage.ProfileStore;
import dev.yu.mwi.storage.ProfileArchive;
import dev.yu.mwi.storage.StoredProfile;
import dev.yu.mwi.storage.WorldProfile;
import dev.yu.mwi.storage.RecoveryInspection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Captures live adapter state once and persists immutable profile batches in order. */
final class ProfileSnapshotWriter implements AutoCloseable {
    private final ProfileStore store;
    private final Logger logger;
    private final ProfilePersistenceQueue queue;
    private final Map<UUID, Map<ProfileKey, StoredProfile>> workingSets = new ConcurrentHashMap<>();
    private final Map<UUID, Map<ProfileKey, String>> unavailable = new ConcurrentHashMap<>();

    ProfileSnapshotWriter(ProfileStore store, Logger logger, RuntimeConfig.Storage storage) {
        this.store = store;
        this.logger = logger;
        this.queue = new ProfilePersistenceQueue(
                storage.ioConcurrency(),
                storage.queueCapacity(),
                Duration.ofSeconds(storage.flushTimeoutSeconds()));
    }

    void preload(UUID playerId) throws IOException {
        if (!workingSets.containsKey(playerId)) {
            workingSets.put(playerId, new ConcurrentHashMap<>(store.loadPlayerProfiles(playerId)));
        }
    }

    Optional<StoredProfile> load(ProfileKey key) {
        if (unavailable.getOrDefault(key.playerId(), Map.of()).containsKey(key))
            throw new IllegalStateException("Profile is waiting for recovery: " + key,
                    new IOException(unavailable.get(key.playerId()).get(key)));
        Map<ProfileKey, StoredProfile> profiles = workingSets.get(key.playerId());
        if (profiles == null) {
            throw new IllegalStateException("Player working set was not preloaded: " + key.playerId());
        }
        return Optional.ofNullable(profiles.get(key));
    }

    /**
     * Returns whether MWI already owns any canonical profile for this share. A login-time live
     * snapshot is only a safe bootstrap source before the first profile for the share exists; once
     * any scope is present, the snapshot's scope provenance is unknown until canonical state is
     * applied.
     */
    boolean hasAnyProfileForShare(UUID playerId, String shareId) {
        Map<ProfileKey, StoredProfile> profiles = workingSets.get(playerId);
        if (profiles == null) {
            throw new IllegalStateException("Player working set was not preloaded: " + playerId);
        }
        return profiles.keySet().stream().anyMatch(key -> key.shareId().equals(shareId))
                || unavailable.getOrDefault(playerId, Map.of()).keySet().stream().anyMatch(key -> key.shareId().equals(shareId));
    }

    java.util.List<WorldProfile> listWorldProfiles(UUID playerId, dev.yu.mwi.domain.GameModeBucket gameMode,
                                                   String shareId) {
        Map<ProfileKey, StoredProfile> profiles = workingSets.getOrDefault(playerId, Map.of());
        return profiles.entrySet().stream()
                .filter(entry -> entry.getKey().scope().type() == ScopeKey.Type.WORLD)
                .filter(entry -> entry.getKey().gameMode() == gameMode)
                .filter(entry -> entry.getKey().shareId().equals(shareId))
                .map(entry -> new WorldProfile(entry.getKey().scope().id(), entry.getValue()))
                .toList();
    }

    void persist(
            ServerPlayer player,
            WorldIdentity world,
            Map<String, ProfileKey> keys,
            Map<String, SharableAdapter> adapters,
            boolean writeWorldBackups,
            long revision,
            String reason,
            boolean asynchronous) throws IOException {
        LinkedHashMap<ProfileKey, CompoundTag> writes = new LinkedHashMap<>();
        LinkedHashMap<String, CompoundTag> captured = new LinkedHashMap<>();
        for (Map.Entry<String, SharableAdapter> entry : adapters.entrySet()) {
            CompoundTag payload = entry.getValue().capture(player, player.registryAccess());
            captured.put(entry.getKey(), payload);
            writes.put(keys.get(entry.getKey()), payload);
        }
        collectWorldBackups(player, world, keys, adapters, captured, writes, writeWorldBackups);
        writes.keySet().removeIf(this::unavailable);
        if (asynchronous) {
            enqueue(player.getUUID(), revision, writes, Optional.empty(), reason);
        } else {
            queue.flush(player.getUUID());
            store.commitBatch(player.getUUID(), revision, writes, Optional.empty());
            remember(player.getUUID(), revision, writes);
            logger.debug("Persisted {} inventory profile for {} at revision {}",
                    reason, player.getUUID(), revision);
        }
    }

    void enqueue(
            java.util.UUID playerId,
            long revision,
            Map<ProfileKey, CompoundTag> writes,
            Optional<CompoundTag> completedJournal,
            String reason) {
        enqueue(playerId, revision, writes, completedJournal, java.util.List.of(), reason);
    }

    void commitImmediately(
            UUID playerId,
            long revision,
            Map<ProfileKey, CompoundTag> writes,
            Optional<CompoundTag> completedJournal) throws IOException {
        queue.flush(playerId);
        store.commitBatch(playerId, revision, writes, completedJournal);
        remember(playerId, revision, writes);
    }

    void commitImmediately(
            UUID playerId,
            long revision,
            Map<ProfileKey, CompoundTag> writes,
            Optional<CompoundTag> completedJournal,
            java.util.List<ProfileArchive> archives) throws IOException {
        queue.flush(playerId);
        store.commitBatch(playerId, revision, writes, completedJournal, archives);
        remember(playerId, revision, writes);
    }

    void enqueue(
            UUID playerId,
            long revision,
            Map<ProfileKey, CompoundTag> writes,
            Optional<CompoundTag> completedJournal,
            java.util.List<ProfileArchive> archives,
            String reason) {
        // Every tag in this map is a newly captured/constructed value whose ownership is transferred
        // to the queue. No live Minecraft object or registry is retained by the closure.
        Map<ProfileKey, CompoundTag> detachedWrites = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(writes));
        Optional<CompoundTag> detachedJournal = completedJournal;
        java.util.List<ProfileArchive> detachedArchives = java.util.List.copyOf(archives);
        if (detachedJournal.isEmpty() && detachedArchives.isEmpty()) {
            queue.submit(playerId, new CoalescingBatch(playerId, revision, detachedWrites));
        } else {
            queue.submit(playerId, () -> {
                store.commitBatch(playerId, revision, detachedWrites, detachedJournal, detachedArchives);
            });
        }
        remember(playerId, revision, detachedWrites);
        logger.debug("Queued {} inventory profile for {} at revision {}", reason, playerId, revision);
    }

    private final class CoalescingBatch implements ProfilePersistenceQueue.CoalescingIoAction {
        private final UUID playerId;
        private long revision;
        private final LinkedHashMap<ProfileKey, CompoundTag> writes;

        private CoalescingBatch(UUID playerId, long revision, Map<ProfileKey, CompoundTag> writes) {
            this.playerId = playerId;
            this.revision = revision;
            this.writes = new LinkedHashMap<>(writes);
        }

        @Override
        public synchronized boolean merge(ProfilePersistenceQueue.CoalescingIoAction newer) {
            if (!(newer instanceof ProfileSnapshotWriter.CoalescingBatch batch)
                    || !playerId.equals(batch.playerId)) {
                return false;
            }
            if (batch.revision <= revision) {
                return true;
            }
            writes.putAll(batch.writes);
            revision = batch.revision;
            return true;
        }

        @Override
        public synchronized void run() throws IOException {
            store.commitBatch(
                    playerId,
                    revision,
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(writes)),
                    Optional.empty());
        }
    }

    private void remember(UUID playerId, long revision, Map<ProfileKey, CompoundTag> writes) {
        Map<ProfileKey, StoredProfile> profiles = workingSets.computeIfAbsent(
                playerId, ignored -> new ConcurrentHashMap<>());
        Instant capturedAt = Instant.now();
        writes.forEach((key, payload) -> profiles.compute(key, (ignored, old) ->
                old != null && old.revision() > revision ? old
                        : new StoredProfile(payload, revision, capturedAt, "pending:" + revision)));
    }

    void refreshWorkingSetIfLoaded(
            UUID playerId, long revision, Map<ProfileKey, CompoundTag> writes) {
        Map<ProfileKey, StoredProfile> profiles = workingSets.get(playerId);
        if (profiles == null) {
            return;
        }
        Instant capturedAt = Instant.now();
        writes.forEach((key, payload) -> profiles.put(
                key, new StoredProfile(payload, revision, capturedAt, "maintenance:" + revision)));
    }

    void reloadWorkingSetIfLoaded(UUID playerId) throws IOException {
        if (workingSets.containsKey(playerId)) {
            workingSets.put(
                    playerId,
                    new ConcurrentHashMap<>(store.inspectPlayerProfiles(playerId)));
        }
    }

    void forget(UUID playerId) {
        workingSets.remove(playerId);
        unavailable.remove(playerId);
    }

    void preserveQuarantined(
            ServerPlayer player,
            Map<String, SharableAdapter> adapters,
            String reason,
            long revision) throws IOException {
        LinkedHashMap<String, CompoundTag> detached = new LinkedHashMap<>();
        for (Map.Entry<String, SharableAdapter> entry : adapters.entrySet()) {
            detached.put(entry.getKey(), entry.getValue().capture(player, player.registryAccess()));
        }
        UUID playerId = player.getUUID();
        queue.submit(playerId, () -> {
            IOException aggregate = null;
            for (Map.Entry<String, CompoundTag> entry : detached.entrySet()) {
                try {
                    store.writeLostFound(playerId, entry.getKey(), entry.getValue(), reason, revision);
                } catch (IOException | RuntimeException failure) {
                    if (aggregate == null) {
                        aggregate = new IOException("Unable to preserve every quarantined share");
                    }
                    aggregate.addSuppressed(failure);
                }
            }
            if (aggregate != null) {
                throw aggregate;
            }
        });
    }

    void throwIfFailed(java.util.UUID playerId) throws IOException {
        queue.throwIfFailed(playerId);
    }

    void flush(java.util.UUID playerId) throws IOException {
        queue.flush(playerId);
    }

    void flushAll() throws IOException {
        queue.flushAll();
    }

    long fence(UUID playerId) { return queue.fence(playerId); }

    RecoveryInspection recover(UUID playerId, long token, UUID sessionId, CompoundTag live, boolean repair) throws IOException {
        queue.drainForRecovery(playerId, token);
        if (live != null) store.preserveRecoverySession(playerId, sessionId, live);
        RecoveryInspection result = store.inspectRecovery(playerId, repair);
        if (!result.pending().isEmpty()) return result;
        if (!repair && !result.unavailable().isEmpty()) return result;
        return queue.retryFailedWrites(playerId, token) ? store.inspectRecovery(playerId, repair) : result;
    }

    void installRecovered(UUID playerId, long token, RecoveryInspection result) throws IOException {
        if (result.generation() != store.recoveryGeneration(playerId)) throw new IOException("Recovery generation changed");
        queue.releaseRecovery(playerId, token);
        installInspection(playerId, result);
    }

    void installInspection(UUID playerId, RecoveryInspection result) {
        workingSets.put(playerId, new ConcurrentHashMap<>(result.profiles()));
        unavailable.put(playerId, result.unavailable());
    }

    void mergeInspection(UUID playerId, RecoveryInspection result) {
        Map<ProfileKey, StoredProfile> working = workingSets.get(playerId);
        if (working == null) return;
        result.profiles().forEach((key, profile) -> working.compute(key,
                (ignored, existing) -> existing == null || existing.revision() <= profile.revision() ? profile : existing));
        unavailable.put(playerId, result.unavailable());
    }

    boolean unavailable(ProfileKey key) { return unavailable.getOrDefault(key.playerId(), Map.of()).containsKey(key); }

    java.util.concurrent.CompletableFuture<RecoveryInspection> inspectInQueue(UUID playerId, boolean repair) {
        var result = new java.util.concurrent.CompletableFuture<RecoveryInspection>();
        try {
            queue.submit(playerId, () -> {
                try { result.complete(store.inspectRecovery(playerId, repair)); }
                catch (IOException failure) { result.completeExceptionally(failure); }
            }).whenComplete((ignored, failure) -> {
                if (failure != null) result.completeExceptionally(failure);
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) { result.completeExceptionally(failure); }
        return result;
    }

    ProfilePersistenceQueue.Metrics metrics() {
        return queue.metrics();
    }

    @Override
    public void close() throws IOException {
        queue.close();
    }

    static void collectWorldBackups(
            ServerPlayer player,
            WorldIdentity world,
            Map<String, ProfileKey> activeKeys,
            Map<String, SharableAdapter> adapters,
            Map<String, CompoundTag> overrides,
            Map<ProfileKey, CompoundTag> writes,
            boolean enabled) {
        if (!enabled) {
            return;
        }
        for (Map.Entry<String, SharableAdapter> entry : adapters.entrySet()) {
            ProfileKey activeKey = activeKeys.get(entry.getKey());
            if (activeKey == null) {
                continue;
            }
            ProfileKey backup = new ProfileKey(
                    player.getUUID(), ScopeKey.world(world), activeKey.gameMode(), entry.getKey());
            CompoundTag payload = overrides.get(entry.getKey());
            if (payload == null) {
                payload = entry.getValue().capture(player, player.registryAccess());
            }
            writes.put(backup, payload);
        }
    }
}
