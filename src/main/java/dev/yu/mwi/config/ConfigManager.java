package dev.yu.mwi.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.yu.mwi.domain.ShareCatalog;
import dev.yu.mwi.domain.WorldGroup;
import dev.yu.mwi.domain.WorldSelector;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Atomically loads, validates and writes the server's TOML configuration. */
public final class ConfigManager {
    public static final String FILE_NAME = "yu_multiverse_inventory.toml";
    static final String LEGACY_FILE_NAME = "yu_multiverse_inventory.json";
    private static final List<String> LEGACY_DEFAULT_INVENTORY_PLUS =
            List.of("inventory", "curios", "cosmetic_armor", "fashion_slots");
    private static final long MAX_CONFIG_BYTES = 1024L * 1024L;
    private static final int MAX_MAP_ENTRIES = 1_024;
    private static final int MAX_LIST_ENTRIES = 4_096;
    private static final int MAX_TEXT_LENGTH = 1_024;

    private final Path file;
    private final Path legacyFile;
    private volatile RuntimeConfig active;

    public ConfigManager(Path configDirectory) {
        Path directory = configDirectory.toAbsolutePath().normalize();
        file = directory.resolve(FILE_NAME);
        legacyFile = directory.resolve(LEGACY_FILE_NAME);
    }

    public synchronized RuntimeConfig loadOrCreate() throws ConfigException {
        if (Files.exists(file)) return reload();
        if (Files.exists(legacyFile)) return migrateLegacyJson();
        RuntimeConfig defaults = RuntimeConfig.defaults();
        replace(defaults);
        return defaults;
    }

    public synchronized RuntimeConfig loadCandidate() throws ConfigException {
        return readCandidate();
    }

    public synchronized RuntimeConfig reload() throws ConfigException {
        RuntimeConfig candidate = readCandidate();
        active = candidate;
        return candidate;
    }

    public RuntimeConfig active() {
        RuntimeConfig result = active;
        if (result == null) throw new IllegalStateException("Configuration has not been loaded");
        return result;
    }

    public Path file() { return file; }

    public synchronized void replace(RuntimeConfig replacement) throws ConfigException {
        try {
            write(replacement);
            active = replacement;
        } catch (IOException failure) {
            throw new ConfigException("Unable to write config " + file, failure);
        }
    }

    private RuntimeConfig readCandidate() throws ConfigException {
        try {
            checkSize(file);
            CommentedConfig root = CommentedConfig.of(LinkedHashMap::new, TomlFormat.instance());
            new TomlParser().parse(new StringReader(Files.readString(file, StandardCharsets.UTF_8)), root, ParsingMode.REPLACE);
            return parse(root);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof ConfigException configFailure) throw configFailure;
            throw new ConfigException("Invalid configuration " + file + ": " + failure.getMessage(), failure);
        }
    }

    private RuntimeConfig migrateLegacyJson() throws ConfigException {
        try {
            checkSize(legacyFile);
            JsonElement json = JsonParser.parseString(Files.readString(legacyFile, StandardCharsets.UTF_8));
            if (!json.isJsonObject()) throw new IllegalArgumentException("Configuration root must be a JSON object");
            RuntimeConfig migrated = parse(jsonObject(json.getAsJsonObject()));
            write(migrated);
            Files.move(legacyFile, legacyFile.resolveSibling(LEGACY_FILE_NAME + ".migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
            active = migrated;
            return migrated;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof ConfigException configFailure) throw configFailure;
            throw new ConfigException("Unable to migrate legacy configuration " + legacyFile + ": " + failure.getMessage(), failure);
        }
    }

    private static void checkSize(Path path) throws IOException, ConfigException {
        if (Files.size(path) > MAX_CONFIG_BYTES) {
            throw new ConfigException("Configuration exceeds " + MAX_CONFIG_BYTES + " bytes: " + path);
        }
    }

    private static RuntimeConfig parse(UnmodifiableConfig root) {
        rejectUnknownKeys(root, "configuration root", Set.of(
                "_documentation", "schema_version", "share_handling", "share_aliases", "group_order", "groups",
                "migration", "storage", "mvinv_compat"));
        int schema = integer(root, "schema_version", 1);
        UnmodifiableConfig handling = table(root, "share_handling", Config.inMemory());
        rejectUnknownKeys(handling, "share_handling", Set.of("_documentation", "enable_gamemode_profiles",
                "default_ungrouped_worlds", "strict_unknown_mod_slots", "always_write_world_profile",
                "restore_last_location", "default_group_name"));
        MwiConfig.ShareHandling shareHandling = new MwiConfig.ShareHandling(
                bool(handling, "enable_gamemode_profiles", false), bool(handling, "default_ungrouped_worlds", false),
                bool(handling, "strict_unknown_mod_slots", true), bool(handling, "always_write_world_profile", true),
                bool(handling, "restore_last_location", false),
                string(handling, "default_group_name", "default"));

        Map<String, List<String>> aliases = parseAliases(table(root, "share_aliases", null));
        List<WorldGroup> groups = parseGroups(requiredTable(root, "groups"), root.get("group_order"));
        UnmodifiableConfig migration = table(root, "migration", Config.inMemory());
        rejectUnknownKeys(migration, "migration", Set.of("_documentation", "on_profile_conflict", "preferred_world",
                "archive_conflicts", "notify_admins"));
        MwiConfig.ProfileConflictPolicy policy = parseEnum(MwiConfig.ProfileConflictPolicy.class,
                string(migration, "on_profile_conflict", "prefer_current_world"), "migration.on_profile_conflict");
        MwiConfig.Migration migrationModel = new MwiConfig.Migration(policy, optionalString(migration, "preferred_world"),
                bool(migration, "archive_conflicts", true), bool(migration, "notify_admins", true));

        UnmodifiableConfig storageTable = table(root, "storage", Config.inMemory());
        rejectUnknownKeys(storageTable, "storage", Set.of(
                "_documentation", "max_revisions", "cache_entries", "cache_megabytes",
                "io_concurrency", "queue_capacity", "flush_timeout_seconds"));
        RuntimeConfig.Storage storage = new RuntimeConfig.Storage(integer(storageTable, "max_revisions", 20),
                integer(storageTable, "cache_entries", 1_024), integer(storageTable, "cache_megabytes", 128),
                integer(storageTable, "io_concurrency", 2), integer(storageTable, "queue_capacity", 256),
                integer(storageTable, "flush_timeout_seconds", 30));
        UnmodifiableConfig compatibility = table(root, "mvinv_compat", Config.inMemory());
        rejectUnknownKeys(compatibility, "mvinv_compat", Set.of("_documentation", "mode", "data_directory"));

        MwiConfig model = new MwiConfig(shareHandling, aliases, groups, migrationModel);
        model.createGroupResolver(new ShareCatalog(ShareCatalog.STANDARD_LEAF_SHARES, aliases));
        return new RuntimeConfig(schema, model, storage,
                RuntimeConfig.MvinvMode.parse(string(compatibility, "mode", "import_and_replace")),
                string(compatibility, "data_directory", "plugins/Multiverse-Inventories"));
    }

    private void write(RuntimeConfig runtime) throws IOException {
        CommentedConfig root = serialize(runtime);
        StringWriter output = new StringWriter();
        new TomlWriter().write(root, output);
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Path temporary = parent.resolve(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, output + System.lineSeparator(), StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.READ)) { channel.force(true); }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static CommentedConfig serialize(RuntimeConfig runtime) {
        CommentedConfig root = CommentedConfig.inMemory();
        root.set("schema_version", runtime.schemaVersion());
        root.setComment("schema_version", "配置格式版本。请勿手动修改。");
        CommentedConfig handling = section(root, "share_handling", "玩家数据共享行为；除 storage 外均支持 /mwi reload 热重载。");
        handling.set("enable_gamemode_profiles", runtime.model().shareHandling().enableGameModeProfiles());
        handling.set("default_ungrouped_worlds", runtime.model().shareHandling().defaultUngroupedWorlds());
        handling.set("strict_unknown_mod_slots", runtime.model().shareHandling().strictUnknownModSlots());
        handling.set("always_write_world_profile", runtime.model().shareHandling().alwaysWriteWorldProfile());
        handling.set("restore_last_location", runtime.model().shareHandling().restoreLastLocation());
        handling.set("default_group_name", runtime.model().shareHandling().defaultGroupName());
        CommentedConfig aliases = section(root, "share_aliases", "可递归引用的 share 别名；负向表达式只能用于 groups.*.shares。");
        runtime.model().shareAliases().forEach(aliases::set);
        CommentedConfig groups = section(root, "groups", "世界组。worlds 支持精确、glob 和 r= 正则选择器。");
        root.set("group_order", runtime.model().groups().stream().map(WorldGroup::name).toList());
        root.setComment("group_order", "世界组声明顺序；由模组维护，请勿重复或遗漏组名。");
        for (WorldGroup group : runtime.model().groups()) {
            CommentedConfig value = CommentedConfig.inMemory();
            value.set("worlds", group.selectors().stream().map(WorldSelector::expression).toList());
            value.set("shares", group.shareExpressions());
            groups.set(group.name(), value);
        }
        CommentedConfig migration = section(root, "migration", "合并旧 profile 时的冲突处理策略。");
        migration.set("on_profile_conflict", runtime.model().migration().onProfileConflict().name().toLowerCase(Locale.ROOT));
        runtime.model().migration().preferredWorld().ifPresent(value -> migration.set("preferred_world", value));
        migration.set("archive_conflicts", runtime.model().migration().archiveConflicts());
        migration.set("notify_admins", runtime.model().migration().notifyAdmins());
        CommentedConfig storage = section(root, "storage", "存储与缓存限制；修改后需要重启服务器。");
        storage.set("max_revisions", runtime.storage().maxRevisions());
        storage.set("cache_entries", runtime.storage().cacheEntries());
        storage.set("cache_megabytes", runtime.storage().cacheMegabytes());
        storage.set("io_concurrency", runtime.storage().ioConcurrency());
        storage.set("queue_capacity", runtime.storage().queueCapacity());
        storage.set("flush_timeout_seconds", runtime.storage().flushTimeoutSeconds());
        CommentedConfig compatibility = section(root, "mvinv_compat", "Multiverse-Inventories 兼容模式。");
        compatibility.set("mode", runtime.mvinvMode().name().toLowerCase(Locale.ROOT));
        compatibility.set("data_directory", runtime.mvinvDataDirectory());
        return root;
    }

    private static CommentedConfig section(CommentedConfig root, String name, String comment) {
        CommentedConfig section = CommentedConfig.inMemory();
        root.set(name, section);
        root.setComment(name, comment);
        return section;
    }

    private static Map<String, List<String>> parseAliases(UnmodifiableConfig table) {
        if (table == null) return ShareCatalog.STANDARD_ALIASES;
        if (table.size() > MAX_MAP_ENTRIES) throw new IllegalArgumentException("share_aliases contains too many entries");
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        table.valueMap().forEach((name, value) -> {
            if (!name.equals("_documentation")) result.put(name, stringList(value, "share_aliases." + name));
        });
        if (LEGACY_DEFAULT_INVENTORY_PLUS.equals(result.get("inventory_plus"))) {
            result.put("inventory_plus", ShareCatalog.STANDARD_ALIASES.get("inventory_plus"));
        }
        return result;
    }

    private static List<WorldGroup> parseGroups(UnmodifiableConfig table, Object configuredOrder) {
        if (table.size() > MAX_MAP_ENTRIES) throw new IllegalArgumentException("groups contains too many entries");
        List<WorldGroup> result = new ArrayList<>();
        table.valueMap().forEach((name, value) -> {
            if (name.equals("_documentation")) return;
            if (!(value instanceof UnmodifiableConfig group)) throw new IllegalArgumentException("groups." + name + " must be a table");
            rejectUnknownKeys(group, "groups." + name,
                    Set.of("_documentation", "worlds", "shares", "announce_advancements"));
            // Accepted only so configurations written by the short-lived announcement-filter
            // release continue to load. The setting no longer changes advancement behavior.
            bool(group, "announce_advancements", true);
            result.add(WorldGroup.of(name, stringList(group.get("worlds"), "groups." + name + ".worlds"),
                    stringList(group.get("shares"), "groups." + name + ".shares")));
        });
        if (result.isEmpty()) throw new IllegalArgumentException("groups must contain at least one group");
        if (configuredOrder == null) return List.copyOf(result);
        List<String> order = stringList(configuredOrder, "group_order");
        if (order.size() != result.size() || Set.copyOf(order).size() != order.size())
            throw new IllegalArgumentException("group_order must contain every group exactly once");
        Map<String, WorldGroup> byName = new LinkedHashMap<>();
        result.forEach(group -> byName.put(group.name(), group));
        List<WorldGroup> ordered = order.stream().map(name -> {
            WorldGroup group = byName.get(name);
            if (group == null) throw new IllegalArgumentException("group_order references unknown group: " + name);
            return group;
        }).toList();
        return List.copyOf(ordered);
    }

    private static UnmodifiableConfig requiredTable(UnmodifiableConfig parent, String key) {
        UnmodifiableConfig value = table(parent, key, null);
        if (value == null) throw new IllegalArgumentException(key + " must be a table");
        return value;
    }

    private static UnmodifiableConfig table(UnmodifiableConfig parent, String key, UnmodifiableConfig fallback) {
        Object value = parent.get(key);
        if (value == null) return fallback;
        if (!(value instanceof UnmodifiableConfig config)) throw new IllegalArgumentException(key + " must be a table");
        return config;
    }

    private static List<String> stringList(Object value, String path) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException(path + " must be an array of strings");
        if (list.size() > MAX_LIST_ENTRIES) throw new IllegalArgumentException(path + " contains too many entries");
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) result.add(requireString(item, path));
        return List.copyOf(result);
    }

    private static boolean bool(UnmodifiableConfig table, String key, boolean fallback) {
        Object value = table.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException(key + " must be a boolean");
        return result;
    }

    private static int integer(UnmodifiableConfig table, String key, int fallback) {
        Object value = table.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        long exact = number.longValue();
        if (number.doubleValue() != exact || exact < Integer.MIN_VALUE || exact > Integer.MAX_VALUE)
            throw new IllegalArgumentException(key + " must be a 32-bit integer");
        return (int) exact;
    }

    private static String string(UnmodifiableConfig table, String key, String fallback) {
        Object value = table.get(key);
        return value == null ? fallback : requireString(value, key);
    }

    private static Optional<String> optionalString(UnmodifiableConfig table, String key) {
        Object value = table.get(key);
        return value == null ? Optional.empty() : Optional.of(requireString(value, key));
    }

    private static String requireString(Object value, String path) {
        if (!(value instanceof String result)) throw new IllegalArgumentException(path + " must be a string");
        if (result.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException(path + " is longer than " + MAX_TEXT_LENGTH);
        return result;
    }

    private static void rejectUnknownKeys(UnmodifiableConfig table, String path, Set<String> allowed) {
        List<String> unknown = table.valueMap().keySet().stream().filter(key -> !allowed.contains(key)).sorted().toList();
        if (!unknown.isEmpty()) throw new IllegalArgumentException(path + " contains unknown keys: " + String.join(", ", unknown));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String path) {
        try { return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(path + " has unsupported value: " + raw, invalid); }
    }

    private static Config jsonObject(JsonObject object) {
        Config result = Config.inMemory();
        object.entrySet().forEach(entry -> result.set(entry.getKey(), jsonValue(entry.getValue())));
        return result;
    }

    private static Object jsonValue(JsonElement value) {
        if (value.isJsonObject()) return jsonObject(value.getAsJsonObject());
        if (value.isJsonArray()) {
            List<Object> result = new ArrayList<>();
            value.getAsJsonArray().forEach(item -> result.add(jsonValue(item)));
            return result;
        }
        if (value.isJsonNull()) return null;
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        if (value.getAsJsonPrimitive().isNumber()) return value.getAsNumber();
        return value.getAsString();
    }
}
