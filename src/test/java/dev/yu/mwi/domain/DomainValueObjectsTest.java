package dev.yu.mwi.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainValueObjectsTest {
    @Test
    void resolvesGameModeBucketAccordingToConfiguration() {
        assertEquals(GameModeBucket.SHARED, GameModeBucket.resolve("creative", false));
        assertEquals(GameModeBucket.CREATIVE, GameModeBucket.resolve("creative", true));
        assertEquals(GameModeBucket.SURVIVAL, GameModeBucket.fromName("SURVIVAL"));
        assertThrows(IllegalArgumentException.class, () -> GameModeBucket.fromName("shared"));
        assertThrows(IllegalArgumentException.class, () -> GameModeBucket.fromName("builder"));
    }

    @Test
    void profileKeyNormalizesShareId() {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = new ProfileKey(playerId, ScopeKey.group("default"), GameModeBucket.SHARED, "XP");

        assertEquals("xp", key.shareId());
        assertEquals(playerId, key.playerId());
    }

    @Test
    void globalScopeHasOneCanonicalIdentity() {
        assertEquals(new ScopeKey(ScopeKey.Type.GLOBAL, "global"), ScopeKey.global());
        assertThrows(IllegalArgumentException.class, () -> new ScopeKey(ScopeKey.Type.GLOBAL, "other"));
    }
}
