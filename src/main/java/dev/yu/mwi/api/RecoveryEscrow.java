package dev.yu.mwi.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** Complete adapter-owned extraction including every dependent equipment and cosmetic slot. */
public record RecoveryEscrow(ListTag items, CompoundTag original) {
    public RecoveryEscrow { items = items.copy(); original = original.copy(); }
    @Override public ListTag items() { return items.copy(); }
    @Override public CompoundTag original() { return original.copy(); }
}
