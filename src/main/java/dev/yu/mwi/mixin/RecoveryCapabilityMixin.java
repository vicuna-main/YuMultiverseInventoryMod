package dev.yu.mwi.mixin;

import dev.yu.mwi.platform.RecoveryLoginFence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.capabilities.EntityCapability;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Blocks automation/mod capability transfers as well as vanilla containers. */
@Mixin(value = EntityCapability.class, remap = false)
abstract class RecoveryCapabilityMixin {
    @Inject(method = "getCapability", at = @At("HEAD"), cancellable = true)
    private void mwi$fenceCapabilities(Entity entity, Object context, CallbackInfoReturnable<Object> cir) {
        if (entity instanceof ServerPlayer player && RecoveryLoginFence.teleportBlocked(player)) cir.setReturnValue(null);
    }
}
