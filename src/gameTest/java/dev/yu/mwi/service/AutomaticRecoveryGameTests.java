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
        var originalMode = player.gameMode.getGameModeForPlayer();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(inLobby(player), "Waiting for recovery lobby"))
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
                    helper.assertTrue(player.gameMode.getGameModeForPlayer() == originalMode, "Original game mode must return");
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
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(inLobby(first), "Waiting for initial hold"))
                .thenExecute(() -> {
                    first.server.getPlayerList().remove(first);
                    first.server.getPlayerList().save(first); // A delayed save from the retired connection must stay fenced.
                    helper.assertTrue(!Files.exists(first.server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat")),
                            "Logout and delayed vanilla save must not persist display state");
                    second[0] = join(helper, owner, "rejoin_mwi");
                }).thenWaitUntil(() -> helper.assertTrue(inLobby(second[0]), "Waiting for new connection hold"))
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

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void administratorNamedHistoricalRestorePersistsAcrossRejoin(GameTestHelper helper) throws Exception {
        UUID owner=UUID.randomUUID(); ServerPlayer player=join(helper,owner,"history_mwi");
        String[] target=new String[1]; ServerPlayer[] rejoined=new ServerPlayer[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(player),"Waiting for activation"))
                .thenExecute(() -> {
                    try {
                        player.getInventory().setItem(0,new ItemStack(Items.DIAMOND,9)); service(helper).checkpointImmediately(player,"history_before");
                        var points=store(helper).restorePoints(owner);
                        target[0]=java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss.SSS")
                                .format(points.getFirst().committedAt().atZone(service(helper).restoreTimeZone()));
                    } catch(Exception failure) { throw new RuntimeException(failure); }
                }).thenIdle(2).thenExecute(() -> {
                    try {
                        player.getInventory().setItem(0,new ItemStack(Items.DIAMOND,3)); service(helper).checkpointImmediately(player,"history_after");
                        int accepted=player.server.getCommands().getDispatcher().execute("mwi restore HISTORY_MWI " + target[0], player.server.createCommandSourceStack());
                        helper.assertTrue(accepted==1,"Named command must be accepted");
                    } catch(Exception failure) { throw new RuntimeException(failure); }
                }).thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(player) && player.getInventory().countItem(Items.DIAMOND)==9,"Waiting for historical restore"))
                .thenExecute(() -> {
                    try {
                        service(helper).checkpointImmediately(player,"after_historical_restore");
                        player.server.getPlayerList().remove(player); rejoined[0]=join(helper,owner,"history_mwi");
                    } catch(Exception failure) { throw new RuntimeException(failure); }
                }).thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(rejoined[0]),"Waiting for restored rejoin"))
                .thenExecute(() -> {
                    helper.assertTrue(rejoined[0].getInventory().countItem(Items.DIAMOND)==9,"Restored inventory must survive rejoin");
                    rejoined[0].server.getPlayerList().remove(rejoined[0]);
                }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void offlineRestoreIsPreparedThenAppliedOnlyOnce(GameTestHelper helper) throws Exception {
        UUID owner=UUID.randomUUID(); ServerPlayer player=join(helper,owner,"offline_mwi");
        String[] target=new String[1]; ServerPlayer[] rejoined=new ServerPlayer[1];
        InventoryIsolationService.RestoreCommandTask[] task=new InventoryIsolationService.RestoreCommandTask[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(player),"Waiting for activation"))
                .thenExecute(() -> {
                    try {
                        player.getInventory().setItem(0,new ItemStack(Items.EMERALD,5)); service(helper).checkpointImmediately(player,"offline_before");
                        target[0]="#"+store(helper).restorePoints(owner).getFirst().revision();
                        player.getInventory().setItem(0,new ItemStack(Items.EMERALD,1)); service(helper).checkpointImmediately(player,"offline_after");
                        player.server.getPlayerList().remove(player);
                        task[0]=service(helper).requestRestore(owner,target[0],"gametest");
                    } catch(Exception failure) { throw new RuntimeException(failure); }
                }).thenWaitUntil(() -> helper.assertTrue(task[0].completion().isDone(),"Waiting for offline preparation"))
                .thenExecute(() -> {
                    helper.assertTrue(task[0].completion().join().contains("已准备"),"Offline result must not claim activation");
                    try { task[0]=service(helper).requestRestore(owner,"latest","gametest"); }
                    catch(Exception failure) { throw new RuntimeException(failure); }
                }).thenWaitUntil(() -> helper.assertTrue(task[0].completion().isDone(),"Waiting for pending-history guard"))
                .thenExecute(() -> {
                    helper.assertTrue(task[0].completion().isCompletedExceptionally(),"latest must identify pending history instead of silently resuming it");
                    String reason=task[0].completion().handle((value,failure) -> {
                        while(failure.getCause()!=null) failure=failure.getCause();
                        return failure.getMessage();
                    }).join();
                    helper.assertTrue(reason.contains("历史恢复任务"),"latest must explain the pending history conflict: "+reason);
                    try { helper.assertTrue("PREPARED".equals(store(helper).restoreRequest(owner).getString("state")),"latest must preserve the historical request"); }
                    catch(Exception failure) { throw new RuntimeException(failure); }
                    rejoined[0]=join(helper,owner,"offline_mwi");
                }).thenWaitUntil(() -> helper.assertTrue(rejoined[0]!=null && !RecoveryLoginFence.held(rejoined[0]),"Waiting for prepared application"))
                .thenExecute(() -> {
                    helper.assertTrue(rejoined[0].getInventory().countItem(Items.EMERALD)==5,"Prepared target must activate");
                    try {
                        rejoined[0].getInventory().setItem(0,new ItemStack(Items.EMERALD,2)); service(helper).checkpointImmediately(rejoined[0],"spent_after_restore");
                        task[0]=service(helper).requestRestore(owner,target[0],"gametest");
                    } catch(Exception failure) { throw new RuntimeException(failure); }
                }).thenWaitUntil(() -> helper.assertTrue(task[0].completion().isDone() && !RecoveryLoginFence.held(rejoined[0]),"Waiting for duplicate request"))
                .thenExecute(() -> {
                    helper.assertTrue(rejoined[0].getInventory().countItem(Items.EMERALD)==2,"Duplicate target must not issue items again");
                    rejoined[0].server.getPlayerList().remove(rejoined[0]);
                }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void unavailableCuriosSlotsEscrowAllDependentItemsAtomically(GameTestHelper helper) throws Exception {
        UUID owner=UUID.randomUUID(); CompoundTag source=new CompoundTag(); source.putInt("schema",2);
        CompoundTag capability=new CompoundTag(); ListTag slots=new ListTag();
        for(int i=0;i<2;i++) {
            CompoundTag slot=new CompoundTag(); slot.putString("Identifier","missing_expansion_"+i);
            CompoundTag stacks=new CompoundTag();
            for(String kind:List.of("Stacks","Cosmetics")) {
                CompoundTag inventory=new CompoundTag(); inventory.putInt("Size",3); ListTag items=new ListTag();
                CompoundTag item=(CompoundTag)new ItemStack(Items.DIAMOND,i+1).save(helper.getLevel().registryAccess());
                item.putInt("Slot",2); items.add(item); inventory.put("Items",items); stacks.put(kind,inventory);
            }
            slot.put("StacksHandler",stacks); slots.add(slot);
        }
        capability.put("Curios",slots); source.put("capability",capability);
        ProfileKey curios=new ProfileKey(owner,new ScopeKey(ScopeKey.Type.GROUP,"default"),GameModeBucket.SHARED,"curios");
        store(helper).save(curios,source,200);
        ServerPlayer player=join(helper,owner,"escrow_mwi");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(player),"Waiting for verified escrow fallback"))
                .thenExecute(() -> {
                    try {
                        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                        var sources=store(helper).listRecoveries(owner); helper.assertTrue(sources.size()==1,"One permanent escrow source: " + sources.size() + " capability=" + top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player));
                        var center=service(helper).recoveryCenter(); var preview=center.preview(player,sources.getFirst().sourceId());
                        helper.assertTrue(preview.items().size()==4,"All functional and cosmetic dependencies must be escrowed");
                        helper.assertTrue(center.claim(player,preview)==6,"All six items must be claimed exactly once");
                        boolean duplicateRejected=false;
                        try { center.claim(player,preview); } catch(java.io.IOException expected) { duplicateRejected=true; }
                        helper.assertTrue(duplicateRejected,"Repeated claim must be rejected");
                        helper.assertTrue(player.getInventory().countItem(Items.DIAMOND)==6,"No item loss or duplication");
                        player.server.getPlayerList().remove(player);
                    } catch(Exception failure) { throw new RuntimeException(failure); }
                }).thenSucceed();
    }
    private static boolean inLobby(ServerPlayer player) {
        return RecoveryLoginFence.held(player) && player.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.ADVENTURE;
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void cosmeticAndTrashRestoreSaveRejoinAndWorldRoundTrip(GameTestHelper helper) throws Exception {
        UUID owner=UUID.randomUUID(); ServerPlayer[] player={join(helper,owner,"extra_slots_mwi")};
        var cosmetic=new dev.yu.mwi.integration.cosmeticarmor.CosmeticArmorInventoryAdapter();
        var trash=new dev.yu.mwi.integration.trashslot.TrashSlotInventoryAdapter();
        CompoundTag[] expected=new CompoundTag[2]; String[] target=new String[1];
        var originalLevel=helper.getLevel();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(player[0]),"Waiting for extra slot activation"))
                .thenExecute(() -> {
                    try {
                        CompoundTag costume=cosmetic.capture(player[0],player[0].registryAccess());
                        CompoundTag inventory=costume.getCompound("inventory"); ListTag items=new ListTag();
                        ItemStack armor=new ItemStack(Items.DIAMOND_CHESTPLATE);
                        armor.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("恢复测试时装"));
                        CompoundTag item=(CompoundTag)armor.save(player[0].registryAccess()); item.putInt("Slot",2); item.putBoolean("isSkinArmor",true); items.add(item);
                        CompoundTag skin=new CompoundTag(); skin.putInt("Slot",0); skin.putBoolean("isSkinArmor",true); items.add(skin);
                        // Native serializers use slot order; this order is deliberately normalized once before snapshotting.
                        inventory.put("Items",items); inventory.putString("Hidden","minecraft"); cosmetic.apply(player[0],costume,player[0].registryAccess());
                        CompoundTag discarded=new CompoundTag(); discarded.putInt("schema",1);
                        ItemStack stack=new ItemStack(Items.PAPER,13);
                        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("恢复测试垃圾槽"));
                        discarded.put("item",stack.save(player[0].registryAccess())); trash.apply(player[0],discarded,player[0].registryAccess());
                        expected[0]=cosmetic.capture(player[0],player[0].registryAccess()); expected[1]=trash.capture(player[0],player[0].registryAccess());
                        helper.assertTrue(expected[0].getCompound("inventory").getList("Items",Tag.TAG_COMPOUND).size()==2,"Skin-only slot and armor must both persist");
                        service(helper).checkpointImmediately(player[0],"extra_slots_target");
                        target[0]="#"+store(helper).restorePoints(owner).getFirst().revision();
                        cosmetic.clear(player[0]); trash.clear(player[0]); service(helper).checkpointImmediately(player[0],"extra_slots_after");
                        service(helper).requestRestore(owner,target[0],"gametest");
                    } catch(Exception failure) { throw new RuntimeException(failure); }
                }).thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(player[0])
                        && expected[1].equals(trash.capture(player[0],player[0].registryAccess())),"Waiting for historical extra slots"))
                .thenExecute(() -> {
                    helper.assertTrue(expected[0].equals(cosmetic.capture(player[0],player[0].registryAccess())),"Cosmetic items, skin and Hidden must restore exactly");
                    try { service(helper).checkpointImmediately(player[0],"extra_slots_restored"); player[0].server.getPlayerList().remove(player[0]); player[0]=join(helper,owner,"extra_slots_mwi"); }
                    catch(Exception failure) { throw new RuntimeException(failure); }
                }).thenWaitUntil(() -> helper.assertTrue(!RecoveryLoginFence.held(player[0]),"Waiting for extra slots rejoin"))
                .thenExecute(() -> {
                    helper.assertTrue(expected[0].equals(cosmetic.capture(player[0],player[0].registryAccess())) && expected[1].equals(trash.capture(player[0],player[0].registryAccess())),"Both extra inventories must survive rejoin");
                    player[0].teleportTo(player[0].server.getLevel(net.minecraft.world.level.Level.NETHER),0.5,70,0.5,0,0);
                }).thenIdle(3).thenExecute(() -> player[0].teleportTo(originalLevel,0.5,-59,0.5,0,0))
                .thenIdle(3).thenExecute(() -> {
                    helper.assertTrue(expected[0].equals(cosmetic.capture(player[0],player[0].registryAccess())) && expected[1].equals(trash.capture(player[0],player[0].registryAccess())),"World round trip must retain both exact payloads");
                    try { service(helper).checkpointImmediately(player[0],"extra_slots_world_roundtrip"); player[0].server.getPlayerList().remove(player[0]); }
                    catch(Exception failure) { throw new RuntimeException(failure); }
                }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void deterministicApplicationFailureHasPersistentBoundedBudget(GameTestHelper helper) throws Exception {
        UUID owner=UUID.randomUUID(); CompoundTag invalid=inventory(helper,3);
        invalid.getList("items",Tag.TAG_COMPOUND).getCompound(0).getCompound("stack").putInt("count",-1);
        store(helper).save(key(owner),invalid,203); byte[] original=Files.readAllBytes(profile(helper,owner));
        ServerPlayer player=join(helper,owner,"budget_mwi");
        @SuppressWarnings("unchecked") java.util.concurrent.CompletableFuture<RecoveryInspection>[] flight=new java.util.concurrent.CompletableFuture[1];
        var sequence=helper.startSequence().thenWaitUntil(() -> helper.assertTrue(inLobby(player),"Waiting for first deterministic failure"));
        for(int attempt=0;attempt<3;attempt++) sequence=sequence.thenExecute(() -> {
            try { flight[0]=service(helper).inspectAutomaticRecovery(owner,true); }
            catch(Exception failure) { throw new RuntimeException(failure); }
        }).thenWaitUntil(() -> helper.assertTrue(flight[0].isDone(),"Waiting for bounded retry"));
        sequence.thenExecute(() -> {
            try {
                CompoundTag task=store(helper).activationTask(owner);
                helper.assertTrue(task.getInt("attempts")==3 && "ACTION_REQUIRED".equals(task.getString("state")),"Backend success and manual retry cannot reset application budget");
                helper.assertTrue(store(helper).listRevisions(owner).isEmpty(),"Rejected application cannot allocate revision journals");
                helper.assertTrue(Arrays.equals(original,Files.readAllBytes(profile(helper,owner))),"Rejected application cannot overwrite original");
                helper.assertTrue(RecoveryLoginFence.held(player) && !RecoveryLoginFence.movementBlocked(player),"Held player must keep a movable protected help session");
                player.server.getPlayerList().remove(player);
            } catch(Exception failure) { throw new RuntimeException(failure); }
        }).thenSucceed();
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
