package dev.yu.mwi.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import dev.yu.mwi.platform.MwiServerController;
import java.util.UUID;

/** Diagnostic and retry entrypoints reuse the automatic recovery implementation. */
public final class AutomaticRecoveryCommands {
    private AutomaticRecoveryCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, MwiServerController controller) {
        RestoreCommands.register(dispatcher, controller);
        dispatcher.register(Commands.literal("mwi").requires(source -> source.hasPermission(2))
                .then(Commands.literal("recovery")
                        .then(Commands.literal("inspect").then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(context -> run(context.getSource(), controller, UuidArgument.getUuid(context, "uuid"), false))))
                        .then(Commands.literal("retry").then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(context -> run(context.getSource(), controller, UuidArgument.getUuid(context, "uuid"), true))
                                .then(Commands.literal("--dry-run").executes(context -> run(context.getSource(), controller,
                                        UuidArgument.getUuid(context, "uuid"), false)))))));
    }
    private static int run(CommandSourceStack source, MwiServerController controller, UUID owner, boolean repair) {
        long start = System.nanoTime();
        controller.requireService().inspectAutomaticRecovery(owner, repair).whenComplete((result, failure) ->
                source.getServer().execute(() -> {
                    if (failure != null) {
                        source.sendFailure(Component.literal("恢复检查未完成，原数据保持封存；详情见服务器日志。"));
                    } else if (result == null) {
                        source.sendSuccess(() -> Component.literal("已加入该玩家的恢复任务，玩家保持恢复会话。"), false);
                    } else {
                        source.sendSuccess(() -> Component.literal("恢复状态=" + result.state() + " generation=" + result.generation()
                                + " 健康档案=" + result.profiles().size() + " 待恢复档案=" + result.unavailable().size()
                                + " 未决事务/元数据=" + result.pending().size() + " 尝试=" + result.attempts()
                                + " 耗时=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + "ms"), false);
                        result.pending().stream().limit(8).forEach(problem -> source.sendFailure(Component.literal(problem)));
                        source.sendSuccess(() -> Component.literal(controller.requireService().recoveryStatus(owner)), false);
                    }
                }));
        return 1;
    }
}
