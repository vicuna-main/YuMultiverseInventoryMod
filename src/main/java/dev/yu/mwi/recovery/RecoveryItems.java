package dev.yu.mwi.recovery;

import dev.yu.mwi.storage.NbtChecksums;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import java.io.IOException;
import java.util.*;

/** Full-component escrow. Decode must round-trip without losing even an unknown component. */
public final class RecoveryItems {
    private RecoveryItems() {}
    public record Plan(List<ItemStack> inventory, List<ItemStack> remaining, int moved) {}

    public static List<ItemStack> decode(Tag input, HolderLookup.Provider lookup) throws IOException {
        if (!(input instanceof ListTag list) || list.size() > 4096
                || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
            throw new IOException("恢复物品列表损坏或超过限制，原件已保留");
        }
        List<ItemStack> result = new ArrayList<>();
        for (Tag value : list) {
            CompoundTag raw = (CompoundTag) value;
            ItemStack stack = ItemStack.parse(lookup, raw).orElseThrow(() -> new IOException("物品或组件当前无法解析"));
            if (stack.isEmpty() || stack.getCount() < 1 || stack.getCount() > stack.getMaxStackSize()
                    || !NbtChecksums.canonicalSha256(raw).equals(NbtChecksums.canonicalSha256((CompoundTag) stack.save(lookup))))
                throw new IOException("物品组件无法无损往返，已暂停本条恢复");
            result.add(stack);
        }
        return List.copyOf(result);
    }

    public static ListTag encode(List<ItemStack> stacks, HolderLookup.Provider lookup) {
        ListTag list = new ListTag();
        for (ItemStack stack : stacks) if (!stack.isEmpty()) list.add(stack.save(lookup));
        return list;
    }

    /** Never replaces occupied slots, changes equipment or drops overflow into the world. */
    public static Plan plan(List<ItemStack> current, List<ItemStack> incoming) {
        if (current.size() != 36) throw new IllegalArgumentException("Expected 36 normal inventory slots");
        List<ItemStack> next = current.stream().map(ItemStack::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<ItemStack> remaining = new ArrayList<>();
        int moved = 0;
        for (ItemStack stack : incoming) {
            ItemStack left = stack.copy();
            for (int pass = 0; pass < 2 && !left.isEmpty(); pass++) {
                for (int slot = 0; slot < 36 && !left.isEmpty(); slot++) {
                    ItemStack at = next.get(slot);
                    if (pass == 0 && (at.isEmpty() || !ItemStack.isSameItemSameComponents(at, left))) continue;
                    if (pass == 1 && !at.isEmpty()) continue;
                    int count = Math.min(left.getCount(), Math.max(0, Math.min(64, left.getMaxStackSize()) - at.getCount()));
                    if (count == 0) continue;
                    next.set(slot, left.copyWithCount(at.getCount() + count));
                    left.shrink(count); moved = Math.addExact(moved, count);
                }
            }
            if (!left.isEmpty()) remaining.add(left);
        }
        return new Plan(List.copyOf(next), List.copyOf(remaining), moved);
    }

    public static void verifyBalance(List<ItemStack> before, List<ItemStack> incoming, Plan plan,
                                     HolderLookup.Provider lookup) throws IOException {
        Map<String, Long> counts = new HashMap<>();
        count(counts, before, lookup, 1); count(counts, incoming, lookup, 1);
        count(counts, plan.inventory(), lookup, -1); count(counts, plan.remaining(), lookup, -1);
        if (counts.values().stream().anyMatch(value -> value != 0)) throw new IOException("恢复前后物品守恒校验失败");
    }

    private static void count(Map<String, Long> counts, List<ItemStack> stacks, HolderLookup.Provider lookup, int sign)
            throws IOException {
        for (ItemStack stack : stacks) if (!stack.isEmpty()) {
            String key = NbtChecksums.canonicalSha256((CompoundTag) stack.copyWithCount(1).save(lookup));
            counts.merge(key, (long) sign * stack.getCount(), Math::addExact);
        }
    }
}
