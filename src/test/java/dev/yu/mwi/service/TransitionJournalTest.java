package dev.yu.mwi.service;

import dev.yu.mwi.domain.GameModeBucket;
import dev.yu.mwi.domain.ProfileKey;
import dev.yu.mwi.domain.ScopeKey;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransitionJournalTest {
    @Test
    void preparedRevisionRetainsRecoverableBeforeSnapshot() {
        UUID playerId = UUID.randomUUID();
        ProfileKey from = new ProfileKey(
                playerId, new ScopeKey(ScopeKey.Type.WORLD, "minecraft:overworld"),
                GameModeBucket.SHARED, "inventory_contents");
        ProfileKey to = new ProfileKey(
                playerId, ScopeKey.group("lobby"), GameModeBucket.SHARED, "inventory_contents");
        CompoundTag before = new CompoundTag();
        before.putString("marker", "before");
        CompoundTag after = new CompoundTag();
        after.putString("marker", "after");
        TransitionJournal.Change change = new TransitionJournal.Change(
                "inventory_contents", from, to, before, null);

        CompoundTag journal = TransitionJournal.prepared(
                "test", "minecraft:overworld", "example:lobby", List.of(change));
        TransitionJournal.complete(journal, "committed", List.of(change.withAfter(after)), null);

        assertEquals("committed", journal.getString("status"));
        var recovered = TransitionJournal.recoverBefore(journal);
        assertEquals(1, recovered.size());
        assertEquals("inventory_contents", recovered.getFirst().shareId());
        assertEquals("before", recovered.getFirst().payload().getString("marker"));
    }
}
