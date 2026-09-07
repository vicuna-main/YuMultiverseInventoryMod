package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.config.MwiConfig;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementPayloadMigratorTest {
    private static final Instant EARLY = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant LATE = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void skipsUnknownAdvancementAndRetainsEveryValidEntry() {
        ResourceLocation first = id("minecraft:first");
        ResourceLocation second = id("minecraft:second");
        CompoundTag payload = payload(
                entry(first.toString(), criterion("done", EARLY)),
                entry("removed_mod:old", criterion("legacy", EARLY)),
                entry(second.toString(), criterion("complete", LATE)));

        AdvancementPreparation prepared = prepare(payload, Map.of(
                first, Set.of("done"),
                second, Set.of("complete")), MwiConfig.AdvancementMigration.defaults());

        assertEquals(Set.of(first.toString(), second.toString()), advancementIds(prepared.payload()));
        assertEquals(1, prepared.report().staleAdvancementsSkipped());
        assertEquals(0, prepared.report().staleCriteriaSkipped());
    }

    @Test
    void skipsUnknownCriteriaAndDropsEntryOnlyWhenNoneRemain() {
        ResourceLocation partial = id("minecraft:partial");
        ResourceLocation empty = id("minecraft:empty_after_drift");
        CompoundTag payload = payload(
                entry(partial.toString(), criterion("valid", EARLY), criterion("removed", LATE)),
                entry(empty.toString(), criterion("old_only", EARLY)));

        AdvancementPreparation prepared = prepare(payload, Map.of(
                partial, Set.of("valid"),
                empty, Set.of("new_only")), MwiConfig.AdvancementMigration.defaults());

        assertEquals(Set.of(partial.toString()), advancementIds(prepared.payload()));
        assertEquals(Map.of("valid", EARLY), criteria(prepared.payload(), partial));
        assertEquals(2, prepared.report().staleCriteriaSkipped());
    }

    @Test
    void explicitAliasMigratesReportedProductionFailureAndPreservesTimestamp() {
        ResourceLocation oldId = id("trailandtales_delight:tat_delight");
        ResourceLocation currentId = id("minecraft:trailandtales_delightmain/tat_delight");
        MwiConfig.AdvancementMigration migration = new MwiConfig.AdvancementMigration(
                Map.of(oldId, currentId), Map.of());

        AdvancementPreparation prepared = prepare(
                payload(entry(oldId.toString(), criterion("trail_and_tales", EARLY))),
                Map.of(currentId, Set.of("trail_and_tales")), migration);

        assertEquals(Set.of(currentId.toString()), advancementIds(prepared.payload()));
        assertEquals(Map.of("trail_and_tales", EARLY), criteria(prepared.payload(), currentId));
        assertEquals(1, prepared.report().advancementAliasesMigrated());
        assertEquals(0, prepared.report().staleAdvancementsSkipped());
    }

    @Test
    void aliasWithMissingTargetIsStillSkippedAndReported() {
        ResourceLocation oldId = id("example:old");
        ResourceLocation missingTarget = id("example:missing_target");
        MwiConfig.AdvancementMigration migration = new MwiConfig.AdvancementMigration(
                Map.of(oldId, missingTarget), Map.of());

        AdvancementPreparation prepared = prepare(
                payload(entry(oldId.toString(), criterion("done", EARLY))),
                Map.of(), migration);

        assertTrue(advancementIds(prepared.payload()).isEmpty());
        assertEquals(1, prepared.report().staleAdvancementsSkipped());
        assertEquals(1, prepared.report().advancementAliasesMigrated());
    }

    @Test
    void aliasCollisionMergesCriteriaDeterministicallyAndKeepsEarliestTime() {
        ResourceLocation oldId = id("example:old");
        ResourceLocation targetId = id("example:current");
        MwiConfig.AdvancementMigration migration = new MwiConfig.AdvancementMigration(
                Map.of(oldId, targetId),
                Map.of(targetId, Map.of("old_name", "shared")));
        CompoundTag payload = payload(
                entry(targetId.toString(), criterion("shared", LATE), criterion("target_only", LATE)),
                entry(oldId.toString(), criterion("old_name", EARLY), criterion("old_only", EARLY)));

        AdvancementPreparation prepared = prepare(payload, Map.of(
                targetId, Set.of("shared", "target_only", "old_only")), migration);

        assertEquals(Map.of(
                "shared", EARLY,
                "target_only", LATE,
                "old_only", EARLY), criteria(prepared.payload(), targetId));
        assertEquals(1, prepared.report().advancementAliasesMigrated());
        assertEquals(1, prepared.report().criterionAliasesMigrated());
    }

    @Test
    void strictCorruptionStillFailsBeforeProducingApplicableData() {
        ResourceLocation known = id("minecraft:known");
        assertThrows(IllegalArgumentException.class, () -> prepare(
                payload(entry("not a resource id", criterion("done", EARLY))),
                Map.of(), MwiConfig.AdvancementMigration.defaults()));
        assertThrows(IllegalArgumentException.class, () -> prepare(
                payload(entry(known.toString())),
                Map.of(known, Set.of("done")), MwiConfig.AdvancementMigration.defaults()));
        assertThrows(IllegalArgumentException.class, () -> prepare(
                payload(entry(known.toString(), malformedTime("done"))),
                Map.of(known, Set.of("done")), MwiConfig.AdvancementMigration.defaults()));
        assertThrows(IllegalArgumentException.class, () -> prepare(
                payload(
                        entry(known.toString(), criterion("done", EARLY)),
                        entry(known.toString(), criterion("done", LATE))),
                Map.of(known, Set.of("done")), MwiConfig.AdvancementMigration.defaults()));
        assertThrows(IllegalArgumentException.class, () -> prepare(
                payload(entry(known.toString(), criterion("done", EARLY), criterion("done", LATE))),
                Map.of(known, Set.of("done")), MwiConfig.AdvancementMigration.defaults()));
        CompoundTag wrongCriteriaType = new CompoundTag();
        wrongCriteriaType.putString("id", known.toString());
        wrongCriteriaType.putString("criteria", "not-a-list");
        assertThrows(IllegalArgumentException.class, () -> prepare(
                payload(wrongCriteriaType),
                Map.of(known, Set.of("done")), MwiConfig.AdvancementMigration.defaults()));
    }

    @Test
    void unchangedPayloadHasNoCompatibilityDegradation() {
        ResourceLocation known = id("minecraft:known");
        AdvancementPreparation prepared = prepare(
                payload(entry(known.toString(), criterion("done", EARLY))),
                Map.of(known, Set.of("done")), MwiConfig.AdvancementMigration.defaults());

        assertFalse(prepared.report().changed());
        assertTrue(prepared.report().advancementSamples().isEmpty());
        assertTrue(prepared.report().criterionSamples().isEmpty());
    }

    private static AdvancementPreparation prepare(
            CompoundTag payload,
            Map<ResourceLocation, Set<String>> registry,
            MwiConfig.AdvancementMigration migration) {
        return AdvancementPayloadMigrator.prepare(payload, migration, registry::get);
    }

    private static CompoundTag payload(CompoundTag... advancements) {
        CompoundTag payload = new CompoundTag();
        ListTag entries = new ListTag();
        for (CompoundTag advancement : advancements) entries.add(advancement);
        payload.put("advancements", entries);
        return payload;
    }

    private static CompoundTag entry(String id, CompoundTag... criteria) {
        CompoundTag entry = new CompoundTag();
        entry.putString("id", id);
        ListTag values = new ListTag();
        for (CompoundTag criterion : criteria) values.add(criterion);
        entry.put("criteria", values);
        return entry;
    }

    private static CompoundTag criterion(String name, Instant obtained) {
        CompoundTag criterion = new CompoundTag();
        criterion.putString("name", name);
        criterion.putString("obtained", obtained.toString());
        return criterion;
    }

    private static CompoundTag malformedTime(String name) {
        CompoundTag criterion = new CompoundTag();
        criterion.putString("name", name);
        criterion.putString("obtained", "yesterday");
        return criterion;
    }

    private static Set<String> advancementIds(CompoundTag payload) {
        java.util.HashSet<String> result = new java.util.HashSet<>();
        ListTag entries = payload.getList("advancements", CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            result.add(entries.getCompound(index).getString("id"));
        }
        return Set.copyOf(result);
    }

    private static Map<String, Instant> criteria(CompoundTag payload, ResourceLocation advancement) {
        LinkedHashMap<String, Instant> result = new LinkedHashMap<>();
        ListTag entries = payload.getList("advancements", CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            if (!entry.getString("id").equals(advancement.toString())) continue;
            ListTag criteria = entry.getList("criteria", CompoundTag.TAG_COMPOUND);
            for (int criterionIndex = 0; criterionIndex < criteria.size(); criterionIndex++) {
                CompoundTag criterion = criteria.getCompound(criterionIndex);
                result.put(
                        criterion.getString("name"),
                        Instant.parse(criterion.getString("obtained")));
            }
        }
        return Map.copyOf(result);
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }
}
