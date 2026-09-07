package dev.yu.mwi.compat;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;

public record ItemPayloadPreparation(CompoundTag payload, ItemCompatibilityReport report) {
    public ItemPayloadPreparation {
        payload = Objects.requireNonNull(payload, "payload").copy();
        report = Objects.requireNonNull(report, "report");
    }

    @Override
    public CompoundTag payload() {
        return payload.copy();
    }
}
