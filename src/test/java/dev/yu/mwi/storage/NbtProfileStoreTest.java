package dev.yu.mwi.storage;

import dev.yu.mwi.domain.GameModeBucket;
import dev.yu.mwi.domain.ProfileKey;
import dev.yu.mwi.domain.ScopeKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtProfileStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void profileRoundTripsAndUnchangedPayloadIsNotRewritten() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId, "minecraft:overworld", "inventory_contents");
        NbtProfileStore store = new NbtProfileStore(temporaryDirectory, 5, 32);
        CompoundTag payload = new CompoundTag();
        payload.putString("marker", "alpha");

        long firstRevision = store.nextRevision(playerId);
        assertEquals(SaveResult.WRITTEN, store.save(key, payload, firstRevision));
        assertEquals(SaveResult.UNCHANGED, store.save(key, payload, firstRevision + 1));

        StoredProfile loaded = store.load(key).orElseThrow();
        assertEquals("alpha", loaded.payload().getString("marker"));
        loaded.payload().putString("marker", "mutated copy");
        assertEquals("alpha", store.load(key).orElseThrow().payload().getString("marker"));
        assertTrue(store.cacheStats().hits() >= 2);
    }

    @Test
    void checksumMismatchFailsClosed() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId, "minecraft:the_nether", "off_hand");
        NbtProfileStore store = new NbtProfileStore(temporaryDirectory, 5, 32);
        CompoundTag payload = new CompoundTag();
        payload.putInt("value", 42);
        store.save(key, payload, store.nextRevision(playerId));

        Path profileFile;
        try (var files = Files.walk(temporaryDirectory.resolve("data/mwi/profiles"))) {
            profileFile = files.filter(path -> path.getFileName().toString().endsWith(".dat"))
                    .findFirst().orElseThrow();
        }
        CompoundTag document = AtomicNbtFile.read(profileFile);
        document.putString("checksum", "not-a-real-checksum");
        AtomicNbtFile.write(profileFile, document);

        NbtProfileStore reopened = new NbtProfileStore(temporaryDirectory, 5, 32);
        assertThrows(ProfileCorruptionException.class, () -> reopened.load(key));
    }

    @Test
    void canonicalNestedProfileSurvivesARealStoreReopen() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId, "minecraft:overworld", "cosmetic_armor");
        CompoundTag payload = new CompoundTag();
        CompoundTag inventory = new CompoundTag();
        inventory.putString("Hidden", "0011");
        inventory.putInt("Size", 4);
        CompoundTag item = new CompoundTag();
        item.putInt("Slot", 2);
        item.putBoolean("isSkinArmor", true);
        net.minecraft.nbt.ListTag items = new net.minecraft.nbt.ListTag();
        items.add(item);
        inventory.put("Items", items);
        payload.put("inventory", inventory);
        payload.putInt("schema", 1);

        NbtProfileStore writer = new NbtProfileStore(temporaryDirectory, 5, 32);
        writer.save(key, payload, writer.nextRevision(playerId));
        writer.close();

        NbtProfileStore reader = new NbtProfileStore(temporaryDirectory, 5, 32);
        assertEquals(payload, reader.load(key).orElseThrow().payload());
        CompoundTag document = AtomicNbtFile.read(onlyProfileFile());
        assertEquals(NbtChecksums.CANONICAL_ALGORITHM, document.getString("checksum_algorithm"));
        assertEquals(
                NbtChecksums.canonicalSha256(document.getCompound("payload")),
                document.getString("checksum"));
    }

    @Test
    void canonicalChecksumIgnoresCompoundInsertionOrder() throws Exception {
        CompoundTag first = new CompoundTag();
        CompoundTag firstNested = new CompoundTag();
        firstNested.putString("hidden", "1010");
        firstNested.putInt("size", 4);
        first.put("inventory", firstNested);
        first.putInt("schema", 1);

        CompoundTag second = new CompoundTag();
        second.putInt("schema", 1);
        CompoundTag secondNested = new CompoundTag();
        secondNested.putInt("size", 4);
        secondNested.putString("hidden", "1010");
        second.put("inventory", secondNested);

        assertEquals(NbtChecksums.canonicalSha256(first), NbtChecksums.canonicalSha256(second));
    }

    @Test
    void legacyProfileIsVerifiedAndMigratedToCanonicalChecksum() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId, "minecraft:overworld", "cosmetic_armor");
        NbtProfileStore store = new NbtProfileStore(temporaryDirectory, 5, 32);
        CompoundTag payload = new CompoundTag();
        CompoundTag inventory = new CompoundTag();
        inventory.putInt("Size", 4);
        inventory.putString("Hidden", "0101");
        payload.putInt("schema", 1);
        payload.put("inventory", inventory);
        store.save(key, payload, store.nextRevision(playerId));

        Path profileFile = onlyProfileFile();
        CompoundTag legacy = AtomicNbtFile.read(profileFile);
        CompoundTag legacyPayload = legacy.getCompound("payload");
        legacy.remove("checksum_algorithm");
        legacy.putString("checksum", NbtChecksums.legacySha256(legacyPayload));
        AtomicNbtFile.write(profileFile, legacy);

        NbtProfileStore reopened = new NbtProfileStore(temporaryDirectory, 5, 32);
        StoredProfile loaded = reopened.load(key).orElseThrow();
        assertEquals(payload, loaded.payload());

        CompoundTag migrated = AtomicNbtFile.read(profileFile);
        assertEquals(NbtChecksums.CANONICAL_ALGORITHM, migrated.getString("checksum_algorithm"));
        assertEquals(
                NbtChecksums.canonicalSha256(migrated.getCompound("payload")),
                migrated.getString("checksum"));
    }

    @Test
    void legacyChecksumDoesNotAuthorizeChangedPayload() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId, "minecraft:overworld", "cosmetic_armor");
        NbtProfileStore store = new NbtProfileStore(temporaryDirectory, 5, 32);
        CompoundTag payload = new CompoundTag();
        payload.putString("marker", "original");
        store.save(key, payload, store.nextRevision(playerId));

        Path profileFile = onlyProfileFile();
        CompoundTag tampered = AtomicNbtFile.read(profileFile);
        String oldLegacyChecksum = NbtChecksums.legacySha256(tampered.getCompound("payload"));
        tampered.remove("checksum_algorithm");
        tampered.getCompound("payload").putString("marker", "changed");
        tampered.putString("checksum", oldLegacyChecksum);
        AtomicNbtFile.write(profileFile, tampered);

        NbtProfileStore reopened = new NbtProfileStore(temporaryDirectory, 5, 32);
        assertThrows(ProfileCorruptionException.class, () -> reopened.load(key));
    }

    @Test
    void versionOneEnvelopeRemainsReadable() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId, "minecraft:overworld", "inventory_contents");
        NbtProfileStore writer = new NbtProfileStore(temporaryDirectory, 5, 32);
        CompoundTag payload = new CompoundTag();
        payload.putString("marker", "v1-envelope");
        writer.save(key, payload, writer.nextRevision(playerId));

        Path profileFile = onlyProfileFile();
        CompoundTag document = AtomicNbtFile.read(profileFile);
        document.putString("envelope_checksum_algorithm", "canonical_profile_envelope_sha256_v1");
        document.remove("envelope_checksum");
        document.putString("envelope_checksum", NbtChecksums.canonicalSha256(document));
        AtomicNbtFile.write(profileFile, document);

        NbtProfileStore reopened = new NbtProfileStore(temporaryDirectory, 5, 32);
        assertEquals("v1-envelope", reopened.load(key).orElseThrow().payload().getString("marker"));
    }

    @Test
    void revisionRetentionKeepsNewestEntries() throws Exception {
        UUID playerId = UUID.randomUUID();
        NbtProfileStore store = new NbtProfileStore(temporaryDirectory, 2, 32);
        for (long revision = 1; revision <= 3; revision++) {
            CompoundTag journal = new CompoundTag();
            journal.putLong("value", revision);
            store.writeRevision(playerId, revision, journal);
        }

        assertEquals(java.util.List.of(3L, 2L), store.listRevisions(playerId));
        assertFalse(store.loadRevision(playerId, 1L).isPresent());
        assertEquals(3L, store.loadRevision(playerId, 3L).orElseThrow().getLong("value"));
    }

    @Test
    void encodedIdentifiersCannotEscapeStorageRoot() throws Exception {
        UUID playerId = UUID.randomUUID();
        NbtProfileStore store = new NbtProfileStore(temporaryDirectory, 5, 32);
        ProfileKey key = key(playerId, "../../outside", "inventory_contents");
        store.save(key, new CompoundTag(), store.nextRevision(playerId));
        Path storageRoot = temporaryDirectory.resolve("data/mwi").toAbsolutePath().normalize();

        try (var files = Files.walk(temporaryDirectory)) {
            assertTrue(files.filter(Files::isRegularFile)
                    .allMatch(path -> path.toAbsolutePath().normalize().startsWith(storageRoot)));
        }
    }

    @Test
    void durableBatchCompletesAfterInjectedMidCommitFailure() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey inventory = key(playerId, "minecraft:overworld", "inventory_contents");
        ProfileKey armor = key(playerId, "minecraft:overworld", "armor_contents");
        NbtProfileStore.FaultInjector failSecondWrite = new NbtProfileStore.FaultInjector() {
            private boolean failed;

            @Override
            public void beforeProfileApply(ProfileKey key, int index) throws java.io.IOException {
                if (!failed && index == 1) {
                    failed = true;
                    throw new java.io.IOException("injected profile write failure");
                }
            }
        };
        NbtProfileStore writer = new NbtProfileStore(temporaryDirectory, 5, 32, 16, failSecondWrite);
        long revision = writer.nextRevision(playerId);
        CompoundTag inventoryPayload = new CompoundTag();
        inventoryPayload.putString("marker", "inventory-new");
        CompoundTag armorPayload = new CompoundTag();
        armorPayload.putString("marker", "armor-new");
        LinkedHashMap<ProfileKey, CompoundTag> writes = new LinkedHashMap<>();
        writes.put(inventory, inventoryPayload);
        writes.put(armor, armorPayload);
        CompoundTag journal = new CompoundTag();
        journal.putString("status", "committed");

        assertThrows(PendingTransactionException.class,
                () -> writer.commitBatch(playerId, revision, writes, Optional.of(journal)));

        NbtProfileStore recovered = new NbtProfileStore(temporaryDirectory, 5, 32, 16);
        assertEquals("inventory-new", recovered.load(inventory).orElseThrow().payload().getString("marker"));
        assertEquals("armor-new", recovered.load(armor).orElseThrow().payload().getString("marker"));
        assertEquals("committed", recovered.loadRevision(playerId, revision).orElseThrow().getString("status"));
        Path transactions = temporaryDirectory.resolve("data/mwi/transactions");
        try (var files = Files.walk(transactions)) {
            assertFalse(files.anyMatch(Files::isRegularFile));
        }
    }

    @Test
    void failureBeforeTransactionForceLeavesPreviousStateIntact() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId, "minecraft:overworld", "inventory_contents");
        NbtProfileStore.FaultInjector failForce = new NbtProfileStore.FaultInjector() {
            @Override
            public void beforeForce(Path temporary, Path destination) throws java.io.IOException {
                throw new java.io.IOException("injected force failure");
            }
        };
        NbtProfileStore writer = new NbtProfileStore(temporaryDirectory, 5, 32, 16, failForce);
        CompoundTag payload = new CompoundTag();
        payload.putString("marker", "not-durable");

        assertThrows(java.io.IOException.class,
                () -> writer.commitBatch(playerId, writer.nextRevision(playerId),
                        Map.of(key, payload), Optional.empty()));

        NbtProfileStore recovered = new NbtProfileStore(temporaryDirectory, 5, 32, 16);
        assertTrue(recovered.load(key).isEmpty());
    }

    @Test
    void failureDuringProfileRenameRollsForwardFromDurableTransaction() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId, "minecraft:overworld", "inventory_contents");
        NbtProfileStore.FaultInjector failProfileMove = new NbtProfileStore.FaultInjector() {
            private int moves;

            @Override
            public void beforeMove(Path temporary, Path destination) throws java.io.IOException {
                if (++moves == 2) {
                    throw new java.io.IOException("injected profile rename failure");
                }
            }
        };
        NbtProfileStore writer = new NbtProfileStore(
                temporaryDirectory, 5, 32, 16, failProfileMove);
        CompoundTag payload = new CompoundTag();
        payload.putString("marker", "roll-forward");

        assertThrows(PendingTransactionException.class,
                () -> writer.commitBatch(playerId, writer.nextRevision(playerId),
                        Map.of(key, payload), Optional.empty()));

        NbtProfileStore recovered = new NbtProfileStore(temporaryDirectory, 5, 32, 16);
        assertEquals("roll-forward", recovered.load(key).orElseThrow().payload().getString("marker"));
    }

    @Test
    void staleRevisionCannotOverwriteNewerProfile() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId, "minecraft:overworld", "inventory_contents");
        NbtProfileStore store = new NbtProfileStore(temporaryDirectory, 5, 32);
        CompoundTag newer = new CompoundTag();
        newer.putString("marker", "newer");
        store.commitBatch(playerId, 2L, Map.of(key, newer), Optional.empty());
        CompoundTag stale = new CompoundTag();
        stale.putString("marker", "stale");

        assertThrows(java.io.IOException.class,
                () -> store.commitBatch(playerId, 1L, Map.of(key, stale), Optional.empty()));
        assertEquals("newer", store.load(key).orElseThrow().payload().getString("marker"));
    }

    @Test
    void preparedJournalRollsOutgoingProfileBackAndMarksMissingTargetForLiveReset() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey source = key(playerId, "minecraft:overworld", "inventory_contents");
        ProfileKey missingTarget = key(playerId, "minecraft:the_nether", "inventory_contents");
        NbtProfileStore writer = new NbtProfileStore(temporaryDirectory, 5, 32);
        CompoundTag oldCheckpoint = new CompoundTag();
        oldCheckpoint.putString("marker", "old-checkpoint");
        writer.save(source, oldCheckpoint, writer.nextRevision(playerId));

        CompoundTag latestBeforeSwitch = new CompoundTag();
        latestBeforeSwitch.putString("marker", "latest-before-switch");
        long interruptedRevision = writer.nextRevision(playerId);
        writer.writeRevision(
                playerId,
                interruptedRevision,
                preparedJournal(source, missingTarget, latestBeforeSwitch));
        writer.close();

        NbtProfileStore recovered = new NbtProfileStore(temporaryDirectory, 5, 32);
        assertTrue(recovered.recoveredFromUncleanShutdown());
        assertTrue(recovered.requiresCanonicalRecovery(playerId));
        assertTrue(recovered.shouldResetLive(missingTarget));
        assertEquals(
                "latest-before-switch",
                recovered.load(source).orElseThrow().payload().getString("marker"));
        assertTrue(recovered.load(missingTarget).isEmpty());
        assertEquals(
                "rolled_back",
                recovered.loadRevision(playerId, interruptedRevision).orElseThrow().getString("status"));

        recovered.completeStartupRecovery(playerId);
        assertFalse(recovered.requiresCanonicalRecovery(playerId));
        assertFalse(recovered.shouldResetLive(missingTarget));
    }

    @Test
    void profileEnvelopeDetectsMetadataTampering() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId, "minecraft:overworld", "inventory_contents");
        NbtProfileStore writer = new NbtProfileStore(temporaryDirectory, 5, 32);
        writer.save(key, new CompoundTag(), writer.nextRevision(playerId));
        writer.close();
        Path profile = onlyProfileFile();
        CompoundTag document = AtomicNbtFile.read(profile);
        document.putLong("saved_at", Long.MAX_VALUE);
        AtomicNbtFile.write(profile, document);

        NbtProfileStore reader = new NbtProfileStore(temporaryDirectory, 5, 32);
        assertThrows(ProfileCorruptionException.class, () -> reader.load(key));
    }

    @Test
    void cacheHonorsByteBudgetInAdditionToEntryCount() throws Exception {
        UUID playerId = UUID.randomUUID();
        NbtProfileStore store = new NbtProfileStore(temporaryDirectory, 5, 10_000, 1);
        for (int index = 0; index < 8; index++) {
            CompoundTag payload = new CompoundTag();
            payload.putByteArray("data", new byte[256 * 1024]);
            store.save(key(playerId, "example:world_" + index, "inventory_contents"), payload,
                    store.nextRevision(playerId));
        }

        assertTrue(store.cacheStats().entries() < 8);
        assertTrue(store.cacheStats().bytes() <= store.cacheStats().maxBytes());
    }

    @Test
    void revisionCountersArePerPlayerAndCorruptionRebuildsFromProfiles() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId, "minecraft:overworld", "inventory_contents");
        NbtProfileStore writer = new NbtProfileStore(temporaryDirectory, 5, 32);
        long first = writer.nextRevision(playerId);
        writer.save(key, new CompoundTag(), first);
        writer.close();

        CompoundTag index = AtomicNbtFile.read(temporaryDirectory.resolve("data/mwi/index.dat"));
        assertFalse(index.contains("player_revisions"));
        Path counter = temporaryDirectory.resolve("data/mwi/revision_counters/" + playerId + ".dat");
        CompoundTag corrupted = AtomicNbtFile.read(counter);
        corrupted.putLong("revision", 0L);
        AtomicNbtFile.write(counter, corrupted);

        NbtProfileStore recovered = new NbtProfileStore(temporaryDirectory, 5, 32);
        assertEquals(2L, recovered.nextRevision(playerId));
        Path archives = temporaryDirectory.resolve("data/mwi/archives/metadata_corruption");
        try (var files = Files.list(archives)) {
            assertTrue(files.anyMatch(Files::isRegularFile));
        }
    }

    private Path onlyProfileFile() throws Exception {
        try (var files = Files.walk(temporaryDirectory.resolve("data/mwi/profiles"))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".dat"))
                    .findFirst().orElseThrow();
        }
    }

    private static ProfileKey key(UUID playerId, String world, String share) {
        return new ProfileKey(
                playerId,
                new ScopeKey(ScopeKey.Type.WORLD, world),
                GameModeBucket.SHARED,
                share);
    }

    private static CompoundTag preparedJournal(ProfileKey from, ProfileKey to, CompoundTag before) {
        CompoundTag journal = new CompoundTag();
        journal.putString("status", "prepared");
        ListTag changes = new ListTag();
        CompoundTag change = new CompoundTag();
        change.putString("share_id", from.shareId());
        change.put("from_key", encodedKey(from));
        change.put("to_key", encodedKey(to));
        change.put("before", before.copy());
        changes.add(change);
        journal.put("changes", changes);
        return journal;
    }

    private static CompoundTag encodedKey(ProfileKey key) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("player_uuid", key.playerId());
        tag.putString("scope_type", key.scope().type().name());
        tag.putString("scope_id", key.scope().id());
        tag.putString("game_mode", key.gameMode().name());
        tag.putString("share_id", key.shareId());
        return tag;
    }
}
