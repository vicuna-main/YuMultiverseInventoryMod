package dev.yu.mwi.storage;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RestoreResultTest {
    @TempDir Path directory;
    @Test void terminalResultSurvivesRestartAndRejectsTampering() throws Exception {
        UUID owner = UUID.randomUUID();
        CompoundTag result = new CompoundTag(); result.putUUID("operation_id", UUID.randomUUID());
        result.putString("state", "FAILED"); result.putString("message", "potion_effects $.effects[0].duration differs");
        try (var store = new NbtProfileStore(directory, 20, 32)) { store.saveRestoreResult(owner, result); }
        try (var store = new NbtProfileStore(directory, 20, 32)) { assertEquals(result, store.restoreResult(owner)); }
        Path file;
        try (var paths = Files.walk(directory)) {
            file = paths.filter(path -> path.toString().contains("restore_results") && path.toString().endsWith(".dat")).findFirst().orElseThrow();
        }
        CompoundTag altered = NbtIo.readCompressed(file, NbtAccounter.create(1024 * 1024));
        altered.getCompound("result").putString("state", "ACTIVE"); NbtIo.writeCompressed(altered, file);
        try (var store = new NbtProfileStore(directory, 20, 32)) {
            assertThrows(java.io.IOException.class, () -> store.restoreResult(owner));
        }
    }
}
