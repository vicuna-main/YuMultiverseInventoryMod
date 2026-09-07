package dev.yu.mwi.compat;

import java.util.List;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** One recoverable stack isolated from a stored profile. */
public record ItemCompatibilityIssue(
        String logicalSlot,
        ItemCompatibilityReason reason,
        ResourceLocation itemId,
        List<ResourceLocation> componentIds,
        CompoundTag originalStack) {
    public ItemCompatibilityIssue {
        logicalSlot = Objects.requireNonNull(logicalSlot, "logicalSlot");
        reason = Objects.requireNonNull(reason, "reason");
        componentIds = List.copyOf(Objects.requireNonNull(componentIds, "componentIds"));
        originalStack = Objects.requireNonNull(originalStack, "originalStack").copy();
    }

    @Override
    public CompoundTag originalStack() {
        return originalStack.copy();
    }
}
