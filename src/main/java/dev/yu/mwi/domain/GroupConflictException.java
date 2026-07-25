package dev.yu.mwi.domain;

import java.util.List;

/** Raised when the same world and leaf share resolve to more than one group. */
public final class GroupConflictException extends IllegalStateException {
    private final WorldIdentity world;
    private final String shareId;
    private final List<String> groupNames;

    public GroupConflictException(WorldIdentity world, String shareId, List<String> groupNames) {
        super("world '" + world.name() + "' and share '" + shareId + "' match multiple groups: "
                + String.join(", ", groupNames));
        this.world = world;
        this.shareId = shareId;
        this.groupNames = List.copyOf(groupNames);
    }

    public WorldIdentity world() {
        return world;
    }

    public String shareId() {
        return shareId;
    }

    public List<String> groupNames() {
        return groupNames;
    }
}
