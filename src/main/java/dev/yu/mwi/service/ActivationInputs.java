package dev.yu.mwi.service;

import dev.yu.mwi.domain.ProfileKey;
import dev.yu.mwi.storage.NbtChecksums;
import dev.yu.mwi.storage.StoredProfile;
import net.minecraft.nbt.*;
import java.util.*;

/** Only the actual return scope, selected restore and runtime rules may refresh an activation budget. */
final class ActivationInputs {
    static final String RULES = "mwi-standalone-recovery-v1;" + dev.yu.mwi.adapter.vanilla.ItemStackSemantics.RULE
            + ";" + dev.yu.mwi.adapter.vanilla.PotionEffectPayloads.RULE + ";health-absorption-capacity-v2;curios-rebuild-escrow-v1;uninstalled-effect-owner-v1";
    private ActivationInputs() {}
    static String digest(Set<ProfileKey> required, Map<ProfileKey, StoredProfile> profiles,
            String environment, CompoundTag request) throws java.io.IOException {
        return digest(required, profiles, environment, request, new CompoundTag());
    }
    static String digest(Set<ProfileKey> required, Map<ProfileKey, StoredProfile> profiles,
            String environment, CompoundTag request, CompoundTag fallbackInputs) throws java.io.IOException {
        CompoundTag input = new CompoundTag();
        required.stream().sorted(Comparator.comparing(Object::toString)).forEach(key -> {
            StoredProfile stored = profiles.get(key);
            CompoundTag source = new CompoundTag();
            source.putBoolean("present", stored != null);
            if (stored != null) source.put("payload", stored.payload());
            else {
                // A never-created share is legitimate. Readiness has already rejected damaged
                // missing sources; retain its absence in the digest without inventing an empty profile.
                source.putBoolean("has_share_history", profiles.keySet().stream()
                        .anyMatch(candidate -> candidate.shareId().equals(key.shareId())));
            }
            input.put(key.toString(), source);
        });
        input.putString("rules", RULES); input.putString("environment", environment);
        input.put("fallback_inputs", fallbackInputs.copy());
        if ("PREPARED".equals(request.getString("state"))) input.put("requested_restore", request.copy());
        return NbtChecksums.canonicalSha256(input);
    }
    static ListTag encode(Set<ProfileKey> required) {
        ListTag result = new ListTag();
        required.stream().sorted(Comparator.comparing(Object::toString)).forEach(key -> result.add(TransitionJournal.encodeKey(key)));
        return result;
    }
}
