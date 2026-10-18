package dev.yu.mwi.adapter.vanilla;

import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.util.*;

/** Health-owned persistent capacity; equipment/effect modifiers remain owned by their original sources. */
final class AbsorptionCapacity {
    static final String KEY = "absorption_capacity";
    private static final ResourceLocation LEGACY_CARRY = ResourceLocation.fromNamespaceAndPath(
            "yu_multiverse_inventory", "preserved_absorption");

    private AbsorptionCapacity() {}

    static CompoundTag capture(AttributeInstance attribute) {
        CompoundTag result = new CompoundTag();
        result.putDouble("base", attribute.getBaseValue());
        ListTag modifiers = new ListTag();
        attribute.save().getList("modifiers", Tag.TAG_COMPOUND).stream().map(tag -> (CompoundTag) tag)
                .sorted(Comparator.comparing(tag -> tag.getString("id"))).forEach(tag -> modifiers.add(tag.copy()));
        result.put("modifiers", modifiers);
        result.putBoolean("legacy_carry", attribute.hasModifier(LEGACY_CARRY));
        validate(result);
        return result;
    }

    static void validate(CompoundTag capacity) {
        if (!capacity.getAllKeys().equals(Set.of("base", "modifiers", "legacy_carry"))
                || !capacity.contains("base", Tag.TAG_DOUBLE) || !Double.isFinite(capacity.getDouble("base"))
                || capacity.getDouble("base") < 0 || capacity.getDouble("base") > 1_000_000
                || !capacity.contains("legacy_carry", Tag.TAG_BYTE))
            throw NbtPayload.invalid("health", "invalid absorption capacity");
        Set<ResourceLocation> seen = new HashSet<>();
        for (Tag entry : NbtPayload.requireCompoundList(capacity, "modifiers", "health")) {
            CompoundTag tag = (CompoundTag) entry;
            AttributeModifier modifier = AttributeModifier.load(tag);
            if (modifier == null || !Double.isFinite(modifier.amount()) || Math.abs(modifier.amount()) > 1_000_000
                    || modifier.id().equals(LEGACY_CARRY) || !seen.add(modifier.id()) || !modifier.save().equals(tag))
                throw NbtPayload.invalid("health", "invalid, duplicate or lossy absorption modifier");
        }
    }

    static void clear(AttributeInstance attribute) {
        // AttributeInstance.save lists permanent modifiers only. Dynamic equipment/effect owners remain intact.
        for (Tag entry : attribute.save().getList("modifiers", Tag.TAG_COMPOUND)) {
            AttributeModifier modifier = AttributeModifier.load((CompoundTag) entry);
            if (modifier == null) throw new IllegalStateException("Invalid live absorption modifier");
            attribute.removeModifier(modifier.id());
        }
        attribute.removeModifier(LEGACY_CARRY);
        attribute.setBaseValue(attribute.getAttribute().value().getDefaultValue());
    }

    static void apply(AttributeInstance attribute, CompoundTag capacity) {
        validate(capacity);
        clear(attribute);
        attribute.setBaseValue(capacity.getDouble("base"));
        for (Tag entry : capacity.getList("modifiers", Tag.TAG_COMPOUND)) {
            AttributeModifier modifier = Objects.requireNonNull(AttributeModifier.load((CompoundTag) entry));
            attribute.addOrReplacePermanentModifier(modifier);
        }
    }

    static boolean hasCarry(AttributeInstance attribute) { return attribute.hasModifier(LEGACY_CARRY); }

    static void preserveExistingShield(AttributeInstance attribute, float absorption) {
        attribute.removeModifier(LEGACY_CARRY);
        if (absorption <= attribute.getValue()) return;
        double factor = 1.0, baseFactor = 1.0;
        for (AttributeModifier modifier : attribute.getModifiers()) {
            if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) baseFactor += modifier.amount();
            if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) factor *= 1 + modifier.amount();
        }
        factor *= baseFactor;
        if (!Double.isFinite(factor) || factor <= 0) throw new IllegalStateException("Absorption capacity has unsupported multipliers");
        double amount = (absorption - attribute.getValue()) / factor;
        if (!Double.isFinite(amount) || amount > 1_000_000) throw new IllegalStateException("Absorption carry exceeds safe capacity");
        attribute.addTransientModifier(new AttributeModifier(LEGACY_CARRY, amount, AttributeModifier.Operation.ADD_VALUE));
    }
}
