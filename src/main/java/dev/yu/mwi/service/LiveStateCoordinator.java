package dev.yu.mwi.service;

import dev.yu.mwi.adapter.vanilla.AdvancementStateController;
import dev.yu.mwi.api.AdapterIds;
import dev.yu.mwi.api.SharableAdapter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Applies and rolls back live player snapshots while preserving synchronization ordering. */
final class LiveStateCoordinator {
    private static final Consumer<ReplacementMetrics> DISCARD_METRICS = ignored -> {
    };
    private static final Set<ResourceLocation> EQUIPMENT_SHARES = Set.of(
            AdapterIds.INVENTORY_CONTENTS, AdapterIds.ARMOR_CONTENTS, AdapterIds.OFF_HAND);

    private LiveStateCoordinator() {
    }

    static LinkedHashMap<SharableAdapter, CompoundTag> capture(
            ServerPlayer player, Collection<SharableAdapter> adapters) {
        LinkedHashMap<SharableAdapter, CompoundTag> snapshots = new LinkedHashMap<>();
        HolderLookup.Provider lookup = player.registryAccess();
        for (SharableAdapter adapter : new LinkedHashSet<>(adapters)) {
            snapshots.put(adapter, adapter.capture(player, lookup));
        }
        return snapshots;
    }

    static void rollback(ServerPlayer player, Map<SharableAdapter, CompoundTag> snapshots) {
        LinkedHashMap<SharableAdapter, CompoundTag> restore = new LinkedHashMap<>();
        for (Map.Entry<SharableAdapter, CompoundTag> entry : snapshots.entrySet()) {
            try {
                CompoundTag current = entry.getKey().capture(player, player.registryAccess());
                if (!entry.getKey().equivalent(entry.getValue(), current)) {
                    restore.put(entry.getKey(), entry.getValue());
                }
            } catch (RuntimeException ignored) {
                restore.put(entry.getKey(), entry.getValue());
            }
        }
        if (restore.isEmpty()) {
            return;
        }

        RuntimeException[] aggregate = new RuntimeException[1];
        replace(player, restore.keySet(), () -> {
            for (Map.Entry<SharableAdapter, CompoundTag> entry : restore.entrySet()) {
                try {
                    entry.getKey().clear(player);
                    entry.getKey().apply(player, entry.getValue(), player.registryAccess());
                } catch (RuntimeException failure) {
                    if (aggregate[0] == null) {
                        aggregate[0] = new IllegalStateException("One or more shares could not be rolled back");
                    }
                    aggregate[0].addSuppressed(failure);
                }
            }
        });
        if (aggregate[0] != null) {
            throw aggregate[0];
        }
    }

    static void replace(ServerPlayer player, Collection<SharableAdapter> adapters, Runnable mutation) {
        replace(player, adapters, mutation, DISCARD_METRICS);
    }

    static void replace(
            ServerPlayer player,
            Collection<SharableAdapter> adapters,
            Runnable mutation,
            Consumer<ReplacementMetrics> metricsRecorder) {
        LinkedHashSet<SharableAdapter> unique = new LinkedHashSet<>(adapters);
        if (unique.isEmpty()) {
            return;
        }
        boolean suspendAdvancementListeners = requiresAdvancementListenerSuspension(unique);
        if (suspendAdvancementListeners) {
            AdvancementStateController.suspend(player);
        }
        boolean synchronizedState = false;
        boolean[] listenerResumeAttempted = {false};
        long synchronizationNanos = 0L;
        try {
            mutation.run();
            long synchronizationStarted = System.nanoTime();
            try {
                settleEquipmentState(unique, player);
                synchronize(
                        unique, player, suspendAdvancementListeners, listenerResumeAttempted);
                synchronizedState = true;
            } finally {
                synchronizationNanos += System.nanoTime() - synchronizationStarted;
            }
        } finally {
            try {
                if (suspendAdvancementListeners && !synchronizedState) {
                    long recoveryStarted = System.nanoTime();
                    try {
                        if (listenerResumeAttempted[0]) {
                            // Registration may have failed part-way through; clear partial state.
                            AdvancementStateController.resumeListeners(player);
                        } else {
                            // Nothing has been registered since suspend(), so another removal is wasteful.
                            AdvancementStateController.resumeListenersAfterSuspend(player);
                        }
                    } finally {
                        synchronizationNanos += System.nanoTime() - recoveryStarted;
                    }
                }
            } finally {
                metricsRecorder.accept(new ReplacementMetrics(
                        suspendAdvancementListeners, synchronizationNanos));
            }
        }
    }

    static boolean requiresAdvancementListenerSuspension(Collection<SharableAdapter> adapters) {
        return adapters.stream().anyMatch(SharableAdapter::requiresAdvancementListenerSuspension);
    }

    static List<TransitionJournal.Change> withAfter(
            ServerPlayer player,
            List<TransitionJournal.Change> changes,
            Map<String, SharableAdapter> adapters) {
        return changes.stream().map(change -> {
            SharableAdapter adapter = adapters.get(change.shareId());
            CompoundTag after = adapter == null ? null : adapter.capture(player, player.registryAccess());
            return change.withAfter(after);
        }).toList();
    }

    static List<TransitionJournal.Change> withAfter(
            List<TransitionJournal.Change> changes,
            Map<String, CompoundTag> captured) {
        return changes.stream()
                .map(change -> change.withAfter(captured.get(change.shareId())))
                .toList();
    }

    static void closeMenu(ServerPlayer player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    private static void settleEquipmentState(Collection<SharableAdapter> adapters, ServerPlayer player) {
        if (adapters.stream().map(SharableAdapter::id).anyMatch(EQUIPMENT_SHARES::contains)) {
            player.detectEquipmentUpdates();
        }
    }

    private static void synchronize(
            Collection<SharableAdapter> adapters,
            ServerPlayer player,
            boolean advancementListenersSuspended,
            boolean[] listenerResumeAttempted) {
        SharableAdapter advancementAdapter = null;
        for (SharableAdapter adapter : adapters) {
            if (adapter.id().equals(AdapterIds.ADVANCEMENTS)) {
                advancementAdapter = adapter;
            } else {
                syncAdapter(adapter, player, advancementListenersSuspended);
            }
        }
        if (advancementAdapter != null) {
            // Always last: its reset packet must describe the final state after teleports/effects.
            listenerResumeAttempted[0] = advancementListenersSuspended;
            syncAdapter(advancementAdapter, player, advancementListenersSuspended);
        } else if (advancementListenersSuspended) {
            listenerResumeAttempted[0] = true;
            AdvancementStateController.resumeListenersAfterSuspend(player);
        }
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    private static void syncAdapter(
            SharableAdapter adapter, ServerPlayer player, boolean advancementListenersSuspended) {
        if (advancementListenersSuspended) {
            adapter.syncAfterAdvancementListenerSuspension(player);
        } else {
            adapter.sync(player);
        }
    }

    record ReplacementMetrics(boolean advancementListenersRebuilt, long synchronizationNanos) {
    }
}
