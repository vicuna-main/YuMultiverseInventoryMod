package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** Captures total experience and fractional progress independently from the level. */
public final class ExperiencePointsAdapter extends AbstractVanillaAdapter {
    private static final String TOTAL = "total";
    private static final String PROGRESS = "progress";

    public ExperiencePointsAdapter() {
        super(AdapterIds.XP);
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        CompoundTag data = new CompoundTag();
        data.putInt(TOTAL, player.totalExperience);
        data.putFloat(PROGRESS, player.experienceProgress);
        return data;
    }

    @Override
    public void clear(ServerPlayer player) {
        player.totalExperience = 0;
        player.experienceProgress = 0.0F;
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        NbtPayload.requireData(data, id().toString());
        int total = NbtPayload.requireInt(data, TOTAL, id().toString());
        float progress = NbtPayload.requireFloat(data, PROGRESS, id().toString());
        if (total < 0) {
            throw NbtPayload.invalid(id().toString(), "total experience must be non-negative");
        }
        if (progress < 0.0F || progress >= 1.0F) {
            throw NbtPayload.invalid(id().toString(), "progress must be in [0, 1)");
        }
        player.totalExperience = total;
        player.experienceProgress = progress;
    }

    @Override
    public void sync(ServerPlayer player) {
        PlayerSync.experience(player);
    }

    @Override
    public boolean requiresAdvancementListenerSuspension() {
        return false;
    }
}
