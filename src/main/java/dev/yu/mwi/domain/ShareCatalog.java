package dev.yu.mwi.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validated catalog of leaf shares and recursively expanded aliases. */
public final class ShareCatalog {
    public static final Set<String> STANDARD_LEAF_SHARES = immutableSet(
            "inventory_contents", "armor_contents", "off_hand", "ender_chest",
            "xp", "lvl", "health", "hunger", "potion_effects", "bed_spawn",
            "last_location", "recipes", "advancements", "statistics", "economy",
            "curios", "curios:ring", "curios:charm", "cosmetic_armor", "fashion_slots",
            "accessory_slots", "trash_slot"
    );

    public static final Map<String, List<String>> STANDARD_ALIASES = standardAliases();

    private final Set<String> leafShares;
    private final Map<String, List<String>> aliases;
    private final Map<String, Set<String>> expandedAliases;

    public ShareCatalog(Collection<String> leafShares, Map<String, ? extends Collection<String>> aliases) {
        Objects.requireNonNull(leafShares, "leafShares");
        Objects.requireNonNull(aliases, "aliases");

        LinkedHashSet<String> normalizedLeaves = new LinkedHashSet<>();
        for (String leafShare : leafShares) {
            normalizedLeaves.add(DomainValidation.requireShareId(leafShare, "leaf share"));
        }
        LinkedHashMap<String, List<String>> normalizedAliases = new LinkedHashMap<>();
        aliases.forEach((name, targets) -> {
            String alias = DomainValidation.requireShareId(name, "alias name");
            if (normalizedLeaves.contains(alias)) {
                throw new IllegalArgumentException("share id is both a leaf and an alias: " + alias);
            }
            Objects.requireNonNull(targets, "targets for alias " + alias);
            List<String> normalizedTargets = new ArrayList<>(targets.size());
            for (String target : targets) {
                String normalizedTarget = DomainValidation.requireText(target, "alias target");
                if (normalizedTarget.startsWith("-")) {
                    throw new IllegalArgumentException("negative entries are not allowed inside aliases: " + target);
                }
                normalizedTargets.add(DomainValidation.requireShareId(normalizedTarget, "alias target"));
            }
            if (normalizedTargets.isEmpty()) {
                throw new IllegalArgumentException("alias must contain at least one target: " + alias);
            }
            if (normalizedAliases.put(alias, List.copyOf(normalizedTargets)) != null) {
                throw new IllegalArgumentException("duplicate alias after normalization: " + alias);
            }
        });

        this.leafShares = unmodifiableSet(normalizedLeaves);
        this.aliases = Collections.unmodifiableMap(normalizedAliases);
        this.expandedAliases = validateAndExpandAliases();
    }

    public static ShareCatalog standard() {
        return new ShareCatalog(STANDARD_LEAF_SHARES, STANDARD_ALIASES);
    }

    public static ShareCatalog standard(Collection<String> additionalLeafShares) {
        Objects.requireNonNull(additionalLeafShares, "additionalLeafShares");
        LinkedHashSet<String> leaves = new LinkedHashSet<>(STANDARD_LEAF_SHARES);
        leaves.addAll(additionalLeafShares);
        return new ShareCatalog(leaves, STANDARD_ALIASES);
    }

    public Set<String> leafShares() {
        return leafShares;
    }

    public Map<String, List<String>> aliases() {
        return aliases;
    }

    public boolean isLeaf(String shareId) {
        return leafShares.contains(normalizeLookup(shareId));
    }

    public boolean isKnown(String shareOrAlias) {
        String normalized = normalizeLookup(shareOrAlias);
        return leafShares.contains(normalized) || aliases.containsKey(normalized);
    }

    public Set<String> expand(String... expressions) {
        Objects.requireNonNull(expressions, "expressions");
        return expand(Arrays.asList(expressions));
    }

    /** Expands positives first and negatives second, making exclusion order deterministic. */
    public Set<String> expand(Collection<String> expressions) {
        Objects.requireNonNull(expressions, "expressions");
        LinkedHashSet<String> included = new LinkedHashSet<>();
        LinkedHashSet<String> excluded = new LinkedHashSet<>();
        for (String expression : expressions) {
            String normalized = DomainValidation.requireText(expression, "share expression");
            boolean negative = normalized.startsWith("-");
            String name = negative ? normalized.substring(1).strip() : normalized;
            name = DomainValidation.requireShareId(name, "share expression");
            Set<String> resolved = resolve(name);
            (negative ? excluded : included).addAll(resolved);
        }
        included.removeAll(excluded);
        return unmodifiableSet(included);
    }

    public Set<String> expandAlias(String alias) {
        String normalized = DomainValidation.requireShareId(alias, "alias");
        Set<String> expanded = expandedAliases.get(normalized);
        if (expanded == null) {
            throw new UnknownShareException(normalized);
        }
        return expanded;
    }

    public String requireLeaf(String shareId) {
        String normalized = DomainValidation.requireShareId(shareId, "shareId");
        if (!leafShares.contains(normalized)) {
            throw new UnknownShareException(normalized);
        }
        return normalized;
    }

    private Map<String, Set<String>> validateAndExpandAliases() {
        LinkedHashMap<String, Set<String>> resolved = new LinkedHashMap<>();
        ArrayDeque<String> path = new ArrayDeque<>();
        for (String alias : aliases.keySet()) {
            expandAlias(alias, resolved, path);
        }
        return Collections.unmodifiableMap(resolved);
    }

    private Set<String> expandAlias(String alias, Map<String, Set<String>> resolved, ArrayDeque<String> path) {
        Set<String> cached = resolved.get(alias);
        if (cached != null) {
            return cached;
        }
        if (path.contains(alias)) {
            List<String> cycle = new ArrayList<>();
            boolean collecting = false;
            for (String element : path) {
                if (element.equals(alias)) {
                    collecting = true;
                }
                if (collecting) {
                    cycle.add(element);
                }
            }
            cycle.add(alias);
            throw new ShareAliasCycleException(cycle);
        }

        List<String> targets = aliases.get(alias);
        if (targets == null) {
            throw new UnknownShareException(alias);
        }
        path.addLast(alias);
        LinkedHashSet<String> expansion = new LinkedHashSet<>();
        for (String target : targets) {
            if (leafShares.contains(target)) {
                expansion.add(target);
            } else if (aliases.containsKey(target)) {
                expansion.addAll(expandAlias(target, resolved, path));
            } else {
                throw new UnknownShareException(target);
            }
        }
        path.removeLast();
        Set<String> immutableExpansion = unmodifiableSet(expansion);
        resolved.put(alias, immutableExpansion);
        return immutableExpansion;
    }

    private Set<String> resolve(String name) {
        if (leafShares.contains(name)) {
            return Set.of(name);
        }
        Set<String> expansion = expandedAliases.get(name);
        if (expansion == null) {
            throw new UnknownShareException(name);
        }
        return expansion;
    }

    private static String normalizeLookup(String value) {
        return DomainValidation.requireShareId(value, "share id");
    }

    private static Map<String, List<String>> standardAliases() {
        LinkedHashMap<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("inventory", List.of("inventory_contents", "armor_contents", "off_hand", "ender_chest"));
        aliases.put("inventory_plus", List.of("inventory", "curios", "cosmetic_armor", "trash_slot"));
        aliases.put("experience", List.of("xp", "lvl"));
        aliases.put("stats", List.of("experience", "health", "hunger"));
        aliases.put("all", List.of("inventory_plus", "stats", "bed_spawn", "potion_effects",
                "recipes", "advancements", "statistics"));
        return Collections.unmodifiableMap(aliases);
    }

    private static Set<String> immutableSet(String... values) {
        return unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }

    private static <T> Set<T> unmodifiableSet(Collection<T> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
