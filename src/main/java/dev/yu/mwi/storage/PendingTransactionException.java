package dev.yu.mwi.storage;

import java.io.IOException;
import java.nio.file.Path;

/** Indicates that a durable transaction log exists and startup recovery must finish the commit. */
public final class PendingTransactionException extends IOException {
    private final Path transactionFile;

    PendingTransactionException(Path transactionFile, Throwable cause) {
        super("Profile transaction is durable but could not be fully applied: " + transactionFile, cause);
        this.transactionFile = transactionFile;
    }

    public Path transactionFile() {
        return transactionFile;
    }
}
