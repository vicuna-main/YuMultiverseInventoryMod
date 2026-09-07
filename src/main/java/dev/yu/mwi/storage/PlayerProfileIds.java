package dev.yu.mwi.storage;

import java.util.List;
import java.util.UUID;

public record PlayerProfileIds(List<UUID> playerIds) {
    public PlayerProfileIds {
        playerIds = List.copyOf(playerIds);
    }
}
