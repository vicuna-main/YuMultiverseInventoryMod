package dev.yu.mwi.recovery;

import net.minecraft.nbt.CompoundTag;

/** Legacy external entitlements remain immutable evidence, never become new native grants. */
public final class RecoverySourcePolicy {
    private RecoverySourcePolicy() {}
    public static boolean nativeSource(CompoundTag payload) {
        CompoundTag evidence = payload.getCompound("identity").getCompound("evidence");
        return "yu_multiverse_inventory".equals(evidence.getString("provider"))
                && "activation_escrow".equals(evidence.getString("type"));
    }
    public static String blockedReason(CompoundTag payload) {
        return nativeSource(payload) ? "" : "历史外部来源仅供查阅；物品、领取凭证和未决记录均已保留，本版不再处理外部交接";
    }
}
