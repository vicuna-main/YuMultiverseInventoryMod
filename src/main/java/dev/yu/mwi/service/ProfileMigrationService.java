package dev.yu.mwi.service;

import dev.yu.mwi.config.MwiConfig;
import dev.yu.mwi.domain.GroupResolver;
import dev.yu.mwi.domain.ProfileKey;
import dev.yu.mwi.domain.ScopeKey;
import dev.yu.mwi.domain.WorldIdentity;
import dev.yu.mwi.storage.WorldProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resolves existing world profiles into one group profile without mutating durable state. */
final class ProfileMigrationService {
    private final MinecraftServer server;
    private final ProfileSnapshotWriter snapshots;
    private final Logger logger;

    ProfileMigrationService(MinecraftServer server, ProfileSnapshotWriter snapshots, Logger logger) {
        this.server = server;
        this.snapshots = snapshots;
        this.logger = logger;
    }

    MigrationResult plan(
            ServerPlayer player,
            WorldIdentity targetWorld,
            ProfileKey groupKey,
            CompoundTag livePayload,
            boolean allowLiveCurrent,
            GroupResolver resolver,
            MwiConfig.Migration migration) throws IOException, TransitionException {
        List<ProfileCandidate> candidates = candidates(player, groupKey, resolver);
        int distinctProfiles = distinctPayloadCount(candidates);

        CompoundTag merged = null;
        if (distinctProfiles > 1
                && migration.onProfileConflict() == MwiConfig.ProfileConflictPolicy.MERGE_IF_POSSIBLE) {
            merged = ItemProfileMerger.merge(groupKey.shareId(), candidates, player.registryAccess());
        }
        ProfileCandidate selected = merged == null ? choose(migration, targetWorld, candidates) : null;
        CompoundTag payload = merged != null
                ? merged
                : selected != null
                ? selected.profile().payload()
                : allowLiveCurrent ? livePayload.copy() : null;
        if (payload == null) {
            return new MigrationResult(null, List.of());
        }

        List<ProfileKey> archives = List.of();
        if (distinctProfiles > 1 && migration.archiveConflicts()) {
            archives = candidates.stream()
                    .filter(candidate -> selected == null || candidate != selected)
                    .map(ProfileCandidate::key)
                    .toList();
            if (migration.notifyAdmins()) {
                logger.warn("Will archive {} conflicting {} profiles for player {} into group {}",
                        archives.size(), groupKey.shareId(), player.getUUID(), groupKey.scope().id());
            }
        }
        return new MigrationResult(payload, archives);
    }

    private List<ProfileCandidate> candidates(
            ServerPlayer player, ProfileKey groupKey, GroupResolver resolver) throws IOException {
        Map<String, WorldIdentity> loadedWorlds = new HashMap<>();
        for (var level : server.getAllLevels()) {
            WorldIdentity world = WorldIdentities.from(level);
            loadedWorlds.put(world.name(), world);
        }
        List<ProfileCandidate> candidates = new ArrayList<>();
        for (WorldProfile stored : snapshots.listWorldProfiles(
                player.getUUID(), groupKey.gameMode(), groupKey.shareId())) {
            WorldIdentity world = loadedWorlds.getOrDefault(
                    stored.worldId(), WorldIdentities.fromName(stored.worldId()));
            if (resolver.resolve(world, groupKey.shareId()).equals(groupKey.scope())) {
                ProfileKey worldKey = new ProfileKey(
                        player.getUUID(), ScopeKey.world(world), groupKey.gameMode(), groupKey.shareId());
                candidates.add(new ProfileCandidate(world, worldKey, stored.profile()));
            }
        }
        return List.copyOf(candidates);
    }

    private static ProfileCandidate choose(
            MwiConfig.Migration migration,
            WorldIdentity currentWorld,
            List<ProfileCandidate> candidates) throws TransitionException {
        if (candidates.isEmpty()) {
            return null;
        }
        if (distinctPayloadCount(candidates) <= 1) {
            return candidates.getFirst();
        }
        return switch (migration.onProfileConflict()) {
            case PREFER_CURRENT_WORLD -> candidates.stream()
                    .filter(candidate -> candidate.world().equals(currentWorld))
                    .findFirst().orElseGet(() -> newest(candidates));
            case PREFER_NEWEST -> newest(candidates);
            case PREFER_NAMED_WORLD -> candidates.stream()
                    .filter(candidate -> candidate.world().hasName(migration.preferredWorld().orElseThrow()))
                    .findFirst()
                    .orElseThrow(() -> new TransitionException(
                            "Preferred migration world has no profile: "
                                    + migration.preferredWorld().orElseThrow()));
            case MANUAL -> throw new TransitionException(
                    "Conflicting profiles require manual resolution for "
                            + candidates.getFirst().key().shareId());
            case MERGE_IF_POSSIBLE -> throw new TransitionException(
                    "merge_if_possible only supports item-container shares");
        };
    }

    private static ProfileCandidate newest(List<ProfileCandidate> candidates) {
        return candidates.stream()
                .max(Comparator.comparing(candidate -> candidate.profile().savedAt()))
                .orElseThrow();
    }

    private static int distinctPayloadCount(List<ProfileCandidate> candidates) {
        List<CompoundTag> distinct = new ArrayList<>();
        for (ProfileCandidate candidate : candidates) {
            CompoundTag payload = candidate.profile().payload();
            if (distinct.stream().noneMatch(payload::equals)) {
                distinct.add(payload);
            }
        }
        return distinct.size();
    }

    record MigrationResult(CompoundTag payload, List<ProfileKey> archives) {
        MigrationResult {
            archives = List.copyOf(archives);
        }
    }
}
