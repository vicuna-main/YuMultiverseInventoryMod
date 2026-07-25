package dev.yu.mwi.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Stable server-level world identity. The canonical name is used for persistence; aliases let platform names,
 * dimension ids and Bukkit world names select the same world.
 */
public record WorldIdentity(String name, Set<String> aliases) implements Comparable<WorldIdentity> {
    public WorldIdentity {
        name = DomainValidation.requireText(name, "name");
        Objects.requireNonNull(aliases, "aliases");
        LinkedHashSet<String> normalizedAliases = new LinkedHashSet<>();
        for (String alias : aliases) {
            normalizedAliases.add(DomainValidation.requireText(alias, "world alias"));
        }
        normalizedAliases.remove(name);
        aliases = Collections.unmodifiableSet(normalizedAliases);
    }

    public WorldIdentity(String name) {
        this(name, Set.of());
    }

    public static WorldIdentity of(String name) {
        return new WorldIdentity(name);
    }

    public static WorldIdentity of(String name, Collection<String> aliases) {
        return new WorldIdentity(name, new LinkedHashSet<>(Objects.requireNonNull(aliases, "aliases")));
    }

    public boolean hasName(String candidate) {
        return name.equals(candidate) || aliases.contains(candidate);
    }

    public boolean anyNameMatches(Predicate<String> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        if (predicate.test(name)) {
            return true;
        }
        return aliases.stream().anyMatch(predicate);
    }

    @Override
    public int compareTo(WorldIdentity other) {
        return name.compareTo(other.name);
    }

    @Override
    public String toString() {
        return name;
    }
}
