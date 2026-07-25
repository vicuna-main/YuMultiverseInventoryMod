package dev.yu.mwi.config;

import dev.yu.mwi.domain.GroupResolver;
import dev.yu.mwi.domain.ShareCatalog;
import dev.yu.mwi.domain.WorldGroup;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Validated immutable runtime configuration, independent of its on-disk format. */
public record MwiConfig(
        ShareHandling shareHandling,
        Map<String, List<String>> shareAliases,
        List<WorldGroup> groups,
        Migration migration
) {
    public MwiConfig {
        Objects.requireNonNull(shareHandling, "shareHandling");
        Objects.requireNonNull(shareAliases, "shareAliases");
        LinkedHashMap<String, List<String>> aliasCopy = new LinkedHashMap<>();
        shareAliases.forEach((name, targets) -> aliasCopy.put(
                Objects.requireNonNull(name, "alias name"),
                List.copyOf(Objects.requireNonNull(targets, "alias targets"))
        ));
        shareAliases = Collections.unmodifiableMap(aliasCopy);
        groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        if (groups.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("groups must not contain null");
        }
        Objects.requireNonNull(migration, "migration");
    }

    public static MwiConfig defaults() {
        List<WorldGroup> defaultGroups = List.of(
                WorldGroup.of("default", List.of("world", "world_nether", "world_the_end"), List.of("all")),
                WorldGroup.of("resource", List.of("resource", "resource_nether"),
                        List.of("inventory_plus", "experience", "health")),
                WorldGroup.of("lobby", List.of("lobby"), List.of("health", "hunger"))
        );
        return new MwiConfig(ShareHandling.defaults(), ShareCatalog.STANDARD_ALIASES, defaultGroups, Migration.defaults());
    }

    public ShareCatalog createShareCatalog(Collection<String> availableLeafShares) {
        return new ShareCatalog(availableLeafShares, shareAliases);
    }

    public GroupResolver createGroupResolver(ShareCatalog catalog) {
        String defaultGroup = shareHandling.defaultUngroupedWorlds()
                ? shareHandling.defaultGroupName()
                : null;
        return new GroupResolver(groups, catalog, defaultGroup);
    }

    public record ShareHandling(
            boolean enableGameModeProfiles,
            boolean defaultUngroupedWorlds,
            boolean strictUnknownModSlots,
            boolean alwaysWriteWorldProfile,
            boolean restoreLastLocation,
            String defaultGroupName
    ) {
        public ShareHandling {
            defaultGroupName = requireGroupName(defaultGroupName);
        }

        public static ShareHandling defaults() {
            return new ShareHandling(false, false, true, true, false, "default");
        }

        private static String requireGroupName(String name) {
            Objects.requireNonNull(name, "defaultGroupName");
            String normalized = name.strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("defaultGroupName must not be blank");
            }
            return normalized;
        }
    }

    public record Migration(
            ProfileConflictPolicy onProfileConflict,
            Optional<String> preferredWorld,
            boolean archiveConflicts,
            boolean notifyAdmins
    ) {
        public Migration {
            Objects.requireNonNull(onProfileConflict, "onProfileConflict");
            preferredWorld = Objects.requireNonNull(preferredWorld, "preferredWorld")
                    .map(String::strip)
                    .filter(value -> !value.isEmpty());
            if (onProfileConflict == ProfileConflictPolicy.PREFER_NAMED_WORLD && preferredWorld.isEmpty()) {
                throw new IllegalArgumentException("preferredWorld is required for PREFER_NAMED_WORLD");
            }
            if (onProfileConflict != ProfileConflictPolicy.PREFER_NAMED_WORLD && preferredWorld.isPresent()) {
                throw new IllegalArgumentException("preferredWorld is only valid for PREFER_NAMED_WORLD");
            }
        }

        public static Migration defaults() {
            return new Migration(ProfileConflictPolicy.PREFER_CURRENT_WORLD, Optional.empty(), true, true);
        }
    }

    public enum ProfileConflictPolicy {
        PREFER_CURRENT_WORLD,
        PREFER_NEWEST,
        PREFER_NAMED_WORLD,
        MANUAL,
        MERGE_IF_POSSIBLE
    }
}
