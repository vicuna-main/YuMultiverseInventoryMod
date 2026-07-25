package dev.yu.mwi.domain;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable group declaration. Share expressions may contain aliases and negative entries. */
public record WorldGroup(
        String name,
        List<WorldSelector> selectors,
        List<String> shareExpressions
) {
    public WorldGroup {
        name = DomainValidation.requireGroupId(name, "group name");
        selectors = List.copyOf(Objects.requireNonNull(selectors, "selectors"));
        shareExpressions = List.copyOf(Objects.requireNonNull(shareExpressions, "shareExpressions"));
        if (selectors.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("selectors must not contain null");
        }
        if (shareExpressions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("shareExpressions must not contain null");
        }
    }

    public static WorldGroup of(String name, Collection<String> worldSelectors, Collection<String> shareExpressions) {
        Objects.requireNonNull(shareExpressions, "shareExpressions");
        return new WorldGroup(name, WorldSelector.parseAll(worldSelectors), List.copyOf(shareExpressions));
    }

    public boolean matches(WorldIdentity world) {
        Objects.requireNonNull(world, "world");
        return selectors.stream().anyMatch(selector -> selector.matches(world));
    }

    public Set<String> resolveShares(ShareCatalog catalog) {
        return Objects.requireNonNull(catalog, "catalog").expand(shareExpressions);
    }
}
