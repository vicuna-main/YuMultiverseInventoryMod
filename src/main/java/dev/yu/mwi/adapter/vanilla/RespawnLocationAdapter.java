package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
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
        replace(new PlayerRespawnState(player), null);
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        applyTo(new PlayerRespawnState(player), data);
    }

    @Override
    public void sync(ServerPlayer player) {
        // Vanilla has no clientbound packet for the player's server-side respawn location.
    }

    @Override
    public boolean requiresAdvancementListenerSuspension() {
        return false;
    }

    static void applyTo(RespawnState state, CompoundTag data) {
        RespawnLocationPayload payload = RespawnLocationPayload.decode(data);
        replace(state, payload.target().orElse(null));
    }

    private static void replace(RespawnState state, RespawnLocationPayload.Target target) {
        ResourceKey<Level> dimension = target == null ? Level.OVERWORLD : target.dimension();
        BlockPos position = target == null ? null : target.position();
        float angle = target == null ? 0.0F : target.angle();
        boolean forced = target != null && target.forced();

        state.replace(dimension, position, angle, forced);
        if (!java.util.Objects.equals(position, state.position())
                || !dimension.equals(state.dimension())
                || Float.compare(angle, state.angle()) != 0
                || forced != state.forced()) {
            throw new IllegalStateException("respawn location application was rejected for player " + state.playerId());
        }
    }

    interface RespawnState {
        java.util.UUID playerId();

        ResourceKey<Level> dimension();

        BlockPos position();

        float angle();

        boolean forced();

        void replace(ResourceKey<Level> dimension, BlockPos position, float angle, boolean forced);
    }

    private record PlayerRespawnState(ServerPlayer player) implements RespawnState {
        private PlayerRespawnState {
            java.util.Objects.requireNonNull(player, "player");
        }

        @Override
        public java.util.UUID playerId() {
            return player.getUUID();
        }

        @Override
        public ResourceKey<Level> dimension() {
            return player.getRespawnDimension();
        }

        @Override
        public BlockPos position() {
            return player.getRespawnPosition();
        }

        @Override
        public float angle() {
            return player.getRespawnAngle();
        }

        @Override
        public boolean forced() {
            return player.isRespawnForced();
        }

        @Override
        public void replace(ResourceKey<Level> dimension, BlockPos position, float angle, boolean forced) {
            player.respawnDimension = dimension;
            player.respawnPosition = position;
            player.respawnAngle = angle;
            player.respawnForced = forced;
        }
    }
}
