package dev.yu.mwi.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BedSpawnMaintenanceSourceTest {
    private static final Path SERVICE =
            Path.of("src/main/java/dev/yu/mwi/service/InventoryIsolationService.java");
    private static final Path COMMANDS =
            Path.of("src/main/java/dev/yu/mwi/command/MwiCommands.java");

    @Test
    void dryRunOnlyUsesSideEffectFreeProfileInspection() throws Exception {
        String service = Files.readString(SERVICE);
        int inspectStart = service.indexOf("public BedSpawnRepairReport inspectBedSpawn");
        int clearStart = service.indexOf("public BedSpawnRepairReport clearBedSpawn", inspectStart);
        String inspect = service.substring(inspectStart, clearStart);

        assertTrue(inspect.contains("store.inspectPlayerProfiles(playerId)"));
        assertFalse(inspect.contains("snapshotWriter.flush"));
        assertFalse(inspect.contains("store.nextRevision"));
        assertFalse(inspect.contains("store.commitBatch"));
        assertFalse(inspect.contains("store.invalidate"));
    }

    @Test
    void clearUsesOneArchiveAndCanonicalWriteTransactionThenInvalidatesAllCaches() throws Exception {
        String service = Files.readString(SERVICE);
        int clearStart = service.indexOf("public BedSpawnRepairReport clearBedSpawn");
        int inspectionStart = service.indexOf("private List<BedSpawnProfileInspection>", clearStart);
        String clear = service.substring(clearStart, inspectionStart);

        assertTrue(clear.contains("Bed-spawn clear requires the player to be offline"));
        assertTrue(clear.contains("snapshotWriter.flush(playerId)"));
        assertTrue(clear.contains("new ProfileArchive("));
        assertTrue(clear.contains("store.nextRevision(playerId)"));
        assertTrue(clear.contains("store.commitBatch(playerId, revision, writes, Optional.of(journal), archives)"));
        assertTrue(clear.lastIndexOf("store.invalidatePlayer(playerId)")
                > clear.indexOf("store.commitBatch(playerId"));
        assertTrue(clear.contains("snapshotWriter.reloadWorkingSetIfLoaded(playerId)"));
    }

    @Test
    void unloadedStatusIsDiagnosticOnlyAndNeverSelectsProfilesForAutomaticClearing() throws Exception {
        String service = Files.readString(SERVICE);
        String adapter = Files.readString(Path.of(
                "src/main/java/dev/yu/mwi/adapter/vanilla/RespawnLocationAdapter.java"));

        assertTrue(service.contains("server.getLevel(dimension) != null"));
        assertTrue(service.contains("return present || validationError.isPresent()"));
        assertFalse(adapter.contains("getLevel("));
        assertFalse(adapter.contains("unknown or unloaded dimension"));
    }

    @Test
    void normalBedSpawnRestoreDoesNotCreateCompatibilityWritesOrArchives() throws Exception {
        String service = Files.readString(SERVICE);
        int prepareStart = service.indexOf("private CompoundTag prepareStoredPayload");
        int prepareEnd = service.indexOf("private CompoundTag preparePayload", prepareStart);
        String preparation = service.substring(prepareStart, prepareEnd);

        assertFalse(preparation.contains("RespawnLocationPayload"));
        assertFalse(preparation.contains("AdapterIds.BED_SPAWN"));
        assertTrue(preparation.contains("adapter instanceof AdvancementAdapter"));
        assertTrue(preparation.contains("ItemProfileCompatibility.supports"));
    }

    @Test
    void offlineCommandsUseStringIdentityAndCacheOnlyUuidLookup() throws Exception {
        String commands = Files.readString(COMMANDS);
        String service = Files.readString(SERVICE);

        assertTrue(commands.contains("Commands.literal(\"bed-spawn\")"));
        assertTrue(commands.contains("Commands.literal(\"--dry-run\")"));
        assertTrue(commands.contains("Commands.literal(\"--clear\")"));
        assertTrue(commands.contains("Commands.argument(\"player_identity\", StringArgumentType.word())"));
        assertTrue(commands.contains("context, \"player_identity\""));
        assertFalse(commands.contains("getProfileCache().get(identity)"));
        assertTrue(service.contains("server.getProfileCache().get(playerId)"));
    }
}
