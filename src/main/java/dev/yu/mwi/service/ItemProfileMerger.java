package dev.yu.mwi.service;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministically merges distinct item-container profile candidates without mutating sources. */
final class ItemProfileMerger {
    private static final Set<String> SUPPORTED_SHARES = Set.of(
            "inventory_contents", "armor_contents", "off_hand", "ender_chest");

    private ItemProfileMerger() {
    }

    static CompoundTag merge(
            String shareId,
            List<ProfileCandidate> candidates,
            HolderLookup.Provider lookup) throws TransitionException {
        if (!SUPPORTED_SHARES.contains(shareId)) {
            throw new TransitionException("merge_if_possible is not defined for share " + shareId);
        }
        int size = -1;
        List<ItemStack> merged = null;
        Set<String> includedChecksums = new HashSet<>();
        for (ProfileCandidate candidate : candidates) {
            if (!includedChecksums.add(candidate.profile().checksum())) {
                continue;
            }
            CompoundTag payload = candidate.profile().payload();
            int candidateSize = payload.getInt("size");
            if (candidateSize < 1 || (size >= 0 && candidateSize != size)) {
                throw new TransitionException("Cannot merge profiles with incompatible container sizes");
            }
            if (size < 0) {
                size = candidateSize;
                merged = new ArrayList<>(size);
                for (int index = 0; index < size; index++) {
                    merged.add(ItemStack.EMPTY);
                }
            }
            mergeItems(merged, payload.getList("items", Tag.TAG_COMPOUND), lookup, shareId);
        }
        if (merged == null) {
            throw new TransitionException("No profiles are available to merge");
        }
        return encode(size, merged, lookup);
    }

    private static void mergeItems(
            List<ItemStack> merged,
            ListTag items,
            HolderLookup.Provider lookup,
            String shareId) throws TransitionException {
        for (int index = 0; index < items.size(); index++) {
            CompoundTag item = items.getCompound(index);
            ItemStack stack = ItemStack.parse(lookup, item.get("stack"))
                    .orElseThrow(() -> new TransitionException("Cannot decode an item while merging profiles"));
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = stack.copy();
            mergeExistingStacks(merged, remaining);
            int preferredSlot = item.getInt("slot");
            if (!remaining.isEmpty() && preferredSlot >= 0 && preferredSlot < merged.size()
                    && merged.get(preferredSlot).isEmpty()) {
                merged.set(preferredSlot, remaining.copyAndClear());
            }
            fillEmptySlots(merged, remaining);
            if (!remaining.isEmpty()) {
                throw new TransitionException(
                        "Merged " + shareId + " does not fit; all sources were left unchanged");
            }
        }
    }

    private static void mergeExistingStacks(List<ItemStack> merged, ItemStack remaining) {
        for (ItemStack existing : merged) {
            if (!remaining.isEmpty() && !existing.isEmpty()
                    && ItemStack.isSameItemSameComponents(existing, remaining)) {
                int moved = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
                if (moved > 0) {
                    existing.grow(moved);
                    remaining.shrink(moved);
                }
            }
        }
    }

    private static void fillEmptySlots(List<ItemStack> merged, ItemStack remaining) {
        for (int slot = 0; slot < merged.size() && !remaining.isEmpty(); slot++) {
            if (merged.get(slot).isEmpty()) {
                int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                merged.set(slot, remaining.copyWithCount(moved));
                remaining.shrink(moved);
            }
        }
    }

    private static CompoundTag encode(int size, List<ItemStack> merged, HolderLookup.Provider lookup) {
        CompoundTag result = new CompoundTag();
        result.putInt("size", size);
        ListTag items = new ListTag();
        for (int slot = 0; slot < merged.size(); slot++) {
            ItemStack stack = merged.get(slot);
            if (!stack.isEmpty()) {
                CompoundTag item = new CompoundTag();
                item.putInt("slot", slot);
                item.put("stack", stack.save(lookup));
                items.add(item);
            }
        }
        result.put("items", items);
        return result;
    }
}
