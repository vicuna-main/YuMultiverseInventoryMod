package dev.yu.mwi.adapter.vanilla;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthAdapterTest {
    @Test
    void restoresDeferredHealthWhenEquipmentRaisesMaximum() {
        assertEquals(HealthAdapter.DeferredAction.RESTORE,
                HealthAdapter.deferredAction(21.2F, 24.0F, 0));
    }

    @Test
    void waitsBrieflyForEquipmentAttributeUpdates() {
        assertEquals(HealthAdapter.DeferredAction.WAIT,
                HealthAdapter.deferredAction(21.2F, 19.08F, 3));
    }

    @Test
    void clampsInsteadOfQuarantiningWhenMaximumRemainsLower() {
        assertEquals(HealthAdapter.DeferredAction.CLAMP,
                HealthAdapter.deferredAction(21.2F, 19.08F, 4));
    }
}
