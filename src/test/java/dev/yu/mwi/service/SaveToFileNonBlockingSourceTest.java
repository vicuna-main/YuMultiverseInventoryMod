package dev.yu.mwi.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveToFileNonBlockingSourceTest {
    @Test
    void saveToFileCheckpointCannotFallBackToLoginOrDirectStoreIo() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/yu/mwi/service/InventoryIsolationService.java"));
        int start = source.indexOf("private void checkpoint(ServerPlayer player, String reason, boolean asynchronous)");
        int end = source.indexOf("public void checkpointAll", start);
        String checkpoint = source.substring(start, end);

        assertFalse(checkpoint.contains("login(player)"));
        assertFalse(checkpoint.contains("store."));
        assertTrue(checkpoint.contains("persistCurrent(player, reason, asynchronous)"));
    }

    @Test
    void runtimeRevisionAllocationContainsNoFileOrPersistenceLockOperation() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/yu/mwi/storage/NbtProfileStore.java"));
        int start = source.indexOf("public long nextRevision(UUID playerId)");
        int end = source.indexOf("@Override", start + 1);
        String allocation = source.substring(start, end);

        assertTrue(allocation.contains("revisionStates.computeIfAbsent"));
        assertTrue(allocation.contains("state.allocate()"));
        assertFalse(allocation.contains("Files."));
        assertFalse(allocation.contains("writeRevisionCounter"));
        assertFalse(allocation.contains("acquirePersistenceLock"));
        assertFalse(allocation.contains("synchronized"));
    }
}
