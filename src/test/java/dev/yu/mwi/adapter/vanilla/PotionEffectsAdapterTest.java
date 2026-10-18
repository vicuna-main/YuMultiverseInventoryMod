package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.RestoreVerification;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PotionEffectsAdapterTest {
    private final PotionEffectsAdapter adapter = new PotionEffectsAdapter();

    @Test
    void curesOrderIsEquivalentWithoutChangingTheOriginalSnapshot() {
        CompoundTag expected = effects("milk", "protected_by_totem");
        CompoundTag original = expected.copy();
        CompoundTag actual = effects("protected_by_totem", "milk");
        assertTrue(adapter.equivalent(expected, actual));
        assertEquals(RestoreVerification.Status.SEMANTIC_EQUIVALENT,
                adapter.verifyRestored(null, expected, actual, null).status());
        assertEquals(original, expected);
    }

    @Test
    void hiddenEffectsHaveTheSameCuresContract() {
        CompoundTag expected = effects("milk");
        expected.getList("effects", Tag.TAG_COMPOUND).getCompound(0)
                .put("hidden_effect", effect("milk", "protected_by_totem"));
        CompoundTag actual = expected.copy();
        actual.getList("effects", Tag.TAG_COMPOUND).getCompound(0)
                .put("hidden_effect", effect("protected_by_totem", "milk"));
        assertTrue(adapter.equivalent(expected, actual));
    }

    @Test
    void changedMembershipDurationAndOtherListsRemainDifferent() {
        assertFalse(adapter.equivalent(effects("milk"), effects("milk", "protected_by_totem")));
        CompoundTag changed = effects("milk");
        changed.getList("effects", Tag.TAG_COMPOUND).getCompound(0).putInt("duration", 301);
        assertFalse(adapter.equivalent(effects("milk"), changed));
        CompoundTag expected = effects("milk");
        ListTag ordered = new ListTag(); ordered.add(IntTag.valueOf(1)); ordered.add(IntTag.valueOf(2));
        expected.put("unrelated", ordered);
        CompoundTag actual = effects("milk");
        ListTag reversed = new ListTag(); reversed.add(IntTag.valueOf(2)); reversed.add(IntTag.valueOf(1));
        actual.put("unrelated", reversed);
        assertFalse(adapter.equivalent(expected, actual));
    }

    @Test
    void duplicateAndWrongTypedCuresAreRejectedEvenWhenBothInputsMatch() {
        CompoundTag duplicates = effects("milk", "milk");
        assertFalse(adapter.equivalent(duplicates, duplicates));
        assertThrows(IllegalArgumentException.class, () -> PotionEffectPayloads.normalize(duplicates));
        CompoundTag wrongType = effects("milk");
        wrongType.getList("effects", Tag.TAG_COMPOUND).getCompound(0).putInt("neoforge:cures", 1);
        assertFalse(adapter.verifyRestored(null, wrongType, wrongType, null).accepted());
    }

    @Test
    void normalizationIsStableAndDoesNotInventAnAbsentCureSet() {
        CompoundTag payload = effects("protected_by_totem", "milk");
        CompoundTag canonical = PotionEffectPayloads.normalize(payload);
        assertEquals(canonical, PotionEffectPayloads.normalize(canonical));
        CompoundTag absent = effects("milk");
        absent.getList("effects", Tag.TAG_COMPOUND).getCompound(0).remove("neoforge:cures");
        assertEquals(absent, PotionEffectPayloads.normalize(absent));
        assertFalse(adapter.equivalent(absent, effects()));
    }

    static CompoundTag effects(String... cures) {
        CompoundTag payload = new CompoundTag();
        ListTag entries = new ListTag(); entries.add(effect(cures)); payload.put("effects", entries);
        return payload;
    }

    private static CompoundTag effect(String... cures) {
        CompoundTag effect = new CompoundTag();
        effect.putString("id", "minecraft:night_vision"); effect.putInt("duration", 300);
        ListTag list = new ListTag(); for (String cure : cures) list.add(StringTag.valueOf(cure));
        effect.put("neoforge:cures", list);
        return effect;
    }
}
