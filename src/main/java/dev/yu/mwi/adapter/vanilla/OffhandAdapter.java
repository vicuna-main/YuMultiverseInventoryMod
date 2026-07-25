package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Captures the vanilla off-hand slot. */
public final class OffhandAdapter extends AbstractItemListAdapter {
    public OffhandAdapter() {
        super(AdapterIds.OFF_HAND);
    }

    @Override
    protected List<ItemStack> slots(ServerPlayer player) {
        return player.getInventory().offhand;
    }

    @Override
    protected void setChanged(ServerPlayer player) {
        player.getInventory().setChanged();
    }

    @Override
    public boolean requiresAdvancementListenerSuspension() {
        // detectEquipmentUpdates() posts equipment-change events for hand changes.
        return true;
    }
}
