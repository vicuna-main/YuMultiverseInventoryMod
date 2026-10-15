package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** Captures total experience and fractional progress independently from the level. */
public final class ExperiencePointsAdapter extends AbstractVanillaAdapter {
    private static final String TOTAL = "total";
    private static final String PROGRESS = "progress";
    private static final float MAX_REASONABLE_MODDED_PROGRESS = 1_000_000.0F;

    public ExperiencePointsAdapter() {
        super(AdapterIds.XP);
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        CompoundTag data = new CompoundTag();
        data.putInt(TOTAL, player.totalExperience);
        data.putFloat(PROGRESS, player.experienceProgress);
        validatePayload(data, id().toString());
        return data;
    }

    @Override
    public void clear(ServerPlayer player) {
        player.totalExperience = 0;
        player.experienceProgress = 0.0F;
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        ValidatedExperience values = validatePayload(data, id().toString());
        player.totalExperience = values.total();
        player.experienceProgress = values.progress();
    }

    static ValidatedExperience validatePayload(CompoundTag data, String adapterId) {
        NbtPayload.requireData(data, adapterId);
        int total = NbtPayload.requireInt(data, TOTAL, adapterId);
        float progress = NbtPayload.requireFloat(data, PROGRESS, adapterId);
        if (total < 0) {
            throw NbtPayload.invalid(adapterId, "total experience must be non-negative");
        }
        if (progress < 0.0F || progress > MAX_REASONABLE_MODDED_PROGRESS) {
            throw NbtPayload.invalid(
                    adapterId, "progress must be finite and in [0, " + MAX_REASONABLE_MODDED_PROGRESS + "]");
        }
        return new ValidatedExperience(total, progress);
    }

    record ValidatedExperience(int total, float progress) {
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
