package dev.yu.mwi.platform;

import dev.yu.mwi.command.MwiCommands;
import dev.yu.mwi.service.TransitionException;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Objects;

/** NeoForge event boundary; all stateful work is delegated to the server controller. */
public final class NeoForgeEventHandler {
    private final MwiServerController controller;
    private final Logger logger;

    public NeoForgeEventHandler(MwiServerController controller, Logger logger) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        try {
            controller.start(event.getServer());
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to start Yu Multiverse Inventory", failure);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        try {
            controller.stop(event.getServer());
        } catch (IOException | LinkageError failure) {
            logger.error("Unable to stop Yu Multiverse Inventory cleanly", failure);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        MwiCommands.register(event.getDispatcher(), controller);
        dev.yu.mwi.recovery.RecoveryCommands.register(event.getDispatcher());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            runPlayerEvent(player, () -> controller.login(player));
            dev.yu.mwi.recovery.RecoveryCommands.notifyPlayer(player);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            controller.markLogoutPending(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerSave(PlayerEvent.SaveToFile event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            runPlayerEvent(player, () -> controller.playerSaved(player));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            runPlayerEvent(player, () -> controller.dimensionChanged(player, event.getFrom(), event.getTo()));
            dev.yu.mwi.recovery.RecoveryNotifications.enqueue(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            runPlayerEvent(player, () -> controller.respawned(player));
            dev.yu.mwi.recovery.RecoveryCommands.notifyPlayer(player);
        }
    }

    @SubscribeEvent
    public void onGameModeChanging(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) controller.invalidateRecoveryConfirmation(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            runPlayerEvent(player, () -> controller.tick(player));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerTick(ServerTickEvent.Post event) {
        controller.flushPendingLogouts(event.getServer());
        dev.yu.mwi.recovery.RecoveryNotifications.tick(event.getServer());
    }

    private void runPlayerEvent(ServerPlayer player, PlayerAction action) {
        try {
            action.run();
        } catch (TransitionException | RuntimeException | LinkageError failure) {
            controller.quarantine(player, failure);
        }
    }

    @FunctionalInterface
    private interface PlayerAction {
        void run() throws TransitionException;
    }
}
