package dev.yu.mwi.platform;

import dev.yu.mwi.service.InventoryIsolationService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Server-scoped bridge used before hybrid join handlers and by packet/capability boundaries. */
public final class RecoveryLoginFence {
    private static final Map<MinecraftServer, InventoryIsolationService> SERVICES = new ConcurrentHashMap<>();
    private RecoveryLoginFence() {}
    private static InventoryIsolationService service(ServerPlayer player) {
        // Curios queries capabilities from Entity's constructor, before ServerPlayer.server is assigned.
        return player.server == null ? null : SERVICES.get(player.server);
    }
    public static void register(MinecraftServer server, InventoryIsolationService service) { SERVICES.put(server, service); }
    public static void unregister(MinecraftServer server) { SERVICES.remove(server); }
    public static void prepare(ServerPlayer player) {
        InventoryIsolationService service = service(player);
        if (service != null) service.prepareLogin(player);
    }
    public static boolean held(ServerPlayer player) {
        InventoryIsolationService service = service(player);
        return service != null && service.isRecoveryHeld(player);
    }
    public static String status(ServerPlayer player) {
        InventoryIsolationService service = service(player);
        return service == null ? "恢复服务尚未就绪" : service.recoveryStatus(player.getUUID());
    }
    public static void loaded(ServerPlayer player, net.minecraft.nbt.CompoundTag input) {
        InventoryIsolationService service = service(player);
        if (service != null) service.captureLoadedPlayer(player, input);
    }
    public static boolean externalTransferBlocked(ServerPlayer player) {
        InventoryIsolationService service = service(player);
        return service != null && service.isRecoveryHeld(player) && !service.isRecoveryInternal();
    }
    public static boolean saveBlocked(ServerPlayer player) {
        InventoryIsolationService service = service(player);
        return service != null && service.isRecoveryHeld(player) && !service.isRecoveryCheckpoint(player);
    }
    public static boolean movementBlocked(ServerPlayer player) {
        InventoryIsolationService service = service(player);
        return service != null && service.isRecoveryHeld(player) && !service.isRecoveryDisplay(player);
    }
    public static boolean teleportBlocked(ServerPlayer player) {
        InventoryIsolationService service = service(player);
        return service != null && service.isRecoveryDisplay(player) && !service.isRecoveryInternal();
    }

    public static boolean commandAllowed(String input) {
        String normalized = input.strip().toLowerCase(java.util.Locale.ROOT);
        String command = normalized.split("\\s+", 2)[0];
        return java.util.Set.of("login", "l", "register", "reg", "authme:login", "authme:register", "help", "?", "mwistatus").contains(command)
                || normalized.matches("mwi recovery (inspect|retry) [0-9a-f-]+( --dry-run)?")
                || normalized.matches("mwi restore [a-z0-9_]+ (latest|list|status|[0-9]{8}(-[0-9]{6}(\\.[0-9]{3})?)?)");
    }
}
