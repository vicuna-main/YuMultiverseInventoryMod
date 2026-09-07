package dev.yu.mwi.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yu.mwi.adapter.vanilla.RespawnLocationPayload;
import dev.yu.mwi.domain.GameModeBucket;
import dev.yu.mwi.domain.ProfileKey;
import dev.yu.mwi.domain.ScopeKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BedSpawnRepairTransactionTest {
    @TempDir
    Path world;

    @Test
    void clearArchivesOriginalAndWritesBothCanonicalChecksumsAtOneRevision() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = bedSpawnKey(playerId);
        NbtProfileStore store = new NbtProfileStore(world, 5, 32);
        CompoundTag original = bedSpawnPayload();
        store.save(key, original, store.nextRevision(playerId));

        CompoundTag details = new CompoundTag();
        details.putString("scope", "world:minecraft:playerworld/chigurengyp");
        CompoundTag cleared = RespawnLocationPayload.clearedPayload();
        long revision = store.nextRevision(playerId);
        CompoundTag journal = new CompoundTag();
        journal.putString("status", "committed");
        store.commitBatch(
                playerId,
                revision,
                Map.of(key, cleared),
                Optional.of(journal),
                List.of(new ProfileArchive(
                        key, "bed_spawn", "operator cleared bed-spawn profile", details)));

        StoredProfile repaired = store.load(key).orElseThrow();
        assertEquals(cleared, repaired.payload());
        assertEquals(revision, repaired.revision());
        assertTrue(store.listRevisions(playerId).contains(revision));

        CompoundTag profileDocument = AtomicNbtFile.read(onlyFile(world.resolve("data/mwi/profiles")));
        assertEquals(NbtChecksums.canonicalSha256(cleared), profileDocument.getString("checksum"));
        assertFalse(profileDocument.getString("envelope_checksum").isBlank());
        assertEquals(revision, profileDocument.getLong("revision"));

        CompoundTag archive = AtomicNbtFile.read(onlyFile(world.resolve("data/mwi/archives/bed_spawn")));
        assertEquals(original, archive.getCompound("payload"));
        assertEquals(revision, archive.getLong("archive_revision"));
        assertEquals("world:minecraft:playerworld/chigurengyp",
                archive.getCompound("details").getString("scope"));

        store.close();
        NbtProfileStore reopened = new NbtProfileStore(world, 5, 32);
        assertEquals(cleared, reopened.load(key).orElseThrow().payload(),
                "reopening must verify and accept both canonical checksums");
    }

    @Test
    void archiveFailureCannotHalfInstallClearAndRecoveryReplaysBoth() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = bedSpawnKey(playerId);
        CompoundTag original = bedSpawnPayload();
        NbtProfileStore seed = new NbtProfileStore(world, 5, 32);
        seed.save(key, original, seed.nextRevision(playerId));
        seed.close();

        NbtProfileStore.FaultInjector failArchive = new NbtProfileStore.FaultInjector() {
            @Override
            public void beforeWrite(Path temporary, Path destination, int bytes) throws IOException {
                if (destination.toString().contains("archives")) {
                    throw new IOException("injected bed-spawn archive failure");
                }
            }
        };
        NbtProfileStore failing = new NbtProfileStore(world, 5, 32, 16, failArchive);
        long revision = failing.nextRevision(playerId);
        assertThrows(PendingTransactionException.class, () -> failing.commitBatch(
                playerId,
                revision,
                Map.of(key, RespawnLocationPayload.clearedPayload()),
                Optional.empty(),
                List.of(new ProfileArchive(
                        key, "bed_spawn", "operator cleared bed-spawn profile", new CompoundTag()))));
        failing.invalidate(key);
        assertEquals(original, failing.load(key).orElseThrow().payload(),
                "canonical profile must remain intact when archive installation fails");

        NbtProfileStore recovered = new NbtProfileStore(world, 5, 32);
        assertEquals(RespawnLocationPayload.clearedPayload(), recovered.load(key).orElseThrow().payload());
        assertTrue(Files.isRegularFile(onlyFile(world.resolve("data/mwi/archives/bed_spawn"))));
    }

    private static ProfileKey bedSpawnKey(UUID playerId) {
        return new ProfileKey(
                playerId,
                new ScopeKey(ScopeKey.Type.WORLD, "minecraft:playerworld/chigurengyp"),
                GameModeBucket.SHARED,
                "bed_spawn");
    }

    private static CompoundTag bedSpawnPayload() {
        CompoundTag payload = new CompoundTag();
        payload.putBoolean("present", true);
        payload.putString("dimension", "minecraft:playerworld/fushengruomeng");
        payload.putLong("position", new BlockPos(30, 81, -44).asLong());
        payload.putFloat("angle", 137.5F);
        payload.putBoolean("forced", true);
        return payload;
    }

    private static Path onlyFile(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
    }
}
