package dev.yu.mwi.domain;

import java.util.Objects;
import java.util.UUID;

/** Complete logical key for one player's one-share profile. */
public record ProfileKey(UUID playerId, ScopeKey scope, GameModeBucket gameMode, String shareId) {
    public ProfileKey {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(gameMode, "gameMode");
        shareId = DomainValidation.requireShareId(shareId, "shareId");
    }
}
