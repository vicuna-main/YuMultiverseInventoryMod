package dev.yu.mwi.service;

import dev.yu.mwi.api.SharableAdapter;
import dev.yu.mwi.domain.ProfileKey;
import dev.yu.mwi.domain.WorldIdentity;
import dev.yu.mwi.storage.PendingTransactionException;
import dev.yu.mwi.storage.ProfileStore;
import dev.yu.mwi.storage.StoredProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Restores a historical before-snapshot and records the recovery as another reversible revision. */
final class RevisionRecoveryService {
    private final ProfileStore store;

    RevisionRecoveryService(ProfileStore store) {
        this.store = store;
    }

    void recover(
            ServerPlayer player,
            long requestedRevision,
            WorldIdentity currentWorld,
            Map<String, ProfileKey> activeProfiles,
            Map<String, SharableAdapter> adapters) throws RecoveryException {
        LinkedHashMap<SharableAdapter, CompoundTag> liveBefore = new LinkedHashMap<>();
        long recoveryRevision = -1L;
        CompoundTag recoveryJournal = null;
        try {
            CompoundTag sourceJournal = store.loadRevision(player.getUUID(), requestedRevision)
                    .orElseThrow(() -> new RecoveryException(
                            "Revision not found: " + requestedRevision, null, false));
            List<TransitionJournal.RecoveredChange> recovered = TransitionJournal.recoverBefore(sourceJournal);
            LinkedHashMap<ProfileKey, StoredProfile> diskBefore = validateAndCapture(
                    player, recovered, activeProfiles, adapters, liveBefore);
            LinkedHashMap<SharableAdapter, CompoundTag> liveRecovery = liveRecovery(
                    recovered, activeProfiles, adapters, liveBefore);

            recoveryRevision = store.nextRevision(player.getUUID());
            List<TransitionJournal.Change> recoveryChanges = recovered.stream().map(change -> {
                ProfileKey key = change.recoveryKey();
                return new TransitionJournal.Change(
                        change.shareId(), key, key, diskBefore.get(key).payload(), null);
            }).toList();
            recoveryJournal = TransitionJournal.prepared(
                    "recover_revision_" + requestedRevision,
                    currentWorld.name(), currentWorld.name(), recoveryChanges);
            store.writeRevision(player.getUUID(), recoveryRevision, recoveryJournal);

            LiveStateCoordinator.replace(player, liveRecovery.keySet(), () -> liveRecovery.forEach((adapter, payload) -> {
                adapter.clear(player);
                adapter.apply(player, payload, player.registryAccess());
            }));
            LinkedHashMap<ProfileKey, CompoundTag> writes = new LinkedHashMap<>();
            recovered.forEach(change -> writes.put(change.recoveryKey(), change.payload()));
            List<TransitionJournal.Change> completed =
                    LiveStateCoordinator.withAfter(player, recoveryChanges, adapters);
            TransitionJournal.complete(recoveryJournal, "committed", completed, null);
            store.commitBatch(player.getUUID(), recoveryRevision, writes, Optional.of(recoveryJournal));
        } catch (Exception failure) {
            boolean unsafe = hasPendingCommit(failure);
            if (!unsafe) {
                try {
                    LiveStateCoordinator.rollback(player, liveBefore);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    unsafe = true;
                }
                if (recoveryJournal != null && recoveryRevision > 0L) {
                    try {
                        TransitionJournal.complete(recoveryJournal, "rolled_back", List.of(), failure.toString());
                        store.writeRevision(player.getUUID(), recoveryRevision, recoveryJournal);
                    } catch (IOException journalFailure) {
                        failure.addSuppressed(journalFailure);
                    }
                }
            }
            if (failure instanceof RecoveryException recoveryFailure) {
                throw recoveryFailure;
            }
            throw new RecoveryException(
                    "Unable to recover revision " + requestedRevision, failure, unsafe);
        }
    }

    private LinkedHashMap<ProfileKey, StoredProfile> validateAndCapture(
            ServerPlayer player,
            List<TransitionJournal.RecoveredChange> recovered,
            Map<String, ProfileKey> activeProfiles,
            Map<String, SharableAdapter> adapters,
            Map<SharableAdapter, CompoundTag> liveBefore) throws IOException, RecoveryException {
        LinkedHashMap<ProfileKey, StoredProfile> diskBefore = new LinkedHashMap<>();
        for (TransitionJournal.RecoveredChange change : recovered) {
            ProfileKey key = change.recoveryKey();
            if (!key.playerId().equals(player.getUUID()) || !key.shareId().equals(change.shareId())) {
                throw new RecoveryException("Revision contains a profile key for another player or share", null, false);
            }
            StoredProfile current = store.load(key).orElseThrow(() -> new RecoveryException(
                    "Cannot safely recover a profile that no longer exists: " + key, null, false));
            if (diskBefore.put(key, current) != null) {
                throw new RecoveryException("Revision contains duplicate profile key " + key, null, false);
            }
            SharableAdapter adapter = adapters.get(change.shareId());
            if (adapter != null && key.equals(activeProfiles.get(change.shareId()))) {
                liveBefore.put(adapter, adapter.capture(player, player.registryAccess()));
            }
        }
        return diskBefore;
    }

    private static LinkedHashMap<SharableAdapter, CompoundTag> liveRecovery(
            List<TransitionJournal.RecoveredChange> recovered,
            Map<String, ProfileKey> activeProfiles,
            Map<String, SharableAdapter> adapters,
            Map<SharableAdapter, CompoundTag> liveBefore) {
        LinkedHashMap<SharableAdapter, CompoundTag> result = new LinkedHashMap<>();
        for (TransitionJournal.RecoveredChange change : recovered) {
            SharableAdapter adapter = adapters.get(change.shareId());
            if (adapter != null
                    && change.recoveryKey().equals(activeProfiles.get(change.shareId()))
                    && !change.payload().equals(liveBefore.get(adapter))) {
                result.put(adapter, change.payload());
            }
        }
        return result;
    }

    private static boolean hasPendingCommit(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof PendingTransactionException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static final class RecoveryException extends Exception {
        private final boolean unsafe;

        private RecoveryException(String message, Throwable cause, boolean unsafe) {
            super(message, cause);
            this.unsafe = unsafe;
        }

        boolean unsafe() {
            return unsafe;
        }
    }
}
