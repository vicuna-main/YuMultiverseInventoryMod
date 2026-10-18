package dev.yu.mwi.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.yu.mwi.platform.MwiServerController;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/** Human-facing names and timestamps; asynchronous completion remains queryable from console/RCON. */
public final class RestoreCommands {
    private RestoreCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, MwiServerController controller) {
        dispatcher.register(Commands.literal("mwi").requires(source -> source.hasPermission(2))
                .then(Commands.literal("restore").then(Commands.argument("restore_player", StringArgumentType.word())
                        .suggests((context,builder) -> controller.requireService().knownPlayerNames()
                                .thenCompose(names -> SharedSuggestionProvider.suggest(names,builder)))
                        .then(Commands.argument("restore_target",StringArgumentType.word())
                                .suggests((context,builder) -> {
                                    java.util.List<String> targets = new java.util.ArrayList<>(java.util.List.of("latest","list","status"));
                                    try {
                                        var service = controller.requireService();
                                        UUID owner = service.resolveKnownPlayerName(StringArgumentType.getString(context,"restore_player"));
                                        return service.restoreTargets(owner).handle((times,failure) -> {
                                            if (times != null) targets.addAll(times);
                                            return targets;
                                        }).thenCompose(values -> SharedSuggestionProvider.suggest(values,builder));
                                    } catch(Exception unknown) { return SharedSuggestionProvider.suggest(targets,builder); }
                                })
                                .executes(context -> run(context.getSource(),controller,
                                        StringArgumentType.getString(context,"restore_player"),StringArgumentType.getString(context,"restore_target")))))));
        dispatcher.register(Commands.literal("mwistatus").executes(context -> {
            var player=context.getSource().getPlayerOrException();
            context.getSource().sendSuccess(() -> Component.literal(controller.requireService().recoveryStatus(player.getUUID())),false);
            return 1;
        }));
    }
    private static int run(CommandSourceStack source, MwiServerController controller, String name, String selector) {
        try {
            var service=controller.requireService(); UUID owner=service.resolveKnownPlayerName(name);
            if ("status".equals(selector)) {
                service.restoreStatusNow(owner).forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
                return 1;
            }
            if ("list".equals(selector)) {
                source.sendSuccess(() -> Component.literal("正在核验 " + name + " 的恢复记录…"),false);
                service.describeRestore(owner,"status".equals(selector)).whenComplete((lines,failure) -> source.getServer().execute(() -> {
                    if(failure!=null) source.sendFailure(Component.literal(reason(failure)));
                    else lines.forEach(line -> source.sendSuccess(() -> Component.literal(line),false));
                })); return 1;
            }
            var task=service.requestRestore(owner,selector,source.getTextName());
            source.sendSuccess(() -> Component.literal("恢复任务 " + task.id() + " 已接收。查询：/mwi restore " + name + " status"),false);
            task.completion().whenComplete((message,failure) -> source.getServer().execute(() -> {
                if(failure!=null) source.sendFailure(Component.literal("恢复任务 " + task.id() + " 未完成：" + reason(failure)));
                else source.sendSuccess(() -> Component.literal("恢复任务 " + task.id() + "：" + message),false);
            })); return 1;
        } catch(Exception failure) { source.sendFailure(Component.literal(reason(failure))); return 0; }
    }
    private static String reason(Throwable failure) {
        while(failure.getCause()!=null) failure=failure.getCause();
        String message=failure.getMessage();
        return message==null ? failure.getClass().getSimpleName() : message.substring(0,Math.min(message.length(),512));
    }
}
