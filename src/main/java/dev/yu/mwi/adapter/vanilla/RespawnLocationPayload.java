package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Fully validated, side-effect-free representation of a stored respawn location. */
public record RespawnLocationPayload(Optional<Target> target) {
    private static final String ADAPTER_ID = AdapterIds.BED_SPAWN.toString();
    private static final String PRESENT = "present";
    private static final String DIMENSION = "dimension";
    private static final String POSITION = "position";
    private static final String ANGLE = "angle";
    private static final String FORCED = "forced";

    public RespawnLocationPayload {
        target = Objects.requireNonNull(target, "target");
    }

    public static RespawnLocationPayload decode(CompoundTag data) {
        NbtPayload.requireData(data, ADAPTER_ID);
        if (!NbtPayload.requireBoolean(data, PRESENT, ADAPTER_ID)) {
            return new RespawnLocationPayload(Optional.empty());
        }

        String dimensionValue = NbtPayload.requireString(data, DIMENSION, ADAPTER_ID);
        ResourceLocation dimensionId;
        try {
            dimensionId = ResourceLocation.parse(dimensionValue);
        } catch (RuntimeException exception) {
            throw NbtPayload.invalid(ADAPTER_ID, "invalid dimension id '" + dimensionValue + "'");
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        if (!data.contains(POSITION, Tag.TAG_LONG)) {
            throw NbtPayload.invalid(ADAPTER_ID, "missing long '" + POSITION + "'");
        }
        if (!data.contains(ANGLE, Tag.TAG_FLOAT)) {
            throw NbtPayload.invalid(ADAPTER_ID, "missing float '" + ANGLE + "'");
        }
        BlockPos position = BlockPos.of(data.getLong(POSITION));
        float angle = data.getFloat(ANGLE);
        if (!Float.isFinite(angle)) {
            throw NbtPayload.invalid(ADAPTER_ID, "'" + ANGLE + "' must be finite");
        }
        boolean forced = NbtPayload.requireBoolean(data, FORCED, ADAPTER_ID);
        return new RespawnLocationPayload(Optional.of(new Target(dimension, position, angle, forced)));
    }

    public static CompoundTag clearedPayload() {
        CompoundTag payload = new CompoundTag();
        payload.putBoolean(PRESENT, false);
        return payload;
    }

    public boolean present() {
        return target.isPresent();
    }

    public record Target(ResourceKey<Level> dimension, BlockPos position, float angle, boolean forced) {
        public Target {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(position, "position");
            if (!Float.isFinite(angle)) {
                throw new IllegalArgumentException("angle must be finite");
            }
        }
    }
}
