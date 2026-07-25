package dev.yu.mwi.command;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemProfileEditorCodecTest {
    @Test
    void vanillaInventoryCanBeClearedWithoutChangingItsShape() {
        CompoundTag payload = new CompoundTag();
        payload.putInt("size", 36);
        payload.put("items", new ListTag());

        CompoundTag empty = ItemProfileEditorCodec.emptyLike("inventory_contents", payload);

        assertEquals(36, empty.getInt("size"));
        assertTrue(empty.getList("items", CompoundTag.TAG_COMPOUND).isEmpty());
    }

    @Test
    void curiosClearingPreservesSlotTypesAndSizes() {
        CompoundTag payload = new CompoundTag();
        CompoundTag capability = new CompoundTag();
        ListTag curios = new ListTag();
        curios.add(curiosType("ring", 3, 2));
        curios.add(curiosType("charm", 1, 1));
        capability.put("Curios", curios);
        payload.put("capability", capability);

        CompoundTag empty = ItemProfileEditorCodec.emptyLike("curios", payload);
        ListTag emptiedTypes = empty.getCompound("capability").getList("Curios", CompoundTag.TAG_COMPOUND);
        assertEquals(2, emptiedTypes.size());
        assertEquals(3, emptiedTypes.getCompound(0).getCompound("StacksHandler")
                .getCompound("Stacks").getInt("Size"));
        assertEquals(2, emptiedTypes.getCompound(0).getCompound("StacksHandler")
                .getCompound("Cosmetics").getInt("Size"));
        for (int index = 0; index < emptiedTypes.size(); index++) {
            CompoundTag handler = emptiedTypes.getCompound(index).getCompound("StacksHandler");
            assertTrue(handler.getCompound("Stacks").getList("Items", CompoundTag.TAG_COMPOUND).isEmpty());
            assertTrue(handler.getCompound("Cosmetics").getList("Items", CompoundTag.TAG_COMPOUND).isEmpty());
        }
    }

    @Test
    void curiosAcceptsDisabledZeroSizedSlotTypes() {
        CompoundTag payload = new CompoundTag();
        CompoundTag capability = new CompoundTag();
        ListTag curios = new ListTag();
        curios.add(curiosType("disabled", 0, 0));
        curios.add(curiosType("ring", 2, 0));
        capability.put("Curios", curios);
        payload.put("capability", capability);

        assertEquals(2, ItemProfileEditorCodec.curiosSlotCount(payload));
    }

    @Test
    void curiosSlotsExposeStableTypeAndPurposeLabels() {
        java.util.List<ItemProfileEditorCodec.SlotInfo> slots = java.util.List.of(
                ItemProfileEditorCodec.curiosSlotInfo("ring", "Stacks", 0),
                ItemProfileEditorCodec.curiosSlotInfo("ring", "Stacks", 1),
                ItemProfileEditorCodec.curiosSlotInfo("ring", "Cosmetics", 0),
                ItemProfileEditorCodec.curiosSlotInfo("back", "Stacks", 0));

        assertEquals(java.util.List.of(
                        "curios/ring/Stacks/0",
                        "curios/ring/Stacks/1",
                        "curios/ring/Cosmetics/0",
                        "curios/back/Stacks/0"),
                slots.stream().map(ItemProfileEditorCodec.SlotInfo::key).toList());
        assertEquals(ItemProfileEditorCodec.SlotKind.CURIOS_EQUIPMENT,
                slots.getFirst().kind());
        assertEquals(ItemProfileEditorCodec.SlotKind.CURIOS_COSMETIC,
                slots.get(2).kind());
        assertTrue(slots.getFirst().label().contains("ring"));
        assertTrue(slots.get(2).label().contains("外观"));
    }

    @Test
    void cosmeticArmorSlotsAreNamedInNativeFeetToHeadOrder() {
        assertEquals(java.util.List.of(
                        "时装盔甲槽 · 靴子",
                        "时装盔甲槽 · 护腿",
                        "时装盔甲槽 · 胸甲",
                        "时装盔甲槽 · 头盔"),
                java.util.stream.IntStream.range(0, 4)
                        .mapToObj(slot -> ItemProfileEditorCodec.cosmeticArmorSlotInfo(slot, 4).label())
                        .toList());
    }

    @Test
    void allVanillaItemContainersAreEditable() {
        for (String shareId : java.util.List.of(
                "inventory_contents", "armor_contents", "off_hand", "ender_chest")) {
            CompoundTag payload = new CompoundTag();
            payload.putInt("size", 1);
            payload.put("items", new ListTag());

            CompoundTag empty = ItemProfileEditorCodec.emptyLike(shareId, payload);
            assertEquals(1, empty.getInt("size"));
            assertTrue(empty.getList("items", CompoundTag.TAG_COMPOUND).isEmpty());
        }
    }

    @Test
    void clearingCosmeticItemsPreservesSkinArmorAndVisibilityMetadata() {
        CompoundTag payload = new CompoundTag();
        CompoundTag inventory = new CompoundTag();
        inventory.putInt("Size", 4);
        inventory.putString("Hidden", "0101");
        ListTag items = new ListTag();
        CompoundTag skin = new CompoundTag();
        skin.putInt("Slot", 2);
        skin.putBoolean("isSkinArmor", true);
        items.add(skin);
        inventory.put("Items", items);
        payload.put("inventory", inventory);

        CompoundTag empty = ItemProfileEditorCodec.emptyLike("cosmetic_armor", payload);

        assertEquals("0101", empty.getCompound("inventory").getString("Hidden"));
        ListTag preserved = empty.getCompound("inventory").getList("Items", CompoundTag.TAG_COMPOUND);
        assertEquals(1, preserved.size());
        assertTrue(preserved.getCompound(0).getBoolean("isSkinArmor"));
        assertFalse(preserved.getCompound(0).contains("id"));
    }

    private static CompoundTag curiosType(String identifier, int equipmentSlots, int cosmeticSlots) {
        CompoundTag type = new CompoundTag();
        type.putString("Identifier", identifier);
        CompoundTag handler = new CompoundTag();
        handler.put("Stacks", itemHandler(equipmentSlots));
        handler.put("Cosmetics", itemHandler(cosmeticSlots));
        type.put("StacksHandler", handler);
        return type;
    }

    private static CompoundTag itemHandler(int size) {
        CompoundTag inventory = new CompoundTag();
        inventory.putInt("Size", size);
        inventory.put("Items", new ListTag());
        return inventory;
    }
}
