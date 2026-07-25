package dev.yu.mwi.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Resolves a scope independently for every world/share pair. */
public final class GroupResolver {
    private final ShareCatalog catalog;
    private final List<ResolvedGroup> groups;
    private final Map<String, ResolvedGroup> groupsByName;
    private final ResolvedGroup defaultGroup;

    public GroupResolver(Collection<WorldGroup> groups, ShareCatalog catalog) {
        this(groups, catalog, null);
    }

    /**
     * @param defaultGroupName group used for shares on worlds not explicitly selected by another group;
     *                         {@code null} disables implicit default grouping
     */
    public GroupResolver(Collection<WorldGroup> groups, ShareCatalog catalog, String defaultGroupName) {
        Objects.requireNonNull(groups, "groups");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        List<ResolvedGroup> resolvedGroups = new ArrayList<>(groups.size());
        LinkedHashMap<String, ResolvedGroup> indexedGroups = new LinkedHashMap<>();
        for (WorldGroup group : groups) {
            Objects.requireNonNull(group, "groups must not contain null");
            ResolvedGroup resolved = new ResolvedGroup(group, group.resolveShares(catalog));
            if (indexedGroups.put(group.name(), resolved) != null) {
                throw new IllegalArgumentException("duplicate group name: " + group.name());
            }
            resolvedGroups.add(resolved);
        }
        this.groups = List.copyOf(resolvedGroups);
        this.groupsByName = Collections.unmodifiableMap(indexedGroups);
        if (defaultGroupName == null) {
            this.defaultGroup = null;
        } else {
            String normalizedName = DomainValidation.requireGroupId(defaultGroupName, "default group name");
            this.defaultGroup = indexedGroups.get(normalizedName);
            if (this.defaultGroup == null) {
                throw new IllegalArgumentException("default group does not exist: " + normalizedName);
            }
        }
    }

    public ScopeKey resolve(WorldIdentity world, String shareId) {
        Objects.requireNonNull(world, "world");
        String normalizedShare = catalog.requireLeaf(shareId);
        List<ResolvedGroup> matches = matchingGroups(world, normalizedShare);
        if (matches.size() > 1) {
            throw new GroupConflictException(world, normalizedShare,
                    matches.stream().map(group -> group.group().name()).toList());
        }
        if (matches.size() == 1) {
            return ScopeKey.group(matches.getFirst().group().name());
        }
        if (defaultGroup != null && defaultGroup.shares().contains(normalizedShare)) {
            return ScopeKey.group(defaultGroup.group().name());
        }
        return ScopeKey.world(world);
    }

    public ScopeKey resolveScope(WorldIdentity world, String shareId) {
        return resolve(world, shareId);
    }

    public Map<String, ScopeKey> resolveAll(WorldIdentity world, Collection<String> shareIds) {
        Objects.requireNonNull(shareIds, "shareIds");
        LinkedHashMap<String, ScopeKey> scopes = new LinkedHashMap<>();
        for (String shareId : shareIds) {
            String normalizedShare = catalog.requireLeaf(shareId);
            scopes.put(normalizedShare, resolve(world, normalizedShare));
        }
        return Collections.unmodifiableMap(scopes);
    }

    public Optional<WorldGroup> group(String name) {
        String normalizedName = DomainValidation.requireGroupId(name, "group name");
        ResolvedGroup group = groupsByName.get(normalizedName);
        return group == null ? Optional.empty() : Optional.of(group.group());
    }

    public List<WorldGroup> groups() {
        return groups.stream().map(ResolvedGroup::group).toList();
    }

    public List<String> matchingGroupNames(WorldIdentity world, String shareId) {
        String normalizedShare = catalog.requireLeaf(shareId);
        return matchingGroups(Objects.requireNonNull(world, "world"), normalizedShare).stream()
                .map(group -> group.group().name())
                .toList();
    }

    private List<ResolvedGroup> matchingGroups(WorldIdentity world, String shareId) {
        return groups.stream()
                .filter(group -> group.shares().contains(shareId) && group.group().matches(world))
                .toList();
    }

    private record ResolvedGroup(WorldGroup group, Set<String> shares) {
    }
}
