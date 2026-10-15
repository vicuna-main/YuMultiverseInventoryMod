package dev.yu.mwi.adapter.vanilla;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression coverage for scalar states that can be captured at modded/transient runtime values. */
class ScalarPayloadContractTest {
    @Test
    void zeroHealthIsAValidCapturedDeathState() {
        CompoundTag payload = new CompoundTag();
        payload.putFloat("health", 0.0F);
        payload.putFloat("absorption", 0.0F);
        assertDoesNotThrow(() -> HealthAdapter.validatePayload(payload, "health"));
    }

    @Test
    void moddedExperienceProgressCanExceedVanillaFractionWithoutBecomingCorruption() {
        CompoundTag payload = new CompoundTag();
        payload.putInt("total", 1234);
        payload.putFloat("progress", 1.25F);
        assertDoesNotThrow(() -> ExperiencePointsAdapter.validatePayload(payload, "xp"));
    }

    @Test
    void scalarContractsStillRejectNonFiniteValues() {
        CompoundTag health = new CompoundTag();
        health.putFloat("health", Float.NaN);
        health.putFloat("absorption", 0.0F);
        assertThrows(IllegalArgumentException.class,
                () -> HealthAdapter.validatePayload(health, "health"));

        CompoundTag experience = new CompoundTag();
        experience.putInt("total", 0);
        experience.putFloat("progress", Float.NEGATIVE_INFINITY);
        assertThrows(IllegalArgumentException.class,
                () -> ExperiencePointsAdapter.validatePayload(experience, "xp"));
    }
}
