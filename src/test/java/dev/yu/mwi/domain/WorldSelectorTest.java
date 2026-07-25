package dev.yu.mwi.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSelectorTest {
    private final WorldIdentity overworld = new WorldIdentity(
            "minecraft:overworld",
            Set.of("overworld", "world")
    );

    @Test
    void exactSelectorMatchesCanonicalNameOrAlias() {
        assertTrue(WorldSelector.parse("minecraft:overworld").matches(overworld));
        assertTrue(WorldSelector.parse("world").matches(overworld));
        assertFalse(WorldSelector.parse("world_nether").matches(overworld));
        assertEquals(WorldSelector.Kind.EXACT, WorldSelector.parse("world").kind());
    }

    @Test
    void globQuotesRegexCharactersAndMatchesAliases() {
        assertTrue(WorldSelector.parse("mine*:overworld").matches(overworld));
        assertTrue(WorldSelector.parse("worl?").matches(overworld));
        assertFalse(WorldSelector.parse("world.*").matches(overworld));
        assertEquals(WorldSelector.Kind.GLOB, WorldSelector.parse("world*").kind());
    }

    @Test
    void regexUsesFullMatchAndRPrefix() {
        WorldSelector selector = WorldSelector.parse("r=world(_[0-9]{1,8})?");

        assertTrue(selector.matches(overworld));
        assertFalse(selector.matches(new WorldIdentity("my_world")));
        assertEquals("r=world(_[0-9]{1,8})?", selector.expression());
        assertEquals(WorldSelector.Kind.REGEX, selector.kind());
    }

    @Test
    void invalidSelectorsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> WorldSelector.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> WorldSelector.parse("r="));
        assertThrows(IllegalArgumentException.class, () -> WorldSelector.parse("r=["));
        assertThrows(IllegalArgumentException.class, () -> WorldSelector.parse("r=(a+)+"));
        assertThrows(IllegalArgumentException.class, () -> WorldSelector.parse("r=(?=world)world"));
        assertThrows(IllegalArgumentException.class, () -> WorldSelector.parse("r=(a)\\1"));
        assertThrows(IllegalArgumentException.class,
                () -> WorldSelector.parse("r=" + "a".repeat(WorldSelector.MAX_EXPRESSION_LENGTH + 1)));
    }

    @Test
    void worldAliasesAreDefensivelyCopiedAndCanonicalNameDrivesScope() {
        Set<String> aliases = new java.util.LinkedHashSet<>(Set.of("world"));
        WorldIdentity identity = new WorldIdentity("minecraft:overworld", aliases);
        aliases.add("changed");

        assertEquals(Set.of("world"), identity.aliases());
        assertThrows(UnsupportedOperationException.class, () -> identity.aliases().add("changed"));
        assertEquals(new ScopeKey(ScopeKey.Type.WORLD, "minecraft:overworld"), ScopeKey.world(identity));
    }
}
