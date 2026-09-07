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
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;

/**
 * Captures completed advancement criteria and their original timestamps. Restoration writes the
 * state directly and never invokes gameplay award/revoke paths, completion rewards, announcements,
 * or NeoForge advancement progress events.
 */
public final class AdvancementAdapter extends AbstractVanillaAdapter {
    private static final String ADVANCEMENTS = "advancements";
    private static final String ID = "id";
    private static final String CRITERIA = "criteria";
    private static final String NAME = "name";
    private static final String OBTAINED = "obtained";
    private static final Instant LEGACY_OBTAINED_TIME = Instant.EPOCH;

    public AdvancementAdapter() {
        super(AdapterIds.ADVANCEMENTS);
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        PlayerAdvancements advancements = player.getAdvancements();
        List<Map.Entry<AdvancementHolder, AdvancementProgress>> materialized =
                new ArrayList<>(advancements.progress.entrySet());
        materialized.sort(Map.Entry.comparingByKey(Comparator.comparing(AdvancementHolder::id)));
        ListTag entries = new ListTag();
        for (Map.Entry<AdvancementHolder, AdvancementProgress> materializedEntry : materialized) {
            AdvancementHolder holder = materializedEntry.getKey();
            AdvancementProgress progress = materializedEntry.getValue();
            List<String> completed = copyCriteria(progress.getCompletedCriteria());
            if (completed.isEmpty()) {
                continue;
            }
            completed.sort(String::compareTo);
            ListTag criteria = new ListTag();
            for (String criterion : completed) {
                CriterionProgress criterionProgress = progress.getCriterion(criterion);
                if (criterionProgress == null || !criterionProgress.isDone()
                        || criterionProgress.getObtained() == null) {
                    throw new IllegalStateException("completed advancement criterion " + holder.id() + '/'
                            + criterion + " has no obtainable timestamp for player " + player.getUUID());
                }
                CompoundTag encodedCriterion = new CompoundTag();
                encodedCriterion.putString(NAME, criterion);
                encodedCriterion.putString(OBTAINED, criterionProgress.getObtained().toString());
                criteria.add(encodedCriterion);
            }
            CompoundTag entry = new CompoundTag();
            entry.putString(ID, holder.id().toString());
            entry.put(CRITERIA, criteria);
            entries.add(entry);
        }
        CompoundTag data = new CompoundTag();
        data.put(ADVANCEMENTS, entries);
        return data;
    }

    @Override
    public void clear(ServerPlayer player) {
        PlayerAdvancements advancements = player.getAdvancements();
        for (AdvancementHolder holder : player.server.getAdvancements().getAllAdvancements()) {
            AdvancementProgress progress = advancements.getOrStartProgress(holder);
            for (String criterion : copyCriteria(progress.getCompletedCriteria())) {
                CriterionProgress existing = progress.getCriterion(criterion);
                if (existing == null || !existing.isDone()) {
                    throw new IllegalStateException("advancement criterion " + holder.id() + '/' + criterion
                            + " disappeared while clearing stored state for player " + player.getUUID());
                }
                progress.criteria.put(criterion, new CriterionProgress());
            }
        }
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        AdvancementPreparation preparation = prepare(
                player.server.getAdvancements(), data, MwiConfig.AdvancementMigration.defaults());
        Map<AdvancementHolder, List<CompletedCriterion>> decoded =
                decode(player.server.getAdvancements(), preparation.payload());
        PlayerAdvancements advancements = player.getAdvancements();
        for (Map.Entry<AdvancementHolder, List<CompletedCriterion>> entry : decoded.entrySet()) {
            AdvancementHolder holder = entry.getKey();
            AdvancementProgress progress = advancements.getOrStartProgress(holder);
            for (CompletedCriterion criterion : entry.getValue()) {
                CriterionProgress existing = progress.getCriterion(criterion.name());
                if (existing == null) {
                    throw new IllegalStateException("advancement criterion " + holder.id() + '/'
                            + criterion.name() + " is unavailable for player " + player.getUUID());
                }
                if (existing.isDone()) {
                    throw new IllegalStateException("advancement criterion " + holder.id() + '/'
                            + criterion.name() + " was already present before apply for player "
                            + player.getUUID());
                }
                progress.criteria.put(criterion.name(), new CriterionProgress(criterion.obtained()));
            }
        }
    }

    /** Validates and normalizes a stored profile without mutating live player advancement state. */
    public AdvancementPreparation prepare(
            ServerAdvancementManager manager,
            CompoundTag data,
            MwiConfig.AdvancementMigration migration) {
        return AdvancementPayloadMigrator.prepare(data, migration, advancementId -> {
            AdvancementHolder holder = manager.get(advancementId);
            return holder == null ? null : Set.copyOf(holder.value().criteria().keySet());
        });
    }

    @Override
    public void sync(ServerPlayer player) {
        AdvancementStateController.resumeAndSynchronizeFullState(player);
    }

    @Override
    public boolean requiresAdvancementListenerSuspension() {
        return true;
    }

    @Override
    public void syncAfterAdvancementListenerSuspension(ServerPlayer player) {
        AdvancementStateController.resumeAndSynchronizeFullStateAfterSuspend(player);
    }

    private Map<AdvancementHolder, List<CompletedCriterion>> decode(
            ServerAdvancementManager manager, CompoundTag data) {
        ListTag entries = NbtPayload.requireCompoundList(data, ADVANCEMENTS, id().toString());
        Map<AdvancementHolder, List<CompletedCriterion>> decoded = new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            String encodedId = NbtPayload.requireString(entry, ID, id().toString());
            ResourceLocation advancementId;
            try {
                advancementId = ResourceLocation.parse(encodedId);
            } catch (RuntimeException exception) {
                throw NbtPayload.invalid(id().toString(), "invalid advancement id '" + encodedId + "'");
            }
            AdvancementHolder holder = manager.get(advancementId);
            if (holder == null) {
                throw NbtPayload.invalid(id().toString(), "unknown advancement " + advancementId);
            }
            if (decoded.containsKey(holder)) {
                throw NbtPayload.invalid(id().toString(), "duplicate advancement " + advancementId);
            }

            List<CompletedCriterion> criteria = decodeCriteria(holder, advancementId, entry);
            decoded.put(holder, criteria);
        }
        return decoded;
    }

    private List<CompletedCriterion> decodeCriteria(
            AdvancementHolder holder, ResourceLocation advancementId, CompoundTag entry) {
        if (!entry.contains(CRITERIA, Tag.TAG_LIST)) {
            throw NbtPayload.invalid(id().toString(), "missing criterion list for " + advancementId);
        }
        Tag raw = entry.get(CRITERIA);
        if (!(raw instanceof ListTag encodedCriteria) || encodedCriteria.isEmpty()) {
            throw NbtPayload.invalid(id().toString(),
                    "advancement " + advancementId + " has no completed criteria");
        }

        Set<String> unique = new HashSet<>();
        List<CompletedCriterion> criteria = new ArrayList<>(encodedCriteria.size());
        if (encodedCriteria.getElementType() == Tag.TAG_STRING) {
            // 1.0.0/1.1.0 profiles had no timestamps. Use a deterministic value instead of
            // fabricating a new completion date every time the profile is installed.
            for (int criterionIndex = 0; criterionIndex < encodedCriteria.size(); criterionIndex++) {
                addDecodedCriterion(holder, advancementId, unique, criteria,
                        encodedCriteria.getString(criterionIndex), LEGACY_OBTAINED_TIME);
            }
            return criteria;
        }
        if (encodedCriteria.getElementType() != Tag.TAG_COMPOUND) {
            throw NbtPayload.invalid(id().toString(),
                    "criteria for " + advancementId + " must contain strings or compounds");
        }

        for (int criterionIndex = 0; criterionIndex < encodedCriteria.size(); criterionIndex++) {
            CompoundTag encodedCriterion = encodedCriteria.getCompound(criterionIndex);
            String criterion = NbtPayload.requireString(encodedCriterion, NAME, id().toString());
            String encodedObtained = NbtPayload.requireString(encodedCriterion, OBTAINED, id().toString());
            Instant obtained;
            try {
                obtained = Instant.parse(encodedObtained);
            } catch (DateTimeParseException exception) {
                throw NbtPayload.invalid(id().toString(), "invalid obtained time '" + encodedObtained
                        + "' for " + advancementId + '/' + criterion);
            }
            addDecodedCriterion(holder, advancementId, unique, criteria, criterion, obtained);
        }
        return criteria;
    }

    private void addDecodedCriterion(
            AdvancementHolder holder,
            ResourceLocation advancementId,
            Set<String> unique,
            List<CompletedCriterion> criteria,
            String criterion,
            Instant obtained) {
        if (criterion.isBlank() || !holder.value().criteria().containsKey(criterion)) {
            throw NbtPayload.invalid(id().toString(),
                    "unknown criterion " + advancementId + '/' + criterion);
        }
        if (!unique.add(criterion)) {
            throw NbtPayload.invalid(id().toString(),
                    "duplicate criterion " + advancementId + '/' + criterion);
        }
        criteria.add(new CompletedCriterion(criterion, obtained));
    }

    private static List<String> copyCriteria(Iterable<String> criteria) {
        List<String> copied = new ArrayList<>();
        criteria.forEach(copied::add);
        return copied;
    }

    private record CompletedCriterion(String name, Instant obtained) {
    }
}
