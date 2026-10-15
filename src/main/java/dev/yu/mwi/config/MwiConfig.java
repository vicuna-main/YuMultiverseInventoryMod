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
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Validated immutable runtime configuration, independent of its on-disk format. */
public record MwiConfig(
        ShareHandling shareHandling,
        Map<String, List<String>> shareAliases,
        List<WorldGroup> groups,
        Migration migration,
        AdvancementMigration advancementMigration,
        ItemMigration itemMigration
) {
    public MwiConfig(
            ShareHandling shareHandling,
            Map<String, List<String>> shareAliases,
            List<WorldGroup> groups,
            Migration migration) {
        this(shareHandling, shareAliases, groups, migration,
                AdvancementMigration.defaults(), ItemMigration.defaults());
    }

    public MwiConfig(
            ShareHandling shareHandling,
            Map<String, List<String>> shareAliases,
            List<WorldGroup> groups,
            Migration migration,
            AdvancementMigration advancementMigration) {
        this(shareHandling, shareAliases, groups, migration,
                advancementMigration, ItemMigration.defaults());
    }

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
        Objects.requireNonNull(advancementMigration, "advancementMigration");
        Objects.requireNonNull(itemMigration, "itemMigration");
    }

    public static MwiConfig defaults() {
        List<WorldGroup> defaultGroups = List.of(
                WorldGroup.of("default", List.of("world", "world_nether", "world_the_end"), List.of("all")),
                WorldGroup.of("resource", List.of("resource", "resource_nether"),
                        List.of("inventory_plus", "experience", "health")),
                WorldGroup.of("lobby", List.of("lobby"), List.of("health", "hunger"))
        );
        return new MwiConfig(
                ShareHandling.defaults(), ShareCatalog.STANDARD_ALIASES, defaultGroups,
                Migration.defaults(), AdvancementMigration.defaults(), ItemMigration.defaults());
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

    public enum MissingItemPolicy {
        QUARANTINE_AND_CONTINUE,
        STRICT_FAIL
    }

    /** Explicit item renames and the policy for registry entries absent from the running server. */
    public record ItemMigration(
            MissingItemPolicy missingItemPolicy,
            Map<ResourceLocation, ResourceLocation> aliases) {
        public ItemMigration {
            Objects.requireNonNull(missingItemPolicy, "missingItemPolicy");
            Objects.requireNonNull(aliases, "aliases");
            LinkedHashMap<ResourceLocation, ResourceLocation> copy = new LinkedHashMap<>();
            aliases.forEach((source, target) -> copy.put(
                    Objects.requireNonNull(source, "item alias source"),
                    Objects.requireNonNull(target, "item alias target")));
            AdvancementMigration.rejectCycles(copy, "item_migration.aliases");
            aliases = Collections.unmodifiableMap(copy);
        }

        public static ItemMigration defaults() {
            return new ItemMigration(MissingItemPolicy.QUARANTINE_AND_CONTINUE, Map.of());
        }

        public ResourceLocation resolve(ResourceLocation source) {
            return AdvancementMigration.resolve(source, aliases);
        }
    }

    /** Explicit, validated registry-schema migrations for stored advancement payloads. */
    public record AdvancementMigration(
            Map<ResourceLocation, ResourceLocation> aliases,
            Map<ResourceLocation, Map<String, String>> criterionAliases
    ) {
        public AdvancementMigration {
            Objects.requireNonNull(aliases, "aliases");
            LinkedHashMap<ResourceLocation, ResourceLocation> aliasCopy = new LinkedHashMap<>();
            aliases.forEach((source, target) -> aliasCopy.put(
                    Objects.requireNonNull(source, "advancement alias source"),
                    Objects.requireNonNull(target, "advancement alias target")));
            rejectCycles(aliasCopy, "advancement_migration.aliases");
            aliases = Collections.unmodifiableMap(aliasCopy);

            Objects.requireNonNull(criterionAliases, "criterionAliases");
            LinkedHashMap<ResourceLocation, Map<String, String>> criterionCopy = new LinkedHashMap<>();
            criterionAliases.forEach((advancement, configured) -> {
                Objects.requireNonNull(advancement, "criterion alias advancement");
                Objects.requireNonNull(configured, "criterion aliases for " + advancement);
                LinkedHashMap<String, String> scoped = new LinkedHashMap<>();
                configured.forEach((source, target) -> {
                    String normalizedSource = requireCriterion(source, "criterion alias source");
                    String normalizedTarget = requireCriterion(target, "criterion alias target");
                    if (scoped.put(normalizedSource, normalizedTarget) != null) {
                        throw new IllegalArgumentException(
                                "duplicate normalized criterion alias " + advancement + '/' + normalizedSource);
                    }
                });
                rejectCycles(scoped, "advancement_migration.criterion_aliases." + advancement);
                criterionCopy.put(advancement, Collections.unmodifiableMap(scoped));
            });
            criterionAliases = Collections.unmodifiableMap(criterionCopy);
        }

        public static AdvancementMigration defaults() {
            return new AdvancementMigration(Map.of(), Map.of());
        }

        public ResourceLocation resolveAdvancement(ResourceLocation source) {
            return resolve(source, aliases);
        }

        public String resolveCriterion(ResourceLocation advancement, String source) {
            return resolve(source, criterionAliases.getOrDefault(advancement, Map.of()));
        }

        public boolean aliasesAdvancement(ResourceLocation source) {
            return aliases.containsKey(source);
        }

        public boolean aliasesCriterion(ResourceLocation advancement, String source) {
            return criterionAliases.getOrDefault(advancement, Map.of()).containsKey(source);
        }

        private static String requireCriterion(String value, String description) {
            Objects.requireNonNull(value, description);
            String normalized = value.strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(description + " must not be blank");
            }
            return normalized;
        }

        private static <T> T resolve(T source, Map<T, T> mappings) {
            T current = source;
            Set<T> visited = new java.util.HashSet<>();
            while (mappings.containsKey(current)) {
                if (!visited.add(current)) {
                    throw new IllegalStateException("validated migration alias cycle");
                }
                current = mappings.get(current);
            }
            return current;
        }

        private static <T> void rejectCycles(Map<T, T> mappings, String path) {
            for (T source : mappings.keySet()) {
                T current = source;
                Set<T> visited = new java.util.LinkedHashSet<>();
                while (mappings.containsKey(current)) {
                    if (!visited.add(current)) {
                        throw new IllegalArgumentException(
                                path + " contains a cycle: " + String.join(" -> ",
                                        visited.stream().map(String::valueOf).toList()));
                    }
                    current = mappings.get(current);
                }
            }
        }
    }
}
