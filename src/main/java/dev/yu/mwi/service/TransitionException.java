package dev.yu.mwi.service;

public final class TransitionException extends Exception {
    public TransitionException(String message) {
        super(message);
    }

    public TransitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
