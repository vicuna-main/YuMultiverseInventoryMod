package dev.yu.mwi.storage;

import net.minecraft.nbt.CompoundTag;
import java.util.Objects;
import java.util.UUID;

/** A compare-and-set update of a permanent recovery source, in the profile WAL. */
public record RecoveryMutation(UUID sourceId, long expectedVersion, UUID operationId, CompoundTag payload) {
    public RecoveryMutation {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(operationId, "operationId");
        if (expectedVersion < -1) throw new IllegalArgumentException("Invalid recovery version");
        payload = Objects.requireNonNull(payload, "payload").copy();
    }

    @Override public CompoundTag payload() { return payload.copy(); }
}
