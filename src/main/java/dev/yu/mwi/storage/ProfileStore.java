package dev.yu.mwi.storage;

import dev.yu.mwi.domain.ProfileKey;
import dev.yu.mwi.domain.GameModeBucket;
import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Durable, per-share profile storage. Implementations must return defensive NBT copies. */
public interface ProfileStore extends AutoCloseable {
    Optional<StoredProfile> load(ProfileKey key) throws IOException;

    /** Loads a complete player working set for server-thread, disk-free profile transitions. */
    default Map<ProfileKey, StoredProfile> loadPlayerProfiles(UUID playerId) throws IOException {
        return Map.of();
    }

    /** Reads and validates a player working set without changing caches, files, or revision state. */
    Map<ProfileKey, StoredProfile> inspectPlayerProfiles(UUID playerId) throws IOException;

    default PlayerProfileIds listPlayerProfileIds() throws IOException {
        return new PlayerProfileIds(List.of());
    }

    SaveResult save(ProfileKey key, CompoundTag payload, long revision) throws IOException;

    /** Durably commits a set of profile writes and an optional completed revision journal. */
    default void commitBatch(
            UUID playerId,
            long revision,
            Map<ProfileKey, CompoundTag> profiles,
            Optional<CompoundTag> completedJournal) throws IOException {
        for (Map.Entry<ProfileKey, CompoundTag> entry : profiles.entrySet()) {
            if (!entry.getKey().playerId().equals(playerId)) {
                throw new IllegalArgumentException("batch contains a profile for another player");
            }
            save(entry.getKey(), entry.getValue(), revision);
        }
        if (completedJournal.isPresent()) {
            writeRevision(playerId, revision, completedJournal.orElseThrow());
        }
    }

    default void commitBatch(
            UUID playerId,
            long revision,
            Map<ProfileKey, CompoundTag> profiles,
            Optional<CompoundTag> completedJournal,
            List<ProfileArchive> archives) throws IOException {
        for (ProfileArchive archive : archives) {
            archive(archive.key(), archive.category(), archive.reason(), revision, archive.details());
        }
        commitBatch(playerId, revision, profiles, completedJournal);
    }

    void delete(ProfileKey key) throws IOException;

    void invalidate(ProfileKey key);

    void invalidatePlayer(UUID playerId);

    long nextRevision(UUID playerId) throws IOException;

    void writeRevision(UUID playerId, long revision, CompoundTag journal) throws IOException;

    Optional<CompoundTag> loadRevision(UUID playerId, long revision) throws IOException;

    List<Long> listRevisions(UUID playerId) throws IOException;

    default List<WorldProfile> listWorldProfiles(
            UUID playerId, GameModeBucket gameMode, String shareId) throws IOException {
        return List.of();
    }

    void archive(ProfileKey key, String category, String reason, long revision) throws IOException;

    default void archive(
            ProfileKey key, String category, String reason, long revision, CompoundTag details) throws IOException {
        archive(key, category, reason, revision);
    }

    default List<CompatibilityArchiveEntry> listCompatibilityArchives(UUID playerId) throws IOException {
        return List.of();
    }

    void writeLostFound(UUID playerId, String shareId, CompoundTag payload, String reason, long revision)
            throws IOException;

    CacheStats cacheStats();

    default boolean recoveredFromUncleanShutdown() {
        return false;
    }

    /** True when prepared journals were rolled back during this startup for the player. */
    default boolean requiresCanonicalRecovery(UUID playerId) {
        return false;
    }

    /** True when an interrupted transition targeted a profile that did not yet exist. */
    default boolean shouldResetLive(ProfileKey key) {
        return false;
    }

    /** Releases startup-only recovery markers after the player was initialized successfully. */
    default void completeStartupRecovery(UUID playerId) {
    }

    default void markUncleanShutdown() {
    }

    @Override
    default void close() throws IOException {
    }

}
