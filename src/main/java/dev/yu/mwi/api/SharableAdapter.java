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

    CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup);

    /**
     * Compares two snapshots using this share's data semantics. Most NBT is ordered state and can
     * use normal equality; adapters backed by unordered collections may override this method.
     */
    default boolean equivalent(CompoundTag first, CompoundTag second) {
        return java.util.Objects.equals(first, second);
    }

    void clear(ServerPlayer player);

    void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup);

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
