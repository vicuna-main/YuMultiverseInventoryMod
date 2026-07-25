package dev.yu.mwi.storage;

import java.io.IOException;
import java.nio.file.Path;

public final class ProfileCorruptionException extends IOException {
    private final Path file;

    public ProfileCorruptionException(Path file, String message) {
        super(message + " [" + file + "]");
        this.file = file;
    }

    public ProfileCorruptionException(Path file, String message, Throwable cause) {
        super(message + " [" + file + "]", cause);
        this.file = file;
    }

    public Path file() {
        return file;
    }
}
