package dev.yu.mwi.adapter.vanilla;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

public final class ItemListNbtCodec {
    private static final String SIZE = "size";
    private static final String ITEMS = "items";
    private static final String SLOT = "slot";
    private static final String STACK = "stack";

    private ItemListNbtCodec() {
    }

    public static CompoundTag encode(List<ItemStack> slots, HolderLookup.Provider lookup) {
        CompoundTag data = new CompoundTag();
        data.putInt(SIZE, slots.size());
        ListTag items = new ListTag();
        for (int slot = 0; slot < slots.size(); slot++) {
            ItemStack stack = slots.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Tag encoded = stack.save(lookup);
            if (!(encoded instanceof CompoundTag stackTag)) {
                throw new IllegalStateException("ItemStack codec produced non-compound NBT for slot " + slot);
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt(SLOT, slot);
            entry.put(STACK, stackTag);
            items.add(entry);
        }
        data.put(ITEMS, items);
        return data;
    }

    public static List<ItemStack> decode(
            CompoundTag data, int expectedSize, HolderLookup.Provider lookup, String adapterId) {
        NbtPayload.requireData(data, adapterId);
        int size = NbtPayload.requireInt(data, SIZE, adapterId);
        if (size != expectedSize) {
            throw NbtPayload.invalid(adapterId,
                    "slot count " + size + " does not match live slot count " + expectedSize);
        }

        ListTag items = NbtPayload.requireCompoundList(data, ITEMS, adapterId);
        List<ItemStack> decoded = new ArrayList<>(Collections.nCopies(expectedSize, ItemStack.EMPTY));
        boolean[] occupied = new boolean[expectedSize];
        for (int index = 0; index < items.size(); index++) {
            CompoundTag entry = items.getCompound(index);
            int slot = NbtPayload.requireInt(entry, SLOT, adapterId);
            if (slot < 0 || slot >= expectedSize) {
                throw NbtPayload.invalid(adapterId, "slot " + slot + " is out of range");
            }
            if (occupied[slot]) {
                throw NbtPayload.invalid(adapterId, "duplicate slot " + slot);
            }
            occupied[slot] = true;
            if (!(entry.get(STACK) instanceof CompoundTag stackTag) || stackTag.isEmpty()) {
                throw NbtPayload.invalid(adapterId, "slot " + slot + " has no encoded stack");
            }
            ItemStack stack = ItemStack.parse(lookup, stackTag)
                    .orElseThrow(() -> NbtPayload.invalid(adapterId, "slot " + slot + " contains an invalid item"));
            if (stack.isEmpty()) {
                throw NbtPayload.invalid(adapterId, "slot " + slot + " decoded to an empty item");
            }
            decoded.set(slot, stack);
        }
        return decoded;
    }
}
