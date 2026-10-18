package dev.yu.mwi.mixin;

import dev.yu.mwi.integration.curios.CuriosRestoreGuard;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional target: no Curios classes are referenced when the mod is absent. */
@Pseudo
@Mixin(targets = "top.theillusivec4.curios.common.capability.CurioInventoryCapability", remap = false)
public abstract class CuriosRestoreMixin {
    @Shadow(remap = false) @Final private net.minecraft.world.entity.LivingEntity livingEntity;

    @Inject(method = "handleInvalidStacks", at = @At("HEAD"), cancellable = true, remap = false)
    private void mwi$holdPendingDelivery(CallbackInfo callback) {
        if (livingEntity instanceof net.minecraft.server.level.ServerPlayer player
                && dev.yu.mwi.platform.RecoveryLoginFence.held(player)) callback.cancel();
    }

    @Inject(method = "loseInvalidStack", at = @At("HEAD"), cancellable = true, remap = false)
    private void mwi$captureRestoreLoss(ItemStack stack, CallbackInfo callback) {
        if (CuriosRestoreGuard.captureRejected(this, stack)) callback.cancel();
    }
}
