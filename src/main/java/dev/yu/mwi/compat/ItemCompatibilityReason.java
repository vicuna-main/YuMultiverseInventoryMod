package dev.yu.mwi.compat;

/** Stable reason codes written to compatibility archives and exposed to administrators. */
public enum ItemCompatibilityReason {
    MISSING_ITEM_REGISTRY_ENTRY,
    MISSING_DATA_COMPONENT_TYPE,
    MALFORMED_NBT,
    INVALID_STACK_SEMANTICS,
    CHECKSUM_OR_ENVELOPE_CORRUPTION
}
