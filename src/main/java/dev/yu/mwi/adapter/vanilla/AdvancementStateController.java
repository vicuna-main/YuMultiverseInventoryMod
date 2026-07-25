package dev.yu.mwi.adapter.vanilla;

import net.minecraft.advancements.AdvancementNode;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;

/**
 * Suspends advancement criteria while a stored profile is installed and rebuilds vanilla's
 * listeners without awarding, revoking, announcing, or replaying completion rewards.
 */
public final class AdvancementStateController {
    private AdvancementStateController() {
    }

    public static void suspend(ServerPlayer player) {
        player.getAdvancements().stopListening();
    }

    public static void resumeListeners(ServerPlayer player) {
        PlayerAdvancements advancements = player.getAdvancements();
        ServerAdvancementManager manager = player.server.getAdvancements();
        advancements.stopListening();
        registerListeners(advancements, manager);
    }

    /** Rebuilds listeners when the caller has already removed all of them. */
    public static void resumeListenersAfterSuspend(ServerPlayer player) {
        registerListeners(player.getAdvancements(), player.server.getAdvancements());
    }

    /**
     * Rebuilds listeners and sends the advancement tree as a reset snapshot. The reset bit is
     * essential: an incremental completion packet makes the client display old completions as new
     * advancement toasts after every isolated-world transition.
     */
    public static void resumeAndSynchronizeFullState(ServerPlayer player) {
        resumeListeners(player);
        synchronizeFullState(player);
    }

    /** Rebuilds and synchronizes after the caller has already removed all listeners. */
    public static void resumeAndSynchronizeFullStateAfterSuspend(ServerPlayer player) {
        resumeListenersAfterSuspend(player);
        synchronizeFullState(player);
    }

    private static void registerListeners(
            PlayerAdvancements advancements, ServerAdvancementManager manager) {
        // Use vanilla's own listener builder. Reimplementing it here would be fragile around
        // data-pack criteria, modded trigger types, and future changes to listener registration.
        advancements.registerListeners(manager);
    }

    private static void synchronizeFullState(ServerPlayer player) {
        PlayerAdvancements advancements = player.getAdvancements();
        ServerAdvancementManager manager = player.server.getAdvancements();

        advancements.visible.clear();
        advancements.progressChanged.clear();
        advancements.rootsToUpdate.clear();
        advancements.progressChanged.addAll(manager.getAllAdvancements());
        for (AdvancementNode root : advancements.tree.roots()) {
            advancements.rootsToUpdate.add(root);
        }
        advancements.isFirstPacket = true;
        advancements.flushDirty(player);
    }
}
