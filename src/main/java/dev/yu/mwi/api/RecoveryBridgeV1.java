package dev.yu.mwi.api;

import dev.yu.mwi.recovery.RecoveryCenter;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;
import java.util.*;
import java.util.function.BiFunction;

/** Versioned optional integration boundary; only public Minecraft types cross the mod boundary. */
public final class RecoveryBridgeV1 {
    private static final Map<MinecraftServer, RecoveryCenter> SERVICES = new IdentityHashMap<>();
    private RecoveryBridgeV1() {}
    public static void register(MinecraftServer server, RecoveryCenter service) {
        SERVICES.put(server, service); dev.yu.mwi.recovery.RecoveryNotifications.register(server);
    }
    public static void unregister(MinecraftServer server) {
        SERVICES.remove(server); dev.yu.mwi.recovery.RecoveryNotifications.unregister(server);
    }
    public static void registerSourceGuard(MinecraftServer server, String provider,
            BiFunction<ServerPlayer, CompoundTag, String> guard) throws IOException {
        if (!server.isSameThread() || !SERVICES.containsKey(server)) throw new IOException("恢复中心尚未就绪");
        SERVICES.get(server).registerSourceGuard(provider, guard);
    }
    public static RecoveryCenter service(ServerPlayer player) throws IOException {
        if (!player.server.isSameThread()) throw new IOException("Recovery requires the server thread");
        RecoveryCenter service = SERVICES.get(player.server);
        if (service == null) throw new IOException("恢复中心尚未就绪");
        return service;
    }
    public static CompoundTag captureScope(ServerPlayer player) throws IOException { return service(player).scope(player); }
    public static void deposit(ServerPlayer player, UUID source, CompoundTag scope, ListTag items,
                               CompoundTag evidence, boolean certified) throws IOException {
        service(player).deposit(player, source, scope, items, evidence, certified);
    }
    public static CompoundTag query(ServerPlayer player, UUID source) throws IOException {
        var record = service(player).get(player.getUUID(), source);
        CompoundTag result = record.payload(); result.putLong("version", record.version());
        result.putString("checksum", record.checksum()); return result;
    }
    public static void completeGraveClaim(ServerPlayer player, UUID source, CompoundTag evidence) throws IOException {
        service(player).completeExternalTransfer(player, source, evidence);
    }
    public static boolean shouldNotify(ServerPlayer player, UUID source) throws IOException {
        return service(player).shouldNotify(player, source);
    }
    public static void notifyReady(ServerPlayer player) {
        dev.yu.mwi.recovery.RecoveryCommands.notifyPlayer(player);
    }
}
