package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Captures every non-zero statistic across all registered vanilla and modded stat types. */
public final class StatisticsAdapter extends AbstractVanillaAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsAdapter.class);
    private static final String STATISTICS = "statistics";
    private static final String TYPE = "type";
    private static final String VALUE = "value";
    private static final String COUNT = "count";

    public StatisticsAdapter() {
        super(AdapterIds.STATISTICS);
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        ServerStatsCounter counter = player.getStats();
        List<EncodedStatistic> materialized = new ArrayList<>();
        for (Object2IntMap.Entry<Stat<?>> stored : counter.stats.object2IntEntrySet()) {
            if (stored.getIntValue() == 0) {
                continue;
            }
            materialized.add(encode(stored.getKey(), stored.getIntValue()));
        }
        materialized.sort(java.util.Comparator
                .comparing(EncodedStatistic::typeId)
                .thenComparing(EncodedStatistic::valueId));
        ListTag entries = new ListTag();
        for (EncodedStatistic statistic : materialized) {
            CompoundTag entry = new CompoundTag();
            entry.putString(TYPE, statistic.typeId().toString());
            entry.putString(VALUE, statistic.valueId().toString());
            entry.putInt(COUNT, statistic.count());
            entries.add(entry);
        }
        CompoundTag data = new CompoundTag();
        data.put(STATISTICS, entries);
        return data;
    }

    private <T> EncodedStatistic encode(Stat<T> stat, int count) {
        ResourceLocation typeId = BuiltInRegistries.STAT_TYPE.getKey(stat.getType());
        ResourceLocation valueId = stat.getType().getRegistry().getKey(stat.getValue());
        if (typeId == null || valueId == null) {
            throw new IllegalStateException("encountered an unregistered statistic");
        }
        return new EncodedStatistic(typeId, valueId, count);
    }

    @Override
    public void clear(ServerPlayer player) {
        ServerStatsCounter counter = player.getStats();
        for (StatType<?> type : BuiltInRegistries.STAT_TYPE) {
            clearType(type, player, counter);
        }
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        NbtPayload.requireData(data, id().toString());
        List<DecodedStatistic> decoded = decode(data);
        ServerStatsCounter counter = player.getStats();
        for (DecodedStatistic statistic : decoded) {
            counter.setValue(player, statistic.stat(), statistic.count());
            if (counter.getValue(statistic.stat()) != statistic.count()) {
                throw new IllegalStateException("statistic " + statistic.typeId() + '/' + statistic.valueId()
                        + " rejected value " + statistic.count() + " for player " + player.getUUID());
            }
            player.getScoreboard().forAllObjectives(
                    statistic.stat(), player, score -> score.set(statistic.count()));
        }
    }

    @Override
    public void sync(ServerPlayer player) {
        ServerStatsCounter counter = player.getStats();
        counter.markAllDirty();
        counter.sendStats(player);
    }

    @Override
    public boolean requiresAdvancementListenerSuspension() {
        return false;
    }

    private <T> void clearType(StatType<T> type, ServerPlayer player, ServerStatsCounter counter) {
        for (T value : type.getRegistry()) {
            Stat<T> stat = type.get(value);
            if (counter.getValue(stat) == 0) {
                continue;
            }
            counter.setValue(player, stat, 0);
            player.getScoreboard().forAllObjectives(stat, player, score -> score.set(0));
            if (counter.getValue(stat) != 0) {
                ResourceLocation typeId = BuiltInRegistries.STAT_TYPE.getKey(type);
                ResourceLocation valueId = type.getRegistry().getKey(value);
                throw new IllegalStateException("statistic " + typeId + '/' + valueId
                        + " rejected zero value for player " + player.getUUID());
            }
        }
    }

    List<DecodedStatistic> decode(CompoundTag data) {
        return decode(data, this::resolveRegisteredStat);
    }

    List<DecodedStatistic> decode(CompoundTag data, StatisticResolver resolver) {
        ListTag entries = NbtPayload.requireCompoundList(data, STATISTICS, id().toString());
        List<DecodedStatistic> decoded = new ArrayList<>(entries.size());
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            ResourceLocation typeId = parseId(
                    NbtPayload.requireString(entry, TYPE, id().toString()), "statistic type");
            ResourceLocation valueId = parseId(
                    NbtPayload.requireString(entry, VALUE, id().toString()), "statistic value");
            int count = NbtPayload.requireInt(entry, COUNT, id().toString());
            if (count == 0) {
                throw NbtPayload.invalid(id().toString(), "statistic counts must be non-zero");
            }
            String key = typeId + "\u0000" + valueId;
            if (!seen.add(key)) {
                throw NbtPayload.invalid(id().toString(), "duplicate statistic " + typeId + '/' + valueId);
            }
            Optional<Stat<?>> stat = resolver.resolve(typeId, valueId);
            if (stat.isEmpty()) {
                warnUnavailable(typeId, valueId);
                continue;
            }
            decoded.add(new DecodedStatistic(stat.orElseThrow(), count, typeId, valueId));
        }
        return decoded;
    }

    private Optional<Stat<?>> resolveRegisteredStat(ResourceLocation typeId, ResourceLocation valueId) {
        return BuiltInRegistries.STAT_TYPE.getOptional(typeId)
                .flatMap(type -> resolveStat(type, valueId));
    }

    private <T> Optional<Stat<?>> resolveStat(StatType<T> type, ResourceLocation valueId) {
        return type.getRegistry().getOptional(valueId).map(type::get);
    }

    private void warnUnavailable(ResourceLocation typeId, ResourceLocation valueId) {
        LOGGER.warn(
                "Ignoring unavailable stored statistic {}/{}; its registry entry is not present in the current mod set",
                typeId,
                valueId);
    }

    private ResourceLocation parseId(String encoded, String field) {
        try {
            return ResourceLocation.parse(encoded);
        } catch (RuntimeException exception) {
            throw NbtPayload.invalid(id().toString(), "invalid " + field + " id '" + encoded + "'");
        }
    }

    private record DecodedStatistic(
            Stat<?> stat, int count, ResourceLocation typeId, ResourceLocation valueId) {
    }

    private record EncodedStatistic(ResourceLocation typeId, ResourceLocation valueId, int count) {
    }

    @FunctionalInterface
    interface StatisticResolver {
        Optional<Stat<?>> resolve(ResourceLocation typeId, ResourceLocation valueId);
    }
}
