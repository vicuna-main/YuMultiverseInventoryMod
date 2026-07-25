package dev.yu.mwi.service;

import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldIdentitiesTest {
    @Test
    void vanillaDimensionsExposeBukkitCompatibleAliases() {
        var overworld = WorldIdentities.from(Level.OVERWORLD);
        var nether = WorldIdentities.from(Level.NETHER);
        var end = WorldIdentities.from(Level.END);

        assertEquals("minecraft:overworld", overworld.name());
        assertTrue(overworld.hasName("world"));
        assertTrue(nether.hasName("world_nether"));
        assertTrue(nether.hasName("DIM-1"));
        assertTrue(end.hasName("world_the_end"));
        assertTrue(end.hasName("DIM1"));
    }

    @Test
    void namespacedWorldPathsPreserveSlashes() {
        var identity = WorldIdentities.fromName("playerworld/xxx");

        assertEquals("playerworld/xxx", identity.name());
        assertTrue(identity.hasName("xxx"));
    }
}
