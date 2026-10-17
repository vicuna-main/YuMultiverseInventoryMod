package dev.yu.mwi.storage;

import dev.yu.mwi.domain.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticProfileRecoveryTest {
    @TempDir Path world;
    private NbtProfileStore open() throws Exception { return new NbtProfileStore(world, 20, 32); }
    private ProfileKey key(UUID owner, String scope) {
        return new ProfileKey(owner, new ScopeKey(ScopeKey.Type.WORLD, scope), GameModeBucket.SHARED, "inventory_contents");
    }
    private CompoundTag payload(String value) { CompoundTag tag = new CompoundTag(); tag.putString("items", value); return tag; }
    private Path file(ProfileKey key) {
        var encode = Base64.getUrlEncoder().withoutPadding();
        return world.resolve("data/mwi/profiles").resolve(key.playerId().toString()).resolve("world")
                .resolve(encode.encodeToString(key.scope().id().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .resolve("shared").resolve(encode.encodeToString(key.shareId().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".dat");
    }
    @Test void staleLegacyIsolationResolvesWithoutRewritingCanonicalOrRestart() throws Exception {
        UUID owner = UUID.randomUUID(); ProfileKey key = key(owner, "world");
        try (var store = open()) { store.save(key, payload("diamonds"), 203); }
        byte[] canonical = Files.readAllBytes(file(key));
        Path legacy = world.resolve("data/mwi/quarantine").resolve(owner.toString());
        Files.createDirectories(legacy); Files.write(legacy.resolve("original.bin"), new byte[]{1, 2});
        try (var store = open()) {
            assertThrows(ProfileCorruptionException.class, () -> store.load(key));
            RecoveryInspection result = store.inspectRecovery(owner, true);
            assertTrue(result.ready(Set.of(key)), result.toString());
            assertEquals("diamonds", store.load(key).orElseThrow().payload().getString("items"));
            assertEquals(204, store.nextRevision(owner));
            assertArrayEquals(canonical, Files.readAllBytes(file(key)));
            assertFalse(Files.exists(legacy));
        }
    }
    @Test void corruptCanonicalRestoresOnlyLatestCommittedExactImage() throws Exception {
        UUID owner = UUID.randomUUID(); ProfileKey key = key(owner, "world");
        try (var store = open()) {
            store.save(key, payload("spent diamonds"), 12);
            store.save(key, payload("remaining 1"), 13);
            Files.writeString(file(key), "broken gzip"); store.invalidate(key);
            RecoveryInspection result = store.inspectRecovery(owner, true);
            assertTrue(result.ready(Set.of(key)), result.toString());
            assertEquals("remaining 1", store.load(key).orElseThrow().payload().getString("items"));
            assertEquals(13, store.load(key).orElseThrow().revision());
            assertTrue(store.inspectRecovery(owner, true).ready(Set.of(key)));
        }
    }
    @Test void missingLatestSourceNeverFallsBackToOlderCommit() throws Exception {
        UUID owner = UUID.randomUUID(); ProfileKey key = key(owner, "world");
        try (var store = open()) {
            store.save(key, payload("64 diamonds"), 12); store.save(key, payload("1 diamond"), 13);
            Files.delete(world.resolve("data/mwi/committed_batches").resolve(owner.toString()).resolve("13.dat"));
            Files.writeString(file(key), "corrupt"); store.invalidate(key);
            RecoveryInspection result = store.inspectRecovery(owner, true);
            assertFalse(result.ready(Set.of(key)));
            assertEquals("corrupt", Files.readString(file(key)));
        }
    }
    @Test void unrelatedDamagedWorldDoesNotBlockHealthyScopeOrAllowOverwrite() throws Exception {
        UUID owner = UUID.randomUUID(); ProfileKey good = key(owner, "world"), bad = key(owner, "resource");
        try (var store = open()) {
            store.save(good, payload("healthy"), 12); store.save(bad, payload("resource"), 13);
            Files.delete(world.resolve("data/mwi/committed_batches").resolve(owner.toString()).resolve("13.dat"));
            Files.writeString(file(bad), "broken"); store.invalidate(bad);
            RecoveryInspection result = store.inspectRecovery(owner, true);
            assertTrue(result.ready(Set.of(good)), result.toString());
            assertFalse(result.ready(Set.of(bad)));
            store.save(good, payload("healthy updated"), store.nextRevision(owner));
            assertThrows(ProfileCorruptionException.class, () -> store.save(bad, new CompoundTag(), 15));
        }
    }
    @Test void completedRevisionCopyProvesHistoryRecoveryAndPreservesHighWater() throws Exception {
        UUID owner = UUID.randomUUID(); ProfileKey key = key(owner, "world");
        try (var store = open()) {
            store.save(key, payload("items"), 203);
            CompoundTag journal = new CompoundTag(); journal.putString("status", "committed");
            store.writeRevision(owner, 108, journal);
        }
        Path history = world.resolve("data/mwi/revisions").resolve(owner.toString()).resolve("108.dat");
        Files.writeString(history, "broken");
        try (var store = open()) {
            assertTrue(store.inspectRecovery(owner, true).ready(Set.of(key)));
            assertEquals(204, store.nextRevision(owner));
            assertEquals("committed", store.loadRevision(owner, 108).orElseThrow().getString("status"));
        }
    }
    @Test void unreadableLegacyHistoryWithoutCompletionProofRemainsWaiting() throws Exception {
        UUID owner = UUID.randomUUID(); ProfileKey key = key(owner, "world");
        try (var store = open()) { store.save(key, payload("items"), 203); }
        Path history = world.resolve("data/mwi/revisions").resolve(owner.toString()).resolve("108.dat");
        Files.createDirectories(history.getParent()); Files.writeString(history, "broken");
        try (var store = open()) {
            assertFalse(store.inspectRecovery(owner, true).ready(Set.of(key)));
            assertEquals("broken", Files.readString(history));
        }
    }
    @Test void dryRunDoesNotRepairOrCreateIncidentFiles() throws Exception {
        UUID owner = UUID.randomUUID(); ProfileKey key = key(owner, "world");
        try (var store = open()) {
            store.save(key, payload("items"), 203);
            Files.writeString(file(key), "broken"); store.invalidate(key);
            assertFalse(store.inspectRecovery(owner, false).ready(Set.of(key)));
            assertEquals("broken", Files.readString(file(key)));
            assertFalse(Files.exists(world.resolve("data/mwi/recovery_incidents").resolve(owner + ".dat")));
        }
    }
    @Test void snapshotEvidenceMatchesFailedBytesEvenIfFileIsReplacedImmediately() throws Exception {
        UUID owner = UUID.randomUUID(); ProfileKey key = key(owner, "world");
        try (var store = open()) { store.save(key, payload("items"), 203); }
        byte[] valid = Files.readAllBytes(file(key)); byte[] corrupt = valid.clone(); corrupt[corrupt.length - 8] ^= 1;
        try (var store = new NbtProfileStore(world, 20, 32, 128, new NbtProfileStore.FaultInjector() {
            @Override public void afterSnapshot(Path path, byte[] bytes) throws java.io.IOException {
                if (path.equals(file(key)) && Arrays.equals(bytes, corrupt)) Files.write(path, valid);
            }
        })) {
            Files.write(file(key), corrupt); store.invalidate(key);
            assertTrue(store.inspectRecovery(owner, true).ready(Set.of(key)));
            try (var files = Files.list(world.resolve("data/mwi/diagnostics/inputs").resolve(owner.toString()))) {
                assertArrayEquals(corrupt, Files.readAllBytes(files.findFirst().orElseThrow()));
            }
        }
    }

    @Test void interruptedMultiProfileRepairResumesWithoutChangingVersionsOrGrantingTwice() throws Exception {
        UUID owner = UUID.randomUUID(); ProfileKey a = key(owner, "world"), b = key(owner, "resource");
        java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
        try (var store = new NbtProfileStore(world, 20, 32, 128, new NbtProfileStore.FaultInjector() {
            @Override public void afterMove(Path destination) throws java.io.IOException {
                if (destination.startsWith(world.toAbsolutePath().resolve("data/mwi/profiles")) && armed.compareAndSet(true, false))
                    throw new java.io.IOException("injected crash after first atomic replacement");
            }
        })) {
            store.commitBatch(owner, 203, Map.of(a, payload("world items"), b, payload("resource items")), Optional.empty());
            Files.writeString(file(a), "bad a"); Files.writeString(file(b), "bad b"); armed.set(true);
            assertThrows(java.io.IOException.class, () -> store.inspectRecovery(owner, true));
            assertTrue(Files.exists(world.resolve("data/mwi/exact_repairs").resolve(owner + ".dat")));
        }
        try (var store = open()) {
            RecoveryInspection result = store.inspectRecovery(owner, true);
            assertTrue(result.ready(Set.of(a, b)), result.toString());
            assertEquals("world items", store.load(a).orElseThrow().payload().getString("items"));
            assertEquals("resource items", store.load(b).orElseThrow().payload().getString("items"));
            assertEquals(203, store.load(a).orElseThrow().revision());
            assertEquals(203, store.load(b).orElseThrow().revision());
            assertFalse(Files.exists(world.resolve("data/mwi/exact_repairs").resolve(owner + ".dat")));
            assertTrue(store.inspectRecovery(owner, true).ready(Set.of(a, b)));
            assertEquals(204, store.nextRevision(owner));
        }
    }

    @Test void newFailureCannotBeClearedByAnOlderRecoveryPlan() throws Exception {
        UUID owner = UUID.randomUUID(); ProfileKey key = key(owner, "world");
        java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
        try (var store = new NbtProfileStore(world, 20, 32, 128, new NbtProfileStore.FaultInjector() {
            @Override public void beforeMove(Path temporary, Path destination) throws java.io.IOException {
                if (destination.equals(file(key)) && armed.compareAndSet(true, false)) Files.writeString(destination, "new failure input");
            }
        })) {
            store.save(key, payload("original"), 203); Files.writeString(file(key), "old failure input"); armed.set(true);
            assertThrows(java.io.IOException.class, () -> store.inspectRecovery(owner, true));
            assertEquals("new failure input", Files.readString(file(key)));
            long old = store.recoveryGeneration(owner);
            assertTrue(store.inspectRecovery(owner, true).ready(Set.of(key)));
            assertTrue(store.recoveryGeneration(owner) > old);
            assertEquals("original", store.load(key).orElseThrow().payload().getString("items"));
        }
    }

    @Test void vanishedLegacyFaultIsNotMistakenForFirstLogin() throws Exception {
        UUID owner = UUID.randomUUID(); ProfileKey good = key(owner, "world"), missing = key(owner, "resource");
        try (var store = open()) { store.save(good, payload("healthy"), 203); }
        Path legacy = world.resolve("data/mwi/quarantine").resolve(owner.toString()); Files.createDirectories(legacy);
        CompoundTag receipt = new CompoundTag(); receipt.putUUID("player_uuid", owner);
        receipt.putString("source", world.resolve("data/mwi").relativize(file(missing)).toString());
        AtomicNbtFile.write(legacy.resolve("incident.dat"), receipt);
        try (var store = open()) {
            RecoveryInspection result = store.inspectRecovery(owner, true);
            assertTrue(result.ready(Set.of(good)));
            assertFalse(result.ready(Set.of(missing)));
            assertTrue(Files.exists(legacy));
            assertFalse(Files.exists(file(missing)));
        }
    }

    @Test void diagnosticWriteFailureKeepsOriginalSnapshotUntilEvidenceCanBeStored() throws Exception {
        UUID owner = UUID.randomUUID(); ProfileKey target = key(owner, "world");
        try (var store = open()) { store.save(target, payload("retained"), 203); }
        byte[] valid = Files.readAllBytes(file(target));
        byte[] broken = "original failing bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file(target), broken);
        var denyEvidence = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var store = new NbtProfileStore(world, 20, 32, 128, new NbtProfileStore.FaultInjector() {
            @Override public void beforeWrite(Path temporary, Path destination, int bytes) throws java.io.IOException {
                if (denyEvidence.get() && destination.toString().contains("diagnostics")) throw new java.io.IOException("diagnostic disk unavailable");
            }
        })) {
            assertThrows(java.io.IOException.class, () -> store.inspectRecovery(owner, true));
            Files.write(file(target), valid);
            assertThrows(java.io.IOException.class, () -> store.inspectRecovery(owner, true));
            denyEvidence.set(false);
            assertTrue(store.inspectRecovery(owner, true).ready(Set.of(target)));
            Path evidence = world.resolve("data/mwi/diagnostics/inputs").resolve(owner.toString());
            try (var paths = Files.list(evidence)) {
                assertTrue(paths.anyMatch(path -> {
                    try { return Arrays.equals(broken, Files.readAllBytes(path)); }
                    catch (java.io.IOException failure) { throw new RuntimeException(failure); }
                }));
            }
        }
    }

    @Test void retentionKeepsEveryLatestHeadEvenWhenOlderThanHistoryWindow() throws Exception {
        UUID owner = UUID.randomUUID(); ProfileKey rare = key(owner, "rare"), active = key(owner, "world");
        try (var store = new NbtProfileStore(world, 2, 32)) {
            store.save(rare, payload("irreplaceable"), 1);
            for (int revision = 2; revision <= 8; revision++) store.save(active, payload("current-" + revision), revision);
            Path copies = world.resolve("data/mwi/committed_batches").resolve(owner.toString());
            try (var paths = Files.list(copies)) { assertEquals(3, paths.count()); }
            Files.writeString(file(rare), "corrupt rare profile");
            assertTrue(store.inspectRecovery(owner, true).ready(Set.of(rare, active)));
            assertEquals("irreplaceable", store.load(rare).orElseThrow().payload().getString("items"));
            assertEquals("current-8", store.load(active).orElseThrow().payload().getString("items"));
        }
    }

    @Test void legacyNinetySixProfilesAndCompleted108KeepRevision203AndAllBytes() throws Exception {
        UUID owner = UUID.randomUUID(); Map<ProfileKey, CompoundTag> profiles = new LinkedHashMap<>();
        for (int index = 0; index < 96; index++) profiles.put(key(owner, "world-" + index), payload("profile-" + index));
        try (var store = open()) {
            CompoundTag journal = new CompoundTag(); journal.putString("status", "committed");
            store.writeRevision(owner, 108, journal);
            store.commitBatch(owner, 203, profiles, Optional.empty());
        }
        Map<ProfileKey, byte[]> before = new LinkedHashMap<>();
        for (ProfileKey key : profiles.keySet()) before.put(key, Files.readAllBytes(file(key)));
        Path legacy = world.resolve("data/mwi/quarantine").resolve(owner.toString());
        Files.createDirectories(legacy); Files.writeString(legacy.resolve("failed-input.bin"), "historical failure");
        try (var store = open()) {
            var inspection = store.inspectRecovery(owner, true);
            assertTrue(inspection.ready(profiles.keySet()), inspection.toString());
            assertEquals(96, inspection.profiles().size());
            assertEquals(204, store.nextRevision(owner));
            for (var entry : before.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(file(entry.getKey())));
        }
    }

    @Test void wrongIdentityAfterImageIsNeverUsed() throws Exception {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(); ProfileKey target = key(owner, "world"), foreign = key(other, "world");
        try (var store = open()) {
            store.save(target, payload("mine"), 203); store.save(foreign, payload("other player"), 203);
            Files.copy(world.resolve("data/mwi/committed_batches").resolve(other.toString()).resolve("203.dat"),
                    world.resolve("data/mwi/committed_batches").resolve(owner.toString()).resolve("203.dat"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(file(target), "damaged");
            assertThrows(ProfileCorruptionException.class, () -> store.inspectRecovery(owner, true));
            assertEquals("damaged", Files.readString(file(target)));
        }
    }
}
