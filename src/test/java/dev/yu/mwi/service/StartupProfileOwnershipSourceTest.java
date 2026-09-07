package dev.yu.mwi.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards against login/playerdata state being silently adopted into the wrong profile scope. */
class StartupProfileOwnershipSourceTest {
    private static final Path SERVICE =
            Path.of("src/main/java/dev/yu/mwi/service/InventoryIsolationService.java");
    private static final Path JOURNAL =
            Path.of("src/main/java/dev/yu/mwi/service/TransitionJournal.java");
    private static final Path STORE =
            Path.of("src/main/java/dev/yu/mwi/storage/NbtProfileStore.java");

    @Test
    void uncleanShutdownNeverMakesLoginLiveAuthoritativeOverCanonicalProfiles() throws Exception {
        String source = Files.readString(SERVICE);
        int initializeStart = source.indexOf("private void initialize");
        int reconcileStart = source.indexOf("private void reconcile", initializeStart);
        String initialize = source.substring(initializeStart, reconcileStart);

        assertFalse(initialize.contains("boolean recoverLive"));
        assertFalse(initialize.contains("crash_recovery"));
        assertTrue(initialize.contains("snapshotWriter.load(target)"));
        assertTrue(initialize.contains("canonicalBefore.put(target, canonicalProfile.payload())"));
        assertTrue(initialize.contains("LoginStateOwnershipPolicy.mayBootstrapUnknownLive"));
        assertTrue(initialize.contains("resetToDefault.add(adapter)"));
        assertTrue(initialize.contains("login live state is rollback-only"));
    }

    @Test
    void hybridPreLoginSourceIsCanonicalizedBeforeItCanBePersisted() throws Exception {
        String source = Files.readString(SERVICE);
        int restoreStart = source.indexOf("private void restoreCanonicalProvisionalSource");
        int completionStart = source.indexOf("private void completeProvisionalLogin", restoreStart);
        String restore = source.substring(restoreStart, completionStart);

        assertFalse(restore.contains("if (!store.requiresCanonicalRecovery"));
        assertTrue(restore.contains("snapshotWriter.load(sourceKey)"));
        assertTrue(restore.contains("snapshotWriter.hasAnyProfileForShare"));
        assertTrue(restore.contains("resets.add(adapter)"));
        assertTrue(restore.contains("canonical.put(adapter, payload)"));
    }

    @Test
    void normalCanonicalPersistenceRequiresSessionOwnershipInsteadOfCurrentWorldInference() throws Exception {
        String source = Files.readString(SERVICE);
        int persistStart = source.indexOf(
                "private void persistCurrent(ServerPlayer player, String reason, boolean asynchronous)");
        int quarantinedStart = source.indexOf("private void persistQuarantined", persistStart);
        String persist = source.substring(persistStart, quarantinedStart);

        assertTrue(persist.contains("Session session = sessions.get(player.getUUID())"));
        assertTrue(persist.contains("Refusing canonical persistence without an owned inventory session"));
        assertTrue(persist.contains("session.profiles()"));
        assertTrue(persist.contains("session.activeAdapters()"));
        assertFalse(persist.contains("profileKeys(player, world"));
    }

    @Test
    void preparedLoginJournalSeparatesLiveRollbackSnapshotFromCanonicalBeforeImage() throws Exception {
        String journal = Files.readString(JOURNAL);
        String store = Files.readString(STORE);

        assertTrue(journal.contains("preparedLogin("));
        assertTrue(journal.contains("target_existed"));
        assertTrue(journal.contains("canonical_before"));
        assertTrue(store.contains("Prepared login revision is missing its canonical before-image"));
        assertTrue(store.contains("resetTargets.add(toKey)"));
        assertTrue(store.contains("unknown vanilla live snapshot"));
    }
}
