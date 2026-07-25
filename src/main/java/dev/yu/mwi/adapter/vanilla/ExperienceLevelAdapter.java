package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** Captures the experience level independently from points and progress. */
public final class ExperienceLevelAdapter extends AbstractVanillaAdapter {
    private static final String LEVEL = "level";

    public ExperienceLevelAdapter() {
        super(AdapterIds.LVL);
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        CompoundTag data = new CompoundTag();
        data.putInt(LEVEL, player.experienceLevel);
        return data;
    }

    @Override
    public void clear(ServerPlayer player) {
        player.experienceLevel = 0;
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        NbtPayload.requireData(data, id().toString());
        int level = NbtPayload.requireInt(data, LEVEL, id().toString());
        if (level < 0) {
            throw NbtPayload.invalid(id().toString(), "level must be non-negative");
        }
        player.experienceLevel = level;
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
