package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Captures and restores the exact server-side player location. */
public final class LastLocationAdapter extends AbstractVanillaAdapter {
    private static final double MAX_HORIZONTAL_COORDINATE = 29_999_984.0D;
    private static final double MAX_VERTICAL_COORDINATE = 20_000_000.0D;
    private static final String DIMENSION = "dimension";
    private static final String X = "x";
    private static final String Y = "y";
    private static final String Z = "z";
    private static final String YAW = "yaw";
    private static final String PITCH = "pitch";

    public LastLocationAdapter() {
        super(AdapterIds.LAST_LOCATION);
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        CompoundTag data = new CompoundTag();
        data.putString(DIMENSION, player.serverLevel().dimension().location().toString());
        data.putDouble(X, player.getX());
        data.putDouble(Y, player.getY());
        data.putDouble(Z, player.getZ());
        data.putFloat(YAW, player.getYRot());
        data.putFloat(PITCH, player.getXRot());
        return data;
    }

    @Override
    public void clear(ServerPlayer player) {
        // A live entity cannot have an absent location; apply atomically replaces it.
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        NbtPayload.requireData(data, id().toString());
        ResourceLocation dimensionId;
        String encodedDimension = NbtPayload.requireString(data, DIMENSION, id().toString());
        try {
            dimensionId = ResourceLocation.parse(encodedDimension);
        } catch (RuntimeException exception) {
            throw NbtPayload.invalid(id().toString(), "invalid dimension id '" + encodedDimension + "'");
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        ServerLevel targetLevel = player.server.getLevel(dimension);
        if (targetLevel == null) {
            throw NbtPayload.invalid(id().toString(), "unknown or unloaded dimension " + dimensionId);
        }

        double x = NbtPayload.requireDouble(data, X, id().toString());
        double y = NbtPayload.requireDouble(data, Y, id().toString());
        double z = NbtPayload.requireDouble(data, Z, id().toString());
        float yaw = NbtPayload.requireFloat(data, YAW, id().toString());
        float pitch = NbtPayload.requireFloat(data, PITCH, id().toString());
        if (Math.abs(x) > MAX_HORIZONTAL_COORDINATE || Math.abs(z) > MAX_HORIZONTAL_COORDINATE
                || Math.abs(y) > MAX_VERTICAL_COORDINATE) {
            throw NbtPayload.invalid(id().toString(), "coordinates are outside Minecraft's safe entity range");
        }
        BlockPos blockPosition = BlockPos.containing(x, y, z);
        if (!Level.isInSpawnableBounds(blockPosition)) {
            throw NbtPayload.invalid(id().toString(), "coordinates are outside Minecraft's spawnable bounds");
        }

        if (!player.teleportTo(targetLevel, x, y, z, Set.of(), yaw, pitch)) {
            throw new IllegalStateException("location teleport was rejected for player " + player.getUUID());
        }
        if (player.serverLevel() != targetLevel
                || player.position().distanceToSqr(x, y, z) > 1.0E-6D) {
            throw new IllegalStateException("location teleport did not reach its target for player " + player.getUUID());
        }
    }

    @Override
    public void sync(ServerPlayer player) {
        // ServerPlayer.teleportTo synchronizes position and dimension as part of the operation.
    }

    @Override
    public boolean requiresAdvancementListenerSuspension() {
        // Teleportation is an event-capable gameplay path and must remain protected by default.
        return true;
    }
}
