package dev.yu.mwi.mixin;

import dev.yu.mwi.platform.RecoveryLoginFence;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
abstract class RecoveryPlayerListMixin {
    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void mwi$beforeHybridJoin(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        RecoveryLoginFence.prepare(player);
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void mwi$retainOriginalPlayerInput(ServerPlayer player,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<java.util.Optional<net.minecraft.nbt.CompoundTag>> cir) {
        cir.getReturnValue().ifPresent(input -> RecoveryLoginFence.loaded(player, input));
    }

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void mwi$excludeRecoveryDisplay(ServerPlayer player, CallbackInfo ci) {
        if (RecoveryLoginFence.saveBlocked(player)) ci.cancel();
    }
}
