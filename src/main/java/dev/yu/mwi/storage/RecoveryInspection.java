package dev.yu.mwi.storage;

import dev.yu.mwi.domain.ProfileKey;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detached, validated storage result; a missing corrupt key is never a first-login profile. */
public record RecoveryInspection(long generation, Map<ProfileKey, StoredProfile> profiles,
        Map<ProfileKey, String> unavailable, List<String> pending, int attempts, String state) {
    public RecoveryInspection {
        profiles = Map.copyOf(profiles);
        unavailable = Map.copyOf(unavailable);
        pending = List.copyOf(pending);
    }

    public boolean ready(Set<ProfileKey> required) {
        if (!pending.isEmpty()) return false;
        for (ProfileKey key : required) {
            if (unavailable.containsKey(key)) return false;
            // A missing group can consult world candidates during migration. Do not select an
            // incomplete set, or mistake the only damaged copy for a never-before-owned share.
            if (!profiles.containsKey(key) && unavailable.keySet().stream()
                    .anyMatch(bad -> bad.shareId().equals(key.shareId()) && bad.gameMode() == key.gameMode())) return false;
        }
        return true;
    }
}
