package dev.yu.mwi.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yu.mwi.config.MwiConfig;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ItemProfileCompatibilityTest {
    private static final ItemStackCompatibilityDecoder.Environment ENVIRONMENT =
            new ItemStackCompatibilityDecoder.Environment() {
                @Override
                public boolean itemExists(ResourceLocation id) {
                    return id.getNamespace().equals("minecraft");
                }

                @Override
                public boolean componentExists(ResourceLocation id) {
                    return id.getNamespace().equals("minecraft");
                }

                @Override
                public void validate(CompoundTag candidate, int count, String logicalSlot) {
                    if (count > 64) {
                        throw new IllegalArgumentException("count exceeds fake test limit");
                    }
                }
            };
    private static final ItemStackCompatibilityDecoder.Environment RESTORED_ENVIRONMENT =
            new ItemStackCompatibilityDecoder.Environment() {
                @Override
                public boolean itemExists(ResourceLocation id) {
                    return true;
                }

                @Override
                public boolean componentExists(ResourceLocation id) {
                    return true;
                }

                @Override
                public void validate(CompoundTag candidate, int count, String logicalSlot) {
                }
            };

    @Test
    void quarantinesEveryMissingVanillaStackWithoutMovingValidSlots() {
        CompoundTag payload = vanilla(36,
                entry(2, stack("minecraft:stone", 12)),
                entry(12, stack("evolvedmekanism:end_stone_osmium_ore", 1)),
                entry(18, stack("evolvedmekanism:end_stone_tin_ore", 2)),
                entry(19, stack("evolvedmekanism:end_stone_lead_ore", 3)),
                entry(35, stack("minecraft:dirt", 7)));

        ItemPayloadPreparation prepared = ItemProfileCompatibility.prepare(
                "inventory_contents", payload, ENVIRONMENT, MwiConfig.ItemMigration.defaults());

        ListTag items = prepared.payload().getList("items", CompoundTag.TAG_COMPOUND);
        assertEquals(2, items.size());
        assertEquals(2, items.getCompound(0).getInt("slot"));
        assertEquals(12, items.getCompound(0).getCompound("stack").getInt("count"));
        assertEquals(35, items.getCompound(1).getInt("slot"));
        assertEquals(3, prepared.report().issues().size());
        assertTrue(prepared.report().issues().stream().anyMatch(issue ->
                issue.logicalSlot().equals("inventory_contents/12")
                        && issue.originalStack().getString("id")
                        .equals("evolvedmekanism:end_stone_osmium_ore")));
        assertEquals(5, payload.getList("items", CompoundTag.TAG_COMPOUND).size(),
                "preparation must not mutate the canonical input");
    }

    @Test
    void coversAllSupportedModContainerLayouts() {
        CompoundTag curios = new CompoundTag();
        CompoundTag capability = new CompoundTag();
        ListTag types = new ListTag();
        CompoundTag type = new CompoundTag();
        type.putString("Identifier", "ring");
        CompoundTag handler = new CompoundTag();
        handler.put("Stacks", handlerInventory(2,
                directEntry(0, "missingmod:ring"), directEntry(1, "minecraft:stone")));
        handler.put("Cosmetics", handlerInventory(1, directEntry(0, "missingmod:cosmetic")));
        type.put("StacksHandler", handler);
        types.add(type);
        capability.put("Curios", types);
        curios.put("capability", capability);
        ItemPayloadPreparation curiosPrepared = ItemProfileCompatibility.prepare(
                "curios", curios, ENVIRONMENT, MwiConfig.ItemMigration.defaults());
        CompoundTag sanitizedHandler = curiosPrepared.payload().getCompound("capability")
                .getList("Curios", CompoundTag.TAG_COMPOUND).getCompound(0).getCompound("StacksHandler");
        assertEquals(1, sanitizedHandler.getCompound("Stacks")
                .getList("Items", CompoundTag.TAG_COMPOUND).size());
        assertTrue(sanitizedHandler.getCompound("Cosmetics")
                .getList("Items", CompoundTag.TAG_COMPOUND).isEmpty());

        CompoundTag armor = new CompoundTag();
        CompoundTag inventory = handlerInventory(4, directEntry(1, "missingmod:boots"));
        inventory.getList("Items", CompoundTag.TAG_COMPOUND).getCompound(0).putBoolean("isSkinArmor", true);
        armor.put("inventory", inventory);
        ItemPayloadPreparation armorPrepared = ItemProfileCompatibility.prepare(
                "cosmetic_armor", armor, ENVIRONMENT, MwiConfig.ItemMigration.defaults());
        CompoundTag preservedSkin = armorPrepared.payload().getCompound("inventory")
                .getList("Items", CompoundTag.TAG_COMPOUND).getCompound(0);
        assertTrue(preservedSkin.getBoolean("isSkinArmor"));
        assertFalse(preservedSkin.contains("id"));

        CompoundTag trash = new CompoundTag();
        trash.put("item", stack("missingmod:trash", 1));
        ItemPayloadPreparation trashPrepared = ItemProfileCompatibility.prepare(
                "trash_slot", trash, ENVIRONMENT, MwiConfig.ItemMigration.defaults());
        assertTrue(trashPrepared.payload().getCompound("item").isEmpty());
    }

    @Test
    void missingComponentQuarantinesWholeOriginalStack() {
        CompoundTag encoded = stack("minecraft:stone", 1);
        CompoundTag components = new CompoundTag();
        components.putString("removedmod:component", "opaque-value");
        encoded.put("components", components);

        ItemPayloadPreparation prepared = ItemProfileCompatibility.prepare(
                "off_hand", vanilla(1, entry(0, encoded)), ENVIRONMENT, MwiConfig.ItemMigration.defaults());

        assertTrue(prepared.payload().getList("items", CompoundTag.TAG_COMPOUND).isEmpty());
        assertEquals(ItemCompatibilityReason.MISSING_DATA_COMPONENT_TYPE,
                prepared.report().issues().getFirst().reason());
        assertEquals("opaque-value", prepared.report().issues().getFirst()
                .originalStack().getCompound("components").getString("removedmod:component"));
    }

    @Test
    void aliasMigratesOnlyToAnExistingDecodableTarget() {
        ResourceLocation old = ResourceLocation.parse("oldmod:old_item");
        MwiConfig.ItemMigration migration = new MwiConfig.ItemMigration(
                MwiConfig.MissingItemPolicy.QUARANTINE_AND_CONTINUE,
                Map.of(old, ResourceLocation.parse("minecraft:stone")));

        ItemPayloadPreparation prepared = ItemProfileCompatibility.prepare(
                "off_hand", vanilla(1, entry(0, stack(old.toString(), 4))), ENVIRONMENT, migration);

        assertEquals("minecraft:stone", prepared.payload().getList("items", CompoundTag.TAG_COMPOUND)
                .getCompound(0).getCompound("stack").getString("id"));
        assertEquals(4, prepared.payload().getList("items", CompoundTag.TAG_COMPOUND)
                .getCompound(0).getCompound("stack").getInt("count"));
        assertEquals(1, prepared.report().aliasesApplied());
        assertTrue(prepared.report().issues().isEmpty());
    }

    @Test
    void corruptionAndStrictPolicyRemainFatal() {
        CompoundTag malformed = stack("not a resource location", 1);
        ItemPayloadException malformedFailure = assertThrows(ItemPayloadException.class, () ->
                ItemProfileCompatibility.prepare(
                "off_hand", vanilla(1, entry(0, malformed)), ENVIRONMENT,
                MwiConfig.ItemMigration.defaults()));
        assertEquals(ItemCompatibilityReason.MALFORMED_NBT, malformedFailure.reason());

        CompoundTag duplicate = vanilla(1,
                entry(0, stack("minecraft:stone", 1)), entry(0, stack("minecraft:dirt", 1)));
        ItemPayloadException duplicateFailure = assertThrows(ItemPayloadException.class, () ->
                ItemProfileCompatibility.prepare(
                "off_hand", duplicate, ENVIRONMENT, MwiConfig.ItemMigration.defaults()));
        assertEquals(ItemCompatibilityReason.INVALID_STACK_SEMANTICS, duplicateFailure.reason());

        MwiConfig.ItemMigration strict = new MwiConfig.ItemMigration(
                MwiConfig.MissingItemPolicy.STRICT_FAIL, Map.of());
        ItemPayloadException strictFailure = assertThrows(ItemPayloadException.class, () ->
                ItemProfileCompatibility.prepare(
                "off_hand", vanilla(1, entry(0, stack("missingmod:item", 1))), ENVIRONMENT, strict));
        assertEquals(ItemCompatibilityReason.MISSING_ITEM_REGISTRY_ENTRY, strictFailure.reason());
    }

    @Test
    void restoreRequiresRegistryAvailabilityAndRefusesOccupiedTarget() {
        CompoundTag empty = vanilla(1);
        CompoundTag archived = stack("missingmod:restored", 1);
        assertThrows(IllegalArgumentException.class, () -> ItemProfileCompatibility.restoreStack(
                "off_hand", empty, "off_hand/0", archived,
                ENVIRONMENT, MwiConfig.ItemMigration.defaults()));

        CompoundTag restored = ItemProfileCompatibility.restoreStack(
                "off_hand", empty, "off_hand/0", archived,
                RESTORED_ENVIRONMENT, MwiConfig.ItemMigration.defaults());
        assertEquals("missingmod:restored", restored.getList("items", CompoundTag.TAG_COMPOUND)
                .getCompound(0).getCompound("stack").getString("id"));
        assertThrows(IllegalArgumentException.class, () -> ItemProfileCompatibility.restoreStack(
                "off_hand", restored, "off_hand/0", archived,
                RESTORED_ENVIRONMENT, MwiConfig.ItemMigration.defaults()));
    }

    private static CompoundTag vanilla(int size, CompoundTag... entries) {
        CompoundTag payload = new CompoundTag();
        payload.putInt("size", size);
        ListTag items = new ListTag();
        Stream.of(entries).forEach(items::add);
        payload.put("items", items);
        return payload;
    }

    private static CompoundTag entry(int slot, CompoundTag stack) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("slot", slot);
        entry.put("stack", stack);
        return entry;
    }

    private static CompoundTag directEntry(int slot, String id) {
        CompoundTag entry = stack(id, 1);
        entry.putInt("Slot", slot);
        return entry;
    }

    private static CompoundTag handlerInventory(int size, CompoundTag... entries) {
        CompoundTag inventory = new CompoundTag();
        inventory.putInt("Size", size);
        ListTag items = new ListTag();
        Stream.of(entries).forEach(items::add);
        inventory.put("Items", items);
        return inventory;
    }

    private static CompoundTag stack(String id, int count) {
        CompoundTag stack = new CompoundTag();
        stack.putString("id", id);
        if (count != 1) {
            stack.putInt("count", count);
        }
        return stack;
    }
}
