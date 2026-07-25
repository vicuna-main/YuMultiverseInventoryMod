package dev.yu.mwi.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroupResolverTest {
    private final ShareCatalog catalog = new ShareCatalog(
            List.of("inventory_contents", "health", "hunger"),
            java.util.Map.of("survival", List.of("inventory_contents", "health", "hunger"))
    );

    @Test
    void resolvesEachShareIndependentlyAcrossOverlappingGroups() {
        WorldGroup inventory = WorldGroup.of("inventory", List.of("world*"), List.of("inventory_contents"));
        WorldGroup stats = WorldGroup.of("stats", List.of("r=world.*"), List.of("health", "hunger"));
        GroupResolver resolver = new GroupResolver(List.of(inventory, stats), catalog);
        WorldIdentity world = new WorldIdentity("minecraft:overworld", java.util.Set.of("world"));

        assertEquals(ScopeKey.group("inventory"), resolver.resolve(world, "inventory_contents"));
        assertEquals(ScopeKey.group("stats"), resolver.resolve(world, "health"));
    }

    @Test
    void fallsBackToCanonicalWorldScope() {
        GroupResolver resolver = new GroupResolver(List.of(), catalog);
        WorldIdentity world = new WorldIdentity("minecraft:overworld", java.util.Set.of("world"));

        assertEquals(ScopeKey.world(world), resolver.resolve(world, "health"));
    }

    @Test
    void implicitDefaultOnlyAppliesSharesDeclaredByDefaultGroup() {
        WorldGroup defaultGroup = WorldGroup.of("default", List.of("world"), List.of("health"));
        GroupResolver resolver = new GroupResolver(List.of(defaultGroup), catalog, "default");
        WorldIdentity customWorld = new WorldIdentity("custom:dimension");

        assertEquals(ScopeKey.group("default"), resolver.resolve(customWorld, "health"));
        assertEquals(ScopeKey.world(customWorld), resolver.resolve(customWorld, "hunger"));
    }

    @Test
    void reportsConflictingGroupNamesInsteadOfChoosingByOrder() {
        WorldGroup first = WorldGroup.of("first", List.of("world*"), List.of("health"));
        WorldGroup second = WorldGroup.of("second", List.of("r=world.*"), List.of("health"));
        GroupResolver resolver = new GroupResolver(List.of(first, second), catalog);

        GroupConflictException exception = assertThrows(GroupConflictException.class,
                () -> resolver.resolve(new WorldIdentity("world_nether"), "health"));
        assertEquals(List.of("first", "second"), exception.groupNames());
        assertEquals("health", exception.shareId());
    }

    @Test
    void rejectsDuplicateAndMissingDefaultGroups() {
        WorldGroup group = WorldGroup.of("same", List.of("world"), List.of("health"));

        assertThrows(IllegalArgumentException.class, () -> new GroupResolver(List.of(group, group), catalog));
        assertThrows(IllegalArgumentException.class, () -> new GroupResolver(List.of(group), catalog, "missing"));
    }
}
