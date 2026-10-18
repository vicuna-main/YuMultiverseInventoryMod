package dev.yu.mwi.service;

import com.mojang.authlib.GameProfile;
import dev.yu.mwi.platform.RecoveryLoginFence;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.util.*;

@GameTestHolder("yu_multiverse_inventory")
@PrefixGameTestTemplate(false)
public final class StandaloneGameTests {
    @GameTest(template="empty",timeoutTicks=800)
    public static void vanillaOnlyLoginSaveAndRejoin(GameTestHelper helper) throws Exception {
        helper.assertFalse(net.neoforged.fml.ModList.get().isLoaded("curios"),"Smoke must run without optional adapters");
        var field=RecoveryLoginFence.class.getDeclaredField("SERVICES"); field.setAccessible(true);
        var service=(InventoryIsolationService)((Map<?,?>)field.get(null)).get(helper.getLevel().getServer());
        UUID owner=UUID.randomUUID(); ServerPlayer[] player={join(helper,owner)};
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(player[0]),"Vanilla login must activate"))
                .thenExecute(() -> {
                    try {
                        player[0].getInventory().setItem(0,new ItemStack(Items.DIAMOND,5));
                        service.checkpointImmediately(player[0],"standalone_smoke");
                        player[0].server.getPlayerList().remove(player[0]); player[0]=join(helper,owner);
                    } catch(Exception failure) { throw new RuntimeException(failure); }
                }).thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(player[0]),"Vanilla rejoin must activate"))
                .thenExecute(() -> {
                    helper.assertTrue(player[0].getInventory().countItem(Items.DIAMOND)==5,"Optional class absence must not break persistence");
                    player[0].server.getPlayerList().remove(player[0]);
                }).thenSucceed();
    }
    private static ServerPlayer join(GameTestHelper helper,UUID owner) {
        var server=helper.getLevel().getServer();
        var cookie=CommonListenerCookie.createInitial(new GameProfile(owner,"standalone_mwi"),false);
        var player=new ServerPlayer(server,helper.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        var connection=new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection,player,cookie);
        return player;
    }
}
