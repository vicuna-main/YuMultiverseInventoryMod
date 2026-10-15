package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Captures hunger, saturation, exhaustion, and the hunger tick timer. */
public final class FoodAdapter extends AbstractVanillaAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(FoodAdapter.class);
    private static final String FOOD_LEVEL = "foodLevel";
    private static final String TICK_TIMER = "foodTickTimer";
    private static final String SATURATION = "foodSaturationLevel";
    private static final String EXHAUSTION = "foodExhaustionLevel";

    /**
     * Vanilla normally keeps these values far lower, but modded FoodData implementations can extend
     * the range. MWI must not reject a finite value merely because it exceeds vanilla's UI/gameplay
     * convention. This ceiling is only a corruption/abuse guard, matching the policy used for
     * modded health values.
     */
    private static final float MAX_REASONABLE_MODDED_VALUE = 1_000_000.0F;
    private static final int MAX_REASONABLE_MODDED_FOOD_LEVEL = 1_000_000;

    public FoodAdapter() {
        super(AdapterIds.HUNGER);
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        CompoundTag data = new CompoundTag();
        player.getFoodData().addAdditionalSaveData(data);
        ExhaustionRepair repair = repairExhaustion(data, id().toString());
        validatePayload(data, id().toString());
        if (repair.repaired()) {
            // FoodData has no exhaustion setter. Re-reading the otherwise unchanged serialized
            // state repairs the live value as well as the snapshot, so the next vanilla player
            // save cannot keep reintroducing the anomaly on login.
            player.getFoodData().readAdditionalSaveData(data);
            logRepair(player, "capture", repair);
        }
        return data;
    }

    @Override
    public void clear(ServerPlayer player) {
        CompoundTag defaults = new CompoundTag();
        new FoodData().addAdditionalSaveData(defaults);
        player.getFoodData().readAdditionalSaveData(defaults);
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        CompoundTag normalized = data.copy();
        ExhaustionRepair repair = repairExhaustion(normalized, id().toString());
        validatePayload(normalized, id().toString());
        player.getFoodData().readAdditionalSaveData(normalized);
        if (repair.repaired()) {
            logRepair(player, "apply", repair);
        }
    }

    /**
     * Repairs scalar exhaustion anomalies locally instead of escalating a recoverable gameplay
     * value into whole-session quarantine. Structural hunger payload damage remains fatal.
     */
    static ExhaustionRepair repairExhaustion(CompoundTag data, String adapterId) {
        NbtPayload.requireData(data, adapterId);
        if (!data.contains(EXHAUSTION, Tag.TAG_ANY_NUMERIC)) {
            throw NbtPayload.invalid(adapterId, "missing float '" + EXHAUSTION + "'");
        }
        float original = data.getFloat(EXHAUSTION);
        float repaired;
        String reason;
        if (!Float.isFinite(original)) {
            repaired = 0.0F;
            reason = "non_finite";
        } else if (original < 0.0F) {
            repaired = 0.0F;
            reason = "below_minimum";
        } else if (original > MAX_REASONABLE_MODDED_VALUE) {
            repaired = 0.0F;
            reason = "above_maximum";
        } else {
            return new ExhaustionRepair(false, original, original, "none");
        }
        data.putFloat(EXHAUSTION, repaired);
        return new ExhaustionRepair(true, original, repaired, reason);
    }

    private static void logRepair(ServerPlayer player, String operation, ExhaustionRepair repair) {
        LOGGER.warn(
                "Auto-repaired hunger exhaustion for player {} ({}): operation={} reason={} original={} repaired={}; continuing without quarantine",
                player.getGameProfile().getName(),
                player.getUUID(),
                operation,
                repair.reason(),
                repair.original(),
                repair.repairedValue());
    }

    static void validatePayload(CompoundTag data, String adapterId) {
        NbtPayload.requireData(data, adapterId);
        int foodLevel = NbtPayload.requireInt(data, FOOD_LEVEL, adapterId);
        int tickTimer = NbtPayload.requireInt(data, TICK_TIMER, adapterId);
        float saturation = NbtPayload.requireFloat(data, SATURATION, adapterId);
        float exhaustion = NbtPayload.requireFloat(data, EXHAUSTION, adapterId);
        if (foodLevel < 0 || foodLevel > MAX_REASONABLE_MODDED_FOOD_LEVEL) {
            throw NbtPayload.invalid(
                    adapterId, "food level must be in [0, " + MAX_REASONABLE_MODDED_FOOD_LEVEL + "]");
        }
        if (tickTimer < 0) {
            throw NbtPayload.invalid(adapterId, "food tick timer must be non-negative");
        }
        if (saturation < 0.0F || saturation > MAX_REASONABLE_MODDED_VALUE) {
            throw NbtPayload.invalid(
                    adapterId, "saturation must be finite and in [0, " + MAX_REASONABLE_MODDED_VALUE + "]");
        }
        if (exhaustion < 0.0F || exhaustion > MAX_REASONABLE_MODDED_VALUE) {
            throw NbtPayload.invalid(
                    adapterId, "exhaustion must be finite and in [0, " + MAX_REASONABLE_MODDED_VALUE + "]");
        }
    }

    record ExhaustionRepair(boolean repaired, float original, float repairedValue, String reason) {
    }

    @Override
    public void sync(ServerPlayer player) {
        PlayerSync.healthAndFood(player);
    }

    @Override
    public boolean requiresAdvancementListenerSuspension() {
        return false;
    }
}
