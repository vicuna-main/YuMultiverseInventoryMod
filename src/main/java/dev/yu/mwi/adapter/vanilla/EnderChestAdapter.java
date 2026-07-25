package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Captures all vanilla ender chest slots. */
public final class EnderChestAdapter extends AbstractItemListAdapter {
    public EnderChestAdapter() {
        super(AdapterIds.ENDER_CHEST);
    }

    @Override
    protected List<ItemStack> slots(ServerPlayer player) {
        return player.getEnderChestInventory().getItems();
    }

    @Override
    protected void setChanged(ServerPlayer player) {
        player.getEnderChestInventory().setChanged();
    }

    @Override
    public boolean requiresAdvancementListenerSuspension() {
        // SimpleContainer#setChanged invokes arbitrary registered container listeners.
        return true;
    }
}
