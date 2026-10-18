package dev.yu.mwi.storage;

import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** A backend success cannot reset this independently persisted application budget. */
public final class ActivationAttempt {
    public static final int MAX_ATTEMPTS = 3;
    private ActivationAttempt() {}

    public static CompoundTag prepare(CompoundTag previous, String signature, boolean manual) {
        CompoundTag task = previous.copy();
        if (!signature.equals(task.getString("input_digest"))) {
            task = new CompoundTag();
            task.putUUID("operation_id", UUID.randomUUID());
            task.putString("input_digest", signature);
        }
        if (task.getInt("attempts") >= MAX_ATTEMPTS) {
            task.putString("state", "ACTION_REQUIRED");
            return task;
        }
        task.putInt("attempts", task.getInt("attempts") + 1);
        task.putString("state", "APPLY");
        task.putString("rule", dev.yu.mwi.adapter.vanilla.ItemStackSemantics.RULE);
        return task;
    }

    public static CompoundTag failed(CompoundTag current, String detail) {
        CompoundTag result = current.copy();
        result.putString("state", result.getInt("attempts") >= MAX_ATTEMPTS ? "ACTION_REQUIRED" : "BACKOFF");
        result.putString("last_error", detail.substring(0, Math.min(detail.length(), 512)));
        return result;
    }
}
