package dev.yu.mwi.recovery;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoverySourcePolicyTest {
    @Test void nativeEscrowIsEligibleAndLegacyExternalDataIsImmutable() {
        CompoundTag payload = payload("yu_multiverse_inventory", "activation_escrow");
        assertTrue(RecoverySourcePolicy.nativeSource(payload));
        assertEquals("", RecoverySourcePolicy.blockedReason(payload));
        for (String provider : new String[]{"", "tombstonedefender", "unknown"}) {
            for (String type : new String[]{"death_escrow", "grave_escrow", "grave_claim", "activation_escrow"}) {
                CompoundTag legacy = payload(provider, type), original = legacy.copy();
                assertFalse(RecoverySourcePolicy.nativeSource(legacy));
                assertFalse(RecoverySourcePolicy.blockedReason(legacy).isBlank());
                assertEquals(original, legacy);
            }
        }
    }
    private static CompoundTag payload(String provider, String type) {
        CompoundTag evidence = new CompoundTag(); evidence.putString("provider", provider); evidence.putString("type", type);
        CompoundTag identity = new CompoundTag(); identity.put("evidence", evidence);
        CompoundTag payload = new CompoundTag(); payload.put("identity", identity); payload.putString("state", "AVAILABLE");
        return payload;
    }
}
