package dev.yu.mwi.compat;

import java.util.Objects;

/** Typed fatal decode failure; callers must never route these through compatibility quarantine. */
public final class ItemPayloadException extends IllegalArgumentException {
    private final ItemCompatibilityReason reason;

    public ItemPayloadException(ItemCompatibilityReason reason, String message) {
        super(message);
        this.reason = requireFatal(reason);
    }

    public ItemPayloadException(ItemCompatibilityReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = requireFatal(reason);
    }

    public ItemCompatibilityReason reason() {
        return reason;
    }

    private static ItemCompatibilityReason requireFatal(ItemCompatibilityReason reason) {
        return Objects.requireNonNull(reason, "reason");
    }
}
