package dev.yu.mwi.storage;

import dev.yu.mwi.domain.ProfileKey;
import java.time.Instant;
import java.util.*;

/** A verified dependency set, not an incremental revision presented as a full inventory. */
public record RestorePoint(long revision, Instant committedAt, Map<ProfileKey, StoredProfile> profiles,
        List<String> problems, String source) {
    public RestorePoint { profiles = Map.copyOf(profiles); problems = List.copyOf(problems); }
    public boolean completeFor(Set<ProfileKey> required) {
        return !required.isEmpty() && problems.isEmpty() && profiles.keySet().containsAll(required);
    }
}
