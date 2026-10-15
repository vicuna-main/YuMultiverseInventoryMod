package dev.yu.mwi.storage;

import dev.yu.mwi.domain.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryCommitTest {
    @TempDir Path directory;
    final UUID owner = UUID.randomUUID(), source = UUID.randomUUID();
    final ProfileKey key = new ProfileKey(owner, new ScopeKey(ScopeKey.Type.WORLD, "minecraft:overworld"),
            GameModeBucket.SURVIVAL, "inventory_contents");

    @ParameterizedTest
    @ValueSource(strings = {"wal_rename", "wal_durable", "profile", "operation"})
    void everyDurableCrashBoundaryResumesTheSameOperation(String boundary) throws Exception {
        UUID operation = UUID.randomUUID();
        var store = new NbtProfileStore(directory, 20, 32, 16, new NbtProfileStore.FaultInjector() {
            @Override public void afterMove(Path destination) throws IOException {
                if (boundary.equals("wal_rename") && destination.toString().contains("transactions")) throw new IOException("after rename");
            }
            @Override public void transactionDurable(Path transaction) throws IOException {
                if (boundary.equals("wal_durable")) throw new IOException("lost acknowledgement");
            }
            @Override public void beforeProfileApply(ProfileKey ignored, int index) throws IOException {
                if (boundary.equals("profile")) throw new IOException("profile interruption");
            }
            @Override public void beforeMove(Path temporary, Path destination) throws IOException {
                if (boundary.equals("operation") && destination.toString().contains("recovery_operations")) throw new IOException("operation interruption");
            }
        });
        assertThrows(PendingTransactionException.class, () -> store.commitRecovery(owner, store.nextRevision(owner), Map.of(key, tag("after")),
                new RecoveryMutation(source, -1, operation, recovery("CLOSED"))));
        assertThrows(IOException.class, () -> store.commitRecovery(owner, store.nextRevision(owner), Map.of(),
                new RecoveryMutation(source, -1, UUID.randomUUID(), recovery("AVAILABLE"))));
        var recovered = new NbtProfileStore(directory, 20, 32);
        assertEquals(tag("after"), recovered.load(key).orElseThrow().payload());
        assertEquals(operation, recovered.loadRecovery(owner, source).orElseThrow().operationId());
        assertTrue(recovered.recoveryOperation(owner, operation).isPresent());
    }

    @Test void corruptCaseDoesNotHideHealthySibling() throws Exception {
        var store = new NbtProfileStore(directory, 20, 32); UUID good = UUID.randomUUID();
        store.commitRecovery(owner, store.nextRevision(owner), Map.of(), new RecoveryMutation(source, -1, UUID.randomUUID(), recovery("AVAILABLE")));
        store.commitRecovery(owner, store.nextRevision(owner), Map.of(), new RecoveryMutation(good, -1, UUID.randomUUID(), recovery("AVAILABLE")));
        Path corrupt = directory.resolve("data/mwi/recovery_sources").resolve(owner.toString()).resolve(source + ".dat");
        java.nio.file.Files.write(corrupt, new byte[]{31, -117, 8});
        assertThrows(IOException.class, () -> store.loadRecovery(owner, source));
        assertEquals(2, store.listRecoveries(owner).size());
        assertEquals("AVAILABLE", store.loadRecovery(owner, good).orElseThrow().payload().getString("state"));
    }

    @Test void sourceAndOperationRemainSpentAfterRevisionPruningAndRestart() throws Exception {
        UUID operation = UUID.randomUUID();
        try (var store = new NbtProfileStore(directory, 2, 32)) {
            store.commitRecovery(owner, store.nextRevision(owner), Map.of(key, tag("before")),
                    new RecoveryMutation(source, -1, UUID.randomUUID(), recovery("AVAILABLE")));
            store.commitRecovery(owner, store.nextRevision(owner), Map.of(key, tag("after")),
                    new RecoveryMutation(source, 0, operation, recovery("CLOSED")));
            for (int i = 0; i < 25; i++) store.writeRevision(owner, store.nextRevision(owner), tag("later"));
        }
        try (var store = new NbtProfileStore(directory, 2, 32)) {
            assertEquals("CLOSED", store.loadRecovery(owner, source).orElseThrow().payload().getString("state"));
            assertTrue(store.recoveryOperation(owner, operation).isPresent());
            assertThrows(IOException.class, () -> store.commitRecovery(owner, store.nextRevision(owner), Map.of(),
                    new RecoveryMutation(source, 1, UUID.randomUUID(), recovery("AVAILABLE"))));
            assertEquals(tag("after"), store.load(key).orElseThrow().payload());
        }
    }

    @Test void crashBetweenProfileAndReceiptReplaysBothAndDoesNotRegrant() throws Exception {
        UUID operation = UUID.randomUUID();
        var store = new NbtProfileStore(directory, 20, 32, 16, new NbtProfileStore.FaultInjector() {
            @Override public void beforeMove(Path temporary, Path destination) throws IOException {
                if (destination.toString().contains("recovery_sources")) throw new IOException("disk failure");
            }
        });
        assertThrows(PendingTransactionException.class, () -> store.commitRecovery(owner, store.nextRevision(owner),
                Map.of(key, tag("after")), new RecoveryMutation(source, -1, operation, recovery("CLOSED"))));
        var recovered = new NbtProfileStore(directory, 20, 32);
        assertEquals(tag("after"), recovered.load(key).orElseThrow().payload());
        assertEquals(0, recovered.loadRecovery(owner, source).orElseThrow().version());
        assertTrue(recovered.recoveryOperation(owner, operation).isPresent());
        assertThrows(IOException.class, () -> recovered.commitRecovery(owner, recovered.nextRevision(owner), Map.of(),
                new RecoveryMutation(source, -1, UUID.randomUUID(), recovery("AVAILABLE"))));
    }

    @Test void failureBeforeWalLeavesInventoryAndSourceUnchanged() throws Exception {
        var store = new NbtProfileStore(directory, 20, 32, 16, new NbtProfileStore.FaultInjector() {
            @Override public void beforeForce(Path temporary, Path destination) throws IOException {
                if (destination.toString().contains("transactions")) throw new IOException("disk full");
            }
        });
        assertThrows(IOException.class, () -> store.commitRecovery(owner, store.nextRevision(owner), Map.of(key, tag("after")),
                new RecoveryMutation(source, -1, UUID.randomUUID(), recovery("AVAILABLE"))));
        assertTrue(store.load(key).isEmpty());
        assertTrue(store.loadRecovery(owner, source).isEmpty());
    }

    @Test void staleConfirmationChangedIdentityAndReusedOperationAreRejected() throws Exception {
        var store = new NbtProfileStore(directory, 20, 32);
        UUID op = UUID.randomUUID();
        store.commitRecovery(owner, store.nextRevision(owner), Map.of(), new RecoveryMutation(source, -1, op, recovery("AVAILABLE")));
        assertThrows(IOException.class, () -> store.commitRecovery(owner, store.nextRevision(owner), Map.of(),
                new RecoveryMutation(source, -1, UUID.randomUUID(), recovery("CLOSED"))));
        CompoundTag changed = recovery("CLOSED"); changed.getCompound("identity").putString("evidence", "replacement");
        assertThrows(IOException.class, () -> store.commitRecovery(owner, store.nextRevision(owner), Map.of(),
                new RecoveryMutation(source, 0, UUID.randomUUID(), changed)));
        assertThrows(IOException.class, () -> store.commitRecovery(owner, store.nextRevision(owner), Map.of(),
                new RecoveryMutation(source, 0, op, recovery("CLOSED"))));
    }

    @Test void defensiveCopiesAndPlayerIsolation() throws Exception {
        var store = new NbtProfileStore(directory, 20, 32);
        store.commitRecovery(owner, store.nextRevision(owner), Map.of(),
                new RecoveryMutation(source, -1, UUID.randomUUID(), recovery("AVAILABLE")));
        store.loadRecovery(owner, source).orElseThrow().payload().putString("state", "CLOSED");
        assertEquals("AVAILABLE", store.listRecoveries(owner).getFirst().payload().getString("state"));
        assertTrue(store.loadRecovery(UUID.randomUUID(), source).isEmpty());
    }

    private static CompoundTag recovery(String state) {
        CompoundTag tag = new CompoundTag(); tag.putString("state", state); tag.put("identity", tag("evidence")); return tag;
    }
    private static CompoundTag tag(String marker) { CompoundTag tag = new CompoundTag(); tag.putString("marker", marker); return tag; }
}
