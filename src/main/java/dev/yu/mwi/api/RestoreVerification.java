package dev.yu.mwi.api;

import net.minecraft.nbt.CompoundTag;

/** Application verification is independent of the immutable on-disk checksum contract. */
public record RestoreVerification(Status status, String rule, String detail, CompoundTag after) {
    public enum Status { EXACT, SEMANTIC_EQUIVALENT, DIFFERENT, UNSUPPORTED }

    public RestoreVerification {
        java.util.Objects.requireNonNull(status);
        java.util.Objects.requireNonNull(rule);
        detail = detail == null ? "" : detail.substring(0, Math.min(detail.length(), 512));
        after = after == null ? null : after.copy();
    }

    @Override public CompoundTag after() { return after == null ? null : after.copy(); }
    public boolean accepted() { return status == Status.EXACT || status == Status.SEMANTIC_EQUIVALENT; }
    public static RestoreVerification strict(boolean equal, CompoundTag actual) {
        return new RestoreVerification(equal ? Status.EXACT : Status.DIFFERENT, "strict-nbt-v1",
                equal ? "" : "payload differs", equal ? actual : null);
    }
}
