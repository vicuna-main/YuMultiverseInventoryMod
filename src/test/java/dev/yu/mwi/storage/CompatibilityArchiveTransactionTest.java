package dev.yu.mwi.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompatibilityArchiveTransactionTest {
    @TempDir
    Path world;

    @Test
    void installsArchiveBeforeSanitizedCanonicalProfile() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId);
        NbtProfileStore store = new NbtProfileStore(world, 5, 32);
        CompoundTag original = payload("evolvedmekanism:end_stone_osmium_ore");
        store.save(key, original, store.nextRevision(playerId));

        CompoundTag details = new CompoundTag();
        details.put("original_stack", original.getCompound("stack"));
        net.minecraft.nbt.ListTag entries = new net.minecraft.nbt.ListTag();
        CompoundTag issue = new CompoundTag();
        issue.putString("logical_slot", "inventory_contents/12");
        issue.put("original_stack", original.getCompound("stack"));
        entries.add(issue);
        details.put("entries", entries);
        long revision = store.nextRevision(playerId);
        store.commitBatch(playerId, revision, Map.of(key, new CompoundTag()), Optional.empty(),
                List.of(new ProfileArchive(key, "missing_items", "registry drift", details)));

        assertTrue(store.load(key).orElseThrow().payload().isEmpty());
        Path archive = onlyArchive();
        CompoundTag document = AtomicNbtFile.read(archive);
        assertEquals("evolvedmekanism:end_stone_osmium_ore",
                document.getCompound("payload").getCompound("stack").getString("id"));
        assertEquals(original.getCompound("stack"),
                document.getCompound("details").getCompound("original_stack"));
        assertEquals(revision, document.getLong("archive_revision"));
        List<CompatibilityArchiveEntry> listed = store.listCompatibilityArchives(playerId);
        assertEquals(1, listed.size());
        assertEquals("inventory_contents/12", listed.getFirst().issue().getString("logical_slot"));
    }

    @Test
    void archiveIoFailureCannotInstallSanitizedProfileAndReplayCompletesBoth() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfileKey key = key(playerId);
        NbtProfileStore seed = new NbtProfileStore(world, 5, 32);
        CompoundTag original = payload("missingmod:item");
        seed.save(key, original, seed.nextRevision(playerId));
        seed.close();

        NbtProfileStore.FaultInjector failArchive = new NbtProfileStore.FaultInjector() {
            @Override
            public void beforeWrite(Path temporary, Path destination, int bytes) throws IOException {
                if (destination.toString().contains("archives")) {
                    throw new IOException("injected archive write failure");
                }
            }
        };
        NbtProfileStore failing = new NbtProfileStore(world, 5, 32, 16, failArchive);
        long revision = failing.nextRevision(playerId);
        assertThrows(PendingTransactionException.class, () -> failing.commitBatch(
                playerId, revision, Map.of(key, new CompoundTag()), Optional.empty(),
                List.of(new ProfileArchive(
                        key, "missing_items", "registry drift", new CompoundTag()))));
        assertEquals(original, failing.load(key).orElseThrow().payload(),
                "canonical data must remain untouched when archive installation fails");

        NbtProfileStore recovered = new NbtProfileStore(world, 5, 32);
        assertTrue(recovered.load(key).orElseThrow().payload().isEmpty());
        assertTrue(Files.isRegularFile(onlyArchive()));
    }

    private Path onlyArchive() throws IOException {
        try (var files = Files.walk(world.resolve("data/mwi/archives/compatibility/missing_items"))) {
            return files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
    }

    private static ProfileKey key(UUID playerId) {
        return new ProfileKey(
                playerId,
                new ScopeKey(ScopeKey.Type.WORLD, "minecraft:the_end"),
                GameModeBucket.SHARED,
                "inventory_contents");
    }

    private static CompoundTag payload(String itemId) {
        CompoundTag stack = new CompoundTag();
        stack.putString("id", itemId);
        CompoundTag payload = new CompoundTag();
        payload.put("stack", stack);
        return payload;
    }
}
