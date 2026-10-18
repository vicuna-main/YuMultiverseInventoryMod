package dev.yu.mwi.api;

import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Owns the live-state lifecycle for one independently sharable player data set.
 * Implementations are invoked on the server thread in capture, clear, apply, sync order.
 */
public interface SharableAdapter {
    ResourceLocation id();

    default Set<ResourceLocation> aliases() {
        return Set.of();
    }

    default boolean available(ServerPlayer player) {
        return true;
    }

    /**
     * Captures a persistable snapshot. Implementations with semantic payload validation must apply
     * the same contract here and in {@link #apply}; MWI must never successfully capture/persist a
     * payload that the same adapter would reject when restoring under the same registry/runtime
     * conditions.
     */
    CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup);

    /**
     * Compares two snapshots using this share's data semantics. Most NBT is ordered state and can
     * use normal equality; adapters backed by unordered collections may override this method.
     */
    default boolean equivalent(CompoundTag first, CompoundTag second) {
        return java.util.Objects.equals(first, second);
    }

    default RestoreVerification verifyRestored(ServerPlayer player, CompoundTag expected,
            CompoundTag actual, HolderLookup.Provider registries) {
        boolean equal = equivalent(expected, actual);
        return new RestoreVerification(equal ? RestoreVerification.Status.EXACT : RestoreVerification.Status.DIFFERENT,
                "strict-nbt-v1", equal ? "" : NbtDifference.first(expected, actual), equal ? actual : null);
    }

    default java.util.Optional<RecoveryEscrow> prepareRecoveryEscrow(ServerPlayer player, CompoundTag expected,
            HolderLookup.Provider registries) { return java.util.Optional.empty(); }

    void clear(ServerPlayer player);

    void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup);

    /** Reconcile scalar dependencies after equipment and effects have all been installed. */
    default void dependenciesApplied(ServerPlayer player) {}

    /** Reject unresolved live side channels before a recovery can establish canonical ownership. */
    default void validateRestoreEnvironment(ServerPlayer player) {}

    void sync(ServerPlayer player);

    /**
     * Whether replacing this share must temporarily remove advancement criterion listeners.
     *
     * <p>The conservative default protects third-party adapters whose mutation and synchronization
     * behavior is unknown. An adapter may return {@code false} only when its complete
     * clear/apply/sync lifecycle has been audited to mutate state directly without invoking a
     * criterion trigger or an event-capable gameplay path.</p>
     */
    default boolean requiresAdvancementListenerSuspension() {
        return true;
    }

    /**
     * Synchronizes after the coordinator has already removed advancement listeners. The default
     * preserves third-party adapter behavior; the advancement adapter overrides this to avoid a
     * redundant second removal before rebuilding its listeners.
     */
    default void syncAfterAdvancementListenerSuspension(ServerPlayer player) {
        sync(player);
    }

    /** Called at the normal player post-tick boundary for adapters with deferred framework updates. */
    default void serverTick(ServerPlayer player) {
    }

    default void sessionClosed(ServerPlayer player) {
    }
}
