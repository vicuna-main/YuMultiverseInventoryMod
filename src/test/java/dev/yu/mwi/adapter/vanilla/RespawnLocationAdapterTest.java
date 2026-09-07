package dev.yu.mwi.adapter.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class RespawnLocationAdapterTest {
    private static final ResourceKey<Level> CURRENT_WORLD = dimension("minecraft:playerworld/chigurengyp");
    private static final ResourceKey<Level> ON_DEMAND_WORLD = dimension("minecraft:playerworld/fushengruomeng");

    @Test
    void restoresEveryFieldWithoutConsultingLoadedLevelState() throws Exception {
        FakeRespawnState state = new FakeRespawnState(
                CURRENT_WORLD, new BlockPos(1, 70, 1), 0.0F, false);
        CompoundTag payload = presentPayload(ON_DEMAND_WORLD.location().toString(), new BlockPos(30, 81, -44),
                137.5F, true);

        RespawnLocationAdapter.applyTo(state, payload);

        assertEquals(ON_DEMAND_WORLD, state.dimension());
        assertEquals(new BlockPos(30, 81, -44), state.position());
        assertEquals(137.5F, state.angle());
        assertTrue(state.forced());
        assertEquals(1, state.replacements);

        String source = Files.readString(Path.of(
                "src/main/java/dev/yu/mwi/adapter/vanilla/RespawnLocationAdapter.java"));
        assertFalse(source.contains("getLevel("));
        assertFalse(source.contains("Multiverse"));
        assertFalse(source.contains("YuDefender"));
    }

    @Test
    void acceptsSyntacticallyValidDimensionEvenWhenItMayHaveBeenPermanentlyDeleted() {
        ResourceKey<Level> removed = dimension("retired_mod:removed_private_world");
        FakeRespawnState state = new FakeRespawnState(Level.OVERWORLD, null, 0.0F, false);

        RespawnLocationAdapter.applyTo(
                state, presentPayload(removed.location().toString(), new BlockPos(2, 64, 3), -45.0F, false));

        assertEquals(removed, state.dimension());
        assertEquals(new BlockPos(2, 64, 3), state.position());
    }

    @Test
    void absentPayloadClearsAllRespawnFields() {
        FakeRespawnState state = new FakeRespawnState(
                ON_DEMAND_WORLD, new BlockPos(30, 81, -44), 137.5F, true);

        RespawnLocationAdapter.applyTo(state, RespawnLocationPayload.clearedPayload());

        assertEquals(Level.OVERWORLD, state.dimension());
        assertNull(state.position());
        assertEquals(0.0F, state.angle());
        assertFalse(state.forced());
    }

    @Test
    void structuralErrorsNeverPartiallyMutateLiveState() {
        List<CompoundTag> invalid = new ArrayList<>();
        CompoundTag missingDimension = presentPayload(
                ON_DEMAND_WORLD.location().toString(), BlockPos.ZERO, 0.0F, false);
        missingDimension.remove("dimension");
        invalid.add(missingDimension);

        for (String field : List.of("position", "angle", "forced")) {
            CompoundTag missingField = presentPayload(
                    ON_DEMAND_WORLD.location().toString(), BlockPos.ZERO, 0.0F, false);
            missingField.remove(field);
            invalid.add(missingField);
        }

        CompoundTag wrongDimensionType = presentPayload(
                ON_DEMAND_WORLD.location().toString(), BlockPos.ZERO, 0.0F, false);
        wrongDimensionType.putInt("dimension", 1);
        invalid.add(wrongDimensionType);

        invalid.add(presentPayload(" ", BlockPos.ZERO, 0.0F, false));
        invalid.add(presentPayload("Minecraft:INVALID WORLD", BlockPos.ZERO, 0.0F, false));

        CompoundTag wrongPositionType = presentPayload(
                ON_DEMAND_WORLD.location().toString(), BlockPos.ZERO, 0.0F, false);
        wrongPositionType.putString("position", "0");
        invalid.add(wrongPositionType);

        CompoundTag wrongNumericPositionType = presentPayload(
                ON_DEMAND_WORLD.location().toString(), BlockPos.ZERO, 0.0F, false);
        wrongNumericPositionType.putInt("position", 0);
        invalid.add(wrongNumericPositionType);

        CompoundTag wrongNumericAngleType = presentPayload(
                ON_DEMAND_WORLD.location().toString(), BlockPos.ZERO, 0.0F, false);
        wrongNumericAngleType.putDouble("angle", 0.0D);
        invalid.add(wrongNumericAngleType);

        invalid.add(presentPayload(ON_DEMAND_WORLD.location().toString(), BlockPos.ZERO, Float.NaN, false));
        invalid.add(presentPayload(
                ON_DEMAND_WORLD.location().toString(), BlockPos.ZERO, Float.POSITIVE_INFINITY, false));

        CompoundTag wrongForcedType = presentPayload(
                ON_DEMAND_WORLD.location().toString(), BlockPos.ZERO, 0.0F, false);
        wrongForcedType.putString("forced", "false");
        invalid.add(wrongForcedType);

        CompoundTag wrongPresentType = new CompoundTag();
        wrongPresentType.putString("present", "true");
        invalid.add(wrongPresentType);

        for (CompoundTag payload : invalid) {
            FakeRespawnState state = new FakeRespawnState(
                    CURRENT_WORLD, new BlockPos(7, 75, 9), 10.0F, true);
            assertThrows(IllegalArgumentException.class, () -> RespawnLocationAdapter.applyTo(state, payload));
            assertEquals(CURRENT_WORLD, state.dimension());
            assertEquals(new BlockPos(7, 75, 9), state.position());
            assertEquals(10.0F, state.angle());
            assertTrue(state.forced());
            assertEquals(0, state.replacements);
        }
    }

    @Test
    void laterTransactionFailureCanRestoreTheCompleteBeforeImage() {
        CompoundTag before = presentPayload(
                CURRENT_WORLD.location().toString(), new BlockPos(7, 75, 9), 10.0F, true);
        FakeRespawnState state = new FakeRespawnState(
                CURRENT_WORLD, new BlockPos(7, 75, 9), 10.0F, true);

        RespawnLocationAdapter.applyTo(state, presentPayload(
                ON_DEMAND_WORLD.location().toString(), new BlockPos(30, 81, -44), 137.5F, false));
        RespawnLocationAdapter.applyTo(state, before);

        assertEquals(CURRENT_WORLD, state.dimension());
        assertEquals(new BlockPos(7, 75, 9), state.position());
        assertEquals(10.0F, state.angle());
        assertTrue(state.forced());
        assertEquals(2, state.replacements);
    }

    private static CompoundTag presentPayload(
            String dimension, BlockPos position, float angle, boolean forced) {
        CompoundTag payload = new CompoundTag();
        payload.putBoolean("present", true);
        payload.putString("dimension", dimension);
        payload.putLong("position", position.asLong());
        payload.putFloat("angle", angle);
        payload.putBoolean("forced", forced);
        return payload;
    }

    private static ResourceKey<Level> dimension(String id) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id));
    }

    private static final class FakeRespawnState implements RespawnLocationAdapter.RespawnState {
        private final UUID playerId = UUID.randomUUID();
        private ResourceKey<Level> dimension;
        private BlockPos position;
        private float angle;
        private boolean forced;
        private int replacements;

        private FakeRespawnState(
                ResourceKey<Level> dimension, BlockPos position, float angle, boolean forced) {
            this.dimension = dimension;
            this.position = position;
            this.angle = angle;
            this.forced = forced;
        }

        @Override
        public UUID playerId() {
            return playerId;
        }

        @Override
        public ResourceKey<Level> dimension() {
            return dimension;
        }

        @Override
        public BlockPos position() {
            return position;
        }

        @Override
        public float angle() {
            return angle;
        }

        @Override
        public boolean forced() {
            return forced;
        }

        @Override
        public void replace(ResourceKey<Level> dimension, BlockPos position, float angle, boolean forced) {
            this.dimension = dimension;
            this.position = position;
            this.angle = angle;
            this.forced = forced;
            replacements++;
        }
    }
}
