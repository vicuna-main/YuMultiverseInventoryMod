package dev.yu.mwi.domain;

import java.util.Objects;

/** Identifies the profile scope selected for one share. */
public record ScopeKey(Type type, String id) {
    public static final String GLOBAL_ID = "global";

    public enum Type {
        WORLD,
        GROUP,
        GLOBAL
    }

    public ScopeKey {
        Objects.requireNonNull(type, "type");
        id = DomainValidation.requireText(id, "scope id");
        if (type == Type.GLOBAL && !GLOBAL_ID.equals(id)) {
            throw new IllegalArgumentException("global scope id must be '" + GLOBAL_ID + "'");
        }
        if (type == Type.GROUP) {
            id = DomainValidation.requireGroupId(id, "group scope id");
        }
    }

    public static ScopeKey world(WorldIdentity world) {
        return new ScopeKey(Type.WORLD, Objects.requireNonNull(world, "world").name());
    }

    public static ScopeKey group(String groupName) {
        return new ScopeKey(Type.GROUP, groupName);
    }

    public static ScopeKey global() {
        return new ScopeKey(Type.GLOBAL, GLOBAL_ID);
    }
}
