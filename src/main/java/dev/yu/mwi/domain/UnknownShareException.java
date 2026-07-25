package dev.yu.mwi.domain;

/** Raised when a group or alias refers to an unavailable share. */
public final class UnknownShareException extends IllegalArgumentException {
    private final String shareId;

    public UnknownShareException(String shareId) {
        super("unknown share or alias: " + shareId);
        this.shareId = shareId;
    }

    public String shareId() {
        return shareId;
    }
}
