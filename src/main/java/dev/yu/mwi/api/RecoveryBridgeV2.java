package dev.yu.mwi.api;

import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;
import java.util.UUID;

/** Explicit capability boundary: V1's post-transfer checkpoint is not a V2 grave handoff. */
public final class RecoveryBridgeV2 {
    private RecoveryBridgeV2() {}
    public static CompoundTag captureGraveScope(ServerPlayer player) throws IOException {
        dev.yu.mwi.recovery.RecoveryNotifications.requireAuthenticated(player);
        return RecoveryBridgeV1.service(player).scope(player);
    }
    public static void depositGrave(ServerPlayer player, UUID source, CompoundTag scope, ListTag items, CompoundTag evidence) throws IOException {
        RecoveryBridgeV1.service(player).depositGrave(player, source, scope, items, evidence);
    }
    public static int claimGrave(ServerPlayer player, UUID source) throws IOException {
        return RecoveryBridgeV1.service(player).claimGrave(player, source);
    }
}
