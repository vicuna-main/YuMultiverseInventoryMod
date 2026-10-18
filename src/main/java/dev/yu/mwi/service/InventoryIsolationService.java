package dev.yu.mwi.service;

import dev.yu.mwi.api.AdapterRegistry;
import dev.yu.mwi.api.AdapterIds;
import dev.yu.mwi.api.ItemEditSlotPolicy;
import dev.yu.mwi.api.SharableAdapter;
import dev.yu.mwi.adapter.vanilla.AdvancementAdapter;
import dev.yu.mwi.adapter.vanilla.AdvancementCompatibilityReport;
import dev.yu.mwi.adapter.vanilla.AdvancementPreparation;
import dev.yu.mwi.adapter.vanilla.RespawnLocationPayload;
import dev.yu.mwi.config.RuntimeConfig;
import dev.yu.mwi.config.MwiConfig;
import dev.yu.mwi.compat.ItemCompatibilityIssue;
import dev.yu.mwi.compat.ItemCompatibilityReport;
import dev.yu.mwi.compat.ItemPayloadPreparation;
import dev.yu.mwi.compat.ItemProfileCompatibility;
import dev.yu.mwi.domain.GameModeBucket;
import dev.yu.mwi.domain.GroupResolver;
import dev.yu.mwi.domain.ProfileKey;
import dev.yu.mwi.domain.ScopeKey;
import dev.yu.mwi.domain.ShareCatalog;
import dev.yu.mwi.domain.WorldIdentity;
import dev.yu.mwi.storage.CacheStats;
import dev.yu.mwi.storage.CompatibilityArchiveEntry;
import dev.yu.mwi.storage.NbtProfileStore;
import dev.yu.mwi.storage.PendingTransactionException;
import dev.yu.mwi.storage.ProfileStore;
import dev.yu.mwi.storage.ProfileArchive;
import dev.yu.mwi.storage.PlayerProfileIds;
import dev.yu.mwi.storage.StoredProfile;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
    private final dev.yu.mwi.recovery.RecoveryCenter recoveryCenter;
    private final Set<UUID> recoveryCheckpoints = new java.util.HashSet<>();
    private final Map<UUID, RecoverySessionToken> recoverySessionTokens = new HashMap<>();
    private long nextRecoverySessionToken;
    private record RecoverySessionToken(Session session, long token) {}
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, ReentrantLock> playerLocks = new ConcurrentHashMap<>();
    private final Set<UUID> activeTransactions = ConcurrentHashMap.newKeySet();
    private final Set<UUID> quarantinedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, RecoveryHold> recoveryHolds = new ConcurrentHashMap<>();
    private final Map<UUID, RestoreCommandTask> restoreCommands = new HashMap<>();
    public record RestoreCommandTask(UUID id, String selector, java.util.concurrent.CompletableFuture<String> completion,
                                     java.util.concurrent.CompletableFuture<String> work) {}
    private final Set<ServerPlayer> retiredRecoveryPlayers = Collections.synchronizedSet(
            Collections.newSetFromMap(new java.util.WeakHashMap<>()));
    private final Map<UUID, Long> backgroundRecoveryAt = new HashMap<>();
    private final Set<UUID> backgroundRecoveryRunning = new HashSet<>();
    private final RecoveryExecutor recoveryExecutor;
    private final ThreadLocal<Boolean> recoveryInternal = ThreadLocal.withInitial(() -> false);
    private boolean stopping;
    private final List<Long> dimensionCaptureLatenciesNanos = new ArrayList<>();
    private final List<Long> revisionAllocationLatenciesNanos = new ArrayList<>();
    private final LongAdder quarantineEvents = new LongAdder();
    private final LoginPerformanceTracker loginPerformance = new LoginPerformanceTracker();
    private final CompatibilityDiagnostics compatibilityDiagnostics = new CompatibilityDiagnostics();
    private final AtomicLong missingItemBatchCursor = new AtomicLong();
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
        this.recoveryExecutor = new RecoveryExecutor(config.storage().ioConcurrency(), config.storage().queueCapacity(),
                java.time.Duration.ofSeconds(config.storage().flushTimeoutSeconds()));
        this.migrationService = new ProfileMigrationService(server, snapshotWriter, logger);
        this.recoveryService = new RevisionRecoveryService(store);
        this.state = State.create(config, adapters, 1L);
        store.recoveryCandidates().forEach(owner -> backgroundRecoveryAt.put(owner, 0L));
        this.recoveryCenter = new dev.yu.mwi.recovery.RecoveryCenter(store,
                new dev.yu.mwi.recovery.RecoveryCenter.Host() {
                    @Override public void awaitPendingWrites(UUID owner) throws IOException {
                        requireServerThread();
                        snapshotWriter.flush(owner);
                    }
                    @Override public String sessionToken(ServerPlayer player) throws IOException {
                        try {
                            Session session = requireRecoverySession(player);
                            RecoverySessionToken token = recoverySessionTokens.get(player.getUUID());
                            if (token == null || token.session() != session) {
                                token = new RecoverySessionToken(session, Math.incrementExact(nextRecoverySessionToken));
                                nextRecoverySessionToken = token.token(); recoverySessionTokens.put(player.getUUID(), token);
                            }
                            return Long.toString(token.token());
                        } catch (TransitionException failure) { throw new IOException(failure.getMessage(), failure); }
                    }
                    @Override public CompoundTag scope(ServerPlayer player) throws IOException {
                        try { return recoveryScope(requireRecoverySession(player)); }
                        catch (TransitionException failure) { throw new IOException(failure.getMessage(), failure); }
                    }
                    @Override public void commit(ServerPlayer player, CompoundTag scope,
                            Optional<CompoundTag> expectedInventory, Optional<CompoundTag> inventory,
                            dev.yu.mwi.storage.RecoveryMutation mutation) throws IOException {
                        commitRecovery(player, scope, expectedInventory, inventory, mutation);
                    }
                });
    }

    public dev.yu.mwi.recovery.RecoveryCenter recoveryCenter() { return recoveryCenter; }

    public void invalidateRecoveryConfirmation(ServerPlayer player) {
        requireServerThread();
        recoverySessionTokens.remove(player.getUUID());
    }

    private Session requireRecoverySession(ServerPlayer player) throws TransitionException {
        requireServerThread();
        Session session = requireEditingSession(player);
        if (session.phase() != SessionPhase.ACTIVE
                || !session.dimension().equals(player.serverLevel().dimension())
                || !session.world().equals(WorldIdentities.from(player.serverLevel()))
                || session.gameMode() != gameMode(player, state.config())
                || session.configGeneration() != state.generation())
            throw new TransitionException("世界或共享配置已变化，请重新打开恢复中心");
        requireEditingAdapter(session, "inventory_contents");
        return session;
    }

    private static CompoundTag recoveryScope(Session session) { return recoveryScope(session.profiles()); }

    private static CompoundTag recoveryScope(Map<String, ProfileKey> profiles) {
        CompoundTag scope = new CompoundTag();
        profiles.forEach((share, key) -> {
            if (ItemProfileCompatibility.supports(share)) {
                CompoundTag tag = new CompoundTag();
                tag.putString("scope_type", key.scope().type().name()); tag.putString("scope_id", key.scope().id());
                tag.putString("game_mode", key.gameMode().name()); scope.put(share, tag);
            }
        });
        return scope;
    }

    private void commitRecovery(ServerPlayer player, CompoundTag expectedScope,
            Optional<CompoundTag> expectedInventory, Optional<CompoundTag> inventory,
            dev.yu.mwi.storage.RecoveryMutation mutation) throws IOException {
        requireServerThread();
        boolean[] committed = {false};
        try {
            withPlayerLock(player, () -> {
                Session session = requireRecoverySession(player);
                if (!expectedScope.equals(recoveryScope(session))) throw new TransitionException("恢复范围已变化，请重新预览");
                try {
                    snapshotWriter.flush(player.getUUID());
                    SharableAdapter inventoryAdapter = requireEditingAdapter(session, "inventory_contents");
                    CompoundTag liveInventory = inventoryAdapter.capture(player, player.registryAccess());
                    if (expectedInventory.isPresent() && !expectedInventory.orElseThrow().equals(liveInventory))
                        throw new IOException("背包已变化，请重新预览，未发放任何物品");
                    LinkedHashMap<String, CompoundTag> captured = new LinkedHashMap<>();
                    LinkedHashMap<ProfileKey, CompoundTag> writes = new LinkedHashMap<>();
                    LinkedHashMap<String, SharableAdapter> itemAdapters = new LinkedHashMap<>();
                    session.activeAdapters().forEach((share, adapter) -> {
                        if (ItemProfileCompatibility.supports(share)) {
                            itemAdapters.put(share, adapter); captured.put(share, adapter.capture(player, player.registryAccess()));
                        }
                    });
                    inventory.ifPresent(after -> captured.put("inventory_contents", after));
                    captured.forEach((share, payload) -> writes.put(session.profiles().get(share), payload));
                    collectWorldBackups(player, session.world(), session.profiles(), itemAdapters, captured, writes);
                    long revision = store.nextRevision(player.getUUID());
                    store.commitRecovery(player.getUUID(), revision, writes, mutation);
                    committed[0] = true;
                    snapshotWriter.refreshWorkingSetIfLoaded(player.getUUID(), revision, writes);
                    if (inventory.isPresent()) {
                        replace(player, List.of(inventoryAdapter), () -> inventoryAdapter.apply(
                                player, inventory.orElseThrow(), player.registryAccess()));
                        if (!inventory.orElseThrow().equals(inventoryAdapter.capture(player, player.registryAccess())))
                            throw new IOException("恢复应用后背包回读不一致");
                    }
                    recoveryCheckpoints.add(player.getUUID());
                    try { dev.yu.mwi.recovery.RecoveryCheckpoint.save(player, mutation.operationId()); }
                    finally { recoveryCheckpoints.remove(player.getUUID()); }
                } catch (IOException failure) { throw new TransitionException(failure.getMessage(), failure); }
            });
        } catch (TransitionException | RuntimeException failure) {
            if (committed[0] || hasPendingCommit(failure)) {
                quarantine(player, failure); // Never roll back/regrant after durable commit; login uses newest profiles.
            }
            throw new IOException(committed[0] || hasPendingCommit(failure)
                    ? "恢复已写入凭证，应用待核验；请重启服务器完成续接，禁止重复补发" : failure.getMessage(), failure);
        }
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
        if (isRecoveryHeld(player) || !sessions.containsKey(player.getUUID())) {
            beginRecoveryHold(player);
            return;
        }
        Session existing = sessions.get(player.getUUID());
        if (existing != null && existing.owner() == player) {
            if (quarantinedPlayers.contains(player.getUUID())) {
                throw new TransitionException("A quarantined inventory session cannot be activated");
            }
            if (existing.phase() == SessionPhase.PROVISIONAL) {
                withPlayerLock(player, () -> completeProvisionalLogin(player));
                quarantinedPlayers.remove(player.getUUID());
            }
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

    public void dimensionChanged(
            ServerPlayer player,
            ResourceKey<Level> fromDimension,
            ResourceKey<Level> toDimension) throws TransitionException {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(fromDimension, "fromDimension");
        Objects.requireNonNull(toDimension, "toDimension");
        if (isRecoveryHeld(player)) return;
        if (activeTransactions.contains(player.getUUID())) {
            return;
        }
        long started = System.nanoTime();
        try {
            Session existing = sessions.get(player.getUUID());
            if (existing != null && existing.owner() != player) {
                sessions.remove(player.getUUID(), existing);
                try {
                    snapshotWriter.flush(player.getUUID());
                } catch (IOException failure) {
                    throw new TransitionException(
                            "Unable to finish earlier profile persistence before dimension transition", failure);
                }
            }
            withPlayerLock(player, () -> {
                Session current = sessions.get(player.getUUID());
                if (current == null) {
                    bootstrapProvisionalSession(player, fromDimension, toDimension);
                } else {
                    if (current.phase() == SessionPhase.PROVISIONAL) {
                        logPreLoginTransition(player, fromDimension, toDimension, current, "reconcile");
                    }
                    reconcile(player, toDimension, "dimension_change");
                }
            });
        } finally {
            recordDimensionCapture(System.nanoTime() - started);
        }
    }

    /** Cheap hybrid-server fallback: only dimension/game-mode/config generation comparisons on the normal path. */
    public void tick(ServerPlayer player) throws TransitionException {
        requireServerThread();
        if (isRecoveryHeld(player)) {
            tickRecoveryHold(player);
            return;
        }
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
        if (state.config().storage().automaticRecovery()
                && backgroundRecoveryAt.getOrDefault(player.getUUID(), Long.MAX_VALUE) <= System.nanoTime()
                && backgroundRecoveryRunning.add(player.getUUID())) {
            backgroundRecoveryAt.put(player.getUUID(), nextRecoveryTime());
            snapshotWriter.inspectInQueue(player.getUUID(), true).whenComplete((inspection, failure) -> server.execute(() -> {
                backgroundRecoveryRunning.remove(player.getUUID());
                if (stopping || isRecoveryHeld(player) || sessions.get(player.getUUID()) != session) return;
                if (failure != null) { logger.warn("Background recovery still waiting for {}", player.getUUID(), failure); return; }
                if (!inspection.ready(Set.copyOf(session.profiles().values()))) {
                    quarantine(player, new IOException("Active profile dependencies require recovery")); return;
                }
                snapshotWriter.mergeInspection(player.getUUID(), inspection);
                if (inspection.unavailable().isEmpty()) backgroundRecoveryAt.remove(player.getUUID());
            }));
        }
    }

    public void respawned(ServerPlayer player) throws TransitionException {
        requireServerThread();
        if (isRecoveryHeld(player)) return;
        withPlayerLock(player, () -> reseedCurrent(player, "respawn"));
    }

    public void logout(ServerPlayer player) throws TransitionException {
        requireServerThread();
        RecoveryHold newer = recoveryHolds.get(player.getUUID());
        if (newer != null && newer.owner != player) return;
        if (isRecoveryHeld(player)) {
            RecoveryHold hold = recoveryHolds.get(player.getUUID());
            if (hold == null) return;
            hold.disconnected = true;
            if (hold.completion != null) hold.completion.completeExceptionally(new IOException("玩家已离线，恢复任务保留，待下次登录核验"));
            retiredRecoveryPlayers.add(player);
            recoveryHolds.remove(player.getUUID(), hold);
            backgroundRecoveryAt.put(player.getUUID(), nextRecoveryTime());
            sessions.remove(player.getUUID());
            recoverySessionTokens.remove(player.getUUID());
            adapters.adapters().forEach(adapter -> adapter.sessionClosed(player));
            return;
        }
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
                if (session == null) {
                    // No MWI session ever established ownership of this live snapshot. Preserve it for
                    // diagnostics/recovery, but never derive a canonical scope from currentWorld.
                    persistQuarantined(player, "disconnect without an owned inventory session");
                    return;
                }
                reconcile(player, "logout_reconcile");
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
            recoverySessionTokens.remove(player.getUUID());
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
        if (isRecoveryHeld(player)) return;
        if (recoveryCheckpoints.contains(player.getUUID())) return;
        Session session = sessions.get(player.getUUID());
        if (session == null || session.owner() != player) {
            throw new TransitionException(
                    "Refusing SaveToFile checkpoint without a preloaded owned inventory session");
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
        MwiConfig.AdvancementMigration advancementMigration =
                candidate.model().advancementMigration();
        details.add("Advancement migration dry-run: aliases="
                + advancementMigration.aliases().size()
                + ", criterion scopes=" + advancementMigration.criterionAliases().size());
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
                        player,
                        revision,
                        session.world(),
                        session.profiles(),
                        session.activeAdapters(),
                        state.config().model().advancementMigration());
            } catch (RevisionRecoveryService.RecoveryException failure) {
                if (failure.unsafe()) {
                    recordQuarantine(player.getUUID());
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
        List<Long> revisionAllocations;
        synchronized (revisionAllocationLatenciesNanos) {
            revisionAllocations = revisionAllocationLatenciesNanos.stream().sorted().toList();
        }
        return new PersistenceMetrics(
                metrics.queueDepth(),
                metrics.oldestTaskAge().toMillis(),
                metrics.p95WriteLatency().toMillis(),
                metrics.p99WriteLatency().toMillis(),
                metrics.failures(),
                metrics.coalesced(),
                metrics.queued(),
                metrics.rejected(),
                metrics.activeWorkers(),
                metrics.p95CommitLatency().toMillis(),
                metrics.p99CommitLatency().toMillis(),
                quarantinedPlayers.size(),
                quarantineEvents.sum(),
                percentileMillis(revisionAllocations, 0.95),
                percentileMillis(revisionAllocations, 0.99),
                percentileMillis(captures, 0.95),
                percentileMillis(captures, 0.99));
    }

    public LoginPerformanceMetrics loginPerformanceMetrics() {
        return loginPerformance.snapshot();
    }

    public CompatibilityMetrics compatibilityMetrics() {
        return compatibilityDiagnostics.snapshot();
    }

    /** Resolves a cached player name without triggering a remote profile lookup. */
    public UUID resolveKnownPlayerName(String name) throws TransitionException {
        requireServerThread();
        String requested = Objects.requireNonNull(name, "name").strip();
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("player name must not be blank");
        }
        try {
            LinkedHashSet<UUID> candidates = new LinkedHashSet<>(store.listPlayerProfileIds().playerIds());
            server.getPlayerList().getPlayers().forEach(player -> candidates.add(player.getUUID()));
            List<UUID> matches = candidates.stream()
                    .filter(playerId -> {
                        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                        return online != null && online.getGameProfile().getName().equalsIgnoreCase(requested)
                                || server.getProfileCache() != null && server.getProfileCache().get(playerId)
                                        .map(profile -> profile.getName().equalsIgnoreCase(requested)).orElse(false);
                    })
                    .toList();
            if (matches.isEmpty()) {
                throw new IllegalArgumentException(
                        "未知玩家名称 '" + requested + "'；未创建身份或空背包，请核对已有用户名缓存");
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException(
                        "玩家名称 '" + requested + "' 对应多个历史身份；请先核对用户名缓存，不会猜测身份");
            }
            return matches.getFirst();
        } catch (IOException failure) {
            throw new TransitionException("Unable to inspect known player profiles", failure);
        }
    }

    /** Side-effect-free inspection of every canonical bed-spawn profile for a player. */
    public java.util.concurrent.CompletableFuture<List<String>> knownPlayerNames() {
        requireServerThread();
        return recoveryExecutor.detached(() -> store.listPlayerProfileIds().playerIds()).thenCompose(ids -> {
            var result = new java.util.concurrent.CompletableFuture<List<String>>();
            server.execute(() -> {
                java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                server.getPlayerList().getPlayers().forEach(player -> names.add(player.getGameProfile().getName()));
                if (server.getProfileCache() != null) ids.forEach(id -> server.getProfileCache().get(id)
                        .ifPresent(profile -> names.add(profile.getName())));
                result.complete(List.copyOf(names));
            });
            return result;
        });
    }

    public java.util.concurrent.CompletableFuture<List<String>> restoreTargets(UUID owner) {
        requireServerThread();
        RecoveryHold hold = recoveryHolds.get(owner); Session session = sessions.get(owner);
        Set<ProfileKey> active = hold != null && hold.required != null ? hold.required
                : session == null ? Set.of() : Set.copyOf(session.profiles().values());
        java.time.ZoneId zone = restoreTimeZone();
        return recoveryExecutor.detached(() -> {
            Set<ProfileKey> required = active.isEmpty() ? RestorePlans.keys(store.restoreContext(owner), owner) : active;
            var points = store.restorePoints(owner);
            return points.stream().filter(point -> point.completeFor(required)).limit(100)
                    .map(point -> RestorePointSelector.label(point, zone, points)).distinct().toList();
        });
    }

    /** Side-effect-free inspection of every canonical bed-spawn profile for a player. */
    public BedSpawnRepairReport inspectBedSpawn(UUID playerId) throws TransitionException {
        requireServerThread();
        Objects.requireNonNull(playerId, "playerId");
        try {
            Map<ProfileKey, StoredProfile> profiles = store.inspectPlayerProfiles(playerId);
            List<BedSpawnProfileInspection> inspected = inspectBedSpawnProfiles(profiles);
            return new BedSpawnRepairReport(inspected.size(), inspected.size(), 0, 0L, false, inspected);
        } catch (IOException | RuntimeException failure) {
            throw new TransitionException("Unable to inspect bed-spawn profiles for " + playerId, failure);
        }
    }

    /** Atomically archives and clears selected canonical bed-spawn profiles for an offline player. */
    public BedSpawnRepairReport clearBedSpawn(UUID playerId, String scopeSelector) throws TransitionException {
        requireServerThread();
        Objects.requireNonNull(playerId, "playerId");
        String selector = normalizeBedSpawnScopeSelector(scopeSelector);
        if (server.getPlayerList().getPlayer(playerId) != null || sessions.containsKey(playerId)) {
            throw new TransitionException(
                    "Bed-spawn clear requires the player to be offline with its inventory session fully closed");
        }
        try {
            snapshotWriter.flush(playerId);
            store.invalidatePlayer(playerId);
            Map<ProfileKey, StoredProfile> profiles = store.inspectPlayerProfiles(playerId);
            List<BedSpawnProfileInspection> all = inspectBedSpawnProfiles(profiles);
            List<BedSpawnProfileInspection> selected = all.stream()
                    .filter(entry -> matchesBedSpawnScope(entry.key(), selector))
                    .toList();
            LinkedHashMap<ProfileKey, CompoundTag> writes = new LinkedHashMap<>();
            List<ProfileArchive> archives = new ArrayList<>();
            List<TransitionJournal.Change> changes = new ArrayList<>();
            CompoundTag cleared = RespawnLocationPayload.clearedPayload();
            for (BedSpawnProfileInspection inspection : selected) {
                if (!inspection.requiresClear()) {
                    continue;
                }
                StoredProfile original = profiles.get(inspection.key());
                writes.put(inspection.key(), cleared.copy());
                CompoundTag details = new CompoundTag();
                details.putString("scope", inspection.scopeSelector());
                details.putString("game_mode", inspection.key().gameMode().name().toLowerCase(java.util.Locale.ROOT));
                details.putLong("profile_revision", original.revision());
                inspection.dimension().ifPresent(value -> details.putString("dimension", value));
                inspection.validationError().ifPresent(value -> details.putString("validation_error", value));
                archives.add(new ProfileArchive(
                        inspection.key(), "bed_spawn", "operator cleared bed-spawn profile", details));
                changes.add(new TransitionJournal.Change(
                        AdapterIds.BED_SPAWN.getPath(), inspection.key(), inspection.key(),
                        original.payload(), cleared));
            }
            if (writes.isEmpty()) {
                return new BedSpawnRepairReport(all.size(), selected.size(), 0, 0L, false, selected);
            }

            long revision = store.nextRevision(playerId);
            CompoundTag journal = TransitionJournal.prepared(
                    "offline_bed_spawn_clear", "offline", selector, changes);
            TransitionJournal.complete(journal, "committed", changes, null);
            store.commitBatch(playerId, revision, writes, Optional.of(journal), archives);
            store.invalidatePlayer(playerId);
            snapshotWriter.reloadWorkingSetIfLoaded(playerId);
            return new BedSpawnRepairReport(
                    all.size(), selected.size(), writes.size(), revision, true, selected);
        } catch (IOException | RuntimeException failure) {
            if (hasPendingCommit(failure)) {
                store.markUncleanShutdown();
            }
            throw new TransitionException("Unable to clear bed-spawn profiles for " + playerId, failure);
        }
    }

    private List<BedSpawnProfileInspection> inspectBedSpawnProfiles(Map<ProfileKey, StoredProfile> profiles) {
        return profiles.entrySet().stream()
                .filter(entry -> entry.getKey().shareId().equals(AdapterIds.BED_SPAWN.getPath()))
                .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(ProfileKey::toString)))
                .map(entry -> inspectBedSpawnProfile(entry.getKey(), entry.getValue()))
                .toList();
    }

    private BedSpawnProfileInspection inspectBedSpawnProfile(ProfileKey key, StoredProfile profile) {
        CompoundTag raw = profile.payload();
        Optional<String> storedDimension = raw.contains("dimension", Tag.TAG_STRING)
                && !raw.getString("dimension").isBlank()
                ? Optional.of(raw.getString("dimension"))
                : Optional.empty();
        Optional<ResourceKey<Level>> storedDimensionKey = storedDimension.flatMap(value -> {
            try {
                return Optional.of(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(value)));
            } catch (RuntimeException invalid) {
                return Optional.empty();
            }
        });
        Optional<Boolean> storedDimensionLoaded = storedDimensionKey
                .map(dimension -> server.getLevel(dimension) != null);
        try {
            RespawnLocationPayload payload = RespawnLocationPayload.decode(raw);
            Optional<String> dimension = payload.target()
                    .map(target -> target.dimension().location().toString());
            Optional<Boolean> loaded = payload.present() ? storedDimensionLoaded : Optional.empty();
            return new BedSpawnProfileInspection(
                    key, profile.revision(), payload.present(), dimension, loaded, Optional.empty());
        } catch (RuntimeException invalid) {
            return new BedSpawnProfileInspection(
                    key, profile.revision(), false, storedDimension, storedDimensionLoaded,
                    Optional.of(meaningfulMessage(invalid)));
        }
    }

    private static String normalizeBedSpawnScopeSelector(String scopeSelector) {
        String selector = Objects.requireNonNull(scopeSelector, "scopeSelector").strip();
        if (selector.equalsIgnoreCase("all")) {
            return "all";
        }
        int separator = selector.indexOf(':');
        if (separator <= 0 || separator == selector.length() - 1) {
            throw new IllegalArgumentException(
                    "scope must be all, world:<id>, group:<id>, or global:global");
        }
        String type = selector.substring(0, separator).toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("world", "group", "global").contains(type)) {
            throw new IllegalArgumentException(
                    "scope must be all, world:<id>, group:<id>, or global:global");
        }
        String id = selector.substring(separator + 1);
        if (type.equals("global") && !id.equals(ScopeKey.GLOBAL_ID)) {
            throw new IllegalArgumentException("global scope must be global:global");
        }
        return type + ':' + id;
    }

    private static boolean matchesBedSpawnScope(ProfileKey key, String selector) {
        return selector.equals("all") || bedSpawnScopeSelector(key).equals(selector);
    }

    private static String bedSpawnScopeSelector(ProfileKey key) {
        return key.scope().type().name().toLowerCase(java.util.Locale.ROOT) + ':' + key.scope().id();
    }

    /** Scans or atomically repairs every stored advancement profile for an online or offline player. */
    public AdvancementRepairReport repairAdvancements(UUID playerId, boolean apply) throws TransitionException {
        requireServerThread();
        Objects.requireNonNull(playerId, "playerId");
        try {
            if (apply) {
                snapshotWriter.flush(playerId);
            }
            Map<ProfileKey, StoredProfile> profiles = store.inspectPlayerProfiles(playerId);
            SharableAdapter configured = adapters.require(AdapterIds.ADVANCEMENTS);
            if (!(configured instanceof AdvancementAdapter advancementAdapter)) {
                throw new IllegalStateException("The configured advancements adapter cannot repair advancements");
            }

            LinkedHashMap<ProfileKey, CompoundTag> writes = new LinkedHashMap<>();
            LinkedHashMap<ProfileKey, StoredProfile> originals = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();
            int scanned = 0;
            int aliasesMigrated = 0;
            int criteriaAliasesMigrated = 0;
            int staleAdvancements = 0;
            int staleCriteria = 0;
            for (Map.Entry<ProfileKey, StoredProfile> entry : profiles.entrySet().stream()
                    .filter(entry -> entry.getKey().shareId().equals(AdapterIds.ADVANCEMENTS.getPath()))
                    .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(ProfileKey::toString)))
                    .toList()) {
                scanned++;
                try {
                    AdvancementPreparation preparation = advancementAdapter.prepare(
                            server.getAdvancements(),
                            entry.getValue().payload(),
                            state.config().model().advancementMigration());
                    AdvancementCompatibilityReport report = preparation.report();
                    aliasesMigrated += report.advancementAliasesMigrated();
                    criteriaAliasesMigrated += report.criterionAliasesMigrated();
                    staleAdvancements += report.staleAdvancementsSkipped();
                    staleCriteria += report.staleCriteriaSkipped();
                    if (report.changed()) {
                        writes.put(entry.getKey(), preparation.payload());
                        originals.put(entry.getKey(), entry.getValue());
                    }
                } catch (RuntimeException corrupt) {
                    errors.add(entry.getKey() + ": " + meaningfulMessage(corrupt));
                }
            }

            if (!apply || writes.isEmpty() || !errors.isEmpty()) {
                return new AdvancementRepairReport(
                        scanned, writes.size(), aliasesMigrated, criteriaAliasesMigrated,
                        staleAdvancements, staleCriteria, 0L, false, errors);
            }

            long revision = store.nextRevision(playerId);
            List<TransitionJournal.Change> changes = new ArrayList<>();
            for (Map.Entry<ProfileKey, CompoundTag> write : writes.entrySet()) {
                StoredProfile original = originals.get(write.getKey());
                AdvancementPreparation preparation = advancementAdapter.prepare(
                        server.getAdvancements(),
                        original.payload(),
                        state.config().model().advancementMigration());
                store.archive(
                        write.getKey(), "compatibility", compatibilityReason(preparation.report()), revision);
                changes.add(new TransitionJournal.Change(
                        AdapterIds.ADVANCEMENTS.getPath(), write.getKey(), write.getKey(),
                        original.payload(), write.getValue()));
            }
            CompoundTag journal = TransitionJournal.prepared(
                    "offline_advancement_repair", "offline", "offline", changes);
            TransitionJournal.complete(journal, "committed", changes, null);
            store.commitBatch(playerId, revision, writes, Optional.of(journal));
            store.invalidatePlayer(playerId);
            snapshotWriter.refreshWorkingSetIfLoaded(playerId, revision, writes);
            return new AdvancementRepairReport(
                    scanned, writes.size(), aliasesMigrated, criteriaAliasesMigrated,
                    staleAdvancements, staleCriteria, revision, true, errors);
        } catch (IOException | RuntimeException failure) {
            if (hasPendingCommit(failure)) {
                ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                if (online == null) {
                    store.markUncleanShutdown();
                } else {
                    quarantine(online, failure);
                }
            }
            throw new TransitionException("Unable to repair advancement profiles for " + playerId, failure);
        }
    }

    /** Side-effect-free scan or atomic offline repair for every item-bearing canonical profile. */
    public MissingItemRepairReport repairMissingItems(UUID playerId, boolean apply) throws TransitionException {
        requireServerThread();
        Objects.requireNonNull(playerId, "playerId");
        try {
            if (apply) {
                snapshotWriter.flush(playerId);
            }
            Map<ProfileKey, StoredProfile> profiles = store.inspectPlayerProfiles(playerId);
            LinkedHashMap<ProfileKey, CompoundTag> writes = new LinkedHashMap<>();
            List<ProfileArchive> archives = new ArrayList<>();
            List<TransitionJournal.Change> changes = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            Map<String, Integer> byNamespace = new java.util.TreeMap<>();
            Map<String, Integer> byShare = new java.util.TreeMap<>();
            int scanned = 0;
            int quarantined = 0;
            int missingItems = 0;
            int missingComponents = 0;
            int aliases = 0;
            for (Map.Entry<ProfileKey, StoredProfile> entry : profiles.entrySet().stream()
                    .filter(candidate -> ItemProfileCompatibility.supports(candidate.getKey().shareId()))
                    .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(ProfileKey::toString)))
                    .toList()) {
                scanned++;
                try {
                    ItemPayloadPreparation preparation = ItemProfileCompatibility.prepare(
                            entry.getKey().shareId(), entry.getValue().payload(), server.registryAccess(),
                            state.config().model().itemMigration());
                    ItemCompatibilityReport report = preparation.report();
                    quarantined += report.issues().size();
                    missingItems += Math.toIntExact(report.missingItems());
                    missingComponents += Math.toIntExact(report.missingComponents());
                    aliases += report.aliasesApplied();
                    report.issues().forEach(issue -> {
                        String namespace = issue.itemId() == null ? "unknown" : issue.itemId().getNamespace();
                        byNamespace.merge(namespace, 1, Integer::sum);
                        byShare.merge(entry.getKey().shareId(), 1, Integer::sum);
                    });
                    if (report.changed()) {
                        writes.put(entry.getKey(), preparation.payload());
                        archives.add(new ProfileArchive(
                                entry.getKey(), "missing_items", itemCompatibilityReason(report),
                                itemArchiveDetails(
                                        entry.getKey(), entry.getValue(), report,
                                        server.getServerVersion(), runtimeModVersion())));
                        changes.add(new TransitionJournal.Change(
                                entry.getKey().shareId(), entry.getKey(), entry.getKey(),
                                entry.getValue().payload(), preparation.payload()));
                    }
                } catch (RuntimeException corrupt) {
                    errors.add(entry.getKey() + ": " + meaningfulMessage(corrupt));
                }
            }
            if (!apply || writes.isEmpty() || !errors.isEmpty()) {
                return new MissingItemRepairReport(
                        scanned, writes.size(), quarantined, missingItems, missingComponents, aliases,
                        0L, false, byNamespace, byShare, errors);
            }

            long revision = store.nextRevision(playerId);
            CompoundTag journal = TransitionJournal.prepared(
                    "offline_missing_item_repair", "offline", "offline", changes);
            TransitionJournal.complete(journal, "committed", changes, null);
            store.commitBatch(playerId, revision, writes, Optional.of(journal), archives);
            store.invalidatePlayer(playerId);
            snapshotWriter.refreshWorkingSetIfLoaded(playerId, revision, writes);
            return new MissingItemRepairReport(
                    scanned, writes.size(), quarantined, missingItems, missingComponents, aliases,
                    revision, true, byNamespace, byShare, errors);
        } catch (IOException | RuntimeException failure) {
            compatibilityDiagnostics.missingItemRepairFailures.increment();
            if (hasPendingCommit(failure)) {
                ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                if (online == null) {
                    store.markUncleanShutdown();
                } else {
                    quarantine(online, failure);
                }
            }
            throw new TransitionException("Unable to repair missing items for " + playerId, failure);
        }
    }

    public MissingItemBatchRepairReport repairMissingItemsAll(boolean apply) throws TransitionException {
        requireServerThread();
        final int maxPlayers = 32;
        final long deadline = System.nanoTime() + java.time.Duration.ofMillis(100).toNanos();
        try {
            PlayerProfileIds available = store.listPlayerProfileIds();
            int availableCount = available.playerIds().size();
            int start = availableCount == 0
                    ? 0
                    : Math.floorMod((int) missingItemBatchCursor.get(), availableCount);
            int processed = 0;
            int scannedProfiles = 0;
            int modifiedProfiles = 0;
            int quarantinedStacks = 0;
            int appliedPlayers = 0;
            List<String> errors = new ArrayList<>();
            while (processed < availableCount) {
                if (processed >= maxPlayers || System.nanoTime() >= deadline) {
                    break;
                }
                UUID playerId = available.playerIds().get((start + processed) % availableCount);
                processed++;
                try {
                    MissingItemRepairReport report = repairMissingItems(playerId, apply);
                    scannedProfiles += report.scannedProfiles();
                    modifiedProfiles += report.modifiedProfiles();
                    quarantinedStacks += report.quarantinedStacks();
                    if (report.applied()) {
                        appliedPlayers++;
                    }
                    report.errors().forEach(error -> errors.add(playerId + ": " + error));
                } catch (TransitionException failure) {
                    errors.add(playerId + ": " + meaningfulMessage(failure));
                }
            }
            if (availableCount > 0) {
                missingItemBatchCursor.set((start + processed) % availableCount);
            }
            return new MissingItemBatchRepairReport(
                    availableCount, processed, scannedProfiles, modifiedProfiles,
                    quarantinedStacks, appliedPlayers,
                    processed < availableCount, errors);
        } catch (IOException failure) {
            throw new TransitionException("Unable to enumerate canonical player profiles", failure);
        }
    }

    public List<MissingItemArchiveSummary> listMissingItemArchives(UUID playerId) throws TransitionException {
        requireServerThread();
        try {
            return store.listCompatibilityArchives(playerId).stream().map(entry -> {
                CompoundTag issue = entry.issue();
                return new MissingItemArchiveSummary(
                        entry.entryId(), entry.sourceKey(), entry.archiveRevision(),
                        issue.getString("logical_slot"), issue.getString("reason"), issue.getString("item_id"));
            }).toList();
        } catch (IOException | RuntimeException failure) {
            throw new TransitionException("Unable to list missing-item archives for " + playerId, failure);
        }
    }

    public MissingItemRestoreReport restoreMissingItem(UUID playerId, String entryId)
            throws TransitionException {
        requireServerThread();
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(entryId, "entryId");
        try {
            snapshotWriter.flush(playerId);
            CompatibilityArchiveEntry archive = store.listCompatibilityArchives(playerId).stream()
                    .filter(candidate -> candidate.entryId().equals(entryId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown compatibility archive entry " + entryId));
            CompoundTag issue = archive.issue();
            if (!(issue.get("original_stack") instanceof CompoundTag originalStack) || originalStack.isEmpty()) {
                throw new IllegalArgumentException("Archive entry has no original stack");
            }
            ProfileKey key = archive.sourceKey();
            StoredProfile current = store.load(key).orElseThrow(
                    () -> new IllegalArgumentException("Canonical target profile no longer exists: " + key));
            CompoundTag restored = ItemProfileCompatibility.restoreStack(
                    key.shareId(), current.payload(), issue.getString("logical_slot"), originalStack,
                    server.registryAccess(), state.config().model().itemMigration());
            long revision = store.nextRevision(playerId);
            List<TransitionJournal.Change> changes = List.of(new TransitionJournal.Change(
                    key.shareId(), key, key, current.payload(), restored));
            CompoundTag journal = TransitionJournal.prepared(
                    "restore_missing_item_" + entryId, "offline", "offline", changes);

            ServerPlayer online = server.getPlayerList().getPlayer(playerId);
            LinkedHashMap<SharableAdapter, CompoundTag> liveBefore = new LinkedHashMap<>();
            SharableAdapter activeAdapter = null;
            if (online != null) {
                Session session = sessions.get(playerId);
                if (session != null && session.owner() == online
                        && key.equals(session.profiles().get(key.shareId()))) {
                    activeAdapter = session.activeAdapters().get(key.shareId());
                    if (activeAdapter != null) {
                        liveBefore.put(activeAdapter, activeAdapter.capture(online, online.registryAccess()));
                        SharableAdapter selected = activeAdapter;
                        replace(online, List.of(selected), () -> {
                            selected.clear(online);
                            selected.apply(online, restored, online.registryAccess());
                        });
                    }
                }
            }
            try {
                TransitionJournal.complete(journal, "committed", changes, null);
                store.commitBatch(playerId, revision, Map.of(key, restored), Optional.of(journal));
            } catch (IOException | RuntimeException failure) {
                if (online != null && !liveBefore.isEmpty() && !hasPendingCommit(failure)) {
                    rollback(online, liveBefore);
                }
                throw failure;
            }
            store.invalidatePlayer(playerId);
            snapshotWriter.refreshWorkingSetIfLoaded(playerId, revision, Map.of(key, restored));
            return new MissingItemRestoreReport(entryId, key, issue.getString("logical_slot"), revision);
        } catch (IOException | RuntimeException failure) {
            if (hasPendingCommit(failure)) {
                ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                if (online == null) {
                    store.markUncleanShutdown();
                } else {
                    quarantine(online, failure);
                }
            }
            throw new TransitionException("Unable to restore missing-item archive " + entryId, failure);
        }
    }

    /** Flushes pending writes, then removes every disk-profile cache entry for a player. */
    public int invalidatePlayerCache(UUID playerId) throws TransitionException {
        requireServerThread();
        Objects.requireNonNull(playerId, "playerId");
        try {
            snapshotWriter.flush(playerId);
            int before = store.cacheStats().entries();
            store.invalidatePlayer(playerId);
            snapshotWriter.reloadWorkingSetIfLoaded(playerId);
            return Math.max(0, before - store.cacheStats().entries());
        } catch (IOException failure) {
            throw new TransitionException("Unable to invalidate profile cache for " + playerId, failure);
        }
    }

    private static String meaningfulMessage(Throwable failure) {
        Throwable current = failure;
        while ((current.getMessage() == null || current.getMessage().isBlank())
                && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
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
                        recordQuarantine(player.getUUID());
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
                        recordQuarantine(player.getUUID());
                    }
                }
                if (durablePendingCommit) {
                    recordQuarantine(player.getUUID());
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
        recordQuarantine(player.getUUID());
        store.markUncleanShutdown();
        logger.error("Inventory transaction failed for {} ({})", player.getGameProfile().getName(), player.getUUID(), failure);
        if (recoverablePersistenceFailure(failure)) {
            beginRecoveryHold(player);
            return;
        }
        player.connection.disconnect(Component.literal(
                "Yu Multiverse Inventory could not safely switch your profile. Canonical profile writes are now "
                        + "blocked; contact an administrator before reconnecting."));
    }

    private void recordQuarantine(UUID playerId) {
        if (quarantinedPlayers.add(playerId)) {
            quarantineEvents.increment();
        }
    }

    @Override
    public void close() throws IOException {
        requireServerThread();
        stopping = true;
        boolean clean = true;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                if (isRecoveryHeld(player)) continue;
                if (quarantinedPlayers.contains(player.getUUID())) {
                    persistQuarantined(player, "server stop while player was quarantined");
                } else {
                    withPlayerLock(player, () -> {
                        Session session = sessions.get(player.getUUID());
                        if (session == null || session.owner() != player) {
                            persistQuarantined(player, "server stop without an owned inventory session");
                            return;
                        }
                        reconcile(player, "server_stop_reconcile");
                        persistCurrent(player, "server_stop", true);
                    });
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
        try { recoveryExecutor.close(); }
        catch (IOException failure) { closeFailure = failure; store.markUncleanShutdown(); }
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

    /**
     * Establishes the source-side working session for hybrid servers that synchronously teleport a
     * player from a Bukkit join handler before NeoForge publishes PlayerLoggedInEvent.
     */
    private void bootstrapProvisionalSession(
            ServerPlayer player,
            ResourceKey<Level> fromDimension,
            ResourceKey<Level> toDimension) throws TransitionException {
        try {
            snapshotWriter.preload(player.getUUID());
        } catch (IOException failure) {
            throw new TransitionException("Unable to preload player profiles for pre-login transition", failure);
        }

        State current = state;
        WorldIdentity sourceWorld = WorldIdentities.from(fromDimension);
        Map<String, SharableAdapter> activeAdapters = activeAdapters(player, current);
        Map<String, ProfileKey> sourceKeys = profileKeys(player, sourceWorld, current, activeAdapters);
        Session provisional = new Session(
                player,
                fromDimension,
                sourceWorld,
                gameMode(player, current.config()),
                sourceKeys,
                activeAdapters,
                current.generation(),
                LocationTracker.unknown(),
                SessionPhase.PROVISIONAL);
        sessions.put(player.getUUID(), provisional);
        restoreCanonicalProvisionalSource(player, provisional);
        logPreLoginTransition(player, fromDimension, toDimension, provisional, "bootstrap");
        reconcile(player, toDimension, "pre_login_dimension_change");
    }

    /**
     * Establishes source-profile ownership before a hybrid pre-login teleport is reconciled. The
     * ServerPlayer snapshot present at this point was loaded by vanilla and has no trustworthy MWI
     * scope provenance. Existing canonical source profiles therefore win even after an otherwise
     * clean shutdown. If the source profile is missing while the same share exists in another MWI
     * scope, the source is reset instead of copying foreign live state into that scope.
     */
    private void restoreCanonicalProvisionalSource(ServerPlayer player, Session provisional)
            throws TransitionException {
        LinkedHashMap<SharableAdapter, CompoundTag> rollbackSnapshots = new LinkedHashMap<>();
        LinkedHashMap<SharableAdapter, CompoundTag> canonical = new LinkedHashMap<>();
        LinkedHashSet<SharableAdapter> resets = new LinkedHashSet<>();
        LinkedHashMap<ProfileKey, CompoundTag> compatibilityWrites = new LinkedHashMap<>();
        List<ArchiveRequest> compatibilityArchives = new ArrayList<>();
        for (Map.Entry<String, SharableAdapter> entry : provisional.activeAdapters().entrySet()) {
            if (entry.getKey().equals(AdapterIds.LAST_LOCATION.getPath())) {
                continue;
            }
            SharableAdapter adapter = entry.getValue();
            CompoundTag live = adapter.capture(player, player.registryAccess());
            rollbackSnapshots.put(adapter, live);
            ProfileKey sourceKey = provisional.profiles().get(entry.getKey());
            if (store.shouldResetLive(sourceKey)) {
                resets.add(adapter);
                continue;
            }
            Optional<StoredProfile> stored = snapshotWriter.load(sourceKey);
            if (stored.isPresent()) {
                CompoundTag payload = prepareStoredPayload(
                        player, adapter, sourceKey, stored.orElseThrow(), compatibilityArchives);
                if (!adapter.equivalent(payload, stored.orElseThrow().payload())) {
                    compatibilityWrites.put(sourceKey, payload);
                }
                if (!adapter.equivalent(payload, live)) {
                    canonical.put(adapter, payload);
                }
            } else if (!LoginStateOwnershipPolicy.mayBootstrapUnknownLive(
                    snapshotWriter.hasAnyProfileForShare(player.getUUID(), entry.getKey()))) {
                // The live snapshot demonstrably cannot be the first MWI-owned value for this share,
                // but there is no canonical value for the event's source scope. Treat the new scope as
                // empty/default rather than copying a value owned by an unknown/foreign scope.
                resets.add(adapter);
                logger.warn(
                        "Pre-login source profile is missing but the share has canonical history: "
                                + "player={}/{} share={} source={} action=reset_default",
                        player.getGameProfile().getName(),
                        player.getUUID(),
                        entry.getKey(),
                        sourceKey);
            }
        }
        if (!compatibilityArchives.isEmpty()) {
            try {
                long revision = nextRevision(player);
                snapshotWriter.commitImmediately(
                        player.getUUID(), revision, compatibilityWrites, Optional.empty(),
                        compatibilityArchives.stream().map(ArchiveRequest::toStoreArchive).toList());
            } catch (IOException failure) {
                throw new TransitionException(
                        "Unable to archive compatibility-degraded canonical profiles", failure);
            }
        }
        if (canonical.isEmpty() && resets.isEmpty()) {
            return;
        }

        LinkedHashSet<SharableAdapter> mutations = new LinkedHashSet<>(resets);
        mutations.addAll(canonical.keySet());
        try {
            replace(player, mutations, () -> {
                for (SharableAdapter adapter : mutations) {
                    adapter.clear(player);
                    CompoundTag payload = canonical.get(adapter);
                    if (payload != null) {
                        adapter.apply(player, payload, player.registryAccess());
                    }
                }
            });
        } catch (RuntimeException failure) {
            try {
                rollback(player, rollbackSnapshots);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new TransitionException("Unable to establish canonical pre-login source profiles", failure);
        }
        logger.info(
                "Established pre-login source ownership: player={}/{} world={} restoredCanonical={} "
                        + "resetUnknownScope={} startupRollbackRecovery={}",
                player.getGameProfile().getName(),
                player.getUUID(),
                provisional.world().name(),
                canonical.size(),
                resets.size(),
                store.requiresCanonicalRecovery(player.getUUID()));
    }

    /** Promotes a successfully reconciled provisional session without applying any profile twice. */
    private void completeProvisionalLogin(ServerPlayer player) throws TransitionException {
        Session provisional = sessions.get(player.getUUID());
        if (provisional == null || provisional.owner() != player) {
            initialize(player);
            return;
        }
        if (provisional.phase() == SessionPhase.ACTIVE) {
            return;
        }

        State current = state;
        ResourceKey<Level> liveDimension = player.serverLevel().dimension();
        WorldIdentity liveWorld = WorldIdentities.from(liveDimension);
        GameModeBucket liveGameMode = gameMode(player, current.config());
        if (!provisional.dimension().equals(liveDimension)
                || !provisional.world().equals(liveWorld)
                || provisional.gameMode() != liveGameMode
                || provisional.configGeneration() != current.generation()) {
            reconcile(player, liveDimension, "pre_login_finalize");
            provisional = sessions.get(player.getUUID());
        }
        if (provisional == null || provisional.owner() != player) {
            throw new TransitionException("Provisional inventory session disappeared before login completion");
        }

        sessions.put(player.getUUID(), provisional.activate());
        loginPerformance.recordLogin();
        store.completeStartupRecovery(player.getUUID());
        logger.info(
                "Pre-login inventory session activated: player={}/{} world={} dimension={} action=promote",
                player.getGameProfile().getName(),
                player.getUUID(),
                provisional.world().name(),
                provisional.dimension().location());
    }

    private void logPreLoginTransition(
            ServerPlayer player,
            ResourceKey<Level> fromDimension,
            ResourceKey<Level> toDimension,
            Session session,
            String action) {
        WorldIdentity fromWorld = WorldIdentities.from(fromDimension);
        WorldIdentity toWorld = WorldIdentities.from(toDimension);
        logger.info(
                "Pre-login dimension transition detected: player={}/{} from={}/{}/{} to={}/{}/{} "
                        + "sessionState={} action={}",
                player.getGameProfile().getName(),
                player.getUUID(),
                fromDimension.location(),
                fromWorld.name(),
                profileScopeSummary(player, fromWorld, session.activeAdapters()),
                toDimension.location(),
                toWorld.name(),
                profileScopeSummary(player, toWorld, session.activeAdapters()),
                session.phase().logName(),
                action);
    }

    private String profileScopeSummary(
            ServerPlayer player,
            WorldIdentity world,
            Map<String, SharableAdapter> activeAdapters) {
        return profileKeys(player, world, state, activeAdapters).values().stream()
                .map(key -> key.scope().type().name().toLowerCase(java.util.Locale.ROOT)
                        + ":" + key.scope().id())
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
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
        long revision = -1;
        List<TransitionJournal.Change> changes = new ArrayList<>();
        LinkedHashMap<ProfileKey, CompoundTag> canonicalBefore = new LinkedHashMap<>();
        Map<String, CompoundTag> incoming = new HashMap<>();
        LinkedHashMap<ProfileKey, CompoundTag> writes = new LinkedHashMap<>();
        List<ArchiveRequest> archives = new ArrayList<>();
        CompoundTag journal = null;
        LinkedHashSet<SharableAdapter> resetToDefault = new LinkedHashSet<>();
        if (store.recoveredFromUncleanShutdown()) {
            logger.warn(
                    "Initializing {} ({}) after an unclean shutdown: login live state is rollback-only; "
                            + "existing canonical profiles remain authoritative",
                    player.getGameProfile().getName(),
                    player.getUUID());
        }

        try {
            for (Map.Entry<String, SharableAdapter> entry : activeAdapters.entrySet()) {
                String share = entry.getKey();
                SharableAdapter adapter = entry.getValue();
                adapter.validateRestoreEnvironment(player);
                ProfileKey target = targetKeys.get(share);
                CompoundTag livePayload = live.get(adapter);
                Optional<StoredProfile> stored = snapshotWriter.load(target);
                if (stored.isPresent()) {
                    StoredProfile canonicalProfile = stored.orElseThrow();
                    canonicalBefore.put(target, canonicalProfile.payload());
                    CompoundTag selected = prepareStoredPayload(
                            player, adapter, target, canonicalProfile, archives);
                    incoming.put(share, selected);
                    if (!adapter.equivalent(selected, canonicalProfile.payload())) {
                        writes.put(target, selected);
                    }
                } else if (target.scope().type() == ScopeKey.Type.GROUP) {
                    boolean allowFirstProfileBootstrap = LoginStateOwnershipPolicy.mayBootstrapUnknownLive(
                            snapshotWriter.hasAnyProfileForShare(player.getUUID(), share));
                    CompoundTag selected = migrateGroupTarget(
                            player, world, target, livePayload, allowFirstProfileBootstrap, writes, archives);
                    if (selected != null) {
                        incoming.put(share, selected);
                    } else {
                        resetToDefault.add(adapter);
                        logger.warn(
                                "Refusing unknown login live state as canonical: player={}/{} share={} "
                                        + "target={} action=reset_default reason=existing_share_history",
                                player.getGameProfile().getName(),
                                player.getUUID(),
                                share,
                                target);
                    }
                } else if (LoginStateOwnershipPolicy.mayBootstrapUnknownLive(
                        snapshotWriter.hasAnyProfileForShare(player.getUUID(), share))) {
                    // The first persisted profile for a share may adopt the vanilla login state. Once any
                    // MWI profile exists for that share, an unowned login snapshot can no longer prove
                    // which scope it belongs to and must never seed another scope.
                    writes.put(target, livePayload);
                    logger.info(
                            "Bootstrapping first MWI profile for share from vanilla login state: "
                                    + "player={}/{} share={} target={}",
                            player.getGameProfile().getName(),
                            player.getUUID(),
                            share,
                            target);
                } else {
                    resetToDefault.add(adapter);
                    logger.warn(
                            "Refusing unknown login live state as canonical: player={}/{} share={} "
                                    + "target={} action=reset_default reason=existing_share_history",
                            player.getGameProfile().getName(),
                            player.getUUID(),
                            share,
                            target);
                }
                changes.add(new TransitionJournal.Change(share, null, target, livePayload, null));
            }
            RecoveryHold recoveryHold = recoveryHolds.get(player.getUUID());
            if (recoveryHold != null && "PREPARED".equals(recoveryHold.request.getString("state"))) {
                Map<String, CompoundTag> targets = RestorePlans.targets(recoveryHold.request, targetKeys, recoveryHold.inspection.profiles());
                for (var entry : targets.entrySet()) {
                    CompoundTag target = prepareRemovedEffects(activeAdapters.get(entry.getKey()), targetKeys.get(entry.getKey()), entry.getValue(), archives);
                    incoming.put(entry.getKey(), target);
                    writes.put(targetKeys.get(entry.getKey()), target);
                    archives.add(new ArchiveRequest(targetKeys.get(entry.getKey()), "administrator-restore", "before explicit historical restore"));
                }
            }
            ActivationEscrow[] escrow = {prepareActivationEscrow(player, activeAdapters, incoming, targetKeys)};
            journal = TransitionJournal.preparedLogin(world.name(), changes, canonicalBefore);
            if (recoveryHold != null && "PREPARED".equals(recoveryHold.request.getString("state")))
                journal.put("restore_operation", recoveryHold.request.copy());
            List<SharableAdapter> mutatedAdapters = new ArrayList<>();
            for (Map.Entry<String, SharableAdapter> entry : activeAdapters.entrySet()) {
                CompoundTag payload = incoming.get(entry.getKey());
                if ((escrow[0] != null && escrow[0].share().equals(entry.getKey())) || resetToDefault.contains(entry.getValue())
                        || (payload != null && !entry.getValue().equivalent(payload, live.get(entry.getValue())))) {
                    mutatedAdapters.add(entry.getValue());
                }
            }
            replace(player, mutatedAdapters, () -> {
                for (Map.Entry<String, SharableAdapter> entry : activeAdapters.entrySet()) {
                    SharableAdapter adapter = entry.getValue();
                    CompoundTag payload = incoming.get(entry.getKey());
                    if ((escrow[0] != null && escrow[0].share().equals(entry.getKey())) || resetToDefault.contains(adapter)) {
                        adapter.clear(player);
                    } else if (payload != null && !adapter.equivalent(payload, live.get(adapter))) {
                        adapter.clear(player);
                        try { adapter.apply(player, payload, player.registryAccess()); }
                        catch (dev.yu.mwi.api.RecoveryEscrowRequiredException rejected) {
                            if (escrow[0] != null) throw new IllegalStateException("Multiple rejected adapter sources need review", rejected);
                            try { escrow[0] = activationEscrow(player, entry.getKey(), rejected.escrow(), targetKeys); }
                            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                            adapter.clear(player);
                        }
                    }
                }
            }, loginPerformance::recordReplacement);
            LinkedHashMap<String, CompoundTag> currentPayloads = new LinkedHashMap<>();
            long finalCaptureStarted = System.nanoTime();
            int finalCaptures = 0;
            try {
                for (Map.Entry<String, SharableAdapter> entry : activeAdapters.entrySet()) {
                    CompoundTag payload = entry.getValue().capture(player, player.registryAccess());
                    currentPayloads.put(entry.getKey(), payload);
                    if (escrow[0] != null && escrow[0].share().equals(entry.getKey())) {
                        // The complete original equipment is exclusively owned by the same WAL's escrow mutation.
                        incoming.put(entry.getKey(), payload.copy());
                        writes.put(targetKeys.get(entry.getKey()), payload);
                        archives.add(new ArchiveRequest(targetKeys.get(entry.getKey()), "activation-escrow", "unavailable or rejected equipment; complete dependency set escrowed"));
                    }
                    if (resetToDefault.contains(entry.getValue())) {
                        writes.put(targetKeys.get(entry.getKey()), payload);
                    }
                    finalCaptures++;
                }
            } finally {
                loginPerformance.recordCapture(
                        finalCaptures, System.nanoTime() - finalCaptureStarted);
            }
            verifyLoginApplication(player, activeAdapters, incoming, currentPayloads, targetKeys, writes, archives);
            if (WorldIdentities.from(player.serverLevel()).equals(world)) {
                collectWorldBackups(player, world, targetKeys, activeAdapters, currentPayloads, writes);
            }

            revision = nextRevision(player);
            List<TransitionJournal.Change> completed = withAfter(changes, currentPayloads);
            TransitionJournal.complete(journal, "committed", completed, null);
            journal.put("restore_context", restoreContext(world, targetKeys));
            if (escrow[0] == null) snapshotWriter.commitImmediately(
                    player.getUUID(), revision, writes, Optional.of(journal), archives.stream().map(ArchiveRequest::toStoreArchive).toList());
            else snapshotWriter.commitEscrow(player.getUUID(), revision, writes, escrow[0].mutation(), journal,
                    archives.stream().map(ArchiveRequest::toStoreArchive).toList());
            sessions.put(player.getUUID(), new Session(
                    player, player.serverLevel().dimension(), world, gameMode, targetKeys, activeAdapters,
                    current.generation(), new LocationTracker(player), SessionPhase.ACTIVE));
            store.completeStartupRecovery(player.getUUID());
        } catch (Exception failure) {
            if (hasPendingCommit(failure)) {
                recordQuarantine(player.getUUID());
            } else {
                try {
                    rollback(player, live);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    recordQuarantine(player.getUUID());
                }
                if (journal != null && revision > 0) {
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

    private void verifyLoginApplication(ServerPlayer player, Map<String, SharableAdapter> adapters,
            Map<String, CompoundTag> expected, Map<String, CompoundTag> actual,
            Map<String, ProfileKey> keys, Map<ProfileKey, CompoundTag> writes, List<ArchiveRequest> archives) throws IOException {
        for (var entry : expected.entrySet()) {
            SharableAdapter adapter = adapters.get(entry.getKey());
            var result = adapter.verifyRestored(player, entry.getValue(), actual.get(entry.getKey()), player.registryAccess());
            if (!result.accepted()) throw new IOException("Application verification " + entry.getKey() + " "
                    + result.status() + ": " + result.detail());
            if (result.status() != dev.yu.mwi.api.RestoreVerification.Status.SEMANTIC_EQUIVALENT) continue;
            CompoundTag normalized = result.after();
            replace(player, List.of(adapter), () -> { adapter.clear(player); adapter.apply(player, normalized, player.registryAccess()); });
            CompoundTag second = adapter.capture(player, player.registryAccess());
            if (!normalized.equals(second)) throw new IOException("Unstable adapter normalization: " + entry.getKey());
            CompoundTag evidence = new CompoundTag(); evidence.putString("rule", result.rule());
            evidence.putString("expected_digest", dev.yu.mwi.storage.NbtChecksums.canonicalSha256(entry.getValue()));
            evidence.putString("actual_digest", dev.yu.mwi.storage.NbtChecksums.canonicalSha256(second));
            evidence.put("verified_after", second.copy());
            ProfileKey key = keys.get(entry.getKey());
            if (snapshotWriter.load(key).isPresent()) archives.add(new ArchiveRequest(key,
                    "semantic-normalization", "verified stable adapter after-image", evidence));
            writes.put(key, second);
            actual.put(entry.getKey(), second);
        }
        // An equipment round trip may affect another share. Verify the complete dependency set again.
        for (var entry : expected.entrySet()) {
            SharableAdapter adapter = adapters.get(entry.getKey());
            CompoundTag captured = adapter.capture(player, player.registryAccess());
            var result = adapter.verifyRestored(player, entry.getValue(), captured, player.registryAccess());
            if (!result.accepted() || !captured.equals(actual.get(entry.getKey())))
                throw new IOException("Adapter dependency changed during verification: " + entry.getKey());
        }
    }

    private record ActivationEscrow(String share, dev.yu.mwi.storage.RecoveryMutation mutation) {}
    private ActivationEscrow prepareActivationEscrow(ServerPlayer player, Map<String, SharableAdapter> adapters,
            Map<String, CompoundTag> expected, Map<String, ProfileKey> keys) throws IOException {
        ActivationEscrow result = null;
        for (var entry : expected.entrySet()) {
            var plan = adapters.get(entry.getKey()).prepareRecoveryEscrow(player, entry.getValue(), player.registryAccess());
            if (plan.isEmpty()) continue;
            if (result != null) throw new IOException("Multiple adapter escrow plans require explicit dependency review");
            result = activationEscrow(player, entry.getKey(), plan.orElseThrow(), keys);
        }
        return result;
    }

    private ActivationEscrow activationEscrow(ServerPlayer player, String share, dev.yu.mwi.api.RecoveryEscrow contents,
            Map<String, ProfileKey> keys) throws IOException {
        String digest = dev.yu.mwi.storage.NbtChecksums.canonicalSha256(contents.original());
        UUID source = UUID.nameUUIDFromBytes((keys.get(share) + ":activation-escrow:" + digest)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (store.loadRecovery(player.getUUID(), source).isPresent())
            throw new IOException("This equipment source already has a permanent escrow receipt; refusing to issue it again");
        CompoundTag evidence = new CompoundTag(); evidence.putString("type", "activation_escrow");
        evidence.putString("provider", "yu_multiverse_inventory"); evidence.putString("adapter", share);
        evidence.putString("source_digest", digest); evidence.put("original_slots", contents.original());
        CompoundTag identity = new CompoundTag(); identity.put("scope", recoveryScope(keys));
        identity.put("evidence", evidence); identity.put("original_items", contents.items());
        CompoundTag payload = new CompoundTag(); payload.put("identity", identity); payload.put("remaining", contents.items());
        payload.putString("state", "AVAILABLE"); payload.putBoolean("notify_ready", true);
        payload.putLong("created_at", System.currentTimeMillis());
        return new ActivationEscrow(share, new dev.yu.mwi.storage.RecoveryMutation(source, -1, source, payload));
    }

    private static CompoundTag restoreContext(WorldIdentity world, Map<String, ProfileKey> keys) {
        CompoundTag context = new CompoundTag(); context.putString("world", world.name());
        net.minecraft.nbt.ListTag encoded = new net.minecraft.nbt.ListTag();
        keys.values().forEach(key -> encoded.add(TransitionJournal.encodeKey(key)));
        context.put("keys", encoded); return context;
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
        reconcile(player, player.serverLevel().dimension(), reason);
    }

    private void reconcile(
            ServerPlayer player,
            ResourceKey<Level> targetDimension,
            String reason) throws TransitionException {
        Session session = sessions.get(player.getUUID());
        if (session == null) {
            initialize(player);
            return;
        }
        if (session.owner() != player) {
            throw new TransitionException("Inventory session belongs to a different player connection");
        }
        if (!player.serverLevel().dimension().equals(targetDimension)) {
            throw new TransitionException(
                    "Dimension transition target does not match the player's live dimension: event="
                            + targetDimension.location() + ", live="
                            + player.serverLevel().dimension().location());
        }

        State current = state;
        WorldIdentity targetWorld = WorldIdentities.from(targetDimension);
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
                try {
                    Optional<CompoundTag> sourceLocation = session.location().payload();
                    Map<String, CompoundTag> overrides = sourceLocation
                            .map(payload -> Map.of(AdapterIds.LAST_LOCATION.getPath(), payload))
                            .orElseGet(Map::of);
                    Map<String, SharableAdapter> backupAdapters = sourceLocation.isPresent()
                            ? activeAdapters
                            : withoutLastLocation(activeAdapters);
                    if (sourceLocation.isEmpty()
                            && activeAdapters.containsKey(AdapterIds.LAST_LOCATION.getPath())) {
                        logSkippedSourceLocation(player, session.world(), targetWorld);
                    }
                    LinkedHashMap<ProfileKey, CompoundTag> writes = new LinkedHashMap<>();
                    collectWorldBackups(
                            player, session.world(), session.profiles(), backupAdapters, overrides, writes);
                    if (!writes.isEmpty()) {
                        snapshotWriter.enqueue(
                                player.getUUID(), nextRevision(player), writes, Optional.empty(), "world_backup");
                    }
                } catch (RuntimeException failure) {
                    throw new TransitionException("Unable to write world profile backups", failure);
                }
            }
            sessions.put(player.getUUID(), new Session(
                    player, targetDimension, targetWorld, targetGameMode, targetKeys, activeAdapters,
                    current.generation(), new LocationTracker(player), session.phase()));
            return;
        }

        closeMenu(player);
        LinkedHashMap<SharableAdapter, CompoundTag> rollbackSnapshots = capture(
                player, moves.stream().map(Move::adapter).toList());
        LinkedHashMap<SharableAdapter, CompoundTag> outgoingSnapshots =
                new LinkedHashMap<>(rollbackSnapshots);
        if (!session.world().equals(targetWorld)) {
            for (Move move : moves) {
                if (!move.shareId().equals(AdapterIds.LAST_LOCATION.getPath())) {
                    continue;
                }
                Optional<CompoundTag> sourceLocation = session.location().payload();
                if (sourceLocation.isPresent()) {
                    outgoingSnapshots.put(move.adapter(), sourceLocation.orElseThrow());
                } else {
                    outgoingSnapshots.remove(move.adapter());
                    logSkippedSourceLocation(player, session.world(), targetWorld);
                }
            }
        }
        long revision = nextRevision(player);
        List<TransitionJournal.Change> changes = moves.stream()
                .filter(move -> outgoingSnapshots.containsKey(move.adapter()))
                .map(move -> new TransitionJournal.Change(
                        move.shareId(), move.fromKey(), move.toKey(),
                        outgoingSnapshots.get(move.adapter()), null))
                .toList();
        CompoundTag journal = TransitionJournal.prepared(
                reason, session.world().name(), targetWorld.name(), changes);
        boolean mutationStarted = false;
        Map<String, CompoundTag> incoming = new HashMap<>();
        LinkedHashMap<ProfileKey, CompoundTag> writes = new LinkedHashMap<>();
        List<ArchiveRequest> archives = new ArrayList<>();

        try {
            for (Move move : moves) {
                if (move.fromKey() == null || !outgoingSnapshots.containsKey(move.adapter())) {
                    continue;
                }
                CompoundTag outgoing = outgoingSnapshots.get(move.adapter());
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
                    StoredProfile canonicalProfile = stored.orElseThrow();
                    incoming.put(move.shareId(), prepareStoredPayload(
                            player, move.adapter(), move.toKey(), canonicalProfile, archives));
                    CompoundTag selected = incoming.get(move.shareId());
                    if (!move.adapter().equivalent(selected, canonicalProfile.payload())) {
                        writes.put(move.toKey(), selected);
                    }
                } else if (move.toKey().scope().type() == ScopeKey.Type.GROUP) {
                    boolean sameWorld = move.fromKey() == null || session.world().equals(targetWorld);
                    CompoundTag migrated = migrateGroupTarget(
                            player, targetWorld, move.toKey(), rollbackSnapshots.get(move.adapter()), sameWorld,
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
                if (payload != null
                        && move.adapter().equivalent(payload, rollbackSnapshots.get(move.adapter()))) {
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

            WorldIdentity finalWorld = WorldIdentities.from(player.serverLevel());
            Map<String, ProfileKey> finalKeys = profileKeys(player, finalWorld, current, activeAdapters);
            if (!targetKeys.equals(finalKeys)) {
                throw new TransitionException(
                        "Applied profile moved the player across an inventory scope boundary: target="
                                + targetWorld.name() + ", final=" + finalWorld.name());
            }

            LinkedHashMap<String, CompoundTag> after = new LinkedHashMap<>();
            for (Move move : moves) {
                CompoundTag payload = incoming.get(move.shareId());
                if (payload == null) {
                    payload = move.adapter().capture(player, player.registryAccess());
                    writes.put(move.toKey(), payload);
                }
                after.put(move.shareId(), payload);
            }
            LinkedHashMap<String, CompoundTag> currentPayloads = new LinkedHashMap<>();
            for (Map.Entry<String, SharableAdapter> entry : activeAdapters.entrySet()) {
                CompoundTag payload = after.get(entry.getKey());
                if (payload == null) {
                    payload = entry.getValue().capture(player, player.registryAccess());
                }
                currentPayloads.put(entry.getKey(), payload);
            }
            collectWorldBackups(player, finalWorld, finalKeys, activeAdapters, currentPayloads, writes);

            List<TransitionJournal.Change> completed = LiveStateCoordinator.withAfter(changes, after);
            TransitionJournal.complete(journal, "committed", completed, null);
            journal.put("restore_context", restoreContext(finalWorld, finalKeys));
            snapshotWriter.enqueue(
                    player.getUUID(),
                    revision,
                    writes,
                    Optional.of(journal),
                    archives.stream().map(ArchiveRequest::toStoreArchive).toList(),
                    reason);
            sessions.put(player.getUUID(), new Session(
                    player, player.serverLevel().dimension(), finalWorld, targetGameMode, finalKeys, activeAdapters,
                    current.generation(), new LocationTracker(player), session.phase()));
        } catch (Exception failure) {
            if (hasPendingCommit(failure)) {
                recordQuarantine(player.getUUID());
                throw transitionFailure("Profile transaction is pending durable recovery", failure);
            }
            Exception rollbackFailure = null;
            if (mutationStarted) {
                try {
                    rollback(player, rollbackSnapshots);
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

    private static Map<String, SharableAdapter> withoutLastLocation(
            Map<String, SharableAdapter> activeAdapters) {
        if (!activeAdapters.containsKey(AdapterIds.LAST_LOCATION.getPath())) {
            return activeAdapters;
        }
        LinkedHashMap<String, SharableAdapter> filtered = new LinkedHashMap<>(activeAdapters);
        filtered.remove(AdapterIds.LAST_LOCATION.getPath());
        return Collections.unmodifiableMap(filtered);
    }

    private CompoundTag prepareStoredPayload(
            ServerPlayer player,
            SharableAdapter adapter,
            ProfileKey key,
            StoredProfile stored,
            List<ArchiveRequest> archives) {
        CompoundTag payload = prepareRemovedEffects(adapter, key, stored.payload(), archives);
        if (adapter instanceof AdvancementAdapter advancements) {
            AdvancementPreparation preparation = advancements.prepare(
                    player.server.getAdvancements(),
                    payload,
                    state.config().model().advancementMigration());
            AdvancementCompatibilityReport report = preparation.report();
            payload = preparation.payload();
            if (report.changed()) {
                ArchiveRequest archive = new ArchiveRequest(
                        key, "compatibility", compatibilityReason(report), new CompoundTag());
                if (!archives.contains(archive)) {
                    archives.add(archive);
                }
                if (compatibilityDiagnostics.record(key, stored.revision(), report)) {
                    logger.warn(
                            "Advancement profile compatibility degraded: player={} scope={}:{} share={} "
                                    + "profileRevision={} staleAdvancementsSkipped={} staleCriteriaSkipped={} "
                                    + "advancementAliasesMigrated={} criterionAliasesMigrated={} "
                                    + "advancementSamples={} criterionSamples={}",
                            player.getUUID(),
                            key.scope().type().name().toLowerCase(java.util.Locale.ROOT),
                            key.scope().id(),
                            key.shareId(),
                            stored.revision(),
                            report.staleAdvancementsSkipped(),
                            report.staleCriteriaSkipped(),
                            report.advancementAliasesMigrated(),
                            report.criterionAliasesMigrated(),
                            report.advancementSamples(),
                            report.criterionSamples());
                }
            }
        }
        if (ItemProfileCompatibility.supports(key.shareId())) {
            ItemPayloadPreparation preparation = ItemProfileCompatibility.prepare(
                    key.shareId(), payload, player.registryAccess(),
                    state.config().model().itemMigration());
            ItemCompatibilityReport report = preparation.report();
            if (isRecoveryHeld(player) && (report.missingItems() > 0 || report.missingComponents() > 0))
                throw new IllegalStateException("恢复依赖的物品或组件注册项缺失；原档案保留，等待环境修复或管理员显式处理");
            payload = preparation.payload();
            if (report.changed()) {
                ArchiveRequest archive = new ArchiveRequest(
                        key, "missing_items", itemCompatibilityReason(report),
                        itemArchiveDetails(
                                key, stored, report, player.server.getServerVersion(), runtimeModVersion()));
                if (!archives.contains(archive)) {
                    archives.add(archive);
                }
                if (compatibilityDiagnostics.recordItems(key, stored.revision(), report)) {
                    logger.warn(
                            "Item profile compatibility degraded: player={} profile={} revision={} share={} "
                                    + "quarantined={} missingItems={} missingComponents={} aliasesApplied={} "
                                    + "samples={} archiveRevision=pending",
                            player.getUUID(), key, stored.revision(), key.shareId(), report.issues().size(),
                            report.missingItems(), report.missingComponents(), report.aliasesApplied(),
                            report.issues().stream().limit(5)
                                    .map(issue -> issue.logicalSlot() + '=' + issue.itemId())
                                    .toList());
                }
            }
        }
        return payload;
    }

    private static CompoundTag itemArchiveDetails(
            ProfileKey key,
            StoredProfile stored,
            ItemCompatibilityReport report,
            String serverVersion,
            String modVersion) {
        CompoundTag details = new CompoundTag();
        details.putInt("schema_version", 1);
        details.putString("adapter", key.shareId());
        details.putString("server_version", serverVersion);
        details.putString("mod_version", modVersion);
        ListTag entries = new ListTag();
        for (ItemCompatibilityIssue issue : report.issues()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("logical_slot", issue.logicalSlot());
            entry.putString("reason", issue.reason().name());
            if (issue.itemId() != null) {
                entry.putString("item_id", issue.itemId().toString());
            }
            ListTag components = new ListTag();
            issue.componentIds().forEach(id -> components.add(net.minecraft.nbt.StringTag.valueOf(id.toString())));
            entry.put("component_ids", components);
            entry.put("original_stack", issue.originalStack());
            entries.add(entry);
        }
        details.put("entries", entries);
        details.putLong("source_revision", stored.revision());
        details.putString("source_checksum", stored.checksum());
        details.putLong("discovered_at", java.time.Instant.now().toEpochMilli());
        details.putInt("aliases_applied", report.aliasesApplied());
        return details;
    }

    private static String runtimeModVersion() {
        return net.neoforged.fml.ModList.get().getModContainerById(dev.yu.mwi.YuMultiverseInventory.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    private static String itemCompatibilityReason(ItemCompatibilityReport report) {
        return "item registry schema drift: missing items=" + report.missingItems()
                + ", missing components=" + report.missingComponents()
                + ", aliases=" + report.aliasesApplied();
    }

    private static String compatibilityReason(AdvancementCompatibilityReport report) {
        return "advancement registry schema drift: stale advancements="
                + report.staleAdvancementsSkipped()
                + ", stale criteria=" + report.staleCriteriaSkipped()
                + ", advancement aliases=" + report.advancementAliasesMigrated()
                + ", criterion aliases=" + report.criterionAliasesMigrated();
    }

    private void logSkippedSourceLocation(
            ServerPlayer player,
            WorldIdentity sourceWorld,
            WorldIdentity targetWorld) {
        logger.warn(
                "Skipped source last_location snapshot: player={}/{} from={} to={} "
                        + "reason=source coordinates unavailable after pre-login dimension transition",
                player.getGameProfile().getName(),
                player.getUUID(),
                sourceWorld.name(),
                targetWorld.name());
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
        SharableAdapter adapter = adapters.require(AdapterIds.id(groupKey.shareId()));
        CompoundTag payload = result.sourceProfile() == null
                ? preparePayload(player, adapter, result.payload())
                : prepareStoredPayload(
                        player,
                        adapter,
                        result.sourceKey(),
                        result.sourceProfile(),
                        archives);
        writes.put(groupKey, payload);
        return payload;
    }

    private CompoundTag preparePayload(
            ServerPlayer player, SharableAdapter adapter, CompoundTag payload) {
        CompoundTag prepared = payload.copy();
        if (adapter instanceof AdvancementAdapter advancements) {
            prepared = advancements.prepare(
                    player.server.getAdvancements(),
                    prepared,
                    state.config().model().advancementMigration()).payload();
        }
        if (ItemProfileCompatibility.supports(adapter.id().getPath())) {
            prepared = ItemProfileCompatibility.prepare(
                    adapter.id().getPath(), prepared, player.registryAccess(),
                    state.config().model().itemMigration()).payload();
        }
        return prepared;
    }

    private CompoundTag prepareRemovedEffects(SharableAdapter adapter, ProfileKey key, CompoundTag original,
            List<ArchiveRequest> archives) {
        if (!(adapter instanceof dev.yu.mwi.adapter.vanilla.PotionEffectsAdapter effects)) return original;
        var preparation = effects.prepareAfterUninstall(original);
        if (preparation.removed().isEmpty()) return original;
        CompoundTag evidence = new CompoundTag(); evidence.put("source_payload", original.copy());
        ListTag removed = new ListTag(); preparation.removed().forEach(id -> removed.add(net.minecraft.nbt.StringTag.valueOf(id)));
        evidence.put("removed_effects", removed); evidence.putString("rule", "uninstalled-effect-owner-v1");
        archives.add(new ArchiveRequest(key, "removed-effects", "Uninstalled effect owners; original effect payload retained", evidence));
        logger.warn("Uninstalled effects archived during profile preparation: profile={} effects={}", key, preparation.removed());
        return preparation.payload();
    }

    private void reseedCurrent(ServerPlayer player, String reason) throws TransitionException {
        State current = state;
        Session existing = sessions.get(player.getUUID());
        SessionPhase phase = existing != null && existing.owner() == player
                ? existing.phase()
                : SessionPhase.ACTIVE;
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
                    current.generation(), new LocationTracker(player), phase));
        } catch (IOException | RuntimeException failure) {
            throw new TransitionException("Unable to persist " + reason + " state", failure);
        }
    }

    private void persistCurrent(ServerPlayer player, String reason) throws TransitionException {
        persistCurrent(player, reason, false);
    }

    private void persistCurrent(ServerPlayer player, String reason, boolean asynchronous) throws TransitionException {
        State current = state;
        Session session = sessions.get(player.getUUID());
        if (session == null || session.owner() != player) {
            throw new TransitionException(
                    "Refusing canonical persistence without an owned inventory session for " + reason);
        }
        GameModeBucket liveGameMode = gameMode(player, current.config());
        if (!session.dimension().equals(player.serverLevel().dimension())
                || !session.world().equals(WorldIdentities.from(player.serverLevel()))
                || session.gameMode() != liveGameMode
                || session.configGeneration() != current.generation()) {
            throw new TransitionException(
                    "Refusing canonical persistence from stale/unknown live-state ownership for " + reason);
        }
        long revision = nextRevision(player);
        try {
            snapshotWriter.persist(
                    player, session.world(), session.profiles(), session.activeAdapters(),
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
        writes.keySet().removeIf(snapshotWriter::unavailable);
    }

    public void prepareLogin(ServerPlayer player) {
        requireServerThread();
        RecoveryHold existing = recoveryHolds.get(player.getUUID());
        if (existing != null && existing.owner == player) return;
        if (existing != null) {
            existing.disconnected = true;
            retiredRecoveryPlayers.add(existing.owner);
            if (existing.completion != null) existing.completion.completeExceptionally(new IOException("连接已更换；旧连接不再拥有恢复任务"));
        }
        recoveryHolds.put(player.getUUID(), new RecoveryHold(player, snapshotWriter.fence(player.getUUID())));
        var command = restoreCommands.get(player.getUUID());
        if (command != null && !command.completion().isDone()) recoveryHolds.get(player.getUUID()).commandPreparing = true;
    }

    public boolean isRecoveryHeld(ServerPlayer player) {
        RecoveryHold hold = recoveryHolds.get(player.getUUID());
        return (hold != null && hold.owner == player) || retiredRecoveryPlayers.contains(player);
    }

    public boolean isRecoveryInternal() { return recoveryInternal.get(); }
    public boolean isRecoveryCheckpoint(ServerPlayer player) {
        return recoveryInternal.get() && recoveryCheckpoints.contains(player.getUUID());
    }

    public void captureLoadedPlayer(ServerPlayer player, CompoundTag loaded) {
        RecoveryHold hold = recoveryHolds.get(player.getUUID());
        if (hold != null && hold.owner == player) hold.loadedPlayer = loaded.copy();
    }

    public boolean isRecoveryDisplay(ServerPlayer player) {
        RecoveryHold hold = recoveryHolds.get(player.getUUID());
        return hold != null && hold.owner == player && hold.captured != null;
    }

    private void beginRecoveryHold(ServerPlayer player) {
        requireServerThread();
        prepareLogin(player);
        RecoveryHold hold = recoveryHolds.get(player.getUUID());
        if (hold.captured == null) {
            recoveryInternal.set(true);
            try {
                closeMenu(player);
                hold.mode = player.gameMode.getGameModeForPlayer();
                hold.returnDimension = player.serverLevel().dimension();
                hold.returnPosition = player.position();
                hold.returnYaw = player.getYRot(); hold.returnPitch = player.getXRot();
                hold.captured = capture(player, activeAdapters(player, state).values());
                hold.detached = new CompoundTag();
                hold.captured.forEach((adapter, tag) -> hold.detached.put(adapter.id().toString(), tag.copy()));
                if (hold.loadedPlayer != null) hold.detached.put("vanilla_login_input", hold.loadedPlayer);
                hold.required = Set.copyOf(profileKeys(player, WorldIdentities.from(player.serverLevel()), state,
                        activeAdapters(player, state)).values());
                sessions.remove(player.getUUID());
            } finally { recoveryInternal.remove(); }
            player.sendSystemMessage(Component.literal("背包正在核验或恢复，请稍候；暂时无法操作物品。"));
        }
        requestAutomaticRecovery(hold);
    }

    private void requestAutomaticRecovery(RecoveryHold hold) {
        if (stopping || hold.running || hold.captured == null || hold.commandPreparing) return;
        refreshRequiredProfiles(hold);
        hold.running = true;
        UUID owner = hold.owner.getUUID(), incident = hold.incident;
        long fence = hold.fence;
        CompoundTag detached = hold.detached.copy();
        boolean repair = state.config().storage().automaticRecovery() || hold.manualRetry;
        boolean manual = hold.manualRetry;
        hold.manualRetry = false;
        if (hold.completion == null || hold.completion.isDone()) hold.completion = new java.util.concurrent.CompletableFuture<>();
        recoveryExecutor.submit(owner, incident, () -> {
                    var inspected = snapshotWriter.recover(owner, fence, incident, detached, repair);
                    hold.request = store.restoreRequest(owner);
                    return inspected;
                })
                .whenComplete((inspection, failure) -> server.execute(() -> {
                    hold.running = false;
                    if (stopping || recoveryHolds.get(owner) != hold || hold.disconnected) return;
                    if (server.getPlayerList().getPlayer(owner) != hold.owner) { hold.disconnected = true; return; }
                    hold.retryAt = nextRecoveryTime();
                    if (failure != null) {
                        logger.warn("Automatic inventory recovery remains pending for {}", owner, failure);
                        hold.completion.completeExceptionally(failure);
                        return;
                    }
                    hold.inspection = inspection;
                    refreshRequiredProfiles(hold);
                    if (!inspection.ready(hold.required)) {
                        displayRecoveryHold(hold);
                        hold.completion.complete(inspection);
                        return;
                    }
                    activateRecovered(hold, inspection, manual);
                    hold.completion.complete(inspection);
                }));
    }

    public void tickOfflineRecovery() {
        requireServerThread();
        if (stopping || !state.config().storage().automaticRecovery()) return;
        int submitted = 0;
        for (UUID owner : List.copyOf(backgroundRecoveryAt.keySet())) {
            RecoveryHold hold = recoveryHolds.get(owner);
            if (sessions.containsKey(owner) || (hold != null && !hold.disconnected) || restoreCommands.containsKey(owner)
                    || backgroundRecoveryRunning.contains(owner) || backgroundRecoveryAt.get(owner) > System.nanoTime()) continue;
            if (++submitted > 4) break;
            backgroundRecoveryRunning.add(owner);
            backgroundRecoveryAt.put(owner, nextRecoveryTime());
            long fence = snapshotWriter.fence(owner);
            recoveryExecutor.submit(owner, "offline", () -> snapshotWriter.recover(owner, fence, null, null, true))
                    .whenComplete((inspection, failure) -> server.execute(() -> {
                        backgroundRecoveryRunning.remove(owner);
                        RecoveryHold current = recoveryHolds.get(owner);
                        if (stopping || (current != null && !current.disconnected)) return;
                        if (failure != null) { logger.warn("Offline recovery remains pending for {}", owner, failure); return; }
                        try {
                            if (inspection.pending().isEmpty()) {
                                snapshotWriter.installRecovered(owner, fence, inspection);
                                snapshotWriter.forget(owner);
                            }
                            if ("RESOLVED".equals(inspection.state())) backgroundRecoveryAt.remove(owner);
                        } catch (IOException changed) { logger.warn("Offline recovery generation changed for {}", owner, changed); }
                    }));
        }
    }

    private void displayRecoveryHold(RecoveryHold hold) {
        if (hold.displayed) return;
        recoveryInternal.set(true);
        try {
            // The original adapter snapshot is durable before this method is called.
            List<SharableAdapter> items = hold.captured.keySet().stream()
                    .filter(adapter -> ItemProfileCompatibility.supports(adapter.id().getPath())).toList();
            replace(hold.owner, items, () -> items.forEach(adapter -> adapter.clear(hold.owner)));
            var lobbyKey = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("yu_multiverse_inventory", "recovery_lobby"));
            var lobby = server.getLevel(lobbyKey);
            if (lobby != null) hold.owner.teleportTo(lobby, 0.5, -59, 0.5, 0, 0);
            hold.owner.setGameMode(net.minecraft.world.level.GameType.ADVENTURE);
            hold.displayed = true;
            hold.owner.sendSystemMessage(Component.literal("[背包恢复] ").withStyle(net.minecraft.ChatFormatting.AQUA)
                    .append(Component.literal(lobby != null ? "原档案已保留。你可以在恢复大厅移动；"
                            : "原档案已保留。恢复空间尚未加载，当前仍处于受保护会话；").withStyle(net.minecraft.ChatFormatting.GOLD))
                    .append(Component.literal("[查看进度与帮助]").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.AQUA)
                            .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/mwistatus")))));
        } catch (RuntimeException displayFailure) {
            // Keep the original player and pending item queues fenced when a safe display cannot
            // be built. A failed help screen must not escape the callback or activate the session.
            String detail = "恢复展示暂不可用：" + meaningfulMessage(displayFailure);
            hold.activation.putString("last_error", detail);
            logger.warn("Recovery display remains fenced for {}: {}", hold.owner.getUUID(), detail);
            hold.owner.sendSystemMessage(Component.literal(detail + "；请用 /mwistatus 查看状态。"));
        } finally { recoveryInternal.remove(); }
    }

    private void refreshRequiredProfiles(RecoveryHold hold) {
        var level = server.getLevel(hold.returnDimension);
        WorldIdentity world = level == null ? WorldIdentities.from(hold.returnDimension) : WorldIdentities.from(level);
        GameModeBucket bucket = GameModeBucket.resolve(hold.mode.getName(), state.config().model().shareHandling().enableGameModeProfiles());
        LinkedHashSet<ProfileKey> required = new LinkedHashSet<>();
        for (String share : activeAdapters(hold.owner, state).keySet())
            required.add(new ProfileKey(hold.owner.getUUID(), state.resolver().resolve(world, share), bucket, share));
        hold.required = Set.copyOf(required);
    }

    private void activateRecovered(RecoveryHold hold, dev.yu.mwi.storage.RecoveryInspection inspection, boolean manual) {
        recoveryInternal.set(true);
        try {
            if (inspection.generation() != store.recoveryGeneration(hold.owner.getUUID())) return;
            String environment = net.neoforged.fml.ModList.get().getMods().stream()
                    .map(mod -> mod.getModId() + "=" + mod.getVersion()).sorted().collect(java.util.stream.Collectors.joining(";"));
            environment += ";return=" + hold.returnDimension.location() + ";mode=" + hold.mode.getName();
            hold.activation = dev.yu.mwi.storage.ActivationAttempt.prepare(store.activationTask(hold.owner.getUUID()),
                    ActivationInputs.digest(hold.required, inspection.profiles(), environment, hold.request,
                            activationFallbackInputs(hold, inspection)), manual);
            hold.activation.put("required_profiles", ActivationInputs.encode(hold.required));
            hold.activation.putString("return_dimension", hold.returnDimension.location().toString());
            hold.activation.putString("return_game_mode", hold.mode.getName());
            hold.activation.putString("rule", ActivationInputs.RULES);
            hold.activation.putLong("updated_at", System.currentTimeMillis());
            store.saveActivationTask(hold.owner.getUUID(), hold.activation);
            if ("ACTION_REQUIRED".equals(hold.activation.getString("state"))) { displayRecoveryHold(hold); return; }
            var returnLevel = server.getLevel(hold.returnDimension);
            if (returnLevel == null) throw new IOException("Original recovery dimension is unavailable");
            if (hold.displayed) hold.owner.teleportTo(returnLevel, hold.returnPosition.x, hold.returnPosition.y,
                    hold.returnPosition.z, hold.returnYaw, hold.returnPitch);
            hold.owner.setGameMode(hold.mode);
            rollback(hold.owner, hold.captured);
            snapshotWriter.installRecovered(hold.owner.getUUID(), hold.fence, inspection);
            quarantinedPlayers.remove(hold.owner.getUUID());
            withPlayerLock(hold.owner, () -> initialize(hold.owner));
            Session session = sessions.get(hold.owner.getUUID());
            // Activation remains fenced until every persisted adapter has been applied and synced.
            for (var entry : session.activeAdapters().entrySet()) {
                var stored = snapshotWriter.load(session.profiles().get(entry.getKey()));
                if (stored.isPresent() && !entry.getValue().verifyRestored(hold.owner, stored.orElseThrow().payload(),
                        entry.getValue().capture(hold.owner, hold.owner.registryAccess()), hold.owner.registryAccess()).accepted())
                    throw new IOException("Adapter verification failed during recovery activation: " + entry.getKey());
            }
            hold.activation.putString("state", "CHECKPOINT");
            store.saveActivationTask(hold.owner.getUUID(), hold.activation);
            recoveryCheckpoints.add(hold.owner.getUUID());
            try { dev.yu.mwi.recovery.RecoveryCheckpoint.save(hold.owner, hold.activation.getUUID("operation_id")); }
            finally { recoveryCheckpoints.remove(hold.owner.getUUID()); }
            hold.activation.putString("state", "ACTIVE");
            hold.activation.putInt("attempts", 0);
            hold.activation.remove("last_error");
            hold.activation.putLong("updated_at", System.currentTimeMillis());
            store.saveActivationTask(hold.owner.getUUID(), hold.activation);
            CompoundTag request = store.restoreRequest(hold.owner.getUUID());
            if ("COMMITTED".equals(request.getString("state"))) {
                request.putString("state", "ACTIVE"); store.saveRestoreRequest(hold.owner.getUUID(), request);
                hold.request = request;
            }
            recoveryHolds.remove(hold.owner.getUUID(), hold);
            dev.yu.mwi.recovery.RecoveryNotifications.enqueue(hold.owner);
            if (!inspection.unavailable().isEmpty()) backgroundRecoveryAt.put(hold.owner.getUUID(),
                    nextRecoveryTime());
            if (hold.displayed) hold.owner.sendSystemMessage(Component.literal("背包已恢复，可以继续游戏。"));
        } catch (IOException | TransitionException | RuntimeException failure) {
            sessions.remove(hold.owner.getUUID());
            recordQuarantine(hold.owner.getUUID());
            hold.fence = snapshotWriter.fence(hold.owner.getUUID());
            hold.displayed = false;
            Throwable reason = failure;
            while (reason.getCause() != null && reason.getCause() != reason) reason = reason.getCause();
            hold.activation = dev.yu.mwi.storage.ActivationAttempt.failed(hold.activation, meaningfulMessage(reason));
            try { store.saveActivationTask(hold.owner.getUUID(), hold.activation); }
            catch (IOException evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            logger.error("Recovery activation {} for {}: {}", hold.activation.getString("state"), hold.owner.getUUID(), failure.toString());
            displayRecoveryHold(hold);
        } finally { recoveryInternal.remove(); }
    }

    private void tickRecoveryHold(ServerPlayer player) {
        RecoveryHold hold = recoveryHolds.get(player.getUUID());
        if (hold.captured == null || hold.disconnected) return;
        closeMenu(player);
        player.fallDistance = 0;
        if (!hold.running && System.nanoTime() >= hold.retryAt && state.config().storage().automaticRecovery()) requestAutomaticRecovery(hold);
    }

    private CompoundTag activationFallbackInputs(RecoveryHold hold, dev.yu.mwi.storage.RecoveryInspection inspection) {
        CompoundTag inputs = new CompoundTag();
        Map<String, WorldIdentity> loadedWorlds = new HashMap<>();
        for (var level : server.getAllLevels()) {
            WorldIdentity world = WorldIdentities.from(level); loadedWorlds.put(world.name(), world);
        }
        for (ProfileKey required : hold.required) {
            if (inspection.profiles().containsKey(required)) continue;
            CompoundTag source = new CompoundTag();
            if (inspection.profiles().keySet().stream().noneMatch(key -> key.shareId().equals(required.shareId()))) {
                CompoundTag bootstrap = hold.detached.getCompound(AdapterIds.id(required.shareId()).toString());
                source.put("first_profile_input", bootstrap.copy());
            } else if (required.scope().type() == ScopeKey.Type.GROUP) {
                // Match ProfileMigrationService's candidate set; an unrelated scope must not
                // reset a failed activation, while a corrected candidate must be retryable.
                inspection.profiles().forEach((key, stored) -> {
                    if (key.scope().type() != ScopeKey.Type.WORLD || key.gameMode() != required.gameMode()
                            || !key.shareId().equals(required.shareId())) return;
                    WorldIdentity world = loadedWorlds.getOrDefault(key.scope().id(), WorldIdentities.fromName(key.scope().id()));
                    if (!state.resolver().resolve(world, key.shareId()).equals(required.scope())) return;
                    CompoundTag candidate = new CompoundTag(); candidate.put("payload", stored.payload());
                    candidate.putString("saved_at", stored.savedAt().toString()); source.put(key.toString(), candidate);
                });
                source.putString("migration_policy", state.config().model().migration().toString());
            }
            inputs.put(required.toString(), source);
        }
        return inputs;
    }

    public java.time.ZoneId restoreTimeZone() { return java.time.ZoneId.of(state.config().storage().restoreTimeZone()); }

    public String recoveryStatus(UUID owner) {
        RecoveryHold hold = recoveryHolds.get(owner);
        if (hold != null) {
            String backend = hold.inspection == null ? "VERIFY_SOURCE" : hold.inspection.state();
            String activation = hold.activation.getString("state");
            if (activation.isEmpty()) activation = "WAITING_FOR_SOURCE";
            return "存储=" + backend + "，玩家激活=" + activation + "，尝试=" + hold.activation.getInt("attempts")
                    + "/3。" + hold.activation.getString("last_error") + " 返回范围=" + (hold.returnDimension == null ? "尚未确定" : hold.returnDimension.location())
                    + "；所需档案=" + requiredSummary(hold.required) + "。原档案保留。";
        }
        return sessions.containsKey(owner) ? "玩家激活=ACTIVE，正常会话已可用。" : "玩家离线；应用和保存将在登录时核验。";
    }

    private static String requiredSummary(Set<ProfileKey> keys) {
        if (keys == null) return "尚未确定";
        return keys.stream().sorted(java.util.Comparator.comparing(Object::toString))
                .map(key -> key.shareId() + "@" + key.scope().type().name().toLowerCase(java.util.Locale.ROOT)
                        + ":" + key.scope().id() + "/" + key.gameMode().name())
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** Bounded status files only: synchronous output is available within the same RCON response. */
    public List<String> restoreStatusNow(UUID owner) throws IOException {
        requireServerThread();
        List<String> lines = new ArrayList<>(); lines.add(recoveryStatus(owner));
        CompoundTag activation = store.activationTask(owner), result = store.restoreResult(owner);
        if (!activation.isEmpty()) {
            lines.add("上次持久化激活=" + activation.getString("state") + "，尝试=" + activation.getInt("attempts") + "/3"
                    + "，原因=" + activation.getString("last_error"));
            lines.add("返回维度=" + activation.getString("return_dimension") + "，模式=" + activation.getString("return_game_mode"));
            List<String> keys = new ArrayList<>();
            for (var tag : activation.getList("required_profiles", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                ProfileKey key = TransitionJournal.decodeKey((CompoundTag) tag);
                keys.add(key.shareId() + "@" + key.scope().type() + ":" + key.scope().id() + "/" + key.gameMode());
            }
            if (!keys.isEmpty()) lines.add("实际所需档案=" + String.join(",", keys));
        }
        if (!result.isEmpty()) lines.add("恢复命令=" + result.getUUID("operation_id") + "，目标=" + result.getString("selector")
                + "，结果状态=" + result.getString("state") + "，结果=" + result.getString("message"));
        return List.copyOf(lines);
    }

    public java.util.concurrent.CompletableFuture<List<String>> describeRestore(UUID owner, boolean statusOnly) {
        requireServerThread();
        RecoveryHold hold = recoveryHolds.get(owner); Session session = sessions.get(owner);
        Set<ProfileKey> known = hold != null && hold.required != null ? hold.required
                : session == null ? Set.of() : Set.copyOf(session.profiles().values());
        String onlineStatus = recoveryStatus(owner); java.time.ZoneId zone = restoreTimeZone();
        return recoveryExecutor.detached(() -> {
            List<String> lines = new ArrayList<>(); lines.add(onlineStatus);
            CompoundTag request = store.restoreRequest(owner), activation = store.activationTask(owner);
            if (request.hasUUID("operation_id")) lines.add("恢复任务=" + request.getUUID("operation_id") + " 状态=" + request.getString("state"));
            if (!activation.isEmpty()) lines.add("持久化激活状态=" + activation.getString("state") + "，尝试=" + activation.getInt("attempts"));
            if (statusOnly) return List.copyOf(lines);
            Set<ProfileKey> required = known;
            if (required.isEmpty()) {
                try { required = RestorePlans.keys(store.restoreContext(owner), owner); }
                catch (IOException unknown) { lines.add(unknown.getMessage()); }
            }
            List<dev.yu.mwi.storage.RestorePoint> points = store.restorePoints(owner);
            lines.add("恢复记录时区：" + zone + "（每行显示 UTC 偏移）");
            for (var point : points.stream().limit(100).toList()) {
                String scopes = point.profiles().keySet().stream().map(key -> key.scope().id()).distinct().sorted()
                        .collect(java.util.stream.Collectors.joining("/"));
                String shares = point.profiles().keySet().stream().map(ProfileKey::shareId).distinct().sorted()
                        .collect(java.util.stream.Collectors.joining(","));
                String missing = required.stream().filter(key -> !point.profiles().containsKey(key))
                        .map(key -> key.shareId() + "@" + key.scope().id()).sorted()
                        .collect(java.util.stream.Collectors.joining(","));
                CompoundTag summary = new CompoundTag();
                for (var profile : point.profiles().entrySet()) summary.putString(profile.getKey().toString(),
                        dev.yu.mwi.storage.NbtChecksums.canonicalSha256(profile.getValue().payload()));
                lines.add(RestorePointSelector.label(point, zone, points) + " " + point.committedAt().atZone(zone).getOffset()
                        + " " + scopes + " [" + shares + "] " + (point.completeFor(required) ? "可恢复" : "部分数据/范围待核验")
                        + " 来源=" + point.source() + " 摘要=" + dev.yu.mwi.storage.NbtChecksums.canonicalSha256(summary).substring(0,16)
                        + (missing.isEmpty() ? "" : " 缺少=" + missing)
                        + (point.problems().isEmpty() ? "" : " " + String.join(";", point.problems())));
            }
            if (points.isEmpty()) lines.add("没有可验证的已提交恢复点。原始档案不会被空数据替代。");
            return List.copyOf(lines);
        });
    }

    public RestoreCommandTask requestRestore(UUID owner, String selector, String operator) {
        requireServerThread();
        if (!"latest".equals(selector) && !selector.matches("#[0-9]+")
                && !selector.matches("[0-9]{8}(-[0-9]{6}(\\.[0-9]{3})?)?"))
            throw new IllegalArgumentException("恢复目标应为 latest 或 list 中的时间 YYYYMMDD-HHmmss[.SSS]");
        RestoreCommandTask existing = restoreCommands.get(owner);
        if (existing != null && !existing.completion().isDone()) {
            if (!existing.selector().equals(selector)) throw new IllegalArgumentException("该玩家已有不同目标的恢复任务，请先查询状态");
            return existing;
        }
        RestoreCommandTask task = new RestoreCommandTask(UUID.randomUUID(), selector,
                new java.util.concurrent.CompletableFuture<>(), new java.util.concurrent.CompletableFuture<>());
        CompoundTag receipt = new CompoundTag(); receipt.putUUID("operation_id", task.id());
        receipt.putString("selector", selector); receipt.putString("state", "ACCEPTED");
        receipt.putString("message", "任务已受理，尚无完成结果"); receipt.putLong("started_at", System.currentTimeMillis());
        try { store.saveRestoreResult(owner, receipt); }
        catch (IOException failure) { throw new java.io.UncheckedIOException("无法持久化恢复任务受理记录", failure); }
        restoreCommands.put(owner, task);
        task.work().whenComplete((message, failure) -> server.execute(() -> {
            Throwable terminalFailure = failure;
            CompoundTag result = receipt.copy();
            result.putString("state", failure == null ? "RESULT_AVAILABLE" : "FAILED");
            result.putString("message", failure == null ? message : meaningfulMessage(failure));
            result.putLong("finished_at", System.currentTimeMillis());
            try { store.saveRestoreResult(owner, result); }
            catch (IOException persistenceFailure) { terminalFailure = persistenceFailure; }
            if (restoreCommands.remove(owner, task)) {
                RecoveryHold current = recoveryHolds.get(owner);
                if (current != null && current.commandPreparing) {
                    current.commandPreparing = false;
                    requestAutomaticRecovery(current);
                }
            }
            if (terminalFailure == null) task.completion().complete(message);
            else task.completion().completeExceptionally(terminalFailure);
        }));
        if ("latest".equals(selector)) {
            try {
                CompoundTag pending = store.restoreRequest(owner);
                if ("PREPARED".equals(pending.getString("state")))
                    throw new IOException("已有历史恢复任务 " + pending.getString("selector")
                            + " 待执行；latest 不会替换或代为执行。请查询 status 或继续原目标。");
                ServerPlayer player = server.getPlayerList().getPlayer(owner);
                if (player != null && !isRecoveryHeld(player)) checkpointImmediately(player, "administrator_latest_current_backup");
                inspectAutomaticRecovery(owner, true).whenComplete((inspection, failure) -> server.execute(() -> {
                    if (failure != null) task.work().completeExceptionally(failure);
                    else if (player != null) {
                        Session session = sessions.get(owner);
                        if (session != null && !inspection.ready(Set.copyOf(session.profiles().values())))
                            task.work().complete("WAITING_FOR_SOURCE：当前存储核验未通过，未执行历史回滚。" + recoveryStatus(owner));
                        else task.work().complete(recoveryStatus(owner));
                    }
                    else if (inspection != null && inspection.unavailable().isEmpty() && inspection.pending().isEmpty())
                        task.work().complete("已核验当前权威状态；玩家离线，待下次登录完成应用、验证与保存。");
                    else task.work().complete("WAITING_FOR_SOURCE：当前可靠来源不足，没有回滚到更旧背包。");
                }));
            } catch (Exception failure) { task.work().completeExceptionally(failure); }
        } else prepareHistoricalRestore(owner, selector, operator, task);
        return task;
    }

    private void prepareHistoricalRestore(UUID owner, String selector, String operator, RestoreCommandTask task) {
        RecoveryHold existing = recoveryHolds.get(owner);
        if (existing != null && existing.running && existing.completion != null) {
            existing.completion.whenComplete((ignored, failure) -> server.execute(() -> prepareHistoricalRestore(owner, selector, operator, task)));
            return;
        }
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player != null && !isRecoveryHeld(player)) checkpointImmediately(player, "before_administrator_historical_restore");
            if (player != null) { prepareLogin(player); recoveryHolds.get(owner).commandPreparing = true; beginRecoveryHold(player); }
            RecoveryHold held = recoveryHolds.get(owner);
            Set<ProfileKey> keys = held == null ? Set.of() : held.required;
            long fence = held == null ? snapshotWriter.fence(owner) : held.fence;
            UUID incident = held == null ? null : held.incident;
            CompoundTag detached = held == null ? null : held.detached.copy();
            java.time.ZoneId zone = restoreTimeZone(); long configGeneration = state.generation();
            recoveryExecutor.detached(() -> {
                var inspection = snapshotWriter.recover(owner, fence, incident, detached, true);
                Set<ProfileKey> required = keys.isEmpty() ? RestorePlans.keys(store.restoreContext(owner), owner) : keys;
                return RestorePlans.prepare(store, owner, selector, required, inspection, zone, operator);
            }).whenComplete((request, failure) -> server.execute(() -> {
                RecoveryHold current = recoveryHolds.get(owner);
                if (current != null) current.commandPreparing = false;
                if (stopping || state.generation() != configGeneration) {
                    task.work().completeExceptionally(new IOException("服务或共享配置已变化，恢复任务保留待核验")); return;
                }
                if (failure != null) {
                    task.work().completeExceptionally(failure);
                    if (current != null && !current.disconnected) requestAutomaticRecovery(current);
                    return;
                }
                if ("ACTIVE".equals(request.getString("state"))) {
                    task.work().complete("同一恢复点已完成，未重复回档或发放物品。");
                    if (current != null) requestAutomaticRecovery(current);
                } else if (current == null || current.disconnected) {
                    task.work().complete("已准备恢复点 " + selector + "；玩家离线，将在下次登录完成应用校验和保存。操作=" + request.getUUID("operation_id"));
                } else {
                    requestAutomaticRecovery(current);
                    current.completion.whenComplete((ignored, activationFailure) -> server.execute(() -> {
                        if (activationFailure != null) task.work().completeExceptionally(activationFailure);
                        else task.work().complete(recoveryStatus(owner));
                    }));
                }
            }));
        } catch (Exception failure) { task.work().completeExceptionally(failure); }
    }

    public java.util.concurrent.CompletableFuture<dev.yu.mwi.storage.RecoveryInspection> inspectAutomaticRecovery(
            UUID owner, boolean repair) {
        requireServerThread();
        RecoveryHold hold = recoveryHolds.get(owner);
        if (hold != null && !hold.disconnected && repair) {
            if (hold.commandPreparing) return java.util.concurrent.CompletableFuture.failedFuture(
                    new IOException("管理员恢复任务正在准备，请查询现有任务状态"));
            hold.retryAt = 0;
            hold.manualRetry = true;
            requestAutomaticRecovery(hold);
            return hold.completion;
        }
        if (sessions.containsKey(owner)) return snapshotWriter.inspectInQueue(owner, repair);
        if (!repair) return recoveryExecutor.submit(owner, "inspect-only", () -> store.inspectRecovery(owner, false));
        long fence = snapshotWriter.fence(owner);
        backgroundRecoveryAt.put(owner, nextRecoveryTime());
        var result = recoveryExecutor.submit(owner, "manual-retry",
                () -> snapshotWriter.recover(owner, fence, null, null, true));
        result.thenAccept(inspection -> server.execute(() -> {
            if (stopping || recoveryHolds.containsKey(owner) || sessions.containsKey(owner)) return;
            try {
                if (inspection.pending().isEmpty()) {
                    snapshotWriter.installRecovered(owner, fence, inspection);
                    snapshotWriter.forget(owner);
                }
                if ("RESOLVED".equals(inspection.state())) backgroundRecoveryAt.remove(owner);
            } catch (IOException changed) { logger.warn("Manual recovery remains pending for {}", owner, changed); }
        }));
        return result;
    }

    private static boolean recoverablePersistenceFailure(Throwable failure) {
        boolean dataFailure = false;
        Throwable root = failure;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof Error) return false;
            if (cause instanceof dev.yu.mwi.storage.ProfileCorruptionException) dataFailure = true;
            root = cause;
        }
        return dataFailure || root instanceof IOException || root instanceof java.util.concurrent.RejectedExecutionException;
    }

    private long nextRecoveryTime() {
        return System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(state.config().storage().recoveryRetrySeconds());
    }

    private static final class RecoveryHold {
        final ServerPlayer owner;
        final UUID incident = UUID.randomUUID();
        long fence;
        Map<SharableAdapter, CompoundTag> captured;
        CompoundTag detached;
        CompoundTag loadedPlayer;
        Set<ProfileKey> required;
        net.minecraft.world.level.GameType mode;
        ResourceKey<Level> returnDimension;
        net.minecraft.world.phys.Vec3 returnPosition;
        float returnYaw, returnPitch;
        dev.yu.mwi.storage.RecoveryInspection inspection;
        CompoundTag activation = new CompoundTag();
        CompoundTag request = new CompoundTag();
        java.util.concurrent.CompletableFuture<dev.yu.mwi.storage.RecoveryInspection> completion;
        boolean running, displayed, disconnected, manualRetry, commandPreparing;
        long retryAt;
        RecoveryHold(ServerPlayer owner, long fence) { this.owner = owner; this.fence = fence; }
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
        long started = System.nanoTime();
        try {
            return store.nextRevision(player.getUUID());
        } catch (IOException failure) {
            throw new TransitionException("Unable to allocate profile revision", failure);
        } finally {
            synchronized (revisionAllocationLatenciesNanos) {
                revisionAllocationLatenciesNanos.add(System.nanoTime() - started);
                if (revisionAllocationLatenciesNanos.size() > 2_048) {
                    revisionAllocationLatenciesNanos.subList(0, 1_024).clear();
                }
            }
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

    private record ArchiveRequest(ProfileKey key, String category, String reason, CompoundTag details) {
        private ArchiveRequest(ProfileKey key, String category, String reason) {
            this(key, category, reason, new CompoundTag());
        }

        private ArchiveRequest {
            details = details.copy();
        }

        private ProfileArchive toStoreArchive() {
            return new ProfileArchive(key, category, reason, details);
        }
    }

    private record Session(
            ServerPlayer owner,
            ResourceKey<Level> dimension,
            WorldIdentity world,
            GameModeBucket gameMode,
            Map<String, ProfileKey> profiles,
            Map<String, SharableAdapter> activeAdapters,
            long configGeneration,
            LocationTracker location,
            SessionPhase phase
    ) {
        private Session {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(gameMode, "gameMode");
            profiles = Map.copyOf(profiles);
            activeAdapters = Map.copyOf(activeAdapters);
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(phase, "phase");
        }

        private Session activate() {
            return phase == SessionPhase.ACTIVE
                    ? this
                    : new Session(
                            owner, dimension, world, gameMode, profiles, activeAdapters,
                            configGeneration, location, SessionPhase.ACTIVE);
        }
    }

    private enum SessionPhase {
        PROVISIONAL,
        ACTIVE;

        private String logName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final class LocationTracker {
        private boolean known;
        private String dimension;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;

        private LocationTracker(ServerPlayer player) {
            update(player);
        }

        private LocationTracker() {
        }

        private static LocationTracker unknown() {
            return new LocationTracker();
        }

        private void update(ServerPlayer player) {
            known = true;
            dimension = player.serverLevel().dimension().location().toString();
            x = player.getX();
            y = player.getY();
            z = player.getZ();
            yaw = player.getYRot();
            pitch = player.getXRot();
        }

        private Optional<CompoundTag> payload() {
            if (!known) {
                return Optional.empty();
            }
            CompoundTag payload = new CompoundTag();
            payload.putString("dimension", dimension);
            payload.putDouble("x", x);
            payload.putDouble("y", y);
            payload.putDouble("z", z);
            payload.putFloat("yaw", yaw);
            payload.putFloat("pitch", pitch);
            return Optional.of(payload);
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
            long queued,
            long rejected,
            int activeWorkers,
            long p95CommitLatencyMillis,
            long p99CommitLatencyMillis,
            int quarantinedPlayers,
            long quarantineEvents,
            long p95RevisionAllocationLatencyMillis,
            long p99RevisionAllocationLatencyMillis,
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

    public record CompatibilityMetrics(
            long staleAdvancementsSkipped,
            long staleCriteriaSkipped,
            long profilesCompatibilityDegraded,
            long advancementAliasesMigrated,
            long criterionAliasesMigrated,
            long missingItemStacksQuarantined,
            long missingComponentStacksQuarantined,
            long itemAliasesApplied,
            long missingItemRepairFailures) {
    }

    public record AdvancementRepairReport(
            int scannedProfiles,
            int modifiedProfiles,
            int advancementAliasesMigrated,
            int criterionAliasesMigrated,
            int staleAdvancementsSkipped,
            int staleCriteriaSkipped,
            long archiveRevision,
            boolean applied,
            List<String> errors) {
        public AdvancementRepairReport {
            errors = List.copyOf(errors);
        }
    }

    public record BedSpawnRepairReport(
            int scannedProfiles,
            int matchedProfiles,
            int clearedProfiles,
            long revision,
            boolean applied,
            List<BedSpawnProfileInspection> profiles) {
        public BedSpawnRepairReport {
            profiles = List.copyOf(profiles);
        }
    }

    public record BedSpawnProfileInspection(
            ProfileKey key,
            long profileRevision,
            boolean present,
            Optional<String> dimension,
            Optional<Boolean> loaded,
            Optional<String> validationError) {
        public BedSpawnProfileInspection {
            Objects.requireNonNull(key, "key");
            dimension = Objects.requireNonNull(dimension, "dimension");
            loaded = Objects.requireNonNull(loaded, "loaded");
            validationError = Objects.requireNonNull(validationError, "validationError");
        }

        public String scopeSelector() {
            return bedSpawnScopeSelector(key);
        }

        public boolean requiresClear() {
            return present || validationError.isPresent();
        }
    }

    public record MissingItemRepairReport(
            int scannedProfiles,
            int modifiedProfiles,
            int quarantinedStacks,
            int missingItemStacks,
            int missingComponentStacks,
            int aliasesApplied,
            long archiveRevision,
            boolean applied,
            Map<String, Integer> byNamespace,
            Map<String, Integer> byShare,
            List<String> errors) {
        public MissingItemRepairReport {
            byNamespace = Map.copyOf(byNamespace);
            byShare = Map.copyOf(byShare);
            errors = List.copyOf(errors);
        }
    }

    public record MissingItemArchiveSummary(
            String entryId,
            ProfileKey sourceKey,
            long archiveRevision,
            String logicalSlot,
            String reason,
            String itemId) {
    }

    public record MissingItemBatchRepairReport(
            int availablePlayers,
            int processedPlayers,
            int scannedProfiles,
            int modifiedProfiles,
            int quarantinedStacks,
            int appliedPlayers,
            boolean truncated,
            List<String> errors) {
        public MissingItemBatchRepairReport {
            errors = List.copyOf(errors);
        }
    }

    public record MissingItemRestoreReport(
            String entryId, ProfileKey targetKey, String logicalSlot, long revision) {
    }

    private static final class CompatibilityDiagnostics {
        private static final int MAX_DEDUPLICATION_KEYS = 8_192;
        private final LinkedHashMap<CompatibilityWarningKey, Boolean> warnings =
                new LinkedHashMap<>(256, 0.75F, true);
        private final LongAdder staleAdvancementsSkipped = new LongAdder();
        private final LongAdder staleCriteriaSkipped = new LongAdder();
        private final LongAdder profilesCompatibilityDegraded = new LongAdder();
        private final LongAdder advancementAliasesMigrated = new LongAdder();
        private final LongAdder criterionAliasesMigrated = new LongAdder();
        private final LongAdder missingItemStacksQuarantined = new LongAdder();
        private final LongAdder missingComponentStacksQuarantined = new LongAdder();
        private final LongAdder itemAliasesApplied = new LongAdder();
        private final LongAdder missingItemRepairFailures = new LongAdder();

        private synchronized boolean record(
                ProfileKey key, long revision, AdvancementCompatibilityReport report) {
            CompatibilityWarningKey warningKey = new CompatibilityWarningKey(key, revision, "advancements");
            if (warnings.put(warningKey, Boolean.TRUE) != null) {
                return false;
            }
            if (warnings.size() > MAX_DEDUPLICATION_KEYS) {
                warnings.remove(warnings.keySet().iterator().next());
            }
            staleAdvancementsSkipped.add(report.staleAdvancementsSkipped());
            staleCriteriaSkipped.add(report.staleCriteriaSkipped());
            profilesCompatibilityDegraded.increment();
            advancementAliasesMigrated.add(report.advancementAliasesMigrated());
            criterionAliasesMigrated.add(report.criterionAliasesMigrated());
            return true;
        }

        private CompatibilityMetrics snapshot() {
            return new CompatibilityMetrics(
                    staleAdvancementsSkipped.sum(),
                    staleCriteriaSkipped.sum(),
                    profilesCompatibilityDegraded.sum(),
                    advancementAliasesMigrated.sum(),
                    criterionAliasesMigrated.sum(),
                    missingItemStacksQuarantined.sum(),
                    missingComponentStacksQuarantined.sum(),
                    itemAliasesApplied.sum(),
                    missingItemRepairFailures.sum());
        }

        private synchronized boolean recordItems(
                ProfileKey key, long revision, ItemCompatibilityReport report) {
            String reasons = report.issues().stream().map(issue -> issue.reason().name())
                    .distinct().sorted().collect(java.util.stream.Collectors.joining(","));
            CompatibilityWarningKey warningKey = new CompatibilityWarningKey(
                    key, revision, "items:" + reasons + ":aliases=" + (report.aliasesApplied() > 0));
            if (warnings.put(warningKey, Boolean.TRUE) != null) {
                return false;
            }
            if (warnings.size() > MAX_DEDUPLICATION_KEYS) {
                warnings.remove(warnings.keySet().iterator().next());
            }
            missingItemStacksQuarantined.add(report.missingItems());
            missingComponentStacksQuarantined.add(report.missingComponents());
            itemAliasesApplied.add(report.aliasesApplied());
            profilesCompatibilityDegraded.increment();
            return true;
        }
    }

    private record CompatibilityWarningKey(ProfileKey key, long revision, String reasonSet) {
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
