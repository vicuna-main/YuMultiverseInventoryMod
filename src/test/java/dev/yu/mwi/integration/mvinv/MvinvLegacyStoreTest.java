package dev.yu.mwi.integration.mvinv;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MvinvLegacyStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void listsMixedCaseNestedWorldsAndGroupsForCommandCompletion() throws Exception {
        Path nestedWorld = temporaryDirectory.resolve("worlds/Legacy World/Mining_01");
        Path group = temporaryDirectory.resolve("groups/Builders");
        Files.createDirectories(nestedWorld);
        Files.createDirectories(group);
        Files.writeString(nestedWorld.resolve("Player.json"), "{}");
        Files.writeString(group.resolve("Player.json"), "{}");

        List<String> scopes = new MvinvLegacyStore(temporaryDirectory).listScopes();

        assertEquals(List.of("Legacy World/Mining_01", "group:Builders"), scopes);
    }

    @Test
    void rejectsWorldPathTraversalBeforeReadingProfiles() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("worlds"));
        Files.createDirectories(temporaryDirectory.resolve("groups"));
        MvinvLegacyStore store = new MvinvLegacyStore(temporaryDirectory);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> store.open(
                "player", "../outside", "survival", MvinvLegacyStore.Section.INVENTORY,
                RegistryAccess.EMPTY));

        assertTrue(failure.getMessage().contains("Invalid MVInv world name"));
    }

    @Test
    void rejectsGroupNamesContainingPathSeparators() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("worlds"));
        Files.createDirectories(temporaryDirectory.resolve("groups"));
        MvinvLegacyStore store = new MvinvLegacyStore(temporaryDirectory);

        assertThrows(IllegalArgumentException.class, () -> store.open(
                "player", "group:default/other", "survival", MvinvLegacyStore.Section.INVENTORY,
                RegistryAccess.EMPTY));
    }

    @Test
    void recoveryDataPreservesCompleteOriginalItemNbtByValue() {
        CompoundTag original = new CompoundTag();
        original.putString("id", "example:missing_mod_item");
        CompoundTag components = new CompoundTag();
        components.putInt("example:charge", 73);
        original.put("components", components);

        CompoundTag encoded = MvinvLegacyStore.encodeRecoveryData(original, "missing registry entry");
        original.putString("id", "minecraft:air");
        MvinvLegacyStore.RecoveryTokenData decoded = MvinvLegacyStore.decodeRecoveryData(encoded);

        assertEquals("example:missing_mod_item", decoded.originalItem().getString("id"));
        assertEquals(73, decoded.originalItem().getCompound("components").getInt("example:charge"));
        assertEquals("missing registry entry", decoded.failure());
    }

    @Test
    void rejectsRecoveryDataWithUnknownSchema() {
        CompoundTag invalid = MvinvLegacyStore.encodeRecoveryData(new CompoundTag(), "failure");
        invalid.getCompound("yu_mwi_mvinv_recovery").putInt("schema", Integer.MAX_VALUE);

        assertThrows(IllegalArgumentException.class, () -> MvinvLegacyStore.decodeRecoveryData(invalid));
    }
}
