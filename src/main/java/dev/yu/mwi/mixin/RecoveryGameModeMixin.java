package dev.yu.mwi.mixin;

import dev.yu.mwi.platform.RecoveryLoginFence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
abstract class RecoveryGameModeMixin {
    @Shadow @Final protected ServerPlayer player;

    @Inject(method = "changeGameModeForPlayer(Lnet/minecraft/world/level/GameType;)Z", at = @At("HEAD"), cancellable = true)
    private void mwi$blockGameMode(CallbackInfoReturnable<Boolean> cir) {
        if (RecoveryLoginFence.teleportBlocked(player)) cir.setReturnValue(false);
    }

    @Inject(method = "setGameModeForPlayer", at = @At("HEAD"), cancellable = true)
    private void mwi$blockModeAssignment(CallbackInfo ci) {
        if (RecoveryLoginFence.teleportBlocked(player)) ci.cancel();
    }

    @Inject(method = "changeGameModeForPlayer(Lnet/minecraft/world/level/GameType;Lorg/bukkit/event/player/PlayerGameModeChangeEvent$Cause;Lnet/kyori/adventure/text/Component;)Lorg/bukkit/event/player/PlayerGameModeChangeEvent;",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void mwi$blockHybridGameMode(CallbackInfoReturnable<Object> cir) {
        if (RecoveryLoginFence.teleportBlocked(player)) cir.setReturnValue(null);
    }
}
