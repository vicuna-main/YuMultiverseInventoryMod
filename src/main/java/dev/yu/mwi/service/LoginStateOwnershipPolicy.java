package dev.yu.mwi.service;

/**
 * Defines when the vanilla state present on {@code ServerPlayer} during login may become an MWI
 * canonical profile.
 *
 * <p>Login live state has unknown scope provenance: hybrid login handlers can move the player before
 * MWI activates, while vanilla playerdata can still contain state owned by the scope from the
 * previous session. Therefore an unknown live snapshot is only admissible as a bootstrap source for
 * a share that has never had any MWI-owned profile. Once MWI has history for the share, canonical
 * profiles, world-profile migration, or an explicit reset must decide the target state.
 */
final class LoginStateOwnershipPolicy {
    private LoginStateOwnershipPolicy() {
    }

    static boolean mayBootstrapUnknownLive(boolean hasAnyCanonicalProfileForShare) {
        return !hasAnyCanonicalProfileForShare;
    }
}
