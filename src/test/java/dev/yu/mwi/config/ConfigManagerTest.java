package dev.yu.mwi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void createsCommentedTomlAndReloadsDefaults() throws Exception {
        ConfigManager manager = new ConfigManager(temporaryDirectory);
        RuntimeConfig defaults = manager.loadOrCreate();
        String text = Files.readString(manager.file());

        assertEquals(RuntimeConfig.defaults(), defaults);
        assertTrue(manager.file().getFileName().toString().endsWith(".toml"));
        assertTrue(text.contains("玩家数据共享行为"));
        assertTrue(text.contains("[groups.default]"));
        assertTrue(text.contains("restore_last_location = false"));
        assertTrue(text.contains("io_concurrency = 2"));
        assertTrue(text.contains("queue_capacity = 256"));
        assertTrue(text.contains("flush_timeout_seconds = 30"));
        assertFalse(text.contains("announce_advancements"));
        assertEquals(defaults, new ConfigManager(temporaryDirectory).reload());
    }

    @Test
    void invalidCandidateDoesNotReplaceActiveConfiguration() throws Exception {
        ConfigManager manager = new ConfigManager(temporaryDirectory);
        RuntimeConfig active = manager.loadOrCreate();
        Files.writeString(manager.file(), "schema_version = 1\n[groups]\n");
        assertThrows(ConfigException.class, manager::reload);
        assertEquals(active, manager.active());
    }

    @Test
    void rejectsUnknownAndCoercedValues() throws Exception {
        ConfigManager manager = new ConfigManager(temporaryDirectory);
        manager.loadOrCreate();
        Files.writeString(manager.file(), Files.readString(manager.file()) + "\nunknown = true\n");
        assertThrows(ConfigException.class, manager::loadCandidate);

        manager.replace(RuntimeConfig.defaults());
        Files.writeString(manager.file(), Files.readString(manager.file())
                .replace("enable_gamemode_profiles = false", "enable_gamemode_profiles = \"false\""));
        assertThrows(ConfigException.class, manager::loadCandidate);
    }

    @Test
    void migratesLegacyJsonOnceAndKeepsBackup() throws Exception {
        Files.writeString(temporaryDirectory.resolve(ConfigManager.LEGACY_FILE_NAME), """
                {"schema_version":1,"groups":{"default":{"worlds":["world"],"shares":["all"]}}}
                """);
        RuntimeConfig migrated = new ConfigManager(temporaryDirectory).loadOrCreate();

        assertEquals(1, migrated.schemaVersion());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve(ConfigManager.FILE_NAME)));
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve(ConfigManager.LEGACY_FILE_NAME + ".migrated")));
        assertFalse(Files.exists(temporaryDirectory.resolve(ConfigManager.LEGACY_FILE_NAME)));
    }

    @Test
    void enforcesSizeAndSchemaLimits() throws Exception {
        ConfigManager manager = new ConfigManager(temporaryDirectory);
        manager.loadOrCreate();
        Files.writeString(manager.file(), "schema_version = 2\n[groups.default]\nworlds=[\"world\"]\nshares=[\"all\"]\n");
        ConfigException schema = assertThrows(ConfigException.class, manager::loadCandidate);
        assertTrue(schema.getMessage().contains("Unsupported configuration schema"));
    }

    @Test
    void preservesCustomizedAlias() throws Exception {
        ConfigManager manager = new ConfigManager(temporaryDirectory);
        manager.loadOrCreate();
        Files.writeString(manager.file(), Files.readString(manager.file()).replace(
                "inventory_plus = [\"inventory\", \"curios\", \"cosmetic_armor\", \"trash_slot\"]",
                "inventory_plus = [\"inventory\", \"curios\"]"));
        assertEquals(java.util.List.of("inventory", "curios"),
                manager.reload().model().shareAliases().get("inventory_plus"));
    }

    @Test
    void preservesCustomizedMvinvDataDirectory() throws Exception {
        ConfigManager manager = new ConfigManager(temporaryDirectory);
        manager.loadOrCreate();
        Files.writeString(manager.file(), Files.readString(manager.file()).replace(
                "data_directory = \"plugins/Multiverse-Inventories\"",
                "data_directory = \"/srv/minecraft/plugins/Multiverse-Inventories\""));

        assertEquals("/srv/minecraft/plugins/Multiverse-Inventories",
                manager.reload().mvinvDataDirectory());
    }

    @Test
    void acceptsAndRemovesRetiredAdvancementAnnouncementSetting() throws Exception {
        ConfigManager manager = new ConfigManager(temporaryDirectory);
        manager.loadOrCreate();
        Files.writeString(manager.file(), Files.readString(manager.file()).replaceFirst(
                "shares = ", "announce_advancements = false\nshares = "));

        RuntimeConfig loaded = manager.reload();
        manager.replace(loaded);
        assertFalse(Files.readString(manager.file()).contains("announce_advancements"));
    }

    @Test
    void loadsExplicitLastLocationRestoreSetting() throws Exception {
        ConfigManager manager = new ConfigManager(temporaryDirectory);
        manager.loadOrCreate();
        Files.writeString(manager.file(), Files.readString(manager.file()).replace(
                "restore_last_location = false", "restore_last_location = true"));

        assertTrue(manager.reload().model().shareHandling().restoreLastLocation());
    }
}
