package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import dev.yu.mwi.config.MwiConfig;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** Pure validation and schema-drift normalization for stored advancement payloads. */
final class AdvancementPayloadMigrator {
    private static final String ADAPTER_ID = AdapterIds.ADVANCEMENTS.toString();
    private static final String ADVANCEMENTS = "advancements";
    private static final String ID = "id";
    private static final String CRITERIA = "criteria";
    private static final String NAME = "name";
    private static final String OBTAINED = "obtained";
    private static final Instant LEGACY_OBTAINED_TIME = Instant.EPOCH;
    private static final int MAX_SAMPLES = 5;

    private AdvancementPayloadMigrator() {
    }

    static AdvancementPreparation prepare(
            CompoundTag data,
            MwiConfig.AdvancementMigration migration,
            Function<ResourceLocation, Set<String>> criteriaLookup) {
        NbtPayload.requireData(data, ADAPTER_ID);
        java.util.Objects.requireNonNull(migration, "migration");
        java.util.Objects.requireNonNull(criteriaLookup, "criteriaLookup");
        ListTag entries = NbtPayload.requireCompoundList(data, ADVANCEMENTS, ADAPTER_ID);
        Set<ResourceLocation> nativeIds = new HashSet<>();
        LinkedHashMap<ResourceLocation, MergedAdvancement> merged = new LinkedHashMap<>();
        Counters counters = new Counters();

        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            String encodedId = NbtPayload.requireString(entry, ID, ADAPTER_ID);
            ResourceLocation sourceId = parseId(encodedId);
            if (!nativeIds.add(sourceId)) {
                throw NbtPayload.invalid(ADAPTER_ID, "duplicate advancement " + sourceId);
            }

            List<EncodedCriterion> encodedCriteria = decodeCriteriaStructure(sourceId, entry);
            ResourceLocation targetId = migration.resolveAdvancement(sourceId);
            boolean advancementAliased = !targetId.equals(sourceId);
            if (advancementAliased) {
                counters.advancementAliasesMigrated++;
            }
            Set<String> currentCriteria = criteriaLookup.apply(targetId);
            if (currentCriteria == null) {
                counters.staleAdvancementsSkipped++;
                counters.addAdvancementSample(advancementAliased
                        ? sourceId + " -> " + targetId
                        : sourceId.toString());
                continue;
            }

            MergedAdvancement target = merged.get(targetId);
            if (target != null && !target.aliasCollisionAllowed && !advancementAliased) {
                throw NbtPayload.invalid(ADAPTER_ID, "duplicate advancement " + targetId);
            }
            if (target == null) {
                target = new MergedAdvancement(advancementAliased);
                merged.put(targetId, target);
            } else if (advancementAliased) {
                target.aliasCollisionAllowed = true;
            }

            for (EncodedCriterion encodedCriterion : encodedCriteria) {
                String sourceCriterion = encodedCriterion.name();
                String targetCriterion = migration.resolveCriterion(targetId, sourceCriterion);
                boolean criterionAliased = !targetCriterion.equals(sourceCriterion);
                if (criterionAliased) {
                    counters.criterionAliasesMigrated++;
                    target.aliasCollisionAllowed = true;
                }
                if (!currentCriteria.contains(targetCriterion)) {
                    counters.staleCriteriaSkipped++;
                    counters.addCriterionSample(targetId + "/" + sourceCriterion
                            + (criterionAliased ? " -> " + targetCriterion : ""));
                    continue;
                }
                target.criteria.merge(targetCriterion, encodedCriterion.obtained(),
                        (first, second) -> first.compareTo(second) <= 0 ? first : second);
            }
        }

        CompoundTag normalized = encode(merged);
        AdvancementCompatibilityReport report = new AdvancementCompatibilityReport(
                counters.staleAdvancementsSkipped,
                counters.staleCriteriaSkipped,
                counters.advancementAliasesMigrated,
                counters.criterionAliasesMigrated,
                counters.advancementSamples,
                counters.criterionSamples);
        return new AdvancementPreparation(normalized, report);
    }

    private static ResourceLocation parseId(String encodedId) {
        try {
            return ResourceLocation.parse(encodedId);
        } catch (RuntimeException invalid) {
            throw NbtPayload.invalid(ADAPTER_ID, "invalid advancement id '" + encodedId + "'");
        }
    }

    private static List<EncodedCriterion> decodeCriteriaStructure(
            ResourceLocation advancementId, CompoundTag entry) {
        if (!entry.contains(CRITERIA, Tag.TAG_LIST)) {
            throw NbtPayload.invalid(ADAPTER_ID, "missing criterion list for " + advancementId);
        }
        Tag raw = entry.get(CRITERIA);
        if (!(raw instanceof ListTag encodedCriteria) || encodedCriteria.isEmpty()) {
            throw NbtPayload.invalid(ADAPTER_ID,
                    "advancement " + advancementId + " has no completed criteria");
        }

        Set<String> unique = new HashSet<>();
        List<EncodedCriterion> criteria = new ArrayList<>(encodedCriteria.size());
        if (encodedCriteria.getElementType() == Tag.TAG_STRING) {
            for (int index = 0; index < encodedCriteria.size(); index++) {
                addCriterion(
                        advancementId, unique, criteria, encodedCriteria.getString(index), LEGACY_OBTAINED_TIME);
            }
            return criteria;
        }
        if (encodedCriteria.getElementType() != Tag.TAG_COMPOUND) {
            throw NbtPayload.invalid(ADAPTER_ID,
                    "criteria for " + advancementId + " must contain strings or compounds");
        }
        for (int index = 0; index < encodedCriteria.size(); index++) {
            CompoundTag encodedCriterion = encodedCriteria.getCompound(index);
            String criterion = NbtPayload.requireString(encodedCriterion, NAME, ADAPTER_ID);
            String encodedObtained = NbtPayload.requireString(encodedCriterion, OBTAINED, ADAPTER_ID);
            Instant obtained;
            try {
                obtained = Instant.parse(encodedObtained);
            } catch (DateTimeParseException invalid) {
                throw NbtPayload.invalid(ADAPTER_ID, "invalid obtained time '" + encodedObtained
                        + "' for " + advancementId + '/' + criterion);
            }
            addCriterion(advancementId, unique, criteria, criterion, obtained);
        }
        return criteria;
    }

    private static void addCriterion(
            ResourceLocation advancementId,
            Set<String> unique,
            List<EncodedCriterion> criteria,
            String criterion,
            Instant obtained) {
        if (criterion == null || criterion.isBlank()) {
            throw NbtPayload.invalid(ADAPTER_ID,
                    "criterion for " + advancementId + " must not be blank");
        }
        if (!unique.add(criterion)) {
            throw NbtPayload.invalid(ADAPTER_ID,
                    "duplicate criterion " + advancementId + '/' + criterion);
        }
        criteria.add(new EncodedCriterion(criterion, obtained));
    }

    private static CompoundTag encode(Map<ResourceLocation, MergedAdvancement> merged) {
        ListTag entries = new ListTag();
        merged.entrySet().stream()
                .filter(entry -> !entry.getValue().criteria.isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    CompoundTag encodedAdvancement = new CompoundTag();
                    encodedAdvancement.putString(ID, entry.getKey().toString());
                    ListTag criteria = new ListTag();
                    entry.getValue().criteria.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                            .forEach(criterion -> {
                                CompoundTag encodedCriterion = new CompoundTag();
                                encodedCriterion.putString(NAME, criterion.getKey());
                                encodedCriterion.putString(OBTAINED, criterion.getValue().toString());
                                criteria.add(encodedCriterion);
                            });
                    encodedAdvancement.put(CRITERIA, criteria);
                    entries.add(encodedAdvancement);
                });
        CompoundTag result = new CompoundTag();
        result.put(ADVANCEMENTS, entries);
        return result;
    }

    private record EncodedCriterion(String name, Instant obtained) {
    }

    private static final class MergedAdvancement {
        private final LinkedHashMap<String, Instant> criteria = new LinkedHashMap<>();
        private boolean aliasCollisionAllowed;

        private MergedAdvancement(boolean aliasCollisionAllowed) {
            this.aliasCollisionAllowed = aliasCollisionAllowed;
        }
    }

    private static final class Counters {
        private int staleAdvancementsSkipped;
        private int staleCriteriaSkipped;
        private int advancementAliasesMigrated;
        private int criterionAliasesMigrated;
        private final List<String> advancementSamples = new ArrayList<>();
        private final List<String> criterionSamples = new ArrayList<>();

        private void addAdvancementSample(String sample) {
            if (advancementSamples.size() < MAX_SAMPLES) advancementSamples.add(sample);
        }

        private void addCriterionSample(String sample) {
            if (criterionSamples.size() < MAX_SAMPLES) criterionSamples.add(sample);
        }
    }
}
