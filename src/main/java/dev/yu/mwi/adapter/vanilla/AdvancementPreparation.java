package dev.yu.mwi.adapter.vanilla;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;

/** Fully validated advancement state plus its compatibility audit result. */
public record AdvancementPreparation(
        CompoundTag payload,
        AdvancementCompatibilityReport report
) {
    public AdvancementPreparation {
        payload = Objects.requireNonNull(payload, "payload").copy();
        Objects.requireNonNull(report, "report");
    }

    @Override
    public CompoundTag payload() {
        return payload.copy();
    }
}
