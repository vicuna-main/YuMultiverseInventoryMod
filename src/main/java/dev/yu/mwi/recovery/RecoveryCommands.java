package dev.yu.mwi.recovery;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import dev.yu.mwi.api.RecoveryBridgeV1;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;
import static net.minecraft.commands.Commands.*;

public final class RecoveryCommands {
    private RecoveryCommands() {}
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static int safely(CommandSourceStack source, Action action) {
        try { action.run(); return 1; }
        catch (Exception failure) { source.sendFailure(Component.literal("[背包恢复] " + failure.getMessage())); return 0; }
    }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = literal("mwirecovery").executes(c -> safely(c.getSource(), () -> RecoveryMenu.open(c.getSource().getPlayerOrException(), null, 0)));
        root.then(literal("open").then(argument("id", StringArgumentType.word()).executes(c -> safely(c.getSource(), () ->
                RecoveryMenu.open(c.getSource().getPlayerOrException(), UUID.fromString(StringArgumentType.getString(c, "id")), 0)))));
        root.then(literal("inspect").requires(s -> s.hasPermission(2)).then(argument("player", EntityArgument.player())
                .then(argument("id", StringArgumentType.word()).executes(c -> safely(c.getSource(), () -> {
                    ServerPlayer player = EntityArgument.getPlayer(c, "player");
                    var record = RecoveryBridgeV1.service(player).get(player.getUUID(), UUID.fromString(StringArgumentType.getString(c, "id")));
                    c.getSource().sendSuccess(() -> Component.literal("状态 " + record.payload().getString("state")
                            + "，版本 " + record.version() + "，核验码 " + record.checksum()
                            + "，范围 " + record.payload().getCompound("identity").getCompound("scope")
                            + "。approve 前必须核实墓碑、掉落、背包及容器无待发物品副本；已人工恢复请 close。"), false);
                })))));
        for (String decision : new String[]{"approve", "close"}) {
            root.then(literal(decision).requires(s -> s.hasPermission(2)).then(argument("player", EntityArgument.player())
                    .then(argument("id", StringArgumentType.word()).then(argument("checksum", StringArgumentType.word())
                            .then(argument("reason", StringArgumentType.greedyString()).executes(c -> safely(c.getSource(), () -> {
                                ServerPlayer player = EntityArgument.getPlayer(c, "player");
                                RecoveryBridgeV1.service(player).review(player, UUID.fromString(StringArgumentType.getString(c, "id")),
                                        StringArgumentType.getString(c, "checksum"), decision.equals("approve"), c.getSource().getTextName(),
                                        StringArgumentType.getString(c, "reason"));
                                c.getSource().sendSuccess(() -> Component.literal("核验决定已保存：" + decision + "。物品由玩家在恢复中心确认领取。"), true);
                            })))))));
        }
        root.then(literal("close-external").requires(s -> s.hasPermission(2)).then(argument("player", EntityArgument.player())
                .then(argument("id", StringArgumentType.word()).then(argument("sha256", StringArgumentType.word())
                        .then(argument("reason", StringArgumentType.greedyString()).executes(c -> safely(c.getSource(), () -> {
                            ServerPlayer player = EntityArgument.getPlayer(c, "player");
                            RecoveryBridgeV1.service(player).closeExternalSource(player, UUID.fromString(StringArgumentType.getString(c, "id")),
                                    StringArgumentType.getString(c, "sha256"), c.getSource().getTextName(), StringArgumentType.getString(c, "reason"));
                            c.getSource().sendSuccess(() -> Component.literal("已永久记录外部恢复结案，未发放物品。相同来源不能重新领取。"), true);
                        })))))));
        dispatcher.register(root);
    }

    public static void notifyPlayer(ServerPlayer player) {
        RecoveryNotifications.enqueue(player);
    }
}
