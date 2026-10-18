package dev.yu.mwi.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideEffectFreeTransactionSourceTest {
    private static final Path SERVICE =
            Path.of("src/main/java/dev/yu/mwi/service/InventoryIsolationService.java");
    private static final Path LIVE_STATE =
            Path.of("src/main/java/dev/yu/mwi/service/LiveStateCoordinator.java");
    private static final Path RECOVERY =
            Path.of("src/main/java/dev/yu/mwi/service/RevisionRecoveryService.java");

    @Test
    void liveReplacementUsesDefaultSafeListenerPolicyAndSkipsEquivalentPayloads() throws Exception {
        String service = Files.readString(SERVICE);
        String source = Files.readString(LIVE_STATE);

        assertTrue(source.contains("requiresAdvancementListenerSuspension(unique)"));
        assertTrue(source.contains("if (suspendAdvancementListeners)"));
        assertTrue(source.contains("AdvancementStateController.suspend(player)"));
        assertTrue(source.contains("AdvancementStateController.resumeListeners(player)"));
        assertTrue(source.contains("AdvancementStateController.resumeListenersAfterSuspend(player)"));
        assertTrue(service.contains("entry.getValue().equivalent(payload, live.get(entry.getValue()))"));
        assertTrue(service.contains(
                "move.adapter().equivalent(payload, rollbackSnapshots.get(move.adapter()))"));
        assertTrue(source.contains("Always last: its reset packet"));
        assertTrue(source.contains("player.detectEquipmentUpdates()"));

        int mutation = source.indexOf("mutation.run();");
        int equipment = source.indexOf("settleEquipmentState(unique, player);");
        int sync = source.indexOf("synchronize(", equipment);
        assertTrue(mutation >= 0 && mutation < equipment && equipment < sync,
                "equipment reconciliation must run inside listener suspension before synchronization");
    }

    @Test
    void loginReusesOneFinalCaptureForBackupsAndJournal() throws Exception {
        String source = Files.readString(SERVICE);
        int initializeStart = source.indexOf("private void initialize");
        int reconcileStart = source.indexOf("private void reconcile", initializeStart);
        String initialize = source.substring(initializeStart, reconcileStart);

        assertTrue(initialize.contains("withAfter(changes, currentPayloads)"));
        assertFalse(initialize.contains("withAfter(player, changes, activeAdapters)"));
        assertTrue(initialize.indexOf("currentPayloads.put(")
                < initialize.indexOf("collectWorldBackups(player, world"));
        assertTrue(initialize.indexOf("currentPayloads.put(")
                < initialize.indexOf("withAfter(changes, currentPayloads)"));
    }

    @Test
    void listenerRecoveryOnlyClearsWhenRegistrationWasAttempted() throws Exception {
        String source = Files.readString(LIVE_STATE);

        assertTrue(source.contains("if (listenerResumeAttempted[0])"));
        assertTrue(source.contains("Registration may have failed part-way through"));
        assertTrue(source.contains("Nothing has been registered since suspend()"));
    }

    @Test
    void advancementOptimizerCooperationKeepsVanillaAsTheOnlyLifecycleAuthority() throws Exception {
        String coordinator = Files.readString(LIVE_STATE);
        String controller = Files.readString(Path.of(
                "src/main/java/dev/yu/mwi/adapter/vanilla/AdvancementStateController.java"));

        assertTrue(controller.contains("advancements.registerListeners(manager)"));
        assertTrue(coordinator.contains("AdvancementStateController.suspend(player)"));
        assertTrue(coordinator.contains("AdvancementStateController.resumeListenersAfterSuspend(player)"));
        assertFalse(coordinator.contains("invadvopt"));
        assertFalse(controller.contains("invadvopt"));
        assertFalse(controller.contains("Class.forName"));
    }

    @Test
    void transactionUsesAdapterSemanticEqualityForLoginTransitionAndRollback() throws Exception {
        String source = Files.readString(SERVICE);
        String liveState = Files.readString(LIVE_STATE);

        assertTrue(source.contains("entry.getValue().equivalent(payload, live.get(entry.getValue()))"));
        assertTrue(source.contains(
                "move.adapter().equivalent(payload, rollbackSnapshots.get(move.adapter()))"));
        assertTrue(liveState.contains("entry.getKey().equivalent(entry.getValue(), current)"));
        assertFalse(source.contains("payload.equals(live.get(entry.getValue()))"));
        assertFalse(source.contains("payload.equals(rollbackSnapshots.get(move.adapter()))"));
    }

    @Test
    void recoveryAndShutdownPreserveDurabilityBarriers() throws Exception {
        String source = Files.readString(SERVICE);
        String recovery = Files.readString(RECOVERY);
        assertTrue(recovery.contains("store.writeRevision(player.getUUID(), recoveryRevision, recoveryJournal)"));
        assertTrue(recovery.contains("store.commitBatch(player.getUUID(), recoveryRevision"));
        assertFalse(recovery.contains("store.save("));

        int closeStart = source.indexOf("public void close()");
        int initializeStart = source.indexOf("private void initialize", closeStart);
        String close = source.substring(closeStart, initializeStart);
        assertTrue(close.contains("quarantinedPlayers.contains(player.getUUID())"));
        assertTrue(close.contains("persistQuarantined(player"));
    }

    @Test
    void loginUsesDurabilityBarrierAndDimensionTransitionQueuesAfterLiveMutation() throws Exception {
        String source = Files.readString(SERVICE);
        int initializeStart = source.indexOf("private void initialize");
        int reconcileStart = source.indexOf("private void reconcile", initializeStart);
        String initialize = source.substring(initializeStart, reconcileStart);
        // The detached login input is already durable while fenced. Validate application before
        // allocating/committing an authoritative revision; deterministic failures cannot grow history.
        assertTrue(initialize.indexOf("replace(player") < initialize.indexOf("verifyLoginApplication(player"));
        assertTrue(initialize.indexOf("verifyLoginApplication(player") < initialize.indexOf("revision = nextRevision(player)"));
        assertTrue(initialize.contains("snapshotWriter.commitImmediately"));

        int migrationStart = source.indexOf("private CompoundTag migrateGroupTarget", reconcileStart);
        String reconcile = source.substring(reconcileStart, migrationStart);
        assertFalse(reconcile.contains("store.writeRevision"));
        assertFalse(reconcile.contains("store.commitBatch"));
        assertTrue(reconcile.indexOf("replace(player") < reconcile.lastIndexOf("snapshotWriter.enqueue"));
    }
}
