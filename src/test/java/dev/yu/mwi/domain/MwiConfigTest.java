package dev.yu.mwi.domain;

import dev.yu.mwi.config.MwiConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MwiConfigTest {
    @Test
    void defaultConfigurationMatchesRecommendedSafetySettings() {
        MwiConfig config = MwiConfig.defaults();

        assertFalse(config.shareHandling().enableGameModeProfiles());
        assertFalse(config.shareHandling().defaultUngroupedWorlds());
        assertTrue(config.shareHandling().strictUnknownModSlots());
        assertTrue(config.shareHandling().alwaysWriteWorldProfile());
        assertFalse(config.shareHandling().restoreLastLocation());
        assertEquals(MwiConfig.ProfileConflictPolicy.PREFER_CURRENT_WORLD,
                config.migration().onProfileConflict());
        assertTrue(config.migration().archiveConflicts());
        assertEquals(List.of("default", "resource", "lobby"),
                config.groups().stream().map(WorldGroup::name).toList());
    }

    @Test
    void copiesNestedCollections() {
        List<String> targets = new ArrayList<>(List.of("health"));
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("stats", targets);
        List<WorldGroup> groups = new ArrayList<>(List.of(
                WorldGroup.of("default", List.of("world"), List.of("stats"))
        ));
        MwiConfig config = new MwiConfig(
                MwiConfig.ShareHandling.defaults(), aliases, groups, MwiConfig.Migration.defaults()
        );
        targets.add("hunger");
        aliases.clear();
        groups.clear();

        assertEquals(Map.of("stats", List.of("health")), config.shareAliases());
        assertEquals(1, config.groups().size());
        assertThrows(UnsupportedOperationException.class, () -> config.groups().clear());
    }

    @Test
    void namedWorldMigrationPolicyRequiresExactlyOneWorldName() {
        assertThrows(IllegalArgumentException.class, () -> new MwiConfig.Migration(
                MwiConfig.ProfileConflictPolicy.PREFER_NAMED_WORLD, Optional.empty(), true, true));
        assertThrows(IllegalArgumentException.class, () -> new MwiConfig.Migration(
                MwiConfig.ProfileConflictPolicy.PREFER_NEWEST, Optional.of("world"), true, true));

        MwiConfig.Migration migration = new MwiConfig.Migration(
                MwiConfig.ProfileConflictPolicy.PREFER_NAMED_WORLD, Optional.of(" world "), true, true);
        assertEquals(Optional.of("world"), migration.preferredWorld());
    }

    @Test
    void createsResolverWithImplicitDefaultWhenEnabled() {
        MwiConfig config = new MwiConfig(
                new MwiConfig.ShareHandling(false, true, true, true, false, "default"),
                Map.of(),
                List.of(WorldGroup.of("default", List.of("world"), List.of("health"))),
                MwiConfig.Migration.defaults()
        );
        ShareCatalog catalog = config.createShareCatalog(List.of("health"));

        assertEquals(ScopeKey.group("default"),
                config.createGroupResolver(catalog).resolve(new WorldIdentity("custom"), "health"));
    }
}
