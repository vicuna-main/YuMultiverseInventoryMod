package dev.yu.mwi.recovery;

import dev.yu.mwi.api.RecoveryBridgeV1;
import dev.yu.mwi.storage.RecoveryNoticeStore;
import net.minecraft.network.chat.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.util.*;

/** Sources are the durable outbox. Login rebuilds this bounded, UUID-only retry index. */
public final class RecoveryNotifications {
    static java.util.function.LongSupplier clock = System::currentTimeMillis;
    private static final Map<MinecraftServer, RecoveryNotifications> SERVICES = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final RecoveryNoticeStore notices;
    private final LinkedHashMap<UUID, Pending> pending = new LinkedHashMap<>();
    private static final class Pending {
        long due; boolean refresh = true; String session = "";
        final Set<UUID> sources = new LinkedHashSet<>();
        Pending(long due) { this.due = due; }
    }
    private RecoveryNotifications(MinecraftServer server) {
        this.server = server; notices = new RecoveryNoticeStore(server.getWorldPath(LevelResource.ROOT));
    }
    public static void register(MinecraftServer server) { SERVICES.put(server, new RecoveryNotifications(server)); }
    public static void unregister(MinecraftServer server) { SERVICES.remove(server); }
    public static void enqueue(ServerPlayer player) {
        var service = SERVICES.get(player.server);
        if (service == null) return;
        var entry = service.pending.computeIfAbsent(player.getUUID(), ignored -> new Pending(clock.getAsLong() + 1000));
        entry.refresh = true;
    }
    public static void tick(MinecraftServer server) {
        var service = SERVICES.get(server);
        if (service != null) service.tick();
    }
    private void tick() {
        long now = clock.getAsLong();
        UUID owner = pending.entrySet().stream().filter(e -> e.getValue().due <= now)
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (owner == null) return;
        Pending task = pending.remove(owner);
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null || player.isRemoved()) return;
        task.due = now + 3000;
        try {
            if (!player.isAlive() || !authenticated(player)) { pending.put(owner, task); return; }
            RecoveryCenter center = RecoveryBridgeV1.service(player);
            String session = center.sessionToken(player);
            if (!session.equals(task.session)) { task.session = session; task.due = now + 1000; pending.put(owner, task); return; }
            if (task.refresh) {
                for (var source : center.visible(player, false)) task.sources.add(source.sourceId());
                task.refresh = false;
            }
            boolean openedThisPass = false;
            for (UUID source : task.sources.stream().limit(32).toList()) {
                task.sources.remove(source);
                try {
                var payload = center.get(owner, source).payload();
                if (!center.shouldNotify(player, source)) continue;
                long epoch = Math.max(payload.getLong("created_at"), payload.getLong("reviewed_at"));
                var notice = notices.read(owner, source, epoch);
                if (notice.viewedAt() != 0) continue;
                long sent = notice.sentAt(), opened = notice.openedAt();
                if (sent == 0 || now - sent >= 300_000) {
                    player.sendSystemMessage(Component.literal("[背包恢复] 你的物品恢复记录已保留。 ")
                            .append(Component.literal("[查看状态和领取条件]").withStyle(s -> s.withUnderlined(true)
                                    .withColor(net.minecraft.ChatFormatting.GOLD).withClickEvent(new ClickEvent(
                                            ClickEvent.Action.RUN_COMMAND, "/mwirecovery open " + source)))));
                    sent = now;
                    dev.yu.mwi.YuMultiverseInventory.LOGGER.info("Recovery notice sent owner={} source={} state={}", owner, source, payload.getString("state"));
                }
                if (opened == 0 && !openedThisPass && player.containerMenu == player.inventoryMenu) {
                    RecoveryMenu.open(player, source, 0, true);
                    opened = now; openedThisPass = true;
                    dev.yu.mwi.YuMultiverseInventory.LOGGER.info("Recovery notice opened owner={} source={}", owner, source);
                }
                if (sent != notice.sentAt() || opened != notice.openedAt())
                    notices.write(owner, source, new RecoveryNoticeStore.Notice(epoch, sent, opened, 0));
                } catch (IOException | RuntimeException failure) {
                    // One corrupt source must not starve notices for the player's other deaths.
                    dev.yu.mwi.YuMultiverseInventory.LOGGER.warn("Recovery source notice deferred owner={} source={}: {}", owner, source, failure.toString());
                    task.due = now + 10_000;
                }
                task.sources.add(source);
            }
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError failure) {
            task.due = now + 10_000;
            dev.yu.mwi.YuMultiverseInventory.LOGGER.warn("Recovery notice deferred owner={}: {}", owner, failure.toString());
        }
        if (task.refresh || !task.sources.isEmpty()) pending.put(owner, task);
    }
    public static void viewed(ServerPlayer player, UUID source) {
        var service = SERVICES.get(player.server);
        if (service == null) return;
        try {
            var payload = RecoveryBridgeV1.service(player).get(player.getUUID(), source).payload();
            long epoch = Math.max(payload.getLong("created_at"), payload.getLong("reviewed_at"));
            var notice = service.notices.read(player.getUUID(), source, epoch);
            service.notices.write(player.getUUID(), source, new RecoveryNoticeStore.Notice(
                    epoch, notice.sentAt(), notice.openedAt(), clock.getAsLong()));
            dev.yu.mwi.YuMultiverseInventory.LOGGER.info("Recovery notice viewed owner={} source={}", player.getUUID(), source);
        } catch (IOException | RuntimeException failure) {
            // Delivery metadata cannot veto an otherwise valid source/claim transaction.
            dev.yu.mwi.YuMultiverseInventory.LOGGER.warn("Recovery viewed receipt unavailable owner={} source={}: {}", player.getUUID(), source, failure.toString());
        }
    }
    /** Optional public AuthMe API; an installed but unavailable authentication service defers delivery. */
    static boolean authenticated(ServerPlayer player) throws ReflectiveOperationException {
        Class<?> bukkit;
        try { bukkit = Class.forName("org.bukkit.Bukkit"); }
        catch (ClassNotFoundException absent) { return true; }
        Object manager = bukkit.getMethod("getPluginManager").invoke(null);
        Object plugin = Class.forName("org.bukkit.plugin.PluginManager").getMethod("getPlugin", String.class).invoke(manager, "AuthMe");
        if (plugin == null) return true;
        if (!(boolean) Class.forName("org.bukkit.plugin.Plugin").getMethod("isEnabled").invoke(plugin)) return false;
        Object live = bukkit.getMethod("getPlayer", UUID.class).invoke(null, player.getUUID());
        if (live == null) return false;
        Class<?> api = Class.forName("fr.xephi.authme.api.v3.AuthMeApi", true, plugin.getClass().getClassLoader());
        Object instance = api.getMethod("getInstance").invoke(null);
        if (instance == null) return false;
        return (boolean) api.getMethod("isAuthenticated", Class.forName("org.bukkit.entity.Player")).invoke(instance, live)
                || (boolean) api.getMethod("isUnrestricted", Class.forName("org.bukkit.entity.Player")).invoke(instance, live);
    }
    public static void requireAuthenticated(ServerPlayer player) throws IOException {
        try { if (authenticated(player)) return; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { throw new IOException("登录验证尚未就绪，请稍后重试", failure); }
        throw new IOException("请完成登录验证后打开恢复记录");
    }
}
