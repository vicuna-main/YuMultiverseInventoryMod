package dev.yu.mwi.adapter.vanilla;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodAdapterTest {
    private static final String ADAPTER = "yu_multiverse_inventory:hunger";

    @Test
    void acceptsFiniteModdedSaturationAboveVanillaTwenty() {
        CompoundTag payload = payload(20, 0, 20.600000381469727F, 1.6692344F);
        assertDoesNotThrow(() -> FoodAdapter.validatePayload(payload, ADAPTER));
    }

    @Test
    void acceptsFiniteModdedExhaustionAboveVanillaForty() {
        CompoundTag payload = payload(20, 0, 4.0F, 42.5F);
        assertDoesNotThrow(() -> FoodAdapter.validatePayload(payload, ADAPTER));
    }

    @Test
    void repairsObservedGorgonNegativeExhaustionToSafeDefault() {
        CompoundTag payload = payload(17, 0, 0.0F, -29.58652114868164F);

        FoodAdapter.ExhaustionRepair repair = FoodAdapter.repairExhaustion(payload, ADAPTER);

        assertTrue(repair.repaired());
        assertEquals("below_minimum", repair.reason());
        assertEquals(-29.58652114868164F, repair.original());
        assertEquals(0.0F, repair.repairedValue());
        assertEquals(0.0F, payload.getFloat("foodExhaustionLevel"));
        assertDoesNotThrow(() -> FoodAdapter.validatePayload(payload, ADAPTER));
    }

    @Test
    void repairsNonFiniteAndClearlyCorruptExhaustionWithoutWeakeningOtherFields() {
        CompoundTag nan = payload(20, 0, 4.0F, Float.NaN);
        CompoundTag infinite = payload(20, 0, 4.0F, Float.POSITIVE_INFINITY);
        CompoundTag excessive = payload(20, 0, 4.0F, 1_000_001.0F);

        assertEquals(0.0F, repair(nan).repairedValue());
        assertEquals(0.0F, repair(infinite).repairedValue());
        assertEquals(0.0F, repair(excessive).repairedValue());
        assertDoesNotThrow(() -> FoodAdapter.validatePayload(nan, ADAPTER));
        assertDoesNotThrow(() -> FoodAdapter.validatePayload(infinite, ADAPTER));
        assertDoesNotThrow(() -> FoodAdapter.validatePayload(excessive, ADAPTER));

        assertThrows(IllegalArgumentException.class,
                () -> FoodAdapter.validatePayload(payload(20, 0, -0.1F, 0.0F), ADAPTER));
    }

    @Test
    void leavesValidExhaustionUnchanged() {
        CompoundTag payload = payload(20, 0, 4.0F, 42.5F);

        FoodAdapter.ExhaustionRepair repair = FoodAdapter.repairExhaustion(payload, ADAPTER);

        assertFalse(repair.repaired());
        assertEquals(42.5F, payload.getFloat("foodExhaustionLevel"));
    }

    @Test
    void validationStillRejectsUnrepairedNonFiniteAndClearlyCorruptValues() {
        assertThrows(IllegalArgumentException.class,
                () -> FoodAdapter.validatePayload(payload(20, 0, Float.NaN, 0.0F), ADAPTER));
        assertThrows(IllegalArgumentException.class,
                () -> FoodAdapter.validatePayload(payload(20, 0, Float.POSITIVE_INFINITY, 0.0F), ADAPTER));
        assertThrows(IllegalArgumentException.class,
                () -> FoodAdapter.validatePayload(payload(20, 0, -0.1F, 0.0F), ADAPTER));
        assertThrows(IllegalArgumentException.class,
                () -> FoodAdapter.validatePayload(payload(20, 0, 1_000_001.0F, 0.0F), ADAPTER));
    }

    private static FoodAdapter.ExhaustionRepair repair(CompoundTag payload) {
        return FoodAdapter.repairExhaustion(payload, ADAPTER);
    }

    private static CompoundTag payload(int food, int tick, float saturation, float exhaustion) {
        CompoundTag payload = new CompoundTag();
        payload.putInt("foodLevel", food);
        payload.putInt("foodTickTimer", tick);
        payload.putFloat("foodSaturationLevel", saturation);
        payload.putFloat("foodExhaustionLevel", exhaustion);
        return payload;
    }
}
