package dev.yu.mwi.mixin;

import dev.yu.mwi.platform.RecoveryLoginFence;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Also covers server/plugin initiated movement and game-mode changes during recovery display. */
@Mixin(ServerPlayer.class)
abstract class RecoveryPlayerActionsMixin {
    @Inject(method = {"teleportTo(DDD)V", "teleportRelative(DDD)V",
            "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V"}, at = @At("HEAD"), cancellable = true)
    private void mwi$blockTeleport(CallbackInfo ci) {
        if (RecoveryLoginFence.teleportBlocked((ServerPlayer) (Object) this)) ci.cancel();
    }

    @Inject(method = {"teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",
            "setGameMode(Lnet/minecraft/world/level/GameType;)Z"}, at = @At("HEAD"), cancellable = true)
    private void mwi$blockExternalStateChange(CallbackInfoReturnable<Boolean> cir) {
        if (RecoveryLoginFence.teleportBlocked((ServerPlayer) (Object) this)) cir.setReturnValue(false);
    }

    // Paper/Youer invoke these overloads directly; no Bukkit classes are linked on plain NeoForge.
    @Inject(method = "setGameMode(Lnet/minecraft/world/level/GameType;Lorg/bukkit/event/player/PlayerGameModeChangeEvent$Cause;Lnet/kyori/adventure/text/Component;)Lorg/bukkit/event/player/PlayerGameModeChangeEvent;",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void mwi$blockHybridGameMode(CallbackInfoReturnable<Object> cir) {
        if (RecoveryLoginFence.teleportBlocked((ServerPlayer) (Object) this)) cir.setReturnValue(null);
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFLorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Z",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void mwi$blockHybridTeleport(CallbackInfoReturnable<Boolean> cir) {
        if (RecoveryLoginFence.teleportBlocked((ServerPlayer) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "teleport(Lorg/bukkit/Location;)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void mwi$blockBukkitLocation(CallbackInfo ci) {
        if (RecoveryLoginFence.teleportBlocked((ServerPlayer) (Object) this)) ci.cancel();
    }
}
