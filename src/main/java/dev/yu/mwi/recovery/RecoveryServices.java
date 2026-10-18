package dev.yu.mwi.recovery;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

/** Internal per-server recovery service. External inventory handoff APIs are intentionally absent. */
public final class RecoveryServices {
    private static final Map<MinecraftServer, RecoveryCenter> SERVICES = new IdentityHashMap<>();
    private RecoveryServices() {}
    public static void register(MinecraftServer server, RecoveryCenter service) {
        SERVICES.put(server, service); RecoveryNotifications.register(server);
    }
    public static void unregister(MinecraftServer server) {
        SERVICES.remove(server); RecoveryNotifications.unregister(server);
    }
    public static RecoveryCenter service(ServerPlayer player) throws IOException {
        if (!player.server.isSameThread()) throw new IOException("Recovery requires the server thread");
        RecoveryCenter service = SERVICES.get(player.server);
        if (service == null) throw new IOException("恢复中心尚未就绪");
        return service;
    }
}
