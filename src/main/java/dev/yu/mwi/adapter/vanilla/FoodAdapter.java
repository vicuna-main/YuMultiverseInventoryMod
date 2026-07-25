package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;

/** Captures hunger, saturation, exhaustion, and the hunger tick timer. */
public final class FoodAdapter extends AbstractVanillaAdapter {
    private static final String FOOD_LEVEL = "foodLevel";
    private static final String TICK_TIMER = "foodTickTimer";
    private static final String SATURATION = "foodSaturationLevel";
    private static final String EXHAUSTION = "foodExhaustionLevel";

    public FoodAdapter() {
        super(AdapterIds.HUNGER);
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        CompoundTag data = new CompoundTag();
        player.getFoodData().addAdditionalSaveData(data);
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
        NbtPayload.requireData(data, id().toString());
        int foodLevel = NbtPayload.requireInt(data, FOOD_LEVEL, id().toString());
        int tickTimer = NbtPayload.requireInt(data, TICK_TIMER, id().toString());
        float saturation = NbtPayload.requireFloat(data, SATURATION, id().toString());
        float exhaustion = NbtPayload.requireFloat(data, EXHAUSTION, id().toString());
        if (foodLevel < 0 || foodLevel > 20) {
            throw NbtPayload.invalid(id().toString(), "food level must be in [0, 20]");
        }
        if (tickTimer < 0) {
            throw NbtPayload.invalid(id().toString(), "food tick timer must be non-negative");
        }
        if (saturation < 0.0F || saturation > 20.0F) {
            throw NbtPayload.invalid(id().toString(), "saturation must be in [0, 20]");
        }
        if (exhaustion < 0.0F || exhaustion > 40.0F) {
            throw NbtPayload.invalid(id().toString(), "exhaustion must be in [0, 40]");
        }
        player.getFoodData().readAdditionalSaveData(data);
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
