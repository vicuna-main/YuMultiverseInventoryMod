package dev.yu.mwi.storage;

import dev.yu.mwi.domain.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RestorePointTest {
    @TempDir Path world;
    private ProfileKey key(UUID owner, String share) { return new ProfileKey(owner, new ScopeKey(ScopeKey.Type.GROUP,"default"),GameModeBucket.SHARED,share); }
    private CompoundTag data(int count) { var tag = new CompoundTag(); tag.putInt("count", count); return tag; }
    @Test void manifestsRetainColdDependenciesBeyondIncrementalWindow() throws Exception {
        UUID owner = UUID.randomUUID(); ProfileKey hot=key(owner,"inventory_contents"), cold=key(owner,"curios");
        try (var store = new NbtProfileStore(world, 2, 32)) {
            store.commitBatch(owner, 1, Map.of(hot,data(1),cold,data(8)), Optional.empty());
            for (int i=2;i<=7;i++) store.commitBatch(owner, i, Map.of(hot,data(i)), Optional.empty());
            var points = store.restorePoints(owner).stream().filter(p -> "manifest".equals(p.source())).toList();
            assertEquals(2, points.size());
            for (var point : points) {
                assertTrue(point.completeFor(Set.of(hot,cold)), point.problems().toString());
                assertEquals(8, point.profiles().get(cold).payload().getInt("count"));
                assertEquals(point.revision(), point.profiles().get(hot).payload().getInt("count"));
            }
        }
        try (var reopened = new NbtProfileStore(world, 2, 32)) {
            assertTrue(reopened.restorePoints(owner).getFirst().completeFor(Set.of(hot,cold)));
        }
    }
    @Test void missingReferenceIsPartialAndCannotBecomeFullRestore() throws Exception {
        UUID owner=UUID.randomUUID(); ProfileKey a=key(owner,"inventory_contents"), b=key(owner,"curios");
        try(var store=new NbtProfileStore(world,20,32)) {
            store.commitBatch(owner,1,Map.of(a,data(2),b,data(3)),Optional.empty());
            store.save(a,data(5),2);
            Files.delete(world.resolve("data/mwi/committed_batches").resolve(owner.toString()).resolve("1.dat"));
            var point=store.restorePoints(owner).stream().filter(p->p.revision()==2).findFirst().orElseThrow();
            assertFalse(point.completeFor(Set.of(a,b))); assertFalse(point.problems().isEmpty());
        }
    }
    @Test void activationBudgetSurvivesRestartAndBackendSuccess() throws Exception {
        UUID owner=UUID.randomUUID();
        for(int i=1;i<=4;i++) {
            try(var store=new NbtProfileStore(world,20,32)) {
                CompoundTag task=ActivationAttempt.prepare(store.activationTask(owner),"same-input",false);
                assertEquals(Math.min(i,3),task.getInt("attempts"));
                if(i==4) assertEquals("ACTION_REQUIRED",task.getString("state"));
                store.saveActivationTask(owner,ActivationAttempt.failed(task,"same mismatch"));
            }
        }
        try(var store=new NbtProfileStore(world,20,32)) {
            var changed=ActivationAttempt.prepare(store.activationTask(owner),"changed-source",false);
            assertEquals(1,changed.getInt("attempts")); assertEquals("APPLY",changed.getString("state"));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"profiles","archives","restore_requests","restore_operations","restore_points","committed_heads","recovery_sources"})
    void escrowNormalizationAndRestoreReceiptReplayAsOneWal(String boundary) throws Exception {
        UUID owner=UUID.randomUUID(), operation=UUID.randomUUID(), source=UUID.randomUUID();
        ProfileKey a=key(owner,"curios"), b=new ProfileKey(owner,new ScopeKey(ScopeKey.Type.WORLD,"minecraft:overworld"),GameModeBucket.SHARED,"curios");
        try(var initial=new NbtProfileStore(world,20,32)) { initial.commitBatch(owner,1,Map.of(a,data(8),b,data(8)),Optional.empty()); }
        var broken=new NbtProfileStore(world,20,32,16,new NbtProfileStore.FaultInjector(){
            @Override public void beforeMove(Path temporary,Path destination)throws java.io.IOException {
                if(destination.toString().contains(java.io.File.separator+boundary+java.io.File.separator)) throw new java.io.IOException("injected "+boundary);
            }
        });
        CompoundTag request=new CompoundTag();request.putUUID("operation_id",operation);request.putString("state","PREPARED");
        CompoundTag journal=new CompoundTag();journal.putString("status","committed");journal.put("restore_operation",request);
        CompoundTag escrow=new CompoundTag();escrow.putString("state","AVAILABLE");escrow.put("identity",data(8));
        assertThrows(PendingTransactionException.class,()->broken.commitRecovery(owner,2,Map.of(a,data(0),b,data(0)),
                new RecoveryMutation(source,-1,source,escrow),journal,List.of(new ProfileArchive(a,"activation-escrow","original",new CompoundTag()))));
        try(var restarted=new NbtProfileStore(world,20,32)) {
            assertEquals(data(0),restarted.load(a).orElseThrow().payload());
            assertEquals(data(0),restarted.load(b).orElseThrow().payload());
            assertEquals("AVAILABLE",restarted.loadRecovery(owner,source).orElseThrow().payload().getString("state"));
            assertEquals("COMMITTED",restarted.restoreOperation(owner,operation).orElseThrow().getString("state"));
            assertTrue(restarted.restorePoints(owner).getFirst().completeFor(Set.of(a,b)));
            assertTrue(restarted.recoveryOperation(owner,source).isPresent());
        }
    }
}
