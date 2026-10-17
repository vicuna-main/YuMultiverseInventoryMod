package dev.yu.mwi.platform;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** All restrictions apply only while the distinct recovery session owns the player. */
public final class RecoveryProtectionEvents {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void damage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && RecoveryLoginFence.held(player)) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void death(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && RecoveryLoginFence.held(player)) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void travel(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && RecoveryLoginFence.teleportBlocked(player)) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void pickup(ItemEntityPickupEvent.Pre event) {
        if (event.getPlayer() instanceof ServerPlayer player && RecoveryLoginFence.held(player))
            event.setCanPickup(net.neoforged.neoforge.common.util.TriState.FALSE);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void toss(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && RecoveryLoginFence.held(player)) event.setCanceled(true);
    }
    private void interaction(PlayerInteractEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && RecoveryLoginFence.externalTransferBlocked(player)
                && event instanceof net.neoforged.bus.api.ICancellableEvent cancellable) cancellable.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void rightClickBlock(PlayerInteractEvent.RightClickBlock event) { interaction(event); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void rightClickItem(PlayerInteractEvent.RightClickItem event) { interaction(event); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void entityInteract(PlayerInteractEvent.EntityInteract event) { interaction(event); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void entityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) { interaction(event); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void leftClickBlock(PlayerInteractEvent.LeftClickBlock event) { interaction(event); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void command(CommandEvent event) {
        ServerPlayer player = event.getParseResults().getContext().getSource().getPlayer();
        if (player == null || !RecoveryLoginFence.externalTransferBlocked(player)) return;
        if (RecoveryLoginFence.commandAllowed(event.getParseResults().getReader().getString())) return;
        event.setCanceled(true);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("背包正在恢复，暂时无法执行此命令。"));
    }
}
