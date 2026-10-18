package dev.yu.mwi.adapter.vanilla;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemovedEffectsTest {
    private static CompoundTag input() throws Exception {
        return TagParser.parseTag("{effects:[{id:'minecraft:night_vision',duration:200},{id:'removed_example:vanished',duration:300},{id:'installed_example:renamed',duration:400}]}");
    }
    @Test void onlyAbsentModAndAbsentRegistrationIsArchived() throws Exception {
        CompoundTag original = input(), before = original.copy();
        var result = PotionEffectPayloads.prepareAfterUninstall(original,
                id -> id.getNamespace().equals("minecraft"), namespace -> namespace.equals("installed_example"));
        assertEquals(java.util.List.of("removed_example:vanished"),result.removed());
        assertEquals(2,result.payload().getList("effects",Tag.TAG_COMPOUND).size());
        assertEquals(before,original);
        assertFalse(PotionEffectPayloads.verify(original,result.payload()).accepted(),"Removal must never masquerade as equivalence");
    }
    @Test void registeredEffectsRemainEvenWhenRegistryNamespaceDiffersFromModId() throws Exception {
        assertEquals(input(),PotionEffectPayloads.prepareAfterUninstall(input(),id -> true,namespace -> false).payload());
    }
    @Test void malformedAndDuplicateEffectsCannotBeSilentlyRemoved() throws Exception {
        var original = input(); original.getList("effects",Tag.TAG_COMPOUND).add(original.getList("effects",Tag.TAG_COMPOUND).getCompound(1).copy());
        assertThrows(IllegalArgumentException.class,() -> PotionEffectPayloads.prepareAfterUninstall(original,id -> false,namespace -> false));
        var wrong = input(); wrong.getList("effects",Tag.TAG_COMPOUND).getCompound(1).putInt("id",2);
        assertThrows(IllegalArgumentException.class,() -> PotionEffectPayloads.prepareAfterUninstall(wrong,id -> false,namespace -> false));
    }
}
