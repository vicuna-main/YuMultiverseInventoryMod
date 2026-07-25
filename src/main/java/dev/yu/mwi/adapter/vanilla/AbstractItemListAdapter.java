package dev.yu.mwi.adapter.vanilla;

import java.util.Collections;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

abstract class AbstractItemListAdapter extends AbstractVanillaAdapter {
    protected AbstractItemListAdapter(ResourceLocation id) {
        super(id);
    }

    protected abstract List<ItemStack> slots(ServerPlayer player);

    protected abstract void setChanged(ServerPlayer player);

    @Override
    public final CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        return ItemListNbtCodec.encode(slots(player), lookup);
    }

    @Override
    public final void clear(ServerPlayer player) {
        Collections.fill(slots(player), ItemStack.EMPTY);
        setChanged(player);
    }

    @Override
    public final void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        List<ItemStack> liveSlots = slots(player);
        List<ItemStack> decoded = ItemListNbtCodec.decode(data, liveSlots.size(), lookup, id().toString());
        for (int slot = 0; slot < decoded.size(); slot++) {
            liveSlots.set(slot, decoded.get(slot));
        }
        setChanged(player);
    }

    @Override
    public void sync(ServerPlayer player) {
        PlayerSync.inventory(player);
    }
}
