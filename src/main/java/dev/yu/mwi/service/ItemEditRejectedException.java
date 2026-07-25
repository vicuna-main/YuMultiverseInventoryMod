package dev.yu.mwi.service;

/** A safe, user-correctable inventory editor rejection that does not invalidate the session. */
public final class ItemEditRejectedException extends Exception {
    public ItemEditRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
