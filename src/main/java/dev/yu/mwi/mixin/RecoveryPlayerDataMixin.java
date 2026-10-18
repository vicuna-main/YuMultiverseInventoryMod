package dev.yu.mwi.mixin;

import dev.yu.mwi.platform.RecoveryLoginFence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerDataStorage.class)
abstract class RecoveryPlayerDataMixin {
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void mwi$excludeRecoveryDisplay(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer && RecoveryLoginFence.saveBlocked(serverPlayer)) ci.cancel();
    }
}
