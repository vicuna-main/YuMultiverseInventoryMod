package dev.yu.mwi.service;

import dev.yu.mwi.domain.*;
import dev.yu.mwi.storage.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RevisionRecoveryScopeTest {
    @TempDir Path directory;
    private final UUID owner = UUID.randomUUID();
    private ProfileKey key(String world, String share) {
        return new ProfileKey(owner, new ScopeKey(ScopeKey.Type.WORLD, world), GameModeBucket.SURVIVAL, share);
    }
    private void receipt(NbtProfileStore store, String type) throws Exception {
        CompoundTag scope = new CompoundTag(), key = new CompoundTag();
        key.putString("scope_type", "WORLD"); key.putString("scope_id", "minecraft:overworld"); key.putString("game_mode", "SURVIVAL");
        scope.put("inventory_contents", key);
        CompoundTag evidence = new CompoundTag(); evidence.putString("type", type);
        CompoundTag identity = new CompoundTag(); identity.put("scope", scope); identity.put("evidence", evidence);
        CompoundTag payload = new CompoundTag(); payload.put("identity", identity); payload.putString("state", "CLOSED");
        store.commitRecovery(owner, store.nextRevision(owner), Map.of(), new RecoveryMutation(UUID.randomUUID(), -1, UUID.randomUUID(), payload));
    }

    @Test void legacyReceiptsStillFenceTheirItemScopeAfterExternalIntegrationIsRemoved() throws Exception {
        try (var store = new NbtProfileStore(directory, 20, 32)) {
            var recovery = new RevisionRecoveryService(store);
            assertDoesNotThrow(() -> recovery.requireNoRecoveryConflict(owner, List.of(key("minecraft:overworld", "inventory_contents"))));
            receipt(store, "grave_claim");
            assertThrows(RevisionRecoveryService.RecoveryException.class,
                    () -> recovery.requireNoRecoveryConflict(owner, List.of(key("minecraft:overworld", "inventory_contents"))));
        }
    }

    @Test void spentEntitlementFencesOnlyItsPlayerAndItemScope() throws Exception {
        try (var store = new NbtProfileStore(directory, 20, 32)) {
            receipt(store, "death_escrow");
            var recovery = new RevisionRecoveryService(store);
            assertThrows(RevisionRecoveryService.RecoveryException.class,
                    () -> recovery.requireNoRecoveryConflict(owner, List.of(key("minecraft:overworld", "inventory_contents"))));
            assertDoesNotThrow(() -> recovery.requireNoRecoveryConflict(owner, List.of(key("minecraft:the_nether", "inventory_contents"))));
            assertDoesNotThrow(() -> recovery.requireNoRecoveryConflict(owner, List.of(key("minecraft:overworld", "experience"))));
            assertDoesNotThrow(() -> recovery.requireNoRecoveryConflict(UUID.randomUUID(), List.of(key("minecraft:overworld", "inventory_contents"))));
        }
    }
}
