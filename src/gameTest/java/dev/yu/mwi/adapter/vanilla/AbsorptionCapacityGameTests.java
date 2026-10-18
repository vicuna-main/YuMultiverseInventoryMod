package dev.yu.mwi.adapter.vanilla;

import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.*;
import net.neoforged.neoforge.gametest.*;

/** Capacity serialization uses the actual bootstrapped attribute registry. */
@GameTestHolder("yu_multiverse_inventory")
@PrefixGameTestTemplate(false)
public final class AbsorptionCapacityGameTests {
    private static AttributeInstance attribute() { return new AttributeInstance(Attributes.MAX_ABSORPTION,ignored -> {}); }
    private static AttributeModifier modifier(String name,double amount,AttributeModifier.Operation operation) {
        return new AttributeModifier(ResourceLocation.fromNamespaceAndPath("example",name),amount,operation);
    }
    @GameTest(template="empty")
    public static void persistentCapacityDoesNotOwnEquipmentTransientModifiers(GameTestHelper helper) {
        var source=attribute(); source.setBaseValue(3);
        source.addOrReplacePermanentModifier(modifier("saved",2,AttributeModifier.Operation.ADD_VALUE));
        source.addTransientModifier(modifier("equipment",4,AttributeModifier.Operation.ADD_VALUE));
        var snapshot=AbsorptionCapacity.capture(source);
        var restored=attribute(); restored.addTransientModifier(modifier("equipment",4,AttributeModifier.Operation.ADD_VALUE));
        AbsorptionCapacity.apply(restored,snapshot); AbsorptionCapacity.apply(restored,snapshot);
        helper.assertTrue(restored.getValue()==9 && snapshot.equals(AbsorptionCapacity.capture(restored)),"Repeated capacity restore must preserve exactly one permanent source");
        AbsorptionCapacity.clear(restored);
        helper.assertTrue(restored.getValue()==4,"Equipment must retain its own transient modifier"); helper.succeed();
    }
    @GameTest(template="empty")
    public static void carryShrinksWithConsumptionAndNeverBecomesPermanent(GameTestHelper helper) {
        var attribute=attribute();
        attribute.addTransientModifier(modifier("scale",0.5,AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        AbsorptionCapacity.preserveExistingShield(attribute,10);
        helper.assertTrue(Math.abs(10-attribute.getValue())<0.000001 && attribute.getBaseValue()==0,"Carry must respect multipliers without raising base capacity");
        helper.assertTrue(AbsorptionCapacity.hasCarry(attribute) && AbsorptionCapacity.capture(attribute).getList("modifiers",10).isEmpty(),"Carry must remain transient");
        AbsorptionCapacity.preserveExistingShield(attribute,4);
        helper.assertTrue(Math.abs(4-attribute.getValue())<0.000001,"Carry must shrink with consumed shield");
        AbsorptionCapacity.preserveExistingShield(attribute,0);
        helper.assertTrue(!AbsorptionCapacity.hasCarry(attribute) && attribute.getValue()==0,"Exhausted carry must disappear"); helper.succeed();
    }
}
