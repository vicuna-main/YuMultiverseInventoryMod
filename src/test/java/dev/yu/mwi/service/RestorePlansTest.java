package dev.yu.mwi.service;

import dev.yu.mwi.domain.*;
import dev.yu.mwi.storage.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RestorePlansTest {
    @TempDir Path directory;
    final UUID owner=UUID.randomUUID();
    final ProfileKey key=new ProfileKey(owner,new ScopeKey(ScopeKey.Type.GROUP,"default"),GameModeBucket.SHARED,"curios");
    CompoundTag data(int value){CompoundTag tag=new CompoundTag();tag.putInt("count",value);return tag;}
    CompoundTag prepare(NbtProfileStore store,String target)throws Exception {
        return RestorePlans.prepare(store,owner,target,Set.of(key),store.inspectRecovery(owner,false),ZoneId.of("Asia/Shanghai"),"test");
    }
    @Test void preparedTargetRejectsChangedAuthorityAndDifferentConcurrentRequest()throws Exception {
        try(var store=new NbtProfileStore(directory,20,32)) {
            store.save(key,data(9),1);store.save(key,data(2),2);
            var request=prepare(store,"#1");assertEquals("PREPARED",request.getString("state"));
            assertEquals(request,prepare(store,"#1"));
            assertEquals(data(9),RestorePlans.targets(request,Map.of("curios",key),store.inspectPlayerProfiles(owner)).get("curios"));
            assertThrows(java.io.IOException.class,()->prepare(store,"#2"));
            store.save(key,data(3),3);
            assertThrows(java.io.IOException.class,()->RestorePlans.targets(request,Map.of("curios",key),store.inspectPlayerProfiles(owner)));
        }
    }
    @Test void completedTargetHasPermanentReceiptAndCannotReissue()throws Exception {
        try(var store=new NbtProfileStore(directory,20,32)) {
            store.save(key,data(9),1);store.save(key,data(2),2);
            var request=prepare(store,"#1");request.putString("state","ACTIVE");store.saveRestoreRequest(owner,request);
            store.save(key,data(1),3);
            assertEquals("ACTIVE",prepare(store,"#1").getString("state"));
            assertEquals(data(1),store.load(key).orElseThrow().payload());
        }
    }
    @Test void historyCannotReopenSettledRecoverySource()throws Exception {
        try(var store=new NbtProfileStore(directory,20,32)) {
            store.save(key,data(9),1);store.save(key,data(2),2);
            CompoundTag scope=new CompoundTag(),identity=new CompoundTag(),payload=new CompoundTag(),entry=new CompoundTag();
            entry.putString("scope_type","GROUP");entry.putString("scope_id","default");entry.putString("game_mode","SHARED");
            scope.put("curios",entry);identity.put("scope",scope);payload.put("identity",identity);payload.putString("state","CLOSED");
            store.commitRecovery(owner,3,Map.of(),new RecoveryMutation(UUID.randomUUID(),-1,UUID.randomUUID(),payload));
            assertThrows(java.io.IOException.class,()->prepare(store,"#1"));
        }
    }
}
