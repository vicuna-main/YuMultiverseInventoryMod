package dev.yu.mwi.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.yu.mwi.domain.ScopeKey;
import dev.yu.mwi.domain.WorldGroup;
import dev.yu.mwi.domain.WorldIdentity;
import dev.yu.mwi.integration.mvinv.MvinvLegacyStore;
import dev.yu.mwi.platform.MwiServerController;
import dev.yu.mwi.service.InventoryIsolationService;
import dev.yu.mwi.service.ReloadReport;
import dev.yu.mwi.service.WorldIdentities;
import dev.yu.mwi.storage.CacheStats;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

/** Operator commands for inspecting and safely administering the inventory service. */
public final class MwiCommands {
    private static final int REQUIRED_PERMISSION_LEVEL = 2;
    private static final int MAX_RELOAD_DETAILS = 20;

    private MwiCommands() {
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            MwiServerController controller) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(controller, "controller");

        dispatcher.register(Commands.literal("mwi")
                .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
                .then(Commands.literal("list")
                        .executes(context -> execute(context.getSource(),
                                source -> list(source, controller))))
                .then(Commands.literal("info")
                        .executes(context -> execute(context.getSource(),
                                source -> info(source, controller, null)))
                        .then(Commands.argument("world_or_group", StringArgumentType.string())
                                .suggests((context, builder) -> suggestInfoTargets(controller, builder))
                                .executes(context -> execute(context.getSource(), source -> info(
                                        source,
                                        controller,
                                        StringArgumentType.getString(context, "world_or_group"))))))
                .then(Commands.literal("reload")
                        .executes(context -> execute(context.getSource(),
                                source -> reload(source, controller))))
                .then(Commands.literal("cache")
                        .then(Commands.literal("stats")
                                .executes(context -> execute(context.getSource(),
                                        source -> cacheStats(source, controller)))))
                .then(Commands.literal("recover")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("revision", LongArgumentType.longArg(1L))
                                        .executes(context -> execute(context.getSource(), source -> recover(
                                                source,
                                                controller,
                                                EntityArgument.getPlayer(context, "player"),
                                                LongArgumentType.getLong(context, "revision")))))))
                .then(Commands.literal("group")
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("worlds", StringArgumentType.string())
                                                .suggests((context, builder) ->
                                                        suggestWorldSelectors(controller, builder))
                                                .then(Commands.argument("shares", StringArgumentType.string())
                                                        .executes(context -> execute(context.getSource(), source ->
                                                                createGroup(
                                                                        source,
                                                                        controller,
                                                                        StringArgumentType.getString(context, "name"),
                                                                        csv(context, "worlds"),
                                                                        csv(context, "shares"))))))))
                        .then(Commands.literal("add-worlds")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("worlds", StringArgumentType.string())
                                                .suggests((context, builder) ->
                                                        suggestWorldSelectors(controller, builder))
                                                .executes(context -> execute(context.getSource(), source ->
                                                        addWorlds(
                                                                source,
                                                                controller,
                                                                StringArgumentType.getString(context, "name"),
                                                                csv(context, "worlds")))))))
                        .then(Commands.literal("add-shares")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("shares", StringArgumentType.string())
                                                .executes(context -> execute(context.getSource(), source ->
                                                        addShares(
                                                                source,
                                                                controller,
                                                                StringArgumentType.getString(context, "name"),
                                                                csv(context, "shares")))))))
                        .then(Commands.literal("remove-shares")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("shares", StringArgumentType.string())
                                                .executes(context -> execute(context.getSource(), source ->
                                                        removeShares(
                                                                source,
                                                                controller,
                                                                StringArgumentType.getString(context, "name"),
                                                                csv(context, "shares")))))))
                        .then(Commands.literal("check-conflicts")
                                .executes(context -> execute(context.getSource(),
                                        source -> checkConflicts(source, controller))))));

        dispatcher.register(Commands.literal("inv")
                .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
                .then(editorArguments(controller, "inventory_contents"))
                .then(Commands.literal("inventory").then(editorArguments(controller, "inventory_contents")))
                .then(Commands.literal("backpack").then(editorArguments(controller, "inventory_contents")))
                .then(Commands.literal("armor").then(editorArguments(controller, "armor_contents")))
                .then(Commands.literal("offhand").then(editorArguments(controller, "off_hand")))
                .then(Commands.literal("off_hand").then(editorArguments(controller, "off_hand")))
                .then(Commands.literal("ender").then(editorArguments(controller, "ender_chest")))
                .then(Commands.literal("enderchest").then(editorArguments(controller, "ender_chest")))
                .then(Commands.literal("ender_chest").then(editorArguments(controller, "ender_chest")))
                .then(Commands.literal("curios").then(editorArguments(controller, "curios")))
                .then(Commands.literal("cosmetic").then(editorArguments(controller, "cosmetic_armor")))
                .then(Commands.literal("cosmetic_armor").then(editorArguments(controller, "cosmetic_armor")))
                .then(Commands.literal("fashion").then(editorArguments(controller, "cosmetic_armor")))
                .then(Commands.literal("trash").then(editorArguments(controller, "trash_slot")))
                .then(Commands.literal("trash_slot").then(editorArguments(controller, "trash_slot"))));

        dispatcher.register(Commands.literal("mvinvopen")
                .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
                .then(legacyEditorArguments(controller, MvinvLegacyStore.Section.ALL, "all"))
                .then(legacyEditorArguments(controller, MvinvLegacyStore.Section.INVENTORY, "inventory"))
                .then(legacyEditorArguments(controller, MvinvLegacyStore.Section.INVENTORY, "backpack"))
                .then(legacyEditorArguments(controller, MvinvLegacyStore.Section.ENDER_CHEST, "ender"))
                .then(legacyEditorArguments(controller, MvinvLegacyStore.Section.ENDER_CHEST, "enderchest"))
                .then(legacyEditorArguments(controller, MvinvLegacyStore.Section.ARMOR, "armor"))
                .then(legacyEditorArguments(controller, MvinvLegacyStore.Section.OFF_HAND, "offhand"))
                .then(Commands.literal("curios").then(editorArguments(controller, "curios")))
                .then(Commands.literal("cosmetic").then(editorArguments(controller, "cosmetic_armor")))
                .then(Commands.literal("fashion").then(editorArguments(controller, "cosmetic_armor")))
                .then(Commands.literal("trash").then(editorArguments(controller, "trash_slot"))));

        dispatcher.register(Commands.literal("mvinvtoken")
                .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
                .then(Commands.literal("restore")
                        .executes(context -> execute(context.getSource(), MwiCommands::restoreLegacyToken))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> legacyEditorArguments(
            MwiServerController controller, MvinvLegacyStore.Section section, String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("legacy_player", StringArgumentType.word())
                        .then(Commands.argument("legacy_world_or_group", StringArgumentType.string())
                                .suggests((context, builder) -> suggestLegacyScopes(controller, builder))
                                .executes(context -> execute(context.getSource(), source -> openLegacyInventory(
                                        source, controller, section,
                                        StringArgumentType.getString(context, "legacy_player"),
                                        StringArgumentType.getString(context, "legacy_world_or_group"),
                                        "survival", 1)))
                                .then(Commands.argument("legacy_gamemode", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            builder.suggest("survival");
                                            builder.suggest("creative");
                                            builder.suggest("adventure");
                                            builder.suggest("spectator");
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> execute(context.getSource(), source ->
                                                openLegacyInventory(
                                                        source, controller, section,
                                                        StringArgumentType.getString(context, "legacy_player"),
                                                        StringArgumentType.getString(
                                                                context, "legacy_world_or_group"),
                                                        StringArgumentType.getString(context, "legacy_gamemode"),
                                                        1)))
                                        .then(Commands.argument("legacy_page", IntegerArgumentType.integer(1))
                                                .executes(context -> execute(context.getSource(), source ->
                                                        openLegacyInventory(
                                                                source, controller, section,
                                                                StringArgumentType.getString(
                                                                        context, "legacy_player"),
                                                                StringArgumentType.getString(
                                                                        context, "legacy_world_or_group"),
                                                                StringArgumentType.getString(
                                                                        context, "legacy_gamemode"),
                                                                IntegerArgumentType.getInteger(
                                                                        context, "legacy_page"))))))));
    }

    private static int openLegacyInventory(
            CommandSourceStack source,
            MwiServerController controller,
            MvinvLegacyStore.Section section,
            String player,
            String scope,
            String gameMode,
            int page) throws Exception {
        ServerPlayer viewer = source.getPlayerOrException();
        MvinvLegacyStore store = new MvinvLegacyStore(controller.mvinvDataDirectory());
        MvinvLegacyStore.OpenProfile profile = store.open(
                player, scope, gameMode, section, viewer.registryAccess());
        MvinvRecoveryMenu.Opened opened = MvinvRecoveryMenu.open(store, profile, viewer, page);
        success(source, "Opened offline MVInv " + section.commandName() + " for " + profile.playerName()
                + " in " + profile.sourceName() + " (" + profile.gameMode().toLowerCase(java.util.Locale.ROOT) + ')'
                + (opened.pages() > 1 ? ", page " + opened.page() + '/' + opened.pages() : ""));
        if (profile.recoveryTokenSlots() > 0) {
            failure(source, profile.recoveryTokenSlots()
                    + " unavailable mod item(s) became takeable lossless recovery tokens; "
                    + "use /mvinvtoken restore after installing their mod/version");
        }
        return 1;
    }

    private static int restoreLegacyToken(CommandSourceStack source) throws Exception {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack restored = MvinvLegacyStore.restoreToken(player.getMainHandItem(), player.registryAccess());
        player.setItemInHand(InteractionHand.MAIN_HAND, restored);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastFullState();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastFullState();
        }
        success(source, "Restored the MVInv recovery token to " + restored.getHoverName().getString());
        return 1;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> editorArguments(
            MwiServerController controller, String shareId) {
        return Commands.argument("player", EntityArgument.player())
                .executes(context -> execute(context.getSource(), source -> openInventoryEditor(
                        source, controller, EntityArgument.getPlayer(context, "player"), shareId, null, 1)))
                .then(Commands.argument("world", StringArgumentType.string())
                        .suggests((context, builder) -> suggestLoadedWorlds(controller, builder))
                        .executes(context -> execute(context.getSource(), source -> openInventoryEditor(
                                source,
                                controller,
                                EntityArgument.getPlayer(context, "player"),
                                shareId,
                                StringArgumentType.getString(context, "world"),
                                1)))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> execute(context.getSource(), source -> openInventoryEditor(
                                        source,
                                        controller,
                                        EntityArgument.getPlayer(context, "player"),
                                        shareId,
                                        StringArgumentType.getString(context, "world"),
                                        IntegerArgumentType.getInteger(context, "page"))))));
    }

    private static int openInventoryEditor(
            CommandSourceStack source,
            MwiServerController controller,
            ServerPlayer target,
            String shareId,
            String requestedWorld,
            int page) throws Exception {
        ServerPlayer viewer = source.getPlayerOrException();
        InventoryIsolationService service = controller.requireService();
        service.checkpoint(target, "inventory_editor_open");
        String world = requestedWorld == null
                ? WorldIdentities.from(target.serverLevel()).name()
                : requestedWorld;
        InventoryIsolationService.EditableProfile profile = service.prepareItemEdit(target, world, shareId);
        InventoryEditorMenu.Opened opened = InventoryEditorMenu.open(service, viewer, target, profile, page);
        success(source, "Opened " + shareId + " for " + target.getGameProfile().getName()
                + " in " + profile.worldName()
                + (opened.pages() > 1 ? " (page " + opened.page() + '/' + opened.pages() + ')' : ""));
        return 1;
    }

    private static int list(CommandSourceStack source, MwiServerController controller) {
        List<WorldGroup> groups = controller.requireService().snapshot().resolver().groups();
        success(source, "Configured world groups (" + groups.size() + "):");
        groups.stream().sorted(Comparator.comparing(WorldGroup::name)).forEach(group -> success(source,
                group.name()
                        + " | worlds=" + join(group.selectors().stream().map(selector -> selector.expression()).toList())
                        + " | shares=" + join(group.shareExpressions())));
        return groups.size();
    }

    private static int info(CommandSourceStack source, MwiServerController controller, String target) {
        InventoryIsolationService service = controller.requireService();
        InventoryIsolationService.StateSnapshot snapshot = service.snapshot();
        if (target == null) {
            success(source, "Yu Multiverse Inventory: groups=" + snapshot.resolver().groups().size()
                    + ", managed shares=" + snapshot.managedShareIds().size()
                    + ", online sessions=" + snapshot.onlineSessions()
                    + ", gamemode profiles="
                    + snapshot.config().model().shareHandling().enableGameModeProfiles()
                    + ", strict mod slots="
                    + snapshot.config().model().shareHandling().strictUnknownModSlots());
            if (source.getEntity() instanceof ServerPlayer player) {
                describeWorld(source, snapshot, WorldIdentities.from(player.serverLevel()));
            }
            return 1;
        }

        Optional<WorldGroup> group = snapshot.resolver().groups().stream()
                .filter(candidate -> candidate.name().equals(target))
                .findFirst();
        if (group.isPresent()) {
            WorldGroup value = group.orElseThrow();
            success(source, "Group " + value.name()
                    + " | worlds=" + join(value.selectors().stream().map(selector -> selector.expression()).toList())
                    + " | shares=" + join(value.shareExpressions()));
            return 1;
        }

        Optional<WorldIdentity> world = StreamSupport.stream(
                        controller.requireServer().getAllLevels().spliterator(), false)
                .map(WorldIdentities::from)
                .filter(identity -> identity.hasName(target))
                .findFirst();
        if (world.isEmpty()) {
            failure(source, "No loaded world or configured group named '" + target + "'");
            return 0;
        }
        describeWorld(source, snapshot, world.orElseThrow());
        return 1;
    }

    private static void describeWorld(
            CommandSourceStack source,
            InventoryIsolationService.StateSnapshot snapshot,
            WorldIdentity world) {
        Map<String, List<String>> byScope = new LinkedHashMap<>();
        snapshot.managedShareIds().stream().sorted().forEach(share -> {
            ScopeKey scope = snapshot.resolver().resolve(world, share);
            String name = scope.type().name().toLowerCase() + ':' + scope.id();
            byScope.computeIfAbsent(name, ignored -> new ArrayList<>()).add(share);
        });
        success(source, "World " + world.name() + " | aliases=" + join(world.aliases()));
        byScope.forEach((scope, shares) -> success(source, "  " + scope + " <- " + join(shares)));
    }

    private static int reload(CommandSourceStack source, MwiServerController controller) throws Exception {
        ReloadReport report = controller.reload();
        success(source, "Configuration activated; affected players=" + report.onlinePlayersAffected()
                + ", changed shares=" + report.changedShares());
        List<String> details = report.details();
        details.stream().limit(MAX_RELOAD_DETAILS).forEach(detail -> success(source, "  " + detail));
        if (details.size() > MAX_RELOAD_DETAILS) {
            success(source, "  ... and " + (details.size() - MAX_RELOAD_DETAILS) + " more");
        }
        return 1;
    }

    private static int cacheStats(CommandSourceStack source, MwiServerController controller) {
        CacheStats stats = controller.requireService().cacheStats();
        long requests = stats.hits() + stats.misses();
        String hitRate = requests == 0L
                ? "n/a"
                : String.format(java.util.Locale.ROOT, "%.1f%%", stats.hits() * 100.0D / requests);
        success(source, "Profile cache: entries=" + stats.entries()
                + ", bytes=" + stats.bytes() + '/' + stats.maxBytes()
                + ", hits=" + stats.hits()
                + ", misses=" + stats.misses()
                + ", hit rate=" + hitRate);
        InventoryIsolationService.PersistenceMetrics persistence =
                controller.requireService().persistenceMetrics();
        success(source, "Profile I/O: depth=" + persistence.queueDepth()
                + ", oldest_ms=" + persistence.oldestTaskAgeMillis()
                + ", p95_ms=" + persistence.p95WriteLatencyMillis()
                + ", p99_ms=" + persistence.p99WriteLatencyMillis()
                + ", failures=" + persistence.failures()
                + ", coalesced=" + persistence.coalesced()
                + ", capture_p95_ms=" + persistence.p95CaptureLatencyMillis()
                + ", capture_p99_ms=" + persistence.p99CaptureLatencyMillis());
        InventoryIsolationService.LoginPerformanceMetrics login =
                controller.requireService().loginPerformanceMetrics();
        success(source, "Login transitions: count=" + login.logins()
                + ", adapter_captures=" + login.adapterCaptures()
                + ", listener_rebuilds=" + login.advancementListenerRebuilds()
                + ", capture_total_ms=" + login.totalCaptureMillis()
                + ", capture_max_batch_ms=" + login.maxCaptureBatchMillis()
                + ", sync_total_ms=" + login.totalSynchronizationMillis()
                + ", sync_max_ms=" + login.maxSynchronizationMillis());
        return 1;
    }

    private static int recover(
            CommandSourceStack source,
            MwiServerController controller,
            ServerPlayer player,
            long revision) throws Exception {
        controller.requireService().recover(player, revision);
        success(source, "Recovered revision " + revision + " for " + player.getGameProfile().getName());
        return 1;
    }

    private static int checkConflicts(CommandSourceStack source, MwiServerController controller) {
        InventoryIsolationService.StateSnapshot snapshot = controller.requireService().snapshot();
        Set<String> shares = snapshot.managedShareIds();
        List<String> conflicts = new ArrayList<>();
        for (ServerLevel level : controller.requireServer().getAllLevels()) {
            WorldIdentity world = WorldIdentities.from(level);
            shares.stream().sorted().forEach(share -> {
                List<String> matching = snapshot.resolver().matchingGroupNames(world, share);
                if (matching.size() > 1) {
                    conflicts.add(world.name() + " / " + share + " -> " + join(matching));
                }
            });
        }
        if (conflicts.isEmpty()) {
            success(source, "No conflicts found across loaded worlds and managed shares");
            return 1;
        }
        failure(source, "Found " + conflicts.size() + " group conflicts:");
        conflicts.forEach(conflict -> failure(source, "  " + conflict));
        return 0;
    }

    private static int createGroup(
            CommandSourceStack source,
            MwiServerController controller,
            String name,
            List<String> worlds,
            List<String> shares) throws Exception {
        ReloadReport report = controller.updateGroups(existing -> {
            if (existing.stream().anyMatch(group -> group.name().equals(name))) {
                throw new IllegalArgumentException("Group already exists: " + name);
            }
            List<WorldGroup> replacement = new ArrayList<>(existing);
            replacement.add(WorldGroup.of(name, worlds, shares));
            return replacement;
        });
        groupMutationResult(source, "Created group " + name, report);
        return 1;
    }

    private static int addWorlds(
            CommandSourceStack source,
            MwiServerController controller,
            String name,
            List<String> worlds) throws Exception {
        ReloadReport report = controller.updateGroups(existing -> replaceGroup(existing, name, group -> {
            LinkedHashSet<String> selectors = new LinkedHashSet<>();
            group.selectors().forEach(selector -> selectors.add(selector.expression()));
            selectors.addAll(worlds);
            return WorldGroup.of(group.name(), selectors, group.shareExpressions());
        }));
        groupMutationResult(source, "Added worlds to group " + name, report);
        return 1;
    }

    private static int addShares(
            CommandSourceStack source,
            MwiServerController controller,
            String name,
            List<String> shares) throws Exception {
        ReloadReport report = controller.updateGroups(existing -> replaceGroup(existing, name, group -> {
            LinkedHashSet<String> expressions = new LinkedHashSet<>(group.shareExpressions());
            for (String share : shares) {
                String positive = stripNegative(share);
                expressions.remove("-" + positive);
                expressions.add(positive);
            }
            return WorldGroup.of(
                    group.name(),
                    group.selectors().stream().map(selector -> selector.expression()).toList(),
                    expressions);
        }));
        groupMutationResult(source, "Added shares to group " + name, report);
        return 1;
    }

    private static int removeShares(
            CommandSourceStack source,
            MwiServerController controller,
            String name,
            List<String> shares) throws Exception {
        ReloadReport report = controller.updateGroups(existing -> replaceGroup(existing, name, group -> {
            LinkedHashSet<String> expressions = new LinkedHashSet<>(group.shareExpressions());
            for (String share : shares) {
                String positive = stripNegative(share);
                expressions.remove(positive);
                expressions.add("-" + positive);
            }
            return WorldGroup.of(
                    group.name(),
                    group.selectors().stream().map(selector -> selector.expression()).toList(),
                    expressions);
        }));
        groupMutationResult(source, "Removed shares from group " + name, report);
        return 1;
    }

    private static List<WorldGroup> replaceGroup(
            List<WorldGroup> existing,
            String name,
            java.util.function.UnaryOperator<WorldGroup> mutation) {
        List<WorldGroup> replacement = new ArrayList<>(existing.size());
        boolean found = false;
        for (WorldGroup group : existing) {
            if (group.name().equals(name)) {
                replacement.add(mutation.apply(group));
                found = true;
            } else {
                replacement.add(group);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Unknown group: " + name);
        }
        return replacement;
    }

    private static void groupMutationResult(CommandSourceStack source, String action, ReloadReport report) {
        success(source, action + "; affected players=" + report.onlinePlayersAffected()
                + ", changed shares=" + report.changedShares());
    }

    private static List<String> csv(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, String name) {
        return csv(StringArgumentType.getString(context, name), name);
    }

    private static List<String> csv(String raw, String name) {
        List<String> values = java.util.Arrays.stream(raw.split(",", -1))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must contain at least one comma-separated value");
        }
        return values;
    }

    private static CompletableFuture<Suggestions> suggestLoadedWorlds(
            MwiServerController controller,
            SuggestionsBuilder builder) {
        return suggestTokens(loadedWorldNames(controller), builder);
    }

    private static CompletableFuture<Suggestions> suggestInfoTargets(
            MwiServerController controller,
            SuggestionsBuilder builder) {
        LinkedHashSet<String> values = loadedWorldNames(controller);
        controller.requireService().snapshot().resolver().groups().forEach(group -> values.add(group.name()));
        return suggestTokens(values, builder);
    }

    private static CompletableFuture<Suggestions> suggestWorldSelectors(
            MwiServerController controller,
            SuggestionsBuilder builder) {
        LinkedHashSet<String> values = loadedWorldNames(controller);
        controller.requireService().snapshot().resolver().groups().stream()
                .flatMap(group -> group.selectors().stream())
                .map(selector -> selector.expression())
                .forEach(values::add);

        String remaining = builder.getRemaining();
        int comma = remaining.lastIndexOf(',');
        SuggestionsBuilder currentValue = comma < 0
                ? builder
                : builder.createOffset(builder.getStart() + comma + 1);
        return suggestTokens(values, currentValue);
    }

    private static CompletableFuture<Suggestions> suggestLegacyScopes(
            MwiServerController controller,
            SuggestionsBuilder builder) {
        LinkedHashSet<String> values = loadedWorldNames(controller);
        try {
            values.addAll(new MvinvLegacyStore(controller.mvinvDataDirectory()).listScopes());
        } catch (java.io.IOException unavailableArchive) {
            // Archive completion is optional; loaded-world completion remains available.
        }
        return suggestTokens(values, builder);
    }

    private static LinkedHashSet<String> loadedWorldNames(MwiServerController controller) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (ServerLevel level : controller.requireServer().getAllLevels()) {
            WorldIdentity world = WorldIdentities.from(level);
            values.add(world.name());
            values.addAll(world.aliases());
        }
        return values;
    }

    private static CompletableFuture<Suggestions> suggestTokens(
            Iterable<String> values,
            SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (String value : values) {
            String lowerCase = value.toLowerCase(java.util.Locale.ROOT);
            String escaped = escapeSuggestion(value);
            if (lowerCase.startsWith(remaining)
                    || escaped.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)
                    || matchesSuggestionSegment(remaining, lowerCase)) {
                builder.suggest(escaped);
            }
        }
        return builder.buildFuture();
    }

    private static boolean matchesSuggestionSegment(String input, String candidate) {
        for (int index = 0; index < candidate.length(); index++) {
            if ((index == 0 || "._:/".indexOf(candidate.charAt(index - 1)) >= 0)
                    && candidate.startsWith(input, index)) {
                return true;
            }
        }
        return false;
    }

    private static String escapeSuggestion(String value) {
        return StringArgumentType.escapeIfRequired(value);
    }

    private static String stripNegative(String share) {
        String normalized = share.strip();
        while (normalized.startsWith("-")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("share id must not be blank");
        }
        return normalized;
    }

    private static int execute(CommandSourceStack source, CheckedCommand command) {
        try {
            return command.run(source);
        } catch (Exception | LinkageError failure) {
            MwiCommands.failure(source, meaningfulMessage(failure));
            return 0;
        }
    }

    private static String meaningfulMessage(Throwable failure) {
        Throwable current = failure;
        while ((current.getMessage() == null || current.getMessage().isBlank()) && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String join(Iterable<String> values) {
        List<String> copy = new ArrayList<>();
        values.forEach(copy::add);
        return copy.isEmpty() ? "(none)" : String.join(", ", copy);
    }

    private static void success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GRAY), false);
    }

    private static void failure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message).withStyle(ChatFormatting.RED));
    }

    @FunctionalInterface
    private interface CheckedCommand {
        int run(CommandSourceStack source) throws Exception;
    }
}
