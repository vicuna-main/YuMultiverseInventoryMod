package dev.yu.mwi.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShareCatalogTest {
    @Test
    void recursivelyExpandsAliasesToLeaves() {
        ShareCatalog catalog = new ShareCatalog(
                List.of("a", "b", "c"),
                Map.of("inner", List.of("a", "b"), "outer", List.of("inner", "c"))
        );

        assertEquals(Set.of("a", "b", "c"), catalog.expand("outer"));
        assertEquals(Set.of("a", "b"), catalog.expandAlias("inner"));
    }

    @Test
    void appliesNegativeEntriesAfterAllPositiveEntries() {
        ShareCatalog catalog = new ShareCatalog(
                List.of("a", "b", "c"),
                Map.of("all", List.of("a", "b", "c"), "remove", List.of("b", "c"))
        );

        assertEquals(Set.of("a"), catalog.expand("-remove", "all"));
        assertEquals(Set.of(), catalog.expand("-all"));
    }

    @Test
    void rejectsUnknownTargetsAndAliasCyclesAtConstruction() {
        assertThrows(UnknownShareException.class,
                () -> new ShareCatalog(List.of("known"), Map.of("bad", List.of("missing"))));

        ShareAliasCycleException exception = assertThrows(ShareAliasCycleException.class,
                () -> new ShareCatalog(List.of("known"), Map.of(
                        "first", List.of("second"),
                        "second", List.of("third"),
                        "third", List.of("first")
                )));
        assertEquals(exception.cycle().getFirst(), exception.cycle().getLast());
    }

    @Test
    void rejectsUnknownGroupExpression() {
        ShareCatalog catalog = new ShareCatalog(List.of("known"), Map.of());

        assertThrows(UnknownShareException.class, () -> catalog.expand("missing"));
    }

    @Test
    void standardAliasesMatchDocumentedContract() {
        ShareCatalog catalog = ShareCatalog.standard();
        Set<String> all = catalog.expand("all");

        assertTrue(all.containsAll(Set.of(
                "inventory_contents", "armor_contents", "off_hand", "ender_chest",
                "curios", "cosmetic_armor", "trash_slot",
                "xp", "lvl", "health", "hunger", "bed_spawn", "potion_effects",
                "recipes", "advancements", "statistics"
        )));
        assertFalse(all.contains("last_location"));
        assertFalse(all.contains("economy"));
        assertFalse(catalog.expand("inventory_plus").contains("accessory_slots"));
        assertFalse(catalog.expand("inventory_plus").contains("fashion_slots"));
    }

    @Test
    void catalogCollectionsAreImmutable() {
        ShareCatalog catalog = ShareCatalog.standard();

        assertThrows(UnsupportedOperationException.class, () -> catalog.leafShares().add("new"));
        assertThrows(UnsupportedOperationException.class, () -> catalog.aliases().put("new", List.of("xp")));
        assertThrows(UnsupportedOperationException.class, () -> catalog.aliases().get("all").add("xp"));
    }
}
