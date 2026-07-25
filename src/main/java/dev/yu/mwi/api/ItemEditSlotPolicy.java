package dev.yu.mwi.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Optional adapter policy used to reject invalid operator edits before live state is mutated. */
public interface ItemEditSlotPolicy {
    void validateItemEditSlot(ServerPlayer player, String slotKey, ItemStack replacement);
}
