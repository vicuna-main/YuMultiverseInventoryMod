package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Captures the player's bed/anchor respawn dimension, position, angle, and forced flag. */
public final class RespawnLocationAdapter extends AbstractVanillaAdapter {
    private static final String PRESENT = "present";
    private static final String DIMENSION = "dimension";
    private static final String POSITION = "position";
    private static final String ANGLE = "angle";
    private static final String FORCED = "forced";

    public RespawnLocationAdapter() {
        super(AdapterIds.BED_SPAWN);
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        CompoundTag data = new CompoundTag();
        BlockPos position = player.getRespawnPosition();
        data.putBoolean(PRESENT, position != null);
        if (position != null) {
            data.putString(DIMENSION, player.getRespawnDimension().location().toString());
            data.putLong(POSITION, position.asLong());
            data.putFloat(ANGLE, player.getRespawnAngle());
            data.putBoolean(FORCED, player.isRespawnForced());
        }
        return data;
    }

    @Override
    public void clear(ServerPlayer player) {
        player.respawnDimension = Level.OVERWORLD;
        player.respawnPosition = null;
        player.respawnAngle = 0.0F;
        player.respawnForced = false;
        if (player.getRespawnPosition() != null) {
            throw new IllegalStateException("respawn location removal was rejected for player " + player.getUUID());
        }
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        NbtPayload.requireData(data, id().toString());
        if (!NbtPayload.requireBoolean(data, PRESENT, id().toString())) {
            clear(player);
            return;
        }

        ResourceLocation dimensionId = parseResourceLocation(
                NbtPayload.requireString(data, DIMENSION, id().toString()), DIMENSION);
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        ServerLevel level = player.server.getLevel(dimension);
        if (level == null) {
            throw NbtPayload.invalid(id().toString(), "unknown or unloaded dimension " + dimensionId);
        }
        BlockPos position = BlockPos.of(NbtPayload.requireLong(data, POSITION, id().toString()));
        float angle = NbtPayload.requireFloat(data, ANGLE, id().toString());
        boolean forced = NbtPayload.requireBoolean(data, FORCED, id().toString());

        player.respawnDimension = dimension;
        player.respawnPosition = position;
        player.respawnAngle = angle;
        player.respawnForced = forced;
        if (!position.equals(player.getRespawnPosition())
                || !dimension.equals(player.getRespawnDimension())
                || Float.compare(angle, player.getRespawnAngle()) != 0
                || forced != player.isRespawnForced()) {
            throw new IllegalStateException("respawn location application was rejected for player " + player.getUUID());
        }
    }

    @Override
    public void sync(ServerPlayer player) {
        // Vanilla has no clientbound packet for the player's server-side respawn location.
    }

    @Override
    public boolean requiresAdvancementListenerSuspension() {
        return false;
    }

    private ResourceLocation parseResourceLocation(String value, String field) {
        try {
            return ResourceLocation.parse(value);
        } catch (RuntimeException exception) {
            throw NbtPayload.invalid(id().toString(), "invalid " + field + " id '" + value + "'");
        }
    }
}
