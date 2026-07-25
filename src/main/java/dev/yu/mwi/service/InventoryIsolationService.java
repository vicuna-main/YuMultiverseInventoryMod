package dev.yu.mwi.service;

import dev.yu.mwi.api.AdapterRegistry;
import dev.yu.mwi.api.AdapterIds;
import dev.yu.mwi.api.ItemEditSlotPolicy;
import dev.yu.mwi.api.SharableAdapter;
import dev.yu.mwi.config.RuntimeConfig;
import dev.yu.mwi.domain.GameModeBucket;
import dev.yu.mwi.domain.GroupResolver;
import dev.yu.mwi.domain.ProfileKey;
import dev.yu.mwi.domain.ScopeKey;
import dev.yu.mwi.domain.ShareCatalog;
import dev.yu.mwi.domain.WorldIdentity;
import dev.yu.mwi.storage.CacheStats;
import dev.yu.mwi.storage.NbtProfileStore;
import dev.yu.mwi.storage.PendingTransactionException;
import dev.yu.mwi.storage.ProfileStore;
import dev.yu.mwi.storage.StoredProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

import static dev.yu.mwi.service.LiveStateCoordinator.capture;
import static dev.yu.mwi.service.LiveStateCoordinator.closeMenu;
import static dev.yu.mwi.service.LiveStateCoordinator.replace;
import static dev.yu.mwi.service.LiveStateCoordinator.rollback;
import static dev.yu.mwi.service.LiveStateCoordinator.withAfter;

/** Server-thread transaction coordinator for all player share transitions. */
public final class InventoryIsolationService implements AutoCloseable {
    private final MinecraftServer server;
    private final AdapterRegistry adapters;
    private final ProfileStore store;
    private final Logger logger;
    private final ProfileSnapshotWriter snapshotWriter;
    private final ProfileMigrationService migrationService;
    private final RevisionRecoveryService recoveryService;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, ReentrantLock> playerLocks = new ConcurrentHashMap<>();
    private final Set<UUID> activeTransactions = ConcurrentHashMap.newKeySet();
    private final Set<UUID> crashRecoveredPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> quarantinedPlayers = ConcurrentHashMap.newKeySet();
    private final List<Long> dimensionCaptureLatenciesNanos = new ArrayList<>();
    private final LoginPerformanceTracker loginPerformance = new LoginPerformanceTracker();
    private volatile State state;

    public InventoryIsolationService(
            MinecraftServer server,
            AdapterRegistry adapters,
            RuntimeConfig config,
            Logger logger) throws IOException {
        this(server, adapters, config, logger, createStore(server, config));
    }

    InventoryIsolationService(
            MinecraftServer server,
            AdapterRegistry adapters,
            RuntimeConfig config,
            Logger logger,
            ProfileStore store) {
        this.server = Objects.requireNonNull(server, "server");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.store = Objects.requireNonNull(store, "store");
        this.snapshotWriter = new ProfileSnapshotWriter(store, logger, config.storage());
        this.migrationService = new ProfileMigrationService(server, snapshotWriter, logger);
        this.recoveryService = new RevisionRecoveryService(store);
        this.state = State.create(config, adapters, 1L);
    }

    private static ProfileStore createStore(MinecraftServer server, RuntimeConfig config) throws IOException {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(config, "config");
        return new NbtProfileStore(
                server.getWorldPath(LevelResource.ROOT),
                config.storage().maxRevisions(),
                config.storage().cacheEntries(),
                config.storage().cacheMegabytes());
    }

    public void login(ServerPlayer player) throws TransitionException {
        requireServerThread();
        Session existing = sessions.get(player.getUUID());
        if (existing != null && existing.owner() == player) {
            return;
        }
        if (existing != null) {
            sessions.remove(player.getUUID(), existing);
        }
        try {
            snapshotWriter.flush(player.getUUID());
        } catch (IOException failure) {
            throw new TransitionException("Unable to finish earlier profile persistence before login", failure);
        }
        withPlayerLock(player, () -> initialize(player));
        quarantinedPlayers.remove(player.getUUID());
    }

    public void dimensionChanged(ServerPlayer player) throws TransitionException {
        requireServerThread();
        if (activeTransactions.contains(player.getUUID())) {
            return;
        }
        long started = System.nanoTime();
        try {
            withPlayerLock(player, () -> reconcile(player, "dimension_change"));
        } finally {
            recordDimensionCapture(System.nanoTime() - started);
        }
    }

    /** Cheap hybrid-server fallback: only dimension/game-mode/config generation comparisons on the normal path. */
    public void tick(ServerPlayer player) throws TransitionException {
        requireServerThread();
        try {
            snapshotWriter.throwIfFailed(player.getUUID());
        } catch (IOException failure) {
            throw new TransitionException("Queued profile persistence failed", failure);
        }
        if (quarantinedPlayers.contains(player.getUUID())) {
            return;
        }
        Session session = sessions.get(player.getUUID());
        if (session == null || session.owner() != player) {
            login(player);
            return;
        }
        for (SharableAdapter adapter : session.activeAdapters().values()) {
            adapter.serverTick(player);
        }
        State current = state;
        GameModeBucket gameMode = gameMode(player, current.config());
        if (!session.dimension().equals(player.serverLevel().dimension()) || session.gameMode() != gameMode
                || session.configGeneration() != current.generation()) {
            withPlayerLock(player, () -> reconcile(player, "hybrid_fallback"));
        } else {
            session.location().update(player);
        }
    }

    public void respawned(ServerPlayer player) throws TransitionException {
        requireServerThread();
        withPlayerLock(player, () -> reseedCurrent(player, "respawn"));
    }

    public void logout(ServerPlayer player) throws TransitionException {
        requireServerThread();
        Session session = sessions.get(player.getUUID());
        if (session != null && session.owner() != player) {
            return;
        }
        try {
            if (quarantinedPlayers.contains(player.getUUID())) {
                withPlayerLock(player, () -> persistQuarantined(player, "disconnect after failed rollback"));
                return;
            }
            withPlayerLock(player, () -> {
                if (session != null) {
                    reconcile(player, "logout_reconcile");
                }
                persistCurrent(player, "logout", true);
            });
        } finally {
            adapters.adapters().forEach(adapter -> adapter.sessionClosed(player));
            if (session == null) {
                sessions.remove(player.getUUID());
            } else {
                sessions.remove(player.getUUID(), session);
            }
            playerLocks.remove(player.getUUID());
            quarantinedPlayers.remove(player.getUUID());
        }
    }

    /** Persists at the same boundary as vanilla PlayerDataStorage saves without ending the session. */
    public void checkpoint(ServerPlayer player, String reason) throws TransitionException {
        checkpoint(player, reason, true);
    }

    /** Synchronously persists a player at an ownership-transfer boundary. */
    public void checkpointImmediately(ServerPlayer player, String reason) throws TransitionException {
        requireServerThread();
        try {
            withPlayerLock(player, () -> {
                requireEditingSession(player);
                persistCurrent(player, reason, false);
            });
        } catch (TransitionException failure) {
            if (hasPendingCommit(failure)) {
                quarantine(player, failure);
            }
            throw failure;
        }
    }

    /**
     * Persists an editor owner unless their state is already inside a transaction that will own
     * the close. This avoids a nested transaction when a dimension switch closes a self-editor.
     */
    public boolean checkpointImmediatelyIfIdle(ServerPlayer player, String reason) throws TransitionException {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        if (activeTransactions.contains(player.getUUID())) {
            return false;
        }
        checkpointImmediately(player, reason);
        return true;
    }

    private void checkpoint(ServerPlayer player, String reason, boolean asynchronous) throws TransitionException {
        requireServerThread();
        Session session = sessions.get(player.getUUID());
        if (session == null || session.owner() != player) {
            login(player);
        }
        withPlayerLock(player, () -> {
            reconcile(player, reason + "_reconcile");
            persistCurrent(player, reason, asynchronous);
        });
    }

    public void checkpointAll(String reason) throws TransitionException {
        requireServerThread();
        TransitionException aggregate = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                checkpoint(player, reason);
            } catch (TransitionException failure) {
                quarantine(player, failure);
                if (aggregate == null) {
                    aggregate = new TransitionException("One or more player checkpoints failed");
                }
                aggregate.addSuppressed(failure);
            }
        }
        if (aggregate != null) {
            throw aggregate;
        }
    }

    public ReloadReport dryRun(RuntimeConfig candidate) {
        requireServerThread();
        if (!candidate.storage().equals(state.config().storage())) {
            throw new IllegalArgumentException("storage settings can only be changed while the server is stopped");
        }
        State candidateState = State.create(candidate, adapters, state.generation() + 1L);
        List<String> details = new ArrayList<>();
        int changedShares = 0;
        int players = 0;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            WorldIdentity world = WorldIdentities.from(player.serverLevel());
            Map<String, SharableAdapter> active = activeAdapters(player, candidateState);
            Map<String, ProfileKey> target = profileKeys(player, world, candidateState, active);
            Session existing = sessions.get(player.getUUID());
            if (existing == null) {
                continue;
            }
            List<String> changed = target.entrySet().stream()
                    .filter(entry -> !entry.getValue().equals(existing.profiles().get(entry.getKey())))
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
            if (!changed.isEmpty()) {
                players++;
                changedShares += changed.size();
                details.add(player.getGameProfile().getName() + ": " + String.join(", ", changed));
            }
        }

        // Resolve every loaded world/share pair now so ambiguous overlapping groups fail before activation.
        for (var level : server.getAllLevels()) {
            WorldIdentity world = WorldIdentities.from(level);
            for (String share : candidateState.managedShareIds()) {
                candidateState.resolver().resolve(world, share);
            }
        }
        return new ReloadReport(players, changedShares, details);
    }

    public void activate(RuntimeConfig config) {
        requireServerThread();
        this.state = State.create(config, adapters, state.generation() + 1L);
    }

    public void reconcileAll(String reason) throws TransitionException {
        requireServerThread();
        TransitionException aggregate = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                withPlayerLock(player, () -> reconcile(player, reason));
            } catch (TransitionException failure) {
                quarantine(player, failure);
                if (aggregate == null) {
                    aggregate = new TransitionException("One or more player reconciliations failed");
                }
                aggregate.addSuppressed(failure);
            }
        }
        if (aggregate != null) {
            throw aggregate;
        }
    }

    public void recover(ServerPlayer player, long revision) throws TransitionException {
        requireServerThread();
        if (revision < 1) {
            throw new TransitionException("Revision must be positive");
        }
        withPlayerLock(player, () -> {
            Session session = sessions.get(player.getUUID());
            if (session == null || session.owner() != player) {
                throw new TransitionException("Player has no active inventory session");
            }
            try {
                recoveryService.recover(
                        player, revision, session.world(), session.profiles(), session.activeAdapters());
            } catch (RevisionRecoveryService.RecoveryException failure) {
                if (failure.unsafe()) {
                    quarantinedPlayers.add(player.getUUID());
                }
                throw new TransitionException(failure.getMessage(), failure);
            }
        });
    }

    public List<Long> revisions(UUID playerId) throws TransitionException {
        try {
            return store.listRevisions(playerId);
        } catch (IOException failure) {
            throw new TransitionException("Unable to list revisions for " + playerId, failure);
        }
    }

    public CacheStats cacheStats() {
        return store.cacheStats();
    }

    public PersistenceMetrics persistenceMetrics() {
        ProfilePersistenceQueue.Metrics metrics = snapshotWriter.metrics();
        List<Long> captures;
        synchronized (dimensionCaptureLatenciesNanos) {
            captures = dimensionCaptureLatenciesNanos.stream().sorted().toList();
        }
        return new PersistenceMetrics(
                metrics.queueDepth(),
                metrics.oldestTaskAge().toMillis(),
                metrics.p95WriteLatency().toMillis(),
                metrics.p99WriteLatency().toMillis(),
                metrics.failures(),
                metrics.coalesced(),
                percentileMillis(captures, 0.95),
                percentileMillis(captures, 0.99));
    }

    public LoginPerformanceMetrics loginPerformanceMetrics() {
        return loginPerformance.snapshot();
    }

    private void recordDimensionCapture(long elapsedNanos) {
        synchronized (dimensionCaptureLatenciesNanos) {
            dimensionCaptureLatenciesNanos.add(elapsedNanos);
            if (dimensionCaptureLatenciesNanos.size() > 2_048) {
                dimensionCaptureLatenciesNanos.subList(0, 1_024).clear();
            }
        }
    }

    private static long percentileMillis(List<Long> sortedNanos, double percentile) {
        if (sortedNanos.isEmpty()) {
            return 0L;
        }
        int index = Math.min(sortedNanos.size() - 1, (int) Math.ceil(sortedNanos.size() * percentile) - 1);
        return java.time.Duration.ofNanos(sortedNanos.get(index)).toMillis();
    }

    /** Opens a stable profile projection for an operator inventory editor. */
    public EditableProfile prepareItemEdit(ServerPlayer player, String worldName, String shareId)
            throws TransitionException {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(shareId, "shareId");
        EditableProfile[] result = new EditableProfile[1];
        withPlayerLock(player, () -> {
            Session session = requireEditingSession(player);
            SharableAdapter adapter = requireEditingAdapter(session, shareId);
            WorldIdentity world = WorldIdentities.fromName(worldName);
            ProfileKey key = new ProfileKey(
                    player.getUUID(), state.resolver().resolve(world, shareId), session.gameMode(), shareId);
            CompoundTag template = adapter.capture(player, player.registryAccess());
            try {
                Optional<CompoundTag> stored = currentEditKey(session, key)
                        ? Optional.of(template.copy())
                        : store.load(key).map(StoredProfile::payload);
                result[0] = new EditableProfile(key, world.name(), shareId,
                        stored, template, state.generation());
            } catch (IOException failure) {
                throw new TransitionException("Unable to load the selected inventory profile", failure);
            }
        });
        return Objects.requireNonNull(result[0]);
    }

    /** Reads the newest profile state so editor changes can be merged without clobbering unrelated slots. */
    public CompoundTag readItemEdit(
            ServerPlayer requestedPlayer, EditableProfile profile, CompoundTag missingFallback)
            throws TransitionException {
        requireServerThread();
        ServerPlayer player = currentPlayer(requestedPlayer);
        validateEditProfile(player, profile);
        ProfileKey key = profile.key();
        String shareId = profile.shareId();
        CompoundTag[] result = new CompoundTag[1];
        withPlayerLock(player, () -> {
            Session session = sessions.get(player.getUUID());
            if (session != null && session.owner() == player && currentEditKey(session, key)) {
                SharableAdapter adapter = requireEditingAdapter(session, shareId);
                result[0] = adapter.capture(player, player.registryAccess());
                return;
            }
            try {
                result[0] = store.load(key).map(StoredProfile::payload).orElseGet(missingFallback::copy);
            } catch (IOException failure) {
                throw new TransitionException("Unable to refresh the selected inventory profile", failure);
            }
        });
        return Objects.requireNonNull(result[0]);
    }

    /** Applies an edited payload live when active and optionally commits it atomically to storage. */
    public void validateItemEditSlot(
            ServerPlayer requestedPlayer, EditableProfile profile, String slotKey,
            net.minecraft.world.item.ItemStack replacement)
            throws TransitionException, ItemEditRejectedException {
        requireServerThread();
        ServerPlayer player = currentPlayer(requestedPlayer);
        validateEditProfile(player, profile);
        Objects.requireNonNull(slotKey, "slotKey");
        Objects.requireNonNull(replacement, "replacement");
        SharableAdapter adapter = adapters.require(AdapterIds.id(profile.shareId()));
        if (adapter instanceof ItemEditSlotPolicy policy) {
            try {
                policy.validateItemEditSlot(player, slotKey, replacement.copy());
            } catch (IllegalArgumentException failure) {
                throw new ItemEditRejectedException(failure.getMessage(), failure);
            }
        }
    }

    /** Applies an edited payload live when active and optionally commits it atomically to storage. */
    public void applyItemEdit(
            ServerPlayer requestedPlayer, EditableProfile profile, CompoundTag payload, boolean persist)
            throws TransitionException {
        requireServerThread();
        ServerPlayer player = currentPlayer(requestedPlayer);
        validateEditProfile(player, profile);
        ProfileKey key = profile.key();
        String shareId = profile.shareId();
        Objects.requireNonNull(payload, "payload");
        withPlayerLock(player, () -> {
            Session session = sessions.get(player.getUUID());
            LinkedHashMap<SharableAdapter, CompoundTag> before = null;
            if (session != null && session.owner() == player && currentEditKey(session, key)) {
                SharableAdapter adapter = requireEditingAdapter(session, shareId);
                before = capture(player, List.of(adapter));
                SharableAdapter selected = adapter;
                try {
                    replace(player, List.of(selected), () -> {
                        selected.clear(player);
                        selected.apply(player, payload.copy(), player.registryAccess());
                    });
                } catch (RuntimeException failure) {
                    try {
                        rollback(player, before);
                    } catch (RuntimeException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                        quarantinedPlayers.add(player.getUUID());
                    }
                    throw new TransitionException("Unable to apply the edited inventory live", failure);
                }
            }
            if (!persist) {
                return;
            }
            try {
                long revision = nextRevision(player);
                snapshotWriter.commitImmediately(
                        player.getUUID(), revision, Map.of(key, payload.copy()), Optional.empty());
            } catch (IOException | TransitionException | RuntimeException failure) {
                boolean durablePendingCommit = hasPendingCommit(failure);
                if (before != null && !durablePendingCommit) {
                    try {
                        rollback(player, before);
                    } catch (RuntimeException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                        quarantinedPlayers.add(player.getUUID());
                    }
                }
                if (durablePendingCommit) {
                    quarantinedPlayers.add(player.getUUID());
                    logger.error("Inventory editor commit is durable but pending for {} ({})",
                            player.getGameProfile().getName(), player.getUUID(), failure);
                    player.connection.disconnect(Component.literal(
                            "Your inventory edit is durably queued but storage could not finish the commit. "
                                    + "The player is quarantined until the server restarts and recovers it."));
                }
                throw transitionFailure("Unable to persist the edited inventory", failure);
            }
        });
    }

    private Session requireEditingSession(ServerPlayer player) throws TransitionException {
        Session session = sessions.get(player.getUUID());
        if (session == null || session.owner() != player) {
            throw new TransitionException("Player has no active inventory session");
        }
        if (quarantinedPlayers.contains(player.getUUID())) {
            throw new TransitionException("Player inventory is quarantined and cannot be edited");
        }
        return session;
    }

    private static SharableAdapter requireEditingAdapter(Session session, String shareId) throws TransitionException {
        SharableAdapter adapter = session.activeAdapters().get(shareId);
        if (adapter == null) {
            throw new TransitionException("The " + shareId + " inventory is unavailable for this player");
        }
        return adapter;
    }

    private static boolean currentEditKey(Session session, ProfileKey key) {
        return key.equals(session.profiles().get(key.shareId()));
    }

    private ServerPlayer currentPlayer(ServerPlayer requestedPlayer) {
        Objects.requireNonNull(requestedPlayer, "requestedPlayer");
        ServerPlayer online = server.getPlayerList().getPlayer(requestedPlayer.getUUID());
        return online == null ? requestedPlayer : online;
    }

    private void validateEditProfile(ServerPlayer player, EditableProfile profile) throws TransitionException {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(profile, "profile");
        ProfileKey key = profile.key();
        if (!player.getUUID().equals(key.playerId()) || !profile.shareId().equals(key.shareId())) {
            throw new IllegalArgumentException("Inventory editor key does not match its player/share");
        }
        if (profile.configGeneration() != state.generation()) {
            throw new TransitionException(
                    "Inventory configuration changed while the editor was open; reopen the editor");
        }
        if (quarantinedPlayers.contains(player.getUUID())) {
            throw new TransitionException("Player inventory is quarantined and cannot be edited");
        }
    }

    public StateSnapshot snapshot() {
        State current = state;
        return new StateSnapshot(current.config(), current.resolver(), current.managedShareIds(), sessions.size());
    }

    public void quarantine(ServerPlayer player, Throwable failure) {
        quarantinedPlayers.add(player.getUUID());
        store.markUncleanShutdown();
        logger.error("Inventory transaction failed for {} ({})", player.getGameProfile().getName(), player.getUUID(), failure);
        player.connection.disconnect(Component.literal(
                "Yu Multiverse Inventory could not safely switch your profile. Canonical profile writes are now "
                        + "blocked; contact an administrator before reconnecting."));
    }

    @Override
    public void close() throws IOException {
        requireServerThread();
        boolean clean = true;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                if (quarantinedPlayers.contains(player.getUUID())) {
                    persistQuarantined(player, "server stop while player was quarantined");
                } else {
                    persistCurrent(player, "server_stop", true);
                }
            } catch (TransitionException failure) {
                clean = false;
                logger.error("Unable to persist {} during server stop", player.getGameProfile().getName(), failure);
            } finally {
                adapters.adapters().forEach(adapter -> adapter.sessionClosed(player));
            }
        }
        sessions.clear();
        quarantinedPlayers.clear();
        if (!clean) {
            store.markUncleanShutdown();
        }
        IOException closeFailure = null;
        try {
            snapshotWriter.close();
        } catch (IOException failure) {
            closeFailure = failure;
            store.markUncleanShutdown();
        }
        try {
            store.close();
        } catch (IOException failure) {
            if (closeFailure == null) {
                closeFailure = failure;
            } else {
                closeFailure.addSuppressed(failure);
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void initialize(ServerPlayer player) throws TransitionException {
        loginPerformance.recordLogin();
        try {
            snapshotWriter.preload(player.getUUID());
        } catch (IOException failure) {
            throw new TransitionException("Unable to preload player profiles", failure);
        }
        State current = state;
        WorldIdentity world = WorldIdentities.from(player.serverLevel());
        GameModeBucket gameMode = gameMode(player, current.config());
        Map<String, SharableAdapter> activeAdapters = activeAdapters(player, current);
        Map<String, ProfileKey> targetKeys = profileKeys(player, world, current, activeAdapters);
        closeMenu(player);
        resetInterruptedTargets(player, targetKeys, activeAdapters);
        long initialCaptureStarted = System.nanoTime();
        LinkedHashMap<SharableAdapter, CompoundTag> live;
        int initialCaptures = 0;
        try {
            live = capture(player, activeAdapters.values());
            initialCaptures = live.size();
        } finally {
            loginPerformance.recordCapture(initialCaptures, System.nanoTime() - initialCaptureStarted);
        }
        long revision = nextRevision(player);
        List<TransitionJournal.Change> changes = new ArrayList<>();
        Map<String, CompoundTag> incoming = new HashMap<>();
        LinkedHashMap<ProfileKey, CompoundTag> writes = new LinkedHashMap<>();
        List<ArchiveRequest> archives = new ArrayList<>();
        CompoundTag journal = null;
        boolean firstCrashRecovery = crashRecoveredPlayers.add(player.getUUID());
        boolean recoverLive = store.recoveredFromUncleanShutdown()
                && firstCrashRecovery
                && !store.requiresCanonicalRecovery(player.getUUID());

        try {
            for (Map.Entry<String, SharableAdapter> entry : activeAdapters.entrySet()) {
                String share = entry.getKey();
                SharableAdapter adapter = entry.getValue();
                ProfileKey target = targetKeys.get(share);
                CompoundTag livePayload = live.get(adapter);
                Optional<StoredProfile> stored = snapshotWriter.load(target);
                CompoundTag selected;
                if (recoverLive) {
                    if (stored.isPresent()) {
                        archives.add(new ArchiveRequest(target, "crash_recovery", "unclean server shutdown"));
                    }
                    selected = livePayload;
                    writes.put(target, selected);
                } else if (stored.isPresent()) {
                    selected = stored.get().payload();
                    incoming.put(share, selected);
                } else if (target.scope().type() == ScopeKey.Type.GROUP) {
                    selected = migrateGroupTarget(
                            player, world, target, livePayload, true, writes, archives);
                    incoming.put(share, selected);
                } else {
                    selected = livePayload;
                    writes.put(target, selected);
                }
                changes.add(new TransitionJournal.Change(share, target, target, livePayload, null));
            }
            journal = TransitionJournal.prepared("login", world.name(), world.name(), changes);
            store.writeRevision(player.getUUID(), revision, journal);

            List<SharableAdapter> mutatedAdapters = new ArrayList<>();
            for (Map.Entry<String, SharableAdapter> entry : activeAdapters.entrySet()) {
                CompoundTag payload = incoming.get(entry.getKey());
                if (payload != null && !entry.getValue().equivalent(payload, live.get(entry.getValue()))) {
                    mutatedAdapters.add(entry.getValue());
                }
            }
            replace(player, mutatedAdapters, () -> {
                for (Map.Entry<String, SharableAdapter> entry : activeAdapters.entrySet()) {
                    CompoundTag payload = incoming.get(entry.getKey());
                    if (payload != null && !entry.getValue().equivalent(payload, live.get(entry.getValue()))) {
                        entry.getValue().clear(player);
                        entry.getValue().apply(player, payload, player.registryAccess());
                    }
                }
            }, loginPerformance::recordReplacement);
            LinkedHashMap<String, CompoundTag> currentPayloads = new LinkedHashMap<>();
            long finalCaptureStarted = System.nanoTime();
            int finalCaptures = 0;
            try {
                for (Map.Entry<String, SharableAdapter> entry : activeAdapters.entrySet()) {
                    currentPayloads.put(
                            entry.getKey(), entry.getValue().capture(player, player.registryAccess()));
                    finalCaptures++;
                }
            } finally {
                loginPerformance.recordCapture(
                        finalCaptures, System.nanoTime() - finalCaptureStarted);
            }
            if (WorldIdentities.from(player.serverLevel()).equals(world)) {
                collectWorldBackups(player, world, targetKeys, activeAdapters, currentPayloads, writes);
            }

            List<TransitionJournal.Change> completed = withAfter(changes, currentPayloads);
            TransitionJournal.complete(journal, "committed", completed, null);
            applyArchives(archives, revision);
            snapshotWriter.commitImmediately(player.getUUID(), revision, writes, Optional.of(journal));
            sessions.put(player.getUUID(), new Session(
                    player, player.serverLevel().dimension(), world, gameMode, targetKeys, activeAdapters,
                    current.generation(), new LocationTracker(player)));
            store.completeStartupRecovery(player.getUUID());
        } catch (Exception failure) {
            if (hasPendingCommit(failure)) {
                quarantinedPlayers.add(player.getUUID());
            } else {
                try {
                    rollback(player, live);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    quarantinedPlayers.add(player.getUUID());
                }
                if (journal != null) {
                    try {
                        TransitionJournal.complete(journal, "rolled_back", changes, failure.toString());
                        store.writeRevision(player.getUUID(), revision, journal);
                    } catch (IOException journalFailure) {
                        failure.addSuppressed(journalFailure);
                    }
                }
            }
            throw transitionFailure("Unable to initialize player profile", failure);
        }
    }

    private void resetInterruptedTargets(
            ServerPlayer player,
            Map<String, ProfileKey> targetKeys,
            Map<String, SharableAdapter> activeAdapters) {
        List<SharableAdapter> reset = activeAdapters.entrySet().stream()
                .filter(entry -> store.shouldResetLive(targetKeys.get(entry.getKey())))
                .map(Map.Entry::getValue)
                .toList();
        replace(player, reset, () -> reset.forEach(adapter -> adapter.clear(player)));
    }

    private void reconcile(ServerPlayer player, String reason) throws TransitionException {
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            if ("dimension_change".equals(reason)) {
                throw new TransitionException(
                        "Dimension transition arrived before the player profile working set was initialized");
            }
            initialize(player);
            return;
        }
        State current = state;
        WorldIdentity targetWorld = WorldIdentities.from(player.serverLevel());
        GameModeBucket targetGameMode = gameMode(player, current.config());
        Map<String, SharableAdapter> activeAdapters = activeAdapters(player, current);
        Map<String, ProfileKey> targetKeys = profileKeys(player, targetWorld, current, activeAdapters);

        List<Move> moves = new ArrayList<>();
        for (Map.Entry<String, SharableAdapter> entry : activeAdapters.entrySet()) {
            ProfileKey from = session.profiles().get(entry.getKey());
            ProfileKey to = targetKeys.get(entry.getKey());
            if (from == null || !from.equals(to)) {
                moves.add(new Move(entry.getKey(), entry.getValue(), from, to));
            }
        }
        if (moves.isEmpty()) {
            if (!session.world().equals(targetWorld)
                    && current.config().model().shareHandling().alwaysWriteWorldProfile()) {
                long revision = nextRevision(player);
                try {
                    Map<String, CompoundTag> overrides = Map.of(
                            "last_location", session.location().toPayload());
                    LinkedHashMap<ProfileKey, CompoundTag> writes = new LinkedHashMap<>();
                    collectWorldBackups(
                            player, session.world(), session.profiles(), activeAdapters, overrides, writes);
                    snapshotWriter.enqueue(
                            player.getUUID(), revision, writes, Optional.empty(), "world_backup");
                } catch (RuntimeException failure) {
                    throw new TransitionException("Unable to write world profile backups", failure);
                }
            }
            sessions.put(player.getUUID(), new Session(
                    player, player.serverLevel().dimension(), targetWorld, targetGameMode, targetKeys, activeAdapters,
                    current.generation(), new LocationTracker(player)));
            return;
        }

        closeMenu(player);
        LinkedHashMap<SharableAdapter, CompoundTag> before = capture(
                player, moves.stream().map(Move::adapter).toList());
        if (!session.world().equals(targetWorld)) {
            for (Move move : moves) {
                if (move.shareId().equals("last_location")) {
                    before.put(move.adapter(), session.location().toPayload());
                }
            }
        }
        long revision = nextRevision(player);
        List<TransitionJournal.Change> changes = moves.stream()
                .map(move -> new TransitionJournal.Change(
                        move.shareId(), move.fromKey(), move.toKey(), before.get(move.adapter()), null))
                .toList();
        CompoundTag journal = TransitionJournal.prepared(
                reason, session.world().name(), targetWorld.name(), changes);
        boolean mutationStarted = false;
        Map<String, CompoundTag> incoming = new HashMap<>();
        LinkedHashMap<ProfileKey, CompoundTag> writes = new LinkedHashMap<>();
        List<ArchiveRequest> archives = new ArrayList<>();

        try {
            for (Move move : moves) {
                if (move.fromKey() == null) {
                    continue;
                }
                CompoundTag outgoing = before.get(move.adapter());
                writes.put(move.fromKey(), outgoing);
                if (current.config().model().shareHandling().alwaysWriteWorldProfile()) {
                    ProfileKey worldBackup = new ProfileKey(
                            player.getUUID(), ScopeKey.world(session.world()), move.fromKey().gameMode(), move.shareId());
                    writes.put(worldBackup, outgoing);
                }
            }
            for (Move move : moves) {
                Optional<StoredProfile> stored = snapshotWriter.load(move.toKey());
                if (stored.isPresent()) {
                    incoming.put(move.shareId(), stored.get().payload());
                } else if (move.toKey().scope().type() == ScopeKey.Type.GROUP) {
                    boolean sameWorld = move.fromKey() == null || session.world().equals(targetWorld);
                    CompoundTag migrated = migrateGroupTarget(
                            player, targetWorld, move.toKey(), before.get(move.adapter()), sameWorld,
                            writes, archives);
                    if (migrated != null) {
                        incoming.put(move.shareId(), migrated);
                    }
                }
            }

            List<Move> mutatedMoves = new ArrayList<>();
            for (Move move : moves) {
                CompoundTag payload = incoming.get(move.shareId());
                if (move.fromKey() == null && payload == null) {
                    continue;
                }
                if (payload != null && move.adapter().equivalent(payload, before.get(move.adapter()))) {
                    continue;
                }
                mutatedMoves.add(move);
            }
            mutationStarted = !mutatedMoves.isEmpty();
            replace(player, mutatedMoves.stream().map(Move::adapter).toList(), () -> {
                for (Move move : mutatedMoves) {
                    CompoundTag payload = incoming.get(move.shareId());
                    move.adapter().clear(player);
                    if (payload != null) {
                        move.adapter().apply(player, payload, player.registryAccess());
                    }
                }
            });

            LinkedHashMap<String, CompoundTag> after = new LinkedHashMap<>();
            for (Move move : moves) {
                CompoundTag payload = incoming.get(move.shareId());
                if (payload == null) {
                    payload = move.adapter().capture(player, player.registryAccess());
                    writes.put(move.toKey(), payload);
                }
                after.put(move.shareId(), payload);
            }
            if (WorldIdentities.from(player.serverLevel()).equals(targetWorld)) {
                LinkedHashMap<String, CompoundTag> currentPayloads = new LinkedHashMap<>();
                for (Map.Entry<String, SharableAdapter> entry : activeAdapters.entrySet()) {
                    CompoundTag payload = after.get(entry.getKey());
                    if (payload == null) {
                        payload = entry.getValue().capture(player, player.registryAccess());
                    }
                    currentPayloads.put(entry.getKey(), payload);
                }
                collectWorldBackups(player, targetWorld, targetKeys, activeAdapters, currentPayloads, writes);
            }

            List<TransitionJournal.Change> completed = LiveStateCoordinator.withAfter(changes, after);
            TransitionJournal.complete(journal, "committed", completed, null);
            snapshotWriter.enqueue(
                    player.getUUID(),
                    revision,
                    writes,
                    Optional.of(journal),
                    archives.stream()
                            .map(archive -> new ProfileSnapshotWriter.Archive(
                                    archive.key(), archive.category(), archive.reason()))
                            .toList(),
                    reason);
            sessions.put(player.getUUID(), new Session(
                    player, player.serverLevel().dimension(), targetWorld, targetGameMode, targetKeys, activeAdapters,
                    current.generation(), new LocationTracker(player)));
        } catch (Exception failure) {
            if (hasPendingCommit(failure)) {
                quarantinedPlayers.add(player.getUUID());
                throw transitionFailure("Profile transaction is pending durable recovery", failure);
            }
            Exception rollbackFailure = null;
            if (mutationStarted) {
                try {
                    rollback(player, before);
                } catch (Exception failedRollback) {
                    rollbackFailure = failedRollback;
                    failure.addSuppressed(failedRollback);
                }
            }
            TransitionJournal.complete(journal, "rolled_back", changes, failure.toString());
            if (rollbackFailure != null) {
                quarantine(player, rollbackFailure);
            }
            throw transitionFailure("Profile transaction rolled back", failure);
        }
    }

    private CompoundTag migrateGroupTarget(
            ServerPlayer player,
            WorldIdentity targetWorld,
            ProfileKey groupKey,
            CompoundTag livePayload,
            boolean allowLiveCurrent,
            Map<ProfileKey, CompoundTag> writes,
            List<ArchiveRequest> archives) throws IOException, TransitionException {
        State current = state;
        ProfileMigrationService.MigrationResult result = migrationService.plan(
                player,
                targetWorld,
                groupKey,
                livePayload,
                allowLiveCurrent,
                current.resolver(),
                current.config().model().migration());
        if (result.payload() == null) {
            return null;
        }
        result.archives().forEach(key ->
                archives.add(new ArchiveRequest(key, "conflicts", "group profile migration")));
        writes.put(groupKey, result.payload());
        return result.payload();
    }

    private void reseedCurrent(ServerPlayer player, String reason) throws TransitionException {
        State current = state;
        WorldIdentity world = WorldIdentities.from(player.serverLevel());
        Map<String, SharableAdapter> active = activeAdapters(player, current);
        Map<String, ProfileKey> keys = profileKeys(player, world, current, active);
        long revision = nextRevision(player);
        try {
            snapshotWriter.persist(
                    player, world, keys, active,
                    current.config().model().shareHandling().alwaysWriteWorldProfile(),
                    revision, reason, true);
            sessions.put(player.getUUID(), new Session(
                    player, player.serverLevel().dimension(), world, gameMode(player, current.config()), keys, active,
                    current.generation(), new LocationTracker(player)));
        } catch (IOException | RuntimeException failure) {
            throw new TransitionException("Unable to persist " + reason + " state", failure);
        }
    }

    private void persistCurrent(ServerPlayer player, String reason) throws TransitionException {
        persistCurrent(player, reason, false);
    }

    private void persistCurrent(ServerPlayer player, String reason, boolean asynchronous) throws TransitionException {
        State current = state;
        WorldIdentity world = WorldIdentities.from(player.serverLevel());
        Map<String, SharableAdapter> active = activeAdapters(player, current);
        Map<String, ProfileKey> keys = profileKeys(player, world, current, active);
        long revision = nextRevision(player);
        try {
            snapshotWriter.persist(
                    player, world, keys, active,
                    current.config().model().shareHandling().alwaysWriteWorldProfile(),
                    revision, reason, asynchronous);
        } catch (IOException | RuntimeException failure) {
            throw new TransitionException("Unable to persist player state for " + reason, failure);
        }
    }

    private void persistQuarantined(ServerPlayer player, String reason) throws TransitionException {
        long revision = nextRevision(player);
        try {
            snapshotWriter.preserveQuarantined(
                    player, activeAdapters(player, state), reason, revision);
        } catch (IOException failure) {
            throw new TransitionException("Unable to preserve quarantined player state", failure);
        }
    }

    private void collectWorldBackups(
            ServerPlayer player,
            WorldIdentity world,
            Map<String, ProfileKey> activeKeys,
            Map<String, SharableAdapter> activeAdapters,
            Map<String, CompoundTag> overrides,
            Map<ProfileKey, CompoundTag> writes) {
        ProfileSnapshotWriter.collectWorldBackups(
                player, world, activeKeys, activeAdapters, overrides, writes,
                state.config().model().shareHandling().alwaysWriteWorldProfile());
    }

    private Map<String, ProfileKey> profileKeys(
            ServerPlayer player,
            WorldIdentity world,
            State current,
            Map<String, SharableAdapter> active) {
        GameModeBucket gameMode = gameMode(player, current.config());
        LinkedHashMap<String, ProfileKey> keys = new LinkedHashMap<>();
        for (String share : active.keySet()) {
            ScopeKey scope = current.resolver().resolve(world, share);
            keys.put(share, new ProfileKey(player.getUUID(), scope, gameMode, share));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(keys));
    }

    private Map<String, SharableAdapter> activeAdapters(ServerPlayer player, State current) {
        return AdapterPlan.active(player, current.candidateAdapters());
    }

    private long nextRevision(ServerPlayer player) throws TransitionException {
        try {
            return store.nextRevision(player.getUUID());
        } catch (IOException failure) {
            throw new TransitionException("Unable to allocate profile revision", failure);
        }
    }

    private void withPlayerLock(ServerPlayer player, CheckedAction action) throws TransitionException {
        ReentrantLock lock = playerLocks.computeIfAbsent(player.getUUID(), ignored -> new ReentrantLock());
        if (!activeTransactions.add(player.getUUID())) {
            throw new TransitionException("A nested inventory transaction was rejected for " + player.getUUID());
        }
        if (!lock.tryLock()) {
            activeTransactions.remove(player.getUUID());
            throw new TransitionException("Another inventory transaction is already running for " + player.getUUID());
        }
        try {
            action.run();
        } finally {
            lock.unlock();
            activeTransactions.remove(player.getUUID());
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Inventory isolation must run on the server thread");
        }
    }

    private static GameModeBucket gameMode(ServerPlayer player, RuntimeConfig config) {
        return GameModeBucket.resolve(
                player.gameMode.getGameModeForPlayer().getName(),
                config.model().shareHandling().enableGameModeProfiles());
    }

    private static TransitionException transitionFailure(String message, Exception cause) {
        return cause instanceof TransitionException transition
                ? transition
                : new TransitionException(message, cause);
    }

    private void applyArchives(List<ArchiveRequest> archives, long revision) throws IOException {
        for (ArchiveRequest archive : archives) {
            store.archive(archive.key(), archive.category(), archive.reason(), revision);
        }
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

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws TransitionException;
    }

    private record Move(String shareId, SharableAdapter adapter, ProfileKey fromKey, ProfileKey toKey) {
    }

    private record ArchiveRequest(ProfileKey key, String category, String reason) {
    }

    private record Session(
            ServerPlayer owner,
            ResourceKey<Level> dimension,
            WorldIdentity world,
            GameModeBucket gameMode,
            Map<String, ProfileKey> profiles,
            Map<String, SharableAdapter> activeAdapters,
            long configGeneration,
            LocationTracker location
    ) {
        private Session {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(dimension, "dimension");
            profiles = Map.copyOf(profiles);
            activeAdapters = Map.copyOf(activeAdapters);
            Objects.requireNonNull(location, "location");
        }
    }

    private static final class LocationTracker {
        private String dimension;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;

        private LocationTracker(ServerPlayer player) {
            update(player);
        }

        private void update(ServerPlayer player) {
            dimension = player.serverLevel().dimension().location().toString();
            x = player.getX();
            y = player.getY();
            z = player.getZ();
            yaw = player.getYRot();
            pitch = player.getXRot();
        }

        private CompoundTag toPayload() {
            CompoundTag payload = new CompoundTag();
            payload.putString("dimension", dimension);
            payload.putDouble("x", x);
            payload.putDouble("y", y);
            payload.putDouble("z", z);
            payload.putFloat("yaw", yaw);
            payload.putFloat("pitch", pitch);
            return payload;
        }
    }

    private record State(
            RuntimeConfig config,
            ShareCatalog catalog,
            GroupResolver resolver,
            Set<String> managedShareIds,
            List<SharableAdapter> candidateAdapters,
            long generation
    ) {
        static State create(RuntimeConfig config, AdapterRegistry adapters, long generation) {
            LinkedHashSet<String> catalogLeaves = new LinkedHashSet<>(ShareCatalog.STANDARD_LEAF_SHARES);
            LinkedHashSet<String> managedShares = new LinkedHashSet<>();
            List<SharableAdapter> candidates = AdapterPlan.candidates(config, adapters);
            candidates.forEach(adapter -> {
                String share = adapter.id().getPath();
                catalogLeaves.add(share);
                managedShares.add(share);
            });
            ShareCatalog catalog = new ShareCatalog(catalogLeaves, config.model().shareAliases());
            return new State(
                    config,
                    catalog,
                    config.model().createGroupResolver(catalog),
                    Set.copyOf(managedShares),
                    candidates,
                    generation);
        }
    }

    public record StateSnapshot(
            RuntimeConfig config,
            GroupResolver resolver,
            Set<String> managedShareIds,
            int onlineSessions
    ) {
        public StateSnapshot {
            managedShareIds = Set.copyOf(managedShareIds);
        }
    }

    public record PersistenceMetrics(
            int queueDepth,
            long oldestTaskAgeMillis,
            long p95WriteLatencyMillis,
            long p99WriteLatencyMillis,
            long failures,
            long coalesced,
            long p95CaptureLatencyMillis,
            long p99CaptureLatencyMillis) {
    }

    public record LoginPerformanceMetrics(
            long logins,
            long adapterCaptures,
            long advancementListenerRebuilds,
            long totalCaptureMillis,
            long maxCaptureBatchMillis,
            long totalSynchronizationMillis,
            long maxSynchronizationMillis) {
    }

    public record EditableProfile(
            ProfileKey key,
            String worldName,
            String shareId,
            Optional<CompoundTag> storedPayload,
            CompoundTag liveTemplate,
            long configGeneration
    ) {
        public EditableProfile {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(shareId, "shareId");
            storedPayload = Objects.requireNonNull(storedPayload, "storedPayload").map(CompoundTag::copy);
            liveTemplate = Objects.requireNonNull(liveTemplate, "liveTemplate").copy();
            if (configGeneration < 0) {
                throw new IllegalArgumentException("configGeneration must be non-negative");
            }
        }

        @Override
        public Optional<CompoundTag> storedPayload() {
            return storedPayload.map(CompoundTag::copy);
        }

        @Override
        public CompoundTag liveTemplate() {
            return liveTemplate.copy();
        }
    }

    private static final class LoginPerformanceTracker {
        private final LongAdder logins = new LongAdder();
        private final LongAdder adapterCaptures = new LongAdder();
        private final LongAdder advancementListenerRebuilds = new LongAdder();
        private final LongAdder captureNanos = new LongAdder();
        private final LongAdder synchronizationNanos = new LongAdder();
        private final AtomicLong maxCaptureNanos = new AtomicLong();
        private final AtomicLong maxSynchronizationNanos = new AtomicLong();

        private void recordLogin() {
            logins.increment();
        }

        private void recordCapture(int captures, long elapsedNanos) {
            adapterCaptures.add(captures);
            captureNanos.add(elapsedNanos);
            maxCaptureNanos.accumulateAndGet(elapsedNanos, Math::max);
        }

        private void recordReplacement(LiveStateCoordinator.ReplacementMetrics metrics) {
            if (metrics.advancementListenersRebuilt()) {
                advancementListenerRebuilds.increment();
            }
            synchronizationNanos.add(metrics.synchronizationNanos());
            maxSynchronizationNanos.accumulateAndGet(metrics.synchronizationNanos(), Math::max);
        }

        private LoginPerformanceMetrics snapshot() {
            return new LoginPerformanceMetrics(
                    logins.sum(),
                    adapterCaptures.sum(),
                    advancementListenerRebuilds.sum(),
                    nanosToMillis(captureNanos.sum()),
                    nanosToMillis(maxCaptureNanos.get()),
                    nanosToMillis(synchronizationNanos.sum()),
                    nanosToMillis(maxSynchronizationNanos.get()));
        }

        private static long nanosToMillis(long nanos) {
            return java.time.Duration.ofNanos(nanos).toMillis();
        }
    }
}
