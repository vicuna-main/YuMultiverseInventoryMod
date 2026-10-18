package dev.yu.mwi.mixin;

import dev.yu.mwi.platform.RecoveryLoginFence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class RecoveryPacketsMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = {"handleContainerClick", "handleContainerButtonClick", "handleSetCreativeModeSlot",
            "handlePlayerAction", "handleUseItem", "handleUseItemOn", "handleInteract", "handleSetCarriedItem",
            "handlePlayerCommand", "handleTeleportToEntityPacket", "handleMoveVehicle",
            "handlePlayerAbilities", "handlePlaceRecipe", "handlePickItem", "handleCustomPayload"},
            at = @At("HEAD"), cancellable = true)
    private void mwi$blockRecoveryTransfers(CallbackInfo ci) {
        if (RecoveryLoginFence.externalTransferBlocked(player)) ci.cancel();
    }
    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void mwi$allowRecoveryLobbyMovement(CallbackInfo ci) {
        if (RecoveryLoginFence.movementBlocked(player)) ci.cancel();
    }

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void mwi$unsignedCommand(net.minecraft.network.protocol.game.ServerboundChatCommandPacket packet, CallbackInfo ci) {
        if (RecoveryLoginFence.externalTransferBlocked(player) && !RecoveryLoginFence.commandAllowed(packet.command())) ci.cancel();
    }

    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void mwi$signedCommand(net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        if (RecoveryLoginFence.externalTransferBlocked(player) && !RecoveryLoginFence.commandAllowed(packet.command())) ci.cancel();
    }

    @Inject(method = {"teleport(DDDFF)V", "teleport(DDDFFLjava/util/Set;)V"}, at = @At("HEAD"), cancellable = true)
    private void mwi$blockConnectionTeleport(CallbackInfo ci) {
        if (RecoveryLoginFence.teleportBlocked(player)) ci.cancel();
    }

    @Inject(method = {"teleport(DDDFFLorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Z",
            "teleport(DDDFFLjava/util/Set;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Z"},
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void mwi$blockHybridConnectionTeleport(CallbackInfoReturnable<Boolean> cir) {
        if (RecoveryLoginFence.teleportBlocked(player)) cir.setReturnValue(false);
    }

    @Inject(method = {"teleport(Lorg/bukkit/Location;)V", "internalTeleport(DDDFFLjava/util/Set;)V"},
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void mwi$blockHybridInternalTeleport(CallbackInfo ci) {
        if (RecoveryLoginFence.teleportBlocked(player)) ci.cancel();
    }
}
