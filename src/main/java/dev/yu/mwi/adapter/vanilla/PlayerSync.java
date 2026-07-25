package dev.yu.mwi.adapter.vanilla;

import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.server.level.ServerPlayer;

final class PlayerSync {
    private PlayerSync() {
    }

    static void inventory(ServerPlayer player) {
        player.inventoryMenu.broadcastFullState();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastFullState();
        }
    }

    static void experience(ServerPlayer player) {
        player.connection.send(new ClientboundSetExperiencePacket(
                player.experienceProgress, player.totalExperience, player.experienceLevel));
    }

    static void healthAndFood(ServerPlayer player) {
        player.connection.send(new ClientboundSetHealthPacket(
                player.getHealth(), player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel()));
    }
}
