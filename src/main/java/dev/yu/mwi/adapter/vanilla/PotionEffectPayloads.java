package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.RestoreVerification;
import net.minecraft.nbt.*;

import java.util.TreeSet;

/** Only NeoForge's explicitly unordered cure set is normalized; every other NBT field stays typed and ordered. */
public final class PotionEffectPayloads {
    public static final String RULE = "neoforge-effect-cures-set-v1";
    private static final String CURES = "neoforge:cures";

    private PotionEffectPayloads() {}

    public record RemovedEffects(CompoundTag payload, java.util.List<String> removed) {
        public RemovedEffects { payload = payload.copy(); removed = java.util.List.copyOf(removed); }
        @Override public CompoundTag payload() { return payload.copy(); }
    }

    /** Explicit source migration, never semantic equivalence: the caller must archive the original in its WAL. */
    public static RemovedEffects prepareAfterUninstall(CompoundTag payload,
            java.util.function.Predicate<net.minecraft.resources.ResourceLocation> registered,
            java.util.function.Predicate<String> installedNamespace) {
        normalize(payload); // Validate the complete structure before selecting any subset.
        CompoundTag result = payload.copy(); ListTag retained = new ListTag();
        java.util.List<String> removed = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Tag tag : NbtPayload.requireCompoundList(payload, "effects", "potion_effects")) {
            CompoundTag effect = (CompoundTag) tag;
            var id = effect.get("id") instanceof StringTag name
                    ? net.minecraft.resources.ResourceLocation.tryParse(name.getAsString()) : null;
            if (id == null || !seen.add(id.toString())) throw NbtPayload.invalid("potion_effects", "invalid or duplicate effect id");
            boolean absentOwner = !java.util.Set.of("minecraft", "neoforge").contains(id.getNamespace())
                    && !installedNamespace.test(id.getNamespace());
            if (!registered.test(id) && absentOwner) removed.add(id.toString());
            else retained.add(effect.copy());
        }
        result.put("effects", retained);
        return new RemovedEffects(result, removed);
    }

    public static CompoundTag normalize(CompoundTag payload) {
        NbtPayload.requireData(payload, "potion_effects");
        CompoundTag result = payload.copy();
        for (Tag effect : NbtPayload.requireCompoundList(result, "effects", "potion_effects")) {
            normalizeEffect((CompoundTag) effect, 0);
        }
        return result;
    }

    private static void normalizeEffect(CompoundTag effect, int depth) {
        if (depth > 64) throw NbtPayload.invalid("potion_effects", "hidden_effect depth exceeds 64");
        if (effect.contains(CURES)) {
            if (!(effect.get(CURES) instanceof ListTag cures)
                    || (!cures.isEmpty() && cures.getElementType() != Tag.TAG_STRING) || cures.size() > 1024)
                throw NbtPayload.invalid("potion_effects", "cures must be a bounded string set");
            TreeSet<String> members = new TreeSet<>();
            for (Tag cure : cures) {
                String member = cure.getAsString();
                if (member.isBlank() || !members.add(member))
                    throw NbtPayload.invalid("potion_effects", "blank or duplicate cure");
            }
            ListTag canonical = new ListTag();
            members.forEach(member -> canonical.add(StringTag.valueOf(member)));
            effect.put(CURES, canonical);
        }
        if (effect.contains("hidden_effect")) {
            if (!(effect.get("hidden_effect") instanceof CompoundTag hidden))
                throw NbtPayload.invalid("potion_effects", "hidden_effect must be a compound");
            normalizeEffect(hidden, depth + 1);
        }
    }

    public static RestoreVerification verify(CompoundTag expected, CompoundTag actual) {
        try {
            CompoundTag normalized = normalize(actual);
            if (!normalize(expected).equals(normalized))
                return new RestoreVerification(RestoreVerification.Status.DIFFERENT, RULE,
                        dev.yu.mwi.api.NbtDifference.first(normalize(expected), normalized), null);
            return new RestoreVerification(expected.equals(actual) ? RestoreVerification.Status.EXACT
                    : RestoreVerification.Status.SEMANTIC_EQUIVALENT, RULE, "", normalized);
        } catch (IllegalArgumentException invalid) {
            return new RestoreVerification(RestoreVerification.Status.UNSUPPORTED, RULE, invalid.getMessage(), null);
        }
    }
}
