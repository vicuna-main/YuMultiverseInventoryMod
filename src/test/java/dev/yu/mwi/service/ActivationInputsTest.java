package dev.yu.mwi.service;

import dev.yu.mwi.domain.*;
import dev.yu.mwi.storage.StoredProfile;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ActivationInputsTest {
    private final UUID owner = UUID.randomUUID();
    private ProfileKey key(String scope) {
        return new ProfileKey(owner, new ScopeKey(ScopeKey.Type.GROUP, scope), GameModeBucket.SHARED, "inventory_contents");
    }
    private StoredProfile profile(int amount) {
        CompoundTag data = new CompoundTag(); data.putInt("amount", amount);
        return new StoredProfile(data, 1, Instant.EPOCH, "test");
    }
    @Test void onlyActualRequiredScopesRefreshFailedApplicationBudget() throws Exception {
        var target = key("lobby"); var unrelated = key("default");
        Map<ProfileKey, StoredProfile> profiles = new HashMap<>(Map.of(target, profile(3), unrelated, profile(7)));
        String original = ActivationInputs.digest(Set.of(target), profiles, "runtime", new CompoundTag());
        profiles.put(unrelated, profile(8));
        assertEquals(original, ActivationInputs.digest(Set.of(target), profiles, "runtime", new CompoundTag()));
        profiles.put(target, profile(4));
        assertNotEquals(original, ActivationInputs.digest(Set.of(target), profiles, "runtime", new CompoundTag()));
        assertNotEquals(original, ActivationInputs.digest(Set.of(unrelated), profiles, "runtime", new CompoundTag()));
    }
    @Test void firstLoginAbsenceIsAnInputAndNotAnEmptyProfile() throws Exception {
        var target = key("default");
        String absent = ActivationInputs.digest(Set.of(target), Map.of(), "runtime", new CompoundTag());
        assertNotEquals(absent, ActivationInputs.digest(Set.of(target), Map.of(target, profile(0)), "runtime", new CompoundTag()));
    }
    @Test void correctedFallbackAndNewRestoreRequestAreMaterialInputs() throws Exception {
        var target = key("default"); CompoundTag fallback = new CompoundTag(); fallback.putInt("candidate", 1);
        String original = ActivationInputs.digest(Set.of(target), Map.of(), "runtime", new CompoundTag(), fallback);
        fallback.putInt("candidate", 2);
        assertNotEquals(original, ActivationInputs.digest(Set.of(target), Map.of(), "runtime", new CompoundTag(), fallback));
        CompoundTag request = new CompoundTag(); request.putString("state", "PREPARED"); request.putLong("revision", 200);
        assertNotEquals(original, ActivationInputs.digest(Set.of(target), Map.of(), "runtime", request, fallback));
    }
}
