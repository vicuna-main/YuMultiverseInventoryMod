package dev.yu.mwi.platform;

import dev.yu.mwi.adapter.vanilla.VanillaAdapters;
import dev.yu.mwi.api.AdapterRegistry;
import dev.yu.mwi.api.SharableAdapter;
import dev.yu.mwi.config.ConfigException;
import dev.yu.mwi.config.ConfigManager;
import dev.yu.mwi.config.MwiConfig;
import dev.yu.mwi.config.RuntimeConfig;
import dev.yu.mwi.domain.WorldGroup;
import dev.yu.mwi.domain.ShareCatalog;
import dev.yu.mwi.integration.cosmeticarmor.CosmeticArmorInventoryAdapter;
import dev.yu.mwi.integration.curios.CuriosInventoryAdapter;
import dev.yu.mwi.integration.trashslot.TrashSlotInventoryAdapter;
import dev.yu.mwi.service.InventoryIsolationService;
import dev.yu.mwi.service.ReloadReport;
import dev.yu.mwi.service.TransitionException;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Owns the server-scoped configuration, adapters, and isolation service. */
public final class MwiServerController {
    private static final List<RequiredAdapter> REQUIRED_MOD_ADAPTERS = List.of(
            new RequiredAdapter(Set.of("curios"), "curios"),
            new RequiredAdapter(Set.of("cosmeticarmorreworked"), "cosmetic_armor"),
            new RequiredAdapter(Set.of("trashslot"), "trash_slot")
    );

    private final Logger logger;
    private MinecraftServer server;
    private ConfigManager configManager;
    private AdapterRegistry adapters;
    private InventoryIsolationService service;
    private final Map<UUID, ServerPlayer> pendingLogouts = new LinkedHashMap<>();

    public MwiServerController(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void start(MinecraftServer startingServer) throws ConfigException, IOException {
        Objects.requireNonNull(startingServer, "startingServer");
        requireServerThread(startingServer);
        if (service != null) {
            if (server == startingServer) {
                return;
            }
            throw new IllegalStateException("Yu Multiverse Inventory is already attached to another server");
        }

        ConfigManager candidateManager = new ConfigManager(FMLPaths.CONFIGDIR.get());
        RuntimeConfig config = candidateManager.loadOrCreate();
        AdapterRegistry candidateAdapters = new AdapterRegistry();
        // Restore vanilla equipment first so modded slot capacities that depend on attributes are
        // already established before Curios and the cosmetic inventories are applied.
        VanillaAdapters.registerAll(candidateAdapters);
        if (ModList.get().isLoaded("curios")) {
            candidateAdapters.register(new CuriosInventoryAdapter());
        }
        if (ModList.get().isLoaded("cosmeticarmorreworked")) {
            candidateAdapters.register(new CosmeticArmorInventoryAdapter());
        }
        if (ModList.get().isLoaded("trashslot")) {
            candidateAdapters.register(new TrashSlotInventoryAdapter());
        }
        validateRequiredAdapters(config, candidateAdapters);

        InventoryIsolationService candidateService =
                new InventoryIsolationService(startingServer, candidateAdapters, config, logger);
        server = startingServer;
        configManager = candidateManager;
        adapters = candidateAdapters;
        service = candidateService;
        pendingLogouts.clear();
        logger.info("Yu Multiverse Inventory started with {} adapters and {} world groups",
                candidateAdapters.size(), config.model().groups().size());
    }

    public void stop(MinecraftServer stoppingServer) throws IOException {
        Objects.requireNonNull(stoppingServer, "stoppingServer");
        requireServerThread(stoppingServer);
        if (service == null || server != stoppingServer) {
            return;
        }
        flushPendingLogouts(stoppingServer);
        try {
            service.close();
        } finally {
            pendingLogouts.clear();
            service = null;
            adapters = null;
            configManager = null;
            server = null;
        }
        logger.info("Yu Multiverse Inventory stopped");
    }

    public void login(ServerPlayer player) throws TransitionException {
        pendingLogouts.remove(player.getUUID());
        requireService(player.server).login(player);
    }

    public void dimensionChanged(
            ServerPlayer player,
            ResourceKey<Level> fromDimension,
            ResourceKey<Level> toDimension) throws TransitionException {
        requireService(player.server).dimensionChanged(player, fromDimension, toDimension);
    }

    public void tick(ServerPlayer player) throws TransitionException {
        InventoryIsolationService current = service;
        if (current != null && player.server == server) {
            current.tick(player);
        }
    }

    public void respawned(ServerPlayer player) throws TransitionException {
        requireService(player.server).respawned(player);
    }

    /** Defers logout persistence until SaveToFile, after hybrid-server quit handlers have run. */
    public void markLogoutPending(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (service != null && player.server == server) {
            pendingLogouts.put(player.getUUID(), player);
        }
    }

    /** Aligns MWI durability with vanilla saves; a pending logout is finalized after hybrid quit handlers. */
    public void playerSaved(ServerPlayer player) throws TransitionException {
        Objects.requireNonNull(player, "player");
        ServerPlayer pending = pendingLogouts.remove(player.getUUID());
        if (pending != null) {
            requireService(pending.server).logout(pending);
        } else {
            requireService(player.server).checkpoint(player, "vanilla_player_save");
        }
    }

    /** End-of-tick fallback for hybrid servers that omit or reorder SaveToFile during disconnect. */
    public void flushPendingLogouts(MinecraftServer tickingServer) {
        if (service == null || server != tickingServer || pendingLogouts.isEmpty()) {
            return;
        }
        List<ServerPlayer> pending = List.copyOf(pendingLogouts.values());
        pendingLogouts.clear();
        for (ServerPlayer player : pending) {
            try {
                requireService(player.server).logout(player);
            } catch (TransitionException | RuntimeException | LinkageError failure) {
                quarantine(player, failure);
            }
        }
    }

    /** Loads and validates a candidate, previews it, then atomically switches the runtime state and reconciles players. */
    public ReloadReport reload() throws ConfigException, TransitionException {
        InventoryIsolationService currentService = requireService();
        ConfigManager currentManager = Objects.requireNonNull(configManager, "configManager");
        AdapterRegistry currentAdapters = Objects.requireNonNull(adapters, "adapters");
        RuntimeConfig previous = currentManager.active();

        RuntimeConfig candidate = currentManager.loadCandidate();
        validateRequiredAdapters(candidate, currentAdapters);
        ReloadReport report = currentService.dryRun(candidate);
        currentService.checkpointAll("pre_config_reload");

        // Re-read at activation time so an external edit cannot activate a candidate different from ConfigManager.active().
        RuntimeConfig activated = currentManager.reload();
        if (!activated.equals(candidate)) {
            validateRequiredAdapters(activated, currentAdapters);
            report = currentService.dryRun(activated);
        }
        currentService.activate(activated);
        try {
            currentService.reconcileAll("config_reload");
        } catch (TransitionException failure) {
            rollbackActivation(currentService, currentManager, previous, "config_reload", failure);
            throw failure;
        }
        return report;
    }

    /** Validates, persists, and activates an administrative world-group mutation. */
    public ReloadReport updateGroups(UnaryOperator<List<WorldGroup>> mutation)
            throws ConfigException, TransitionException {
        Objects.requireNonNull(mutation, "mutation");
        InventoryIsolationService currentService = requireService();
        ConfigManager currentManager = Objects.requireNonNull(configManager, "configManager");
        RuntimeConfig current = currentManager.active();
        List<WorldGroup> groups = List.copyOf(mutation.apply(current.model().groups()));
        MwiConfig replacementModel = new MwiConfig(
                current.model().shareHandling(),
                current.model().shareAliases(),
                groups,
                current.model().migration(),
                current.model().advancementMigration());
        RuntimeConfig replacement = new RuntimeConfig(
                current.schemaVersion(), replacementModel, current.storage(), current.mvinvMode(),
                current.mvinvDataDirectory());
        validateRequiredAdapters(replacement, Objects.requireNonNull(adapters, "adapters"));
        ReloadReport report = currentService.dryRun(replacement);
        currentService.checkpointAll("pre_group_command");
        currentManager.replace(replacement);
        currentService.activate(replacement);
        try {
            currentService.reconcileAll("group_command");
        } catch (TransitionException failure) {
            rollbackActivation(currentService, currentManager, current, "group_command", failure);
            throw failure;
        }
        return report;
    }

    private static void rollbackActivation(
            InventoryIsolationService service,
            ConfigManager manager,
            RuntimeConfig previous,
            String reason,
            TransitionException original) {
        service.activate(previous);
        try {
            manager.replace(previous);
        } catch (ConfigException restoreFailure) {
            original.addSuppressed(restoreFailure);
        }
        try {
            service.reconcileAll(reason + "_rollback");
        } catch (TransitionException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    public InventoryIsolationService requireService() {
        InventoryIsolationService current = service;
        if (current == null) {
            throw new IllegalStateException("Yu Multiverse Inventory is not running");
        }
        return current;
    }

    public MinecraftServer requireServer() {
        MinecraftServer current = server;
        if (current == null || service == null) {
            throw new IllegalStateException("Yu Multiverse Inventory is not running");
        }
        return current;
    }

    public Path mvinvDataDirectory() {
        String configured = requireService().snapshot().config().mvinvDataDirectory();
        Path path = Path.of(configured);
        return (path.isAbsolute() ? path : FMLPaths.GAMEDIR.get().resolve(path)).toAbsolutePath().normalize();
    }

    public void quarantine(ServerPlayer player, Throwable failure) {
        InventoryIsolationService current = service;
        if (current == null) {
            logger.error("Inventory event failed while the service was unavailable for {}",
                    player.getGameProfile().getName(), failure);
            return;
        }
        current.quarantine(player, failure);
    }

    private InventoryIsolationService requireService(MinecraftServer expectedServer) {
        InventoryIsolationService current = requireService();
        if (server != expectedServer) {
            throw new IllegalStateException("Inventory event belongs to a different Minecraft server");
        }
        return current;
    }

    private static void validateRequiredAdapters(RuntimeConfig config, AdapterRegistry registry) {
        if (!config.model().shareHandling().strictUnknownModSlots()) {
            return;
        }
        Set<String> registeredShares = new LinkedHashSet<>();
        for (SharableAdapter adapter : registry.adapters()) {
            registeredShares.add(adapter.id().getPath());
            adapter.aliases().forEach(alias -> registeredShares.add(alias.getPath()));
        }

        LinkedHashSet<String> leaves = new LinkedHashSet<>(ShareCatalog.STANDARD_LEAF_SHARES);
        leaves.addAll(registeredShares);
        ShareCatalog catalog = new ShareCatalog(leaves, config.model().shareAliases());
        Set<String> configuredShares = config.model().groups().stream()
                .flatMap(group -> group.resolveShares(catalog).stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        List<String> missing = REQUIRED_MOD_ADAPTERS.stream()
                .filter(requirement -> requirement.modIds().stream().anyMatch(ModList.get()::isLoaded))
                .filter(requirement -> !registeredShares.contains(requirement.shareId()))
                .map(requirement -> requirement.loadedModName() + " -> " + requirement.shareId())
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "strict_unknown_mod_slots rejected missing adapters: " + String.join(", ", missing));
        }

        if (configuredShares.contains("economy") && !registeredShares.contains("economy")) {
            throw new IllegalStateException(
                    "strict_unknown_mod_slots rejected configured share economy because no economy bridge is loaded");
        }
        if (ModList.get().isLoaded("curios")) {
            List<String> unsupportedCuriosShares = configuredShares.stream()
                    .filter(share -> share.startsWith("curios:"))
                    .filter(share -> !registeredShares.contains(share))
                    .sorted()
                    .toList();
            if (!unsupportedCuriosShares.isEmpty()) {
                throw new IllegalStateException("strict_unknown_mod_slots rejected unsupported Curios sub-slots: "
                        + String.join(", ", unsupportedCuriosShares)
                        + "; configure the aggregate 'curios' share instead");
            }
        }
        List<String> reservedWithoutAdapters = configuredShares.stream()
                .filter(Set.of("fashion_slots", "accessory_slots")::contains)
                .filter(share -> !registeredShares.contains(share))
                .sorted()
                .toList();
        if (!reservedWithoutAdapters.isEmpty()) {
            throw new IllegalStateException(
                    "strict_unknown_mod_slots rejected configured shares without adapters: "
                            + String.join(", ", reservedWithoutAdapters));
        }
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Yu Multiverse Inventory lifecycle must run on the server thread");
        }
    }

    private record RequiredAdapter(Set<String> modIds, String shareId) {
        private RequiredAdapter {
            modIds = Set.copyOf(modIds);
        }

        private String loadedModName() {
            return modIds.stream().filter(ModList.get()::isLoaded).findFirst().orElse(String.join("/", modIds));
        }
    }
}
