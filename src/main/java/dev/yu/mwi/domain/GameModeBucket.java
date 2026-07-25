package dev.yu.mwi.domain;

import java.util.Locale;

/** Game mode partition used in profile keys. */
public enum GameModeBucket {
    SHARED,
    SURVIVAL,
    CREATIVE,
    ADVENTURE,
    SPECTATOR;

    public static GameModeBucket resolve(String gameMode, boolean separateGameModeProfiles) {
        if (!separateGameModeProfiles) {
            return SHARED;
        }
        return fromName(gameMode);
    }

    public static GameModeBucket fromName(String gameMode) {
        String normalized = DomainValidation.requireText(gameMode, "gameMode").toUpperCase(Locale.ROOT);
        if (SHARED.name().equals(normalized)) {
            throw new IllegalArgumentException("shared is not a concrete game mode");
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown game mode: " + gameMode, exception);
        }
    }
}
