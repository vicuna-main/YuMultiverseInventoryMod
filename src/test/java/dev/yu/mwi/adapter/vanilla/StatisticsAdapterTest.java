package dev.yu.mwi.adapter.vanilla;

import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatisticsAdapterTest {
    @Test
    void ignoresStatisticValuesRemovedWithAMod() {
        CompoundTag payload = payload("minecraft:mined", "evolvedmekanism:end_stone_lead_ore", 3);

        assertEquals(0, new StatisticsAdapter().decode(payload, (type, value) -> Optional.empty()).size());
    }

    @Test
    void ignoresStatisticTypesRemovedWithAMod() {
        CompoundTag payload = payload("removed_mod:custom_stat", "removed_mod:value", 3);

        assertEquals(0, new StatisticsAdapter().decode(payload, (type, value) -> Optional.empty()).size());
    }

    @Test
    void malformedStatisticIdentifiersRemainFatal() {
        CompoundTag payload = payload("not a resource location", "minecraft:stone", 3);

        assertThrows(IllegalArgumentException.class,
                () -> new StatisticsAdapter().decode(payload, (type, value) -> Optional.empty()));
    }

    private static CompoundTag payload(String type, String value, int count) {
        CompoundTag entry = new CompoundTag();
        entry.putString("type", type);
        entry.putString("value", value);
        entry.putInt("count", count);
        ListTag entries = new ListTag();
        entries.add(entry);
        CompoundTag payload = new CompoundTag();
        payload.put("statistics", entries);
        return payload;
    }
}
