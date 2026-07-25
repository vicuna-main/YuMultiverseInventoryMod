package dev.yu.mwi.storage;

import java.util.Objects;

/** A durable world-scoped profile discovered during group migration. */
public record WorldProfile(String worldId, StoredProfile profile) {
    public WorldProfile {
        worldId = Objects.requireNonNull(worldId, "worldId");
        profile = Objects.requireNonNull(profile, "profile");
    }
}
