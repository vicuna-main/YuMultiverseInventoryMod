package dev.yu.mwi.service;

import com.mojang.authlib.GameProfile;
import dev.yu.mwi.domain.*;
import dev.yu.mwi.platform.RecoveryLoginFence;
import dev.yu.mwi.storage.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.nio.file.*;
import java.util.*;

@GameTestHolder("yu_multiverse_inventory")
@PrefixGameTestTemplate(false)
public final class AutomaticRecoveryGameTests {
    @GameTest(template = "empty", timeoutTicks = 800)
    public static void healthyLoginPassesPreJoinFenceAndCanPersist(GameTestHelper helper) throws Exception {
        ServerPlayer player = join(helper, UUID.randomUUID(), "healthy_mwi");
        helper.assertTrue(RecoveryLoginFence.held(player), "Join must be fenced before asynchronous validation");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(player), "Waiting for healthy activation"))
                .thenExecute(() -> {
                    player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
                    try { service(helper).checkpointImmediately(player, "gametest"); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                    helper.assertTrue(player.connection.getConnection().isConnected(), "Healthy player must stay connected");
                    player.server.getPlayerList().remove(player);
                }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void staleIsolationAutomaticallyActivatesOriginalInventory(GameTestHelper helper) throws Exception {
        UUID owner = UUID.randomUUID();
        NbtProfileStore store = store(helper);
        store.save(key(owner), inventory(helper, 11), 203);
        Path profile = profile(helper, owner);
        byte[] before = Files.readAllBytes(profile);
        Path legacy = root(helper).resolve("quarantine").resolve(owner.toString());
        Files.createDirectories(legacy); Files.writeString(legacy.resolve("sample.bin"), "synthetic historical input");
        ServerPlayer player = join(helper, owner, "stale_mwi");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(player), "Waiting for stale lock release"))
                .thenExecute(() -> {
                    helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 11, "Original inventory must be applied once");
                    try { helper.assertTrue(Arrays.equals(before, Files.readAllBytes(profile)), "Healthy canonical must not be rewritten"); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                    helper.assertTrue(!Files.exists(legacy), "Legacy evidence must be archived automatically");
                    player.server.getPlayerList().remove(player);
                }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void missingSourceStaysOnlineAndRecoversAfterSourceReturns(GameTestHelper helper) throws Exception {
        UUID owner = UUID.randomUUID();
        store(helper).save(key(owner), inventory(helper, 5), 203);
        Path file = profile(helper, owner);
        byte[] healthy = Files.readAllBytes(file);
        Files.delete(root(helper).resolve("committed_batches").resolve(owner.toString()).resolve("203.dat"));
        Files.writeString(file, "synthetic broken gzip"); store(helper).invalidate(key(owner));
        ServerPlayer player = join(helper, owner, "waiting_mwi");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(player.isSpectator(), "Waiting for recovery display"))
                .thenExecute(() -> {
                    helper.assertTrue(RecoveryLoginFence.held(player), "Missing source must remain fenced");
                    helper.assertTrue(player.connection.getConnection().isConnected(), "Corrupt data must not kick the player");
                    player.server.getPlayerList().saveAll();
                    helper.assertTrue(!Files.exists(player.server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat")),
                            "Recovery display must never replace vanilla player data");
                    helper.assertTrue(player.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ENTITY, null) == null,
                            "Mod capability transfer must be unavailable while held");
                    var position = player.position();
                    player.teleportTo(position.x + 100, position.y, position.z);
                    helper.assertTrue(position.equals(player.position()), "External teleport must not change recovery ownership");
                    helper.assertTrue(!player.setGameMode(net.minecraft.world.level.GameType.CREATIVE), "External game mode changes must remain fenced");
                    helper.assertTrue(!player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.CREATIVE),
                            "Direct game mode controller access must remain fenced");
                    player.connection.teleport(position.x + 100, position.y, position.z, 0, 0);
                    helper.assertTrue(position.equals(player.position()), "Direct connection teleport must remain fenced");
                    player.hurt(player.damageSources().generic(), 1000);
                    helper.assertTrue(player.isAlive(), "Recovery display must not die");
                    try {
                        helper.assertTrue("synthetic broken gzip".equals(Files.readString(file)), "No empty canonical overwrite");
                        Files.write(file, healthy);
                        service(helper).inspectAutomaticRecovery(owner, true);
                    } catch (Exception failure) { throw new RuntimeException(failure); }
                }).thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(player), "Waiting for automatic activation"))
                .thenExecute(() -> {
                    helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 5, "Exact inventory must return once");
                    helper.assertTrue(!player.isSpectator(), "Original game mode must return");
                    player.server.getPlayerList().remove(player);
                }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void latestCommittedCopyRepairsCorruptionDuringRealJoin(GameTestHelper helper) throws Exception {
        UUID owner = UUID.randomUUID();
        store(helper).save(key(owner), inventory(helper, 64), 202);
        store(helper).save(key(owner), inventory(helper, 3), 203);
        Files.writeString(profile(helper, owner), "broken canonical"); store(helper).invalidate(key(owner));
        ServerPlayer player = join(helper, owner, "exact_mwi");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(player), "Waiting for exact recovery"))
                .thenExecute(() -> {
                    helper.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 3, "Must not restore the older 64-item inventory");
                    player.server.getPlayerList().remove(player);
                }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void heldLogoutAndReconnectDoNotSaveDisplayOrLoseOriginalInventory(GameTestHelper helper) throws Exception {
        UUID owner = UUID.randomUUID();
        store(helper).save(key(owner), inventory(helper, 17), 203);
        Path file = profile(helper, owner);
        byte[] healthy = Files.readAllBytes(file);
        Files.delete(root(helper).resolve("committed_batches").resolve(owner.toString()).resolve("203.dat"));
        Files.writeString(file, "broken reconnect input"); store(helper).invalidate(key(owner));
        ServerPlayer first = join(helper, owner, "rejoin_mwi");
        ServerPlayer[] second = new ServerPlayer[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(first.isSpectator(), "Waiting for initial hold"))
                .thenExecute(() -> {
                    first.server.getPlayerList().remove(first);
                    first.server.getPlayerList().save(first); // A delayed save from the retired connection must stay fenced.
                    helper.assertTrue(!Files.exists(first.server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat")),
                            "Logout and delayed vanilla save must not persist display state");
                    second[0] = join(helper, owner, "rejoin_mwi");
                }).thenWaitUntil(() -> helper.assertTrue(second[0].isSpectator(), "Waiting for new connection hold"))
                .thenExecute(() -> {
                    try { Files.write(file, healthy); service(helper).inspectAutomaticRecovery(owner, true); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                }).thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(second[0]), "Waiting for new connection activation"))
                .thenExecute(() -> {
                    helper.assertTrue(second[0].getInventory().countItem(Items.DIAMOND) == 17, "Rejoin must restore the exact inventory once");
                    helper.assertTrue(second[0].connection.getConnection().isConnected(), "New connection must remain online");
                    second[0].server.getPlayerList().remove(second[0]);
                }).thenSucceed();
    }

    private static ServerPlayer join(GameTestHelper helper, UUID owner, String name) {
        var server = helper.getLevel().getServer();
        var cookie = CommonListenerCookie.createInitial(new GameProfile(owner, name), false);
        var player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }
    private static InventoryIsolationService service(GameTestHelper helper) throws Exception {
        var field = RecoveryLoginFence.class.getDeclaredField("SERVICES"); field.setAccessible(true);
        return (InventoryIsolationService) ((Map<?, ?>) field.get(null)).get(helper.getLevel().getServer());
    }
    private static NbtProfileStore store(GameTestHelper helper) throws Exception {
        var field = InventoryIsolationService.class.getDeclaredField("store"); field.setAccessible(true);
        return (NbtProfileStore) field.get(service(helper));
    }
    private static ProfileKey key(UUID owner) {
        return new ProfileKey(owner, new ScopeKey(ScopeKey.Type.GROUP, "default"), GameModeBucket.SHARED, "inventory_contents");
    }
    private static CompoundTag inventory(GameTestHelper helper, int count) {
        var slots = new ArrayList<>(Collections.nCopies(36, ItemStack.EMPTY));
        slots.set(0, new ItemStack(Items.DIAMOND, count));
        return dev.yu.mwi.adapter.vanilla.ItemListNbtCodec.encode(slots, helper.getLevel().registryAccess());
    }
    private static Path root(GameTestHelper helper) { return helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("data/mwi"); }
    private static Path profile(GameTestHelper helper, UUID owner) {
        return root(helper).resolve("profiles").resolve(owner.toString()).resolve("group/ZGVmYXVsdA/shared/aW52ZW50b3J5X2NvbnRlbnRz.dat");
    }
}
