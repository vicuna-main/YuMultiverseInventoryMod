package dev.yu.mwi.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the lifecycle and data-ownership invariants of hybrid-server pre-login teleports. */
class PreLoginDimensionTransitionSourceTest {
    private static final Path SERVICE =
            Path.of("src/main/java/dev/yu/mwi/service/InventoryIsolationService.java");
    private static final Path EVENT_HANDLER =
            Path.of("src/main/java/dev/yu/mwi/platform/NeoForgeEventHandler.java");
    private static final Path CONTROLLER =
            Path.of("src/main/java/dev/yu/mwi/platform/MwiServerController.java");

    @Test
    void neoForgeBoundaryPreservesBothEventDimensions() throws Exception {
        String eventHandler = Files.readString(EVENT_HANDLER);
        String controller = Files.readString(CONTROLLER);

        assertTrue(eventHandler.contains(
                "controller.dimensionChanged(player, event.getFrom(), event.getTo())"));
        assertTrue(controller.contains("ResourceKey<Level> fromDimension"));
        assertTrue(controller.contains("ResourceKey<Level> toDimension"));
        assertTrue(controller.contains("dimensionChanged(player, fromDimension, toDimension)"));
        assertFalse(eventHandler.contains("enqueueWork"));
    }

    @Test
    void absentSessionBootstrapsFromTheEventSourceBeforeReconciliation() throws Exception {
        String source = Files.readString(SERVICE);
        int bootstrapStart = source.indexOf("private void bootstrapProvisionalSession");
        int canonicalRestoreStart = source.indexOf(
                "private void restoreCanonicalProvisionalSource", bootstrapStart);
        String bootstrap = source.substring(bootstrapStart, canonicalRestoreStart);

        assertTrue(bootstrap.contains("WorldIdentities.from(fromDimension)"));
        assertTrue(bootstrap.contains("profileKeys(player, sourceWorld"));
        assertTrue(bootstrap.contains("LocationTracker.unknown()"));
        assertTrue(bootstrap.contains("SessionPhase.PROVISIONAL"));
        assertTrue(bootstrap.indexOf("sessions.put(")
                < bootstrap.indexOf("reconcile(player, toDimension"));
        assertFalse(source.contains(
                "Dimension transition arrived before the player profile working set was initialized"));
    }

    @Test
    void crossGroupTransitionArchivesSourceAndLoadsTargetByDistinctKeys() throws Exception {
        String reconcile = reconcileSource();

        assertTrue(reconcile.contains("writes.put(move.fromKey(), outgoing)"));
        assertTrue(reconcile.contains("snapshotWriter.load(move.toKey())"));
        assertTrue(reconcile.contains("incoming.put(move.shareId(), prepareStoredPayload("));
        assertTrue(reconcile.contains("writes.put(move.toKey(), payload)"));
        assertTrue(reconcile.indexOf("writes.put(move.fromKey(), outgoing)")
                < reconcile.indexOf("snapshotWriter.load(move.toKey())"));
    }

    @Test
    void missingTargetNeverAdoptsTheSourcePayloadAcrossWorlds() throws Exception {
        String reconcile = reconcileSource();

        assertTrue(reconcile.contains("boolean sameWorld = move.fromKey() == null"
                + " || session.world().equals(targetWorld)"));
        assertTrue(reconcile.contains("rollbackSnapshots.get(move.adapter()), sameWorld"));
        assertTrue(reconcile.contains("move.adapter().clear(player)"));
        assertFalse(reconcile.contains("incoming.put(move.shareId(), outgoing)"));
    }

    @Test
    void sameScopeTransitionOnlyAdvancesWorldIdentityAndSessionPhase() throws Exception {
        String reconcile = reconcileSource();
        int noMovesStart = reconcile.indexOf("if (moves.isEmpty())");
        int mutationStart = reconcile.indexOf("closeMenu(player)", noMovesStart);
        String noMoves = reconcile.substring(noMovesStart, mutationStart);

        assertTrue(noMoves.contains("new Session("));
        assertTrue(noMoves.contains("targetDimension"));
        assertTrue(noMoves.contains("targetWorld"));
        assertTrue(noMoves.contains("session.phase()"));
        assertFalse(noMoves.contains("replace(player"));
    }

    @Test
    void repeatedPreLoginTransitionsReuseTheProvisionalSession() throws Exception {
        String source = Files.readString(SERVICE);
        int dimensionStart = source.indexOf("public void dimensionChanged(");
        int tickStart = source.indexOf("public void tick(", dimensionStart);
        String dimensionChanged = source.substring(dimensionStart, tickStart);

        assertTrue(dimensionChanged.contains("if (current == null)"));
        assertTrue(dimensionChanged.contains("bootstrapProvisionalSession"));
        assertTrue(dimensionChanged.contains("current.phase() == SessionPhase.PROVISIONAL"));
        assertTrue(dimensionChanged.contains("reconcile(player, toDimension, \"dimension_change\")"));
    }

    @Test
    void loggedInEventPromotesWithoutReinitializingOrApplyingProfilesAgain() throws Exception {
        String source = Files.readString(SERVICE);
        int completionStart = source.indexOf("private void completeProvisionalLogin");
        int loggingStart = source.indexOf("private void logPreLoginTransition", completionStart);
        String completion = source.substring(completionStart, loggingStart);

        assertTrue(completion.contains("provisional.activate()"));
        assertTrue(completion.contains("loginPerformance.recordLogin()"));
        assertTrue(completion.contains("store.completeStartupRecovery"));
        assertFalse(completion.contains("snapshotWriter.commitImmediately"));
        assertFalse(completion.contains("adapter.apply"));
    }

    @Test
    void unknownSourceLocationIsNeitherPersistedNorJournaled() throws Exception {
        String reconcile = reconcileSource();

        assertTrue(reconcile.contains("outgoingSnapshots.remove(move.adapter())"));
        assertTrue(reconcile.contains("withoutLastLocation(activeAdapters)"));
        assertTrue(reconcile.contains("filter(move -> outgoingSnapshots.containsKey(move.adapter()))"));
        assertTrue(reconcile.contains("Skipped source last_location snapshot"));
        assertTrue(reconcile.contains("collectWorldBackups(player, finalWorld, finalKeys"));
    }

    @Test
    void startupRecoveryStillRestoresCanonicalSourceBeforeTransition() throws Exception {
        String source = Files.readString(SERVICE);
        int restoreStart = source.indexOf("private void restoreCanonicalProvisionalSource");
        int completionStart = source.indexOf("private void completeProvisionalLogin", restoreStart);
        String restore = source.substring(restoreStart, completionStart);

        assertTrue(restore.contains("store.requiresCanonicalRecovery"));
        assertTrue(restore.contains("store.shouldResetLive(sourceKey)"));
        assertTrue(restore.contains("snapshotWriter.load(sourceKey)"));
        assertTrue(restore.contains("rollback(player, rollbackSnapshots)"));
        assertTrue(restore.contains("entry.getKey().equals(AdapterIds.LAST_LOCATION.getPath())"));
        assertTrue(restore.indexOf("entry.getKey().equals(AdapterIds.LAST_LOCATION.getPath())")
                < restore.indexOf("snapshotWriter.load(sourceKey)"));
    }

    @Test
    void realTransactionFailuresRetainRollbackAndQuarantineBarriers() throws Exception {
        String source = Files.readString(SERVICE);
        String reconcile = reconcileSource();
        String eventHandler = Files.readString(EVENT_HANDLER);

        assertTrue(reconcile.contains("hasPendingCommit(failure)"));
        assertTrue(reconcile.contains("rollback(player, rollbackSnapshots)"));
        assertTrue(reconcile.contains("quarantine(player, rollbackFailure)"));
        assertTrue(eventHandler.contains("controller.quarantine(player, failure)"));
        assertTrue(source.contains("persistQuarantined(player"));
    }

    private static String reconcileSource() throws Exception {
        String source = Files.readString(SERVICE);
        int reconcileStart = source.indexOf(
                "private void reconcile(\n            ServerPlayer player,\n            ResourceKey<Level> targetDimension");
        int migrationStart = source.indexOf("private CompoundTag migrateGroupTarget", reconcileStart);
        return source.substring(reconcileStart, migrationStart);
    }
}
