package dev.yu.mwi.storage;

import dev.yu.mwi.domain.GameModeBucket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileStoreContractTest {
    @Test
    void storageContractDoesNotDependOnCompilerGeneratedNestedTypes() throws Exception {
        assertEquals(0, ProfileStore.class.getDeclaredClasses().length);
        assertEquals(SaveResult.class,
                ProfileStore.class.getMethod("save", dev.yu.mwi.domain.ProfileKey.class,
                        net.minecraft.nbt.CompoundTag.class, long.class).getReturnType());
        var listMethod = ProfileStore.class.getMethod(
                "listWorldProfiles", UUID.class, GameModeBucket.class, String.class);
        assertEquals(List.class, listMethod.getReturnType());
        assertTrue(listMethod.getGenericReturnType().getTypeName().contains(WorldProfile.class.getName()));
        assertEquals(CacheStats.class, ProfileStore.class.getMethod("cacheStats").getReturnType());
        assertEquals(void.class, ProfileStore.class.getMethod(
                "invalidate", dev.yu.mwi.domain.ProfileKey.class).getReturnType());
        assertEquals(void.class, ProfileStore.class.getMethod(
                "invalidatePlayer", UUID.class).getReturnType());
        assertEquals(Map.class, ProfileStore.class.getMethod(
                "inspectPlayerProfiles", UUID.class).getReturnType());
    }
}
