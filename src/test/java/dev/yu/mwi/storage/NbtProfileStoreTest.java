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
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtProfileStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void nextRevisionIsMemoryOnlyAndConcurrentAllocationsAreUnique() throws Exception {
        UUID playerId = UUID.randomUUID();
        NbtProfileStore store = new NbtProfileStore(temporaryDirectory, 5, 32);
        Path counter = temporaryDirectory.resolve("data/mwi/revision_counters").resolve(playerId + ".dat");

        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> allocations = java.util.stream.IntStream.range(0, 64)
                    .mapToObj(ignored -> workers.submit(() -> {
                        assertTrue(start.await(2, TimeUnit.SECONDS));
                        return store.nextRevision(playerId);
                    }))
                    .toList();
            start.countDown();
            List<Long> revisions = allocations.stream().map(NbtProfileStoreTest::get).sorted().toList();
            assertEquals(64, new HashSet<>(revisions).size());
            assertEquals(java.util.stream.LongStream.rangeClosed(1, 64).boxed().toList(), revisions);
        }
        assertFalse(Files.exists(counter), "revision allocation must not perform caller-thread disk I/O");
    }

    @Test
    void blockedProfileReadForOnePlayerDoesNotBlockRevisionAllocationOrAnotherPlayer() throws Exception {
        UUID blockedPlayer = UUID.randomUUID();
        UUID progressingPlayer = UUID.randomUUID();
        ProfileKey blockedKey = key(blockedPlayer, "minecraft:overworld", "inventory_contents");
        ProfileKey progressingKey = key(progressingPlayer, "minecraft:overworld", "inventory_contents");
        CompoundTag initial = new CompoundTag();
        initial.putString("marker", "initial");
        NbtProfileStore seed = new NbtProfileStore(temporaryDirectory, 5, 32);
        seed.save(blockedKey, initial, seed.nextRevision(blockedPlayer));
        seed.save(progressingKey, initial, seed.nextRevision(progressingPlayer));
        seed.close();

        CountDownLatch blockedReadStarted = new CountDownLatch(1);
        CountDownLatch releaseBlockedRead = new CountDownLatch(1);
        NbtProfileStore.FaultInjector blocker = new NbtProfileStore.FaultInjector() {
            @Override
            public void beforeProfileRead(Path file, ProfileKey key) throws java.io.IOException {
                if (key.playerId().equals(blockedPlayer)) {
                    blockedReadStarted.countDown();
                    await(releaseBlockedRead);
                }
            }
        };
        NbtProfileStore store = new NbtProfileStore(temporaryDirectory, 5, 32, 16, blocker);
        store.invalidatePlayer(blockedPlayer);
        store.invalidatePlayer(progressingPlayer);
        CompoundTag updated = new CompoundTag();
        updated.putString("marker", "updated");

        try (ExecutorService workers = Executors.newFixedThreadPool(4)) {
            Future<?> blocked = workers.submit(() -> {
                store.commitBatch(blockedPlayer, store.nextRevision(blockedPlayer),
                        Map.of(blockedKey, updated), Optional.empty());
                return null;
            });
            assertTrue(blockedReadStarted.await(2, TimeUnit.SECONDS));

            assertEquals(2L, workers.submit(() -> store.nextRevision(progressingPlayer))
                    .get(1, TimeUnit.SECONDS));
            assertEquals(3L, workers.submit(() -> store.nextRevision(blockedPlayer))
                    .get(1, TimeUnit.SECONDS));
            workers.submit(store::cacheStats).get(1, TimeUnit.SECONDS);
            Future<?> progressing = workers.submit(() -> {
                store.commitBatch(progressingPlayer, 2L,
                        Map.of(progressingKey, updated), Optional.empty());
                return null;
            });
            progressing.get(1, TimeUnit.SECONDS);
            assertFalse(releaseBlockedRead.await(5, TimeUnit.SECONDS),
                    "the simulated disk read must remain blocked for the full acceptance interval");
            releaseBlockedRead.countDown();
            blocked.get(2, TimeUnit.SECONDS);
        } finally {
            releaseBlockedRead.countDown();
        }
    }

    @Test
    void concurrentCacheMutationPreservesWeightAndEntryInvariants() throws Exception {
        NbtProfileStore store = new NbtProfileStore(temporaryDirectory, 5, 8, 1);
        List<UUID> players = java.util.stream.IntStream.range(0, 12)
                .mapToObj(ignored -> UUID.randomUUID()).toList();
        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<? extends Future<?>> tasks = players.stream().map(playerId -> workers.submit(() -> {
                assertTrue(start.await(2, TimeUnit.SECONDS));
                ProfileKey key = key(playerId, "minecraft:overworld", "inventory_contents");
                for (int index = 0; index < 20; index++) {
                    CompoundTag payload = new CompoundTag();
                    payload.putInt("index", index);
                    store.save(key, payload, store.nextRevision(playerId));
                    store.load(key);
                    if ((index & 3) == 0) {
                        store.invalidate(key);
                    }
                }
                return null;
            })).toList();
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }

        NbtProfileStore.CacheInvariant invariant = store.cacheInvariant();
        assertTrue(invariant.valid(), invariant::description);
        assertTrue(invariant.entries() <= 8);
        assertTrue(invariant.bytes() >= 0L && invariant.bytes() <= invariant.maxBytes());
    }

    @Test
    void differentPlayersReachProfileApplyTogetherAndEvictSafely() throws Exception {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        ProfileKey firstKey = key(firstPlayer, "minecraft:overworld", "inventory_contents");
        ProfileKey secondKey = key(secondPlayer, "minecraft:overworld", "inventory_contents");
        CyclicBarrier bothApplying = new CyclicBarrier(2);
        NbtProfileStore.FaultInjector barrier = new NbtProfileStore.FaultInjector() {
            @Override
            public void beforeProfileApply(ProfileKey key, int index) throws java.io.IOException {
                try {
                    bothApplying.await(2, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new java.io.IOException("profile apply barrier failed", failure);
                }
            }
        };
        NbtProfileStore store = new NbtProfileStore(temporaryDirectory, 5, 1, 1, barrier);
        CompoundTag firstPayload = new CompoundTag();
        firstPayload.putString("marker", "first");
        CompoundTag secondPayload = new CompoundTag();
        secondPayload.putString("marker", "second");

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> {
                store.commitBatch(firstPlayer, store.nextRevision(firstPlayer),
                        Map.of(firstKey, firstPayload), Optional.empty());
                return null;
            });
            Future<?> second = workers.submit(() -> {
                store.commitBatch(secondPlayer, store.nextRevision(secondPlayer),
                        Map.of(secondKey, secondPayload), Optional.empty());
                return null;
            });
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        assertEquals("first", store.load(firstKey).orElseThrow().payload().getString("marker"));
        assertEquals("second", store.load(secondKey).orElseThrow().payload().getString("marker"));
        assertTrue(store.cacheStats().evictions() > 0L);
        assertTrue(store.cacheInvariant().valid(), store.cacheInvariant()::description);
    }

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
        // Historical profiles predate envelope checksums; retaining a modern envelope
        // after changing its checksum metadata would instead construct a corrupt profile.
        legacy.remove("envelope_checksum_algorithm");
        legacy.remove("envelope_checksum");
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
        assertEquals(revision + 1L, recovered.nextRevision(playerId));
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
    void preparedLoginRecoveryRestoresCanonicalBeforeImageInsteadOfUnknownLiveState() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey lobby = key(playerId, "minecraft:lobby", "inventory_contents");
        NbtProfileStore writer = new NbtProfileStore(temporaryDirectory, 5, 32);
        CompoundTag canonicalLobby = new CompoundTag();
        canonicalLobby.putString("marker", "empty-lobby");
        writer.save(lobby, canonicalLobby, writer.nextRevision(playerId));

        CompoundTag foreignLoginLive = new CompoundTag();
        foreignLoginLive.putString("marker", "private-world-items");
        long interruptedRevision = writer.nextRevision(playerId);
        writer.writeRevision(
                playerId,
                interruptedRevision,
                preparedLoginJournal(lobby, foreignLoginLive, canonicalLobby, true));
        writer.close();

        NbtProfileStore recovered = new NbtProfileStore(temporaryDirectory, 5, 32);
        assertTrue(recovered.requiresCanonicalRecovery(playerId));
        assertEquals(
                "empty-lobby",
                recovered.load(lobby).orElseThrow().payload().getString("marker"));
        assertEquals(
                "rolled_back",
                recovered.loadRevision(playerId, interruptedRevision).orElseThrow().getString("status"));
    }

    @Test
    void preparedLoginRecoveryDoesNotCreateAnAbsentTargetFromUnknownLiveState() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey lobby = key(playerId, "minecraft:lobby", "inventory_contents");
        NbtProfileStore writer = new NbtProfileStore(temporaryDirectory, 5, 32);
        CompoundTag foreignLoginLive = new CompoundTag();
        foreignLoginLive.putString("marker", "private-world-items");
        long interruptedRevision = writer.nextRevision(playerId);
        writer.writeRevision(
                playerId,
                interruptedRevision,
                preparedLoginJournal(lobby, foreignLoginLive, null, false));
        writer.close();

        NbtProfileStore recovered = new NbtProfileStore(temporaryDirectory, 5, 32);
        assertTrue(recovered.requiresCanonicalRecovery(playerId));
        assertTrue(recovered.shouldResetLive(lobby));
        assertTrue(recovered.load(lobby).isEmpty());
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

    @Test
    void invalidationMaintainsEntryAndByteAccountingAndReloadsDiskState() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();
        ProfileKey first = key(playerId, "minecraft:overworld", "advancements");
        ProfileKey second = key(playerId, "minecraft:the_nether", "advancements");
        ProfileKey other = key(otherPlayer, "minecraft:overworld", "advancements");
        NbtProfileStore store = new NbtProfileStore(temporaryDirectory, 5, 32);
        CompoundTag original = new CompoundTag();
        original.putString("marker", "original");
        store.save(first, original, store.nextRevision(playerId));
        store.save(second, original, store.nextRevision(playerId));
        store.save(other, original, store.nextRevision(otherPlayer));
        CacheStats populated = store.cacheStats();

        store.invalidate(first);
        assertEquals(populated.entries() - 1, store.cacheStats().entries());
        assertTrue(store.cacheStats().bytes() < populated.bytes());
        store.invalidatePlayer(playerId);
        assertEquals(1, store.cacheStats().entries());
        assertTrue(store.load(other).isPresent());

        NbtProfileStore externalWriter = new NbtProfileStore(temporaryDirectory, 5, 32);
        CompoundTag updated = new CompoundTag();
        updated.putString("marker", "updated");
        externalWriter.save(first, updated, externalWriter.nextRevision(playerId));
        store.invalidatePlayer(playerId);
        assertEquals("updated", store.load(first).orElseThrow().payload().getString("marker"));
        assertTrue(store.cacheStats().bytes() >= 0L);
    }

    @Test
    void inspectionHasNoDiskRevisionOrCacheSideEffects() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId, "minecraft:overworld", "advancements");
        NbtProfileStore store = new NbtProfileStore(temporaryDirectory, 5, 32);
        CompoundTag payload = new CompoundTag();
        payload.putString("marker", "inspect");
        long revision = store.nextRevision(playerId);
        store.save(key, payload, revision);
        store.invalidatePlayer(playerId);
        CacheStats before = store.cacheStats();
        Path profile = onlyProfileFile();
        java.nio.file.attribute.FileTime modified = Files.getLastModifiedTime(profile);

        Map<ProfileKey, StoredProfile> inspected = store.inspectPlayerProfiles(playerId);

        assertEquals("inspect", inspected.get(key).payload().getString("marker"));
        assertEquals(before, store.cacheStats());
        assertEquals(modified, Files.getLastModifiedTime(profile));
        assertEquals(revision + 1L, store.nextRevision(playerId));
    }

    @Test
    void compatibilityArchiveFailureLeavesSourceProfileUnchanged() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId, "minecraft:overworld", "advancements");
        CompoundTag original = new CompoundTag();
        original.putString("marker", "original");
        NbtProfileStore initial = new NbtProfileStore(temporaryDirectory, 5, 32);
        initial.save(key, original, initial.nextRevision(playerId));
        initial.close();

        NbtProfileStore.FaultInjector failCompatibilityArchive = new NbtProfileStore.FaultInjector() {
            @Override
            public void beforeWrite(Path temporary, Path destination, int bytes) throws java.io.IOException {
                if (destination.toString().contains("archives"
                        + java.io.File.separator + "compatibility")) {
                    throw new java.io.IOException("injected compatibility archive failure");
                }
            }
        };
        NbtProfileStore store = new NbtProfileStore(
                temporaryDirectory, 5, 32, 16, failCompatibilityArchive);

        assertThrows(java.io.IOException.class,
                () -> store.archive(key, "compatibility", "registry drift", 2L));
        store.invalidate(key);
        assertEquals("original", store.load(key).orElseThrow().payload().getString("marker"));
    }

    private Path onlyProfileFile() throws Exception {
        try (var files = Files.walk(temporaryDirectory.resolve("data/mwi/profiles"))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".dat"))
                    .findFirst().orElseThrow();
        }
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(CountDownLatch latch) throws java.io.IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new java.io.IOException("test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("test interrupted", interrupted);
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

    private static CompoundTag preparedLoginJournal(
            ProfileKey target,
            CompoundTag liveBefore,
            CompoundTag canonicalBefore,
            boolean targetExisted) {
        CompoundTag journal = new CompoundTag();
        journal.putString("status", "prepared");
        journal.putString("reason", "login");
        ListTag changes = new ListTag();
        CompoundTag change = new CompoundTag();
        change.putString("share_id", target.shareId());
        change.put("to_key", encodedKey(target));
        change.put("before", liveBefore.copy());
        change.putBoolean("target_existed", targetExisted);
        if (canonicalBefore != null) {
            change.put("canonical_before", canonicalBefore.copy());
        }
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
