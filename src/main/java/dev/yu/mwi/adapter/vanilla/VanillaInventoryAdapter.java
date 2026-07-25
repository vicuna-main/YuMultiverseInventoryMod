package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Captures the 36 main inventory and hotbar slots. */
public final class VanillaInventoryAdapter extends AbstractItemListAdapter {
    public VanillaInventoryAdapter() {
        super(AdapterIds.INVENTORY_CONTENTS);
    }

    @Override
    protected List<ItemStack> slots(ServerPlayer player) {
        return player.getInventory().items;
    }

    @Override
    protected void setChanged(ServerPlayer player) {
        player.getInventory().setChanged();
    }

    @Override
    public boolean requiresAdvancementListenerSuspension() {
        // detectEquipmentUpdates() posts equipment-change events for hotbar hand changes.
        return true;
    }
}
