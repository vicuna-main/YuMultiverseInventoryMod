package dev.yu.mwi.storage;

import dev.yu.mwi.domain.GameModeBucket;
import dev.yu.mwi.domain.ProfileKey;
import dev.yu.mwi.domain.ScopeKey;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CorruptProfileIsolationTest {
    @TempDir Path world;

    @Test
    void invalidTagIsWrappedAndOtherPlayerRemainsUsableAcrossRestarts() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        ProfileKey bad = key();
        ProfileKey good = key();
        try (NbtProfileStore store = open()) {
            store.save(bad, payload(), 419);
            store.save(good, payload(), 91);
        }
        try (var gzip = new GZIPOutputStream(Files.newOutputStream(path(bad)))) {
            gzip.write(new byte[]{10, 0, 0, 116, 0, 1, 120, 0});
        }
        IOException readFailure = assertThrows(IOException.class, () -> AtomicNbtFile.read(path(bad)));
        assertInstanceOf(net.minecraft.ReportedException.class, readFailure.getCause().getCause());
        byte[] original = Files.readAllBytes(path(bad));
        for (int restart = 0; restart < 2; restart++) {
            try (NbtProfileStore store = open()) {
                assertEquals(91, store.load(good).orElseThrow().revision());
                assertEquals(92, store.nextRevision(good.playerId()));
                assertThrows(ProfileCorruptionException.class, () -> store.load(bad));
                assertThrows(ProfileCorruptionException.class, () -> store.save(bad, new CompoundTag(), 420));
                assertThrows(ProfileCorruptionException.class, () -> store.delete(bad));
                assertThrows(IllegalStateException.class, () -> store.nextRevision(bad.playerId()));
                assertThrows(ProfileCorruptionException.class, () -> store.commitBatch(bad.playerId(), 420,
                        Map.of(bad, new CompoundTag()), Optional.empty()));
            }
        }
        assertArrayEquals(original, Files.readAllBytes(path(bad)));
        Path quarantine = world.resolve("data/mwi/diagnostics/inputs").resolve(bad.playerId().toString());
        try (var files = Files.list(quarantine)) {
            Path archived = files.filter(p -> p.toString().endsWith(".bin")).findFirst().orElseThrow();
            assertArrayEquals(original, Files.readAllBytes(archived));
        }
        // Even deletion of the damaged canonical must never reclassify it as a fresh player.
        Files.delete(path(bad));
        try (NbtProfileStore store = open()) {
            assertThrows(ProfileCorruptionException.class, () -> store.load(bad));
        }
    }

    @Test
    void crcAndTruncatedTrailerAreRejected() throws Exception {
        Path file = world.resolve("crc.dat");
        AtomicNbtFile.write(file, payload());
        byte[] valid = Files.readAllBytes(file);
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 8] ^= 1;
        Files.write(file, corrupt);
        assertThrows(IOException.class, () -> AtomicNbtFile.read(file));
        Files.write(file, java.util.Arrays.copyOf(valid, valid.length - 3));
        assertThrows(IOException.class, () -> AtomicNbtFile.read(file));
    }

    @Test
    void temporaryAndBackupFilesDoNotParticipateInRevisionScan() throws Exception {
        ProfileKey key = key();
        try (NbtProfileStore store = open()) {
            store.save(key, payload(), 419);
        }
        for (String suffix : new String[]{".bak", ".tmp", ".tmp-interrupted"}) {
            Files.writeString(path(key).resolveSibling(path(key).getFileName() + suffix), "broken");
        }
        Files.writeString(path(key).resolveSibling("backup.dat"), "broken");
        try (NbtProfileStore store = open()) {
            assertEquals(420, store.nextRevision(key.playerId()));
            assertEquals(1, store.loadPlayerProfiles(key.playerId()).size());
        }
    }

    @Test
    void checksumDamageDuringRuntimeCannotBeOverwritten() throws Exception {
        ProfileKey key = key();
        try (NbtProfileStore store = open()) {
            store.save(key, payload(), 419);
            CompoundTag document = AtomicNbtFile.read(path(key));
            document.getCompound("payload").putString("marker", "tampered");
            AtomicNbtFile.write(path(key), document);
            store.invalidate(key);
            assertThrows(ProfileCorruptionException.class, () -> store.load(key));
            assertThrows(ProfileCorruptionException.class, () -> store.save(key, payload(), 420));
            assertEquals("tampered", AtomicNbtFile.read(path(key)).getCompound("payload").getString("marker"));
        }
    }

    @Test
    void envelopeDamageDoesNotRebuildCounterOrAbortStartup() throws Exception {
        ProfileKey key = key();
        try (NbtProfileStore store = open()) {
            store.save(key, payload(), 419);
        }
        CompoundTag document = AtomicNbtFile.read(path(key));
        document.putLong("revision", 1);
        AtomicNbtFile.write(path(key), document);
        Path counter = world.resolve("data/mwi/revision_counters").resolve(key.playerId() + ".dat");
        byte[] before = Files.readAllBytes(counter);
        try (NbtProfileStore store = open()) {
            assertThrows(ProfileCorruptionException.class, () -> store.load(key));
        }
        assertArrayEquals(before, Files.readAllBytes(counter));
    }

    @Test
    void interruptedQuarantineStillBlocksPlayerWithMissingCanonical() throws Exception {
        ProfileKey key = key();
        Files.createDirectories(world.resolve("data/mwi/quarantine").resolve(key.playerId().toString()));
        try (NbtProfileStore store = open()) {
            assertThrows(ProfileCorruptionException.class, () -> store.load(key));
            assertThrows(ProfileCorruptionException.class, () -> store.save(key, payload(), 1));
        }
    }

    private NbtProfileStore open() throws IOException {
        return new NbtProfileStore(world, 5, 32);
    }

    @Test
    void corruptTransactionAndRevisionOnlyBlockTheirOwners() throws Exception {
        ProfileKey transactionOwner = key();
        ProfileKey revisionOwner = key();
        ProfileKey healthy = key();
        try (NbtProfileStore store = open()) {
            store.save(healthy, payload(), 25);
        }
        for (var entry : Map.of("transactions", transactionOwner, "revisions", revisionOwner).entrySet()) {
            Path file = world.resolve("data/mwi").resolve(entry.getKey())
                    .resolve(entry.getValue().playerId().toString()).resolve("419.dat");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "invalid compressed NBT");
        }
        try (NbtProfileStore store = open()) {
            assertThrows(ProfileCorruptionException.class, () -> store.load(transactionOwner));
            assertThrows(ProfileCorruptionException.class, () -> store.load(revisionOwner));
            assertEquals(25, store.load(healthy).orElseThrow().revision());
        }
    }

    @Test
    void missingCounterAndDamagedProfileNeverWriteLowerCounter() throws Exception {
        ProfileKey key = key();
        try (NbtProfileStore store = open()) {
            store.save(key, payload(), 419);
        }
        Path counter = world.resolve("data/mwi/revision_counters").resolve(key.playerId() + ".dat");
        Files.delete(counter);
        Files.writeString(path(key), "broken");
        try (NbtProfileStore store = open()) {
            assertThrows(IllegalStateException.class, () -> store.nextRevision(key.playerId()));
            assertFalse(Files.exists(counter));
        }
    }

    private static ProfileKey key() {
        return new ProfileKey(UUID.randomUUID(), new ScopeKey(ScopeKey.Type.WORLD, "minecraft:overworld"),
                GameModeBucket.SHARED, "advancements");
    }

    private static CompoundTag payload() {
        CompoundTag payload = new CompoundTag();
        payload.putString("marker", "retained");
        return payload;
    }

    private Path path(ProfileKey key) {
        return world.resolve("data/mwi/profiles").resolve(key.playerId().toString()).resolve("world")
                .resolve(encode(key.scope().id())).resolve("shared").resolve(encode(key.shareId()) + ".dat");
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
