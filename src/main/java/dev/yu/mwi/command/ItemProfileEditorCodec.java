package dev.yu.mwi.command;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Strict item-only projection of the supported profile payloads used by the inventory editor. */
final class ItemProfileEditorCodec {
    private static final int MAX_EDITABLE_SLOTS = 4096;

    private ItemProfileEditorCodec() {
    }

    static Document decode(String shareId, CompoundTag payload, HolderLookup.Provider lookup) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(lookup, "lookup");
        return switch (shareId) {
            case "inventory_contents", "armor_contents", "off_hand", "ender_chest" ->
                    decodeItemList(shareId, payload, lookup);
            case "curios" -> decodeCurios(payload, lookup);
            case "cosmetic_armor" -> decodeCosmeticArmor(payload, lookup);
            case "trash_slot" -> decodeTrashSlot(payload, lookup);
            default -> throw new IllegalArgumentException("Share is not item-editable: " + shareId);
        };
    }

    static CompoundTag emptyLike(String shareId, CompoundTag template) {
        CompoundTag empty = template.copy();
        switch (shareId) {
            case "inventory_contents", "armor_contents", "off_hand", "ender_chest" ->
                    empty.put("items", new ListTag());
            case "curios" -> clearCurios(empty);
            case "cosmetic_armor" -> clearCosmeticArmor(empty);
            case "trash_slot" -> empty.put("item", new CompoundTag());
            default -> throw new IllegalArgumentException("Share is not item-editable: " + shareId);
        }
        return empty;
    }

    private static Document decodeItemList(
            String shareId, CompoundTag payload, HolderLookup.Provider lookup) {
        String context = switch (shareId) {
            case "inventory_contents" -> "inventory";
            case "armor_contents" -> "armor";
            case "off_hand" -> "off-hand";
            case "ender_chest" -> "ender chest";
            default -> throw new IllegalArgumentException("Share is not an item list: " + shareId);
        };
        int size = boundedSize(payload, "size", context, 1);
        ListTag items = compoundList(payload, "items", context);
        Map<Integer, CompoundTag> entries = indexedEntries(items, "slot", size, context);
        List<SlotReference> slots = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            int current = slot;
            CompoundTag entry = entries.get(slot);
            ItemStack stack = entry == null ? ItemStack.EMPTY : parseStack(entry.getCompound("stack"), lookup,
                    context + " slot " + slot);
            slots.add(new SlotReference(stack, slotInfo(
                    shareId + "/" + slot,
                    vanillaSlotLabel(shareId, slot, size),
                    SlotKind.STANDARD), (root, replacement) -> {
                ListTag target = compoundList(root, "items", context);
                replaceIndexed(target, "slot", current, replacement, encoded -> {
                    CompoundTag result = new CompoundTag();
                    result.putInt("slot", current);
                    result.put("stack", encoded);
                    return result;
                }, lookup);
            }));
        }
        return new Document(payload, slots);
    }

    private static Document decodeTrashSlot(CompoundTag payload, HolderLookup.Provider lookup) {
        Tag raw = payload.get("item");
        if (!(raw instanceof CompoundTag item)) {
            throw invalid("trash slot has no compound item");
        }
        ItemStack stack = item.isEmpty() ? ItemStack.EMPTY : parseStack(item, lookup, "trash slot");
        return new Document(payload, List.of(new SlotReference(stack,
                slotInfo("trash_slot/0", "垃圾槽", SlotKind.STANDARD),
                (root, replacement) -> root.put("item", saveOptional(replacement, lookup)))));
    }

    private static Document decodeCosmeticArmor(CompoundTag payload, HolderLookup.Provider lookup) {
        Tag rawInventory = payload.get("inventory");
        if (!(rawInventory instanceof CompoundTag inventory)) {
            throw invalid("cosmetic armor has no compound inventory");
        }
        int size = boundedSize(inventory, "Size", "cosmetic armor", 1);
        ListTag items = compoundList(inventory, "Items", "cosmetic armor");
        Map<Integer, CompoundTag> entries = indexedEntries(items, "Slot", size, "cosmetic armor");
        List<SlotReference> slots = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            int current = slot;
            CompoundTag entry = entries.get(slot);
            ItemStack stack = entry == null || !entry.contains("id", Tag.TAG_STRING)
                    ? ItemStack.EMPTY : parseStack(entry, lookup, "cosmetic armor slot " + slot);
            slots.add(new SlotReference(stack, cosmeticArmorSlotInfo(slot, size), (root, replacement) -> {
                CompoundTag targetInventory = root.getCompound("inventory");
                ListTag target = compoundList(targetInventory, "Items", "cosmetic armor");
                replaceCosmetic(target, current, replacement, lookup);
            }));
        }
        return new Document(payload, slots);
    }

    private static Document decodeCurios(CompoundTag payload, HolderLookup.Provider lookup) {
        curiosSlotCount(payload);
        Tag rawCapability = payload.get("capability");
        if (!(rawCapability instanceof CompoundTag capability)) {
            throw invalid("Curios legacy profiles must be loaded once before editing");
        }
        ListTag curios = compoundList(capability, "Curios", "Curios");
        List<SlotReference> slots = new ArrayList<>();
        for (int typeIndex = 0; typeIndex < curios.size(); typeIndex++) {
            CompoundTag type = curios.getCompound(typeIndex);
            String identifier = requireString(type, "Identifier", "Curios slot type");
            Tag rawHandler = type.get("StacksHandler");
            if (!(rawHandler instanceof CompoundTag handler)) {
                throw invalid("Curios slot type " + identifier + " has no StacksHandler");
            }
            appendCuriosInventory(slots, payload, typeIndex, identifier, "Stacks", handler, lookup);
            appendCuriosInventory(slots, payload, typeIndex, identifier, "Cosmetics", handler, lookup);
        }
        return new Document(payload, slots);
    }

    static int curiosSlotCount(CompoundTag payload) {
        Tag rawCapability = payload.get("capability");
        if (!(rawCapability instanceof CompoundTag capability)) {
            throw invalid("Curios legacy profiles must be loaded once before editing");
        }
        ListTag curios = compoundList(capability, "Curios", "Curios");
        int total = 0;
        for (int typeIndex = 0; typeIndex < curios.size(); typeIndex++) {
            CompoundTag type = curios.getCompound(typeIndex);
            String identifier = requireString(type, "Identifier", "Curios slot type");
            Tag rawHandler = type.get("StacksHandler");
            if (!(rawHandler instanceof CompoundTag handler)) {
                throw invalid("Curios slot type " + identifier + " has no StacksHandler");
            }
            for (String inventoryName : List.of("Stacks", "Cosmetics")) {
                if (handler.get(inventoryName) instanceof CompoundTag inventory) {
                    int size = boundedSize(
                            inventory, "Size", "Curios " + identifier + ' ' + inventoryName, 0);
                    if (total > MAX_EDITABLE_SLOTS - size) {
                        throw invalid("profile contains too many editable slots");
                    }
                    total += size;
                }
            }
        }
        return total;
    }

    private static void appendCuriosInventory(
            List<SlotReference> slots,
            CompoundTag payload,
            int typeIndex,
            String identifier,
            String inventoryName,
            CompoundTag handler,
            HolderLookup.Provider lookup) {
        Tag raw = handler.get(inventoryName);
        if (!(raw instanceof CompoundTag inventory)) {
            return;
        }
        int size = boundedSize(inventory, "Size", "Curios " + identifier + ' ' + inventoryName, 0);
        ListTag items = compoundList(inventory, "Items", "Curios " + identifier + ' ' + inventoryName);
        Map<Integer, CompoundTag> entries = indexedEntries(items, "Slot", size,
                "Curios " + identifier + ' ' + inventoryName);
        for (int slot = 0; slot < size; slot++) {
            int current = slot;
            CompoundTag entry = entries.get(slot);
            ItemStack stack = entry == null ? ItemStack.EMPTY
                    : parseStack(entry, lookup, "Curios " + identifier + ' ' + inventoryName + " slot " + slot);
            slots.add(new SlotReference(stack, curiosSlotInfo(identifier, inventoryName, slot),
                    (root, replacement) -> {
                ListTag rootTypes = root.getCompound("capability").getList("Curios", Tag.TAG_COMPOUND);
                if (typeIndex >= rootTypes.size()
                        || !identifier.equals(rootTypes.getCompound(typeIndex).getString("Identifier"))) {
                    throw invalid("Curios slot layout changed while the editor was open");
                }
                CompoundTag rootInventory = rootTypes.getCompound(typeIndex)
                        .getCompound("StacksHandler").getCompound(inventoryName);
                ListTag rootItems = compoundList(rootInventory, "Items", "Curios " + identifier);
                replaceIndexed(rootItems, "Slot", current, replacement, encoded -> {
                    CompoundTag result = encoded.copy();
                    result.putInt("Slot", current);
                    return result;
                }, lookup);
            }));
        }
        if (slots.size() > MAX_EDITABLE_SLOTS) {
            throw invalid("profile contains too many editable slots");
        }
    }

    private static void clearCurios(CompoundTag payload) {
        ListTag curios = payload.getCompound("capability").getList("Curios", Tag.TAG_COMPOUND);
        for (int index = 0; index < curios.size(); index++) {
            CompoundTag handler = curios.getCompound(index).getCompound("StacksHandler");
            clearItemHandler(handler, "Stacks");
            clearItemHandler(handler, "Cosmetics");
        }
    }

    private static void clearItemHandler(CompoundTag handler, String key) {
        if (handler.get(key) instanceof CompoundTag inventory) {
            inventory.put("Items", new ListTag());
        }
    }

    private static void clearCosmeticArmor(CompoundTag payload) {
        CompoundTag inventory = payload.getCompound("inventory");
        ListTag source = inventory.getList("Items", Tag.TAG_COMPOUND);
        ListTag preserved = new ListTag();
        for (int index = 0; index < source.size(); index++) {
            CompoundTag entry = source.getCompound(index).copy();
            removeStackFields(entry);
            if (entry.getBoolean("isSkinArmor")) {
                preserved.add(entry);
            }
        }
        inventory.put("Items", preserved);
    }

    private static void replaceCosmetic(
            ListTag items, int slot, ItemStack replacement, HolderLookup.Provider lookup) {
        int index = findIndex(items, "Slot", slot);
        CompoundTag entry = index < 0 ? new CompoundTag() : items.getCompound(index).copy();
        removeStackFields(entry);
        if (!replacement.isEmpty()) {
            merge(entry, save(replacement, lookup));
            entry.putInt("Slot", slot);
        }
        if (replacement.isEmpty() && !entry.getBoolean("isSkinArmor")) {
            if (index >= 0) {
                items.remove(index);
            }
        } else if (index >= 0) {
            items.set(index, entry);
        } else {
            items.add(entry);
        }
    }

    private static void removeStackFields(CompoundTag entry) {
        entry.remove("id");
        entry.remove("count");
        entry.remove("components");
    }

    private static void merge(CompoundTag target, CompoundTag source) {
        for (String key : source.getAllKeys()) {
            target.put(key, Objects.requireNonNull(source.get(key)).copy());
        }
    }

    private static void replaceIndexed(
            ListTag items,
            String slotKey,
            int slot,
            ItemStack replacement,
            EntryFactory factory,
            HolderLookup.Provider lookup) {
        int index = findIndex(items, slotKey, slot);
        if (replacement.isEmpty()) {
            if (index >= 0) {
                items.remove(index);
            }
            return;
        }
        CompoundTag entry = factory.create(save(replacement, lookup));
        if (index >= 0) {
            items.set(index, entry);
        } else {
            items.add(entry);
        }
    }

    private static int findIndex(ListTag items, String slotKey, int slot) {
        for (int index = 0; index < items.size(); index++) {
            if (items.getCompound(index).getInt(slotKey) == slot) {
                return index;
            }
        }
        return -1;
    }

    private static Map<Integer, CompoundTag> indexedEntries(
            ListTag items, String slotKey, int size, String context) {
        Map<Integer, CompoundTag> indexed = new LinkedHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            CompoundTag entry = items.getCompound(index);
            if (!entry.contains(slotKey, Tag.TAG_ANY_NUMERIC)) {
                throw invalid(context + " item has no " + slotKey);
            }
            int slot = entry.getInt(slotKey);
            if (slot < 0 || slot >= size || indexed.put(slot, entry) != null) {
                throw invalid(context + " contains an invalid or duplicate slot " + slot);
            }
        }
        return indexed;
    }

    private static int boundedSize(CompoundTag tag, String key, String context, int minimum) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
            throw invalid(context + " has no slot count");
        }
        int size = tag.getInt(key);
        if (size < minimum || size > MAX_EDITABLE_SLOTS) {
            throw invalid(context + " slot count is out of range: " + size);
        }
        return size;
    }

    private static ListTag compoundList(CompoundTag tag, String key, String context) {
        Tag raw = tag.get(key);
        if (!(raw instanceof ListTag list)
                || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
            throw invalid(context + ' ' + key + " must be a compound list");
        }
        return list;
    }

    private static String requireString(CompoundTag tag, String key, String context) {
        if (!tag.contains(key, Tag.TAG_STRING) || tag.getString(key).isBlank()) {
            throw invalid(context + " has no " + key);
        }
        return tag.getString(key);
    }

    private static ItemStack parseStack(
            CompoundTag encoded, HolderLookup.Provider lookup, String context) {
        return ItemStack.parse(lookup, encoded)
                .filter(stack -> !stack.isEmpty())
                .orElseThrow(() -> invalid(context + " contains an invalid item"));
    }

    private static CompoundTag save(ItemStack stack, HolderLookup.Provider lookup) {
        Tag encoded = stack.save(lookup);
        if (!(encoded instanceof CompoundTag compound)) {
            throw invalid("item codec returned non-compound data");
        }
        return compound;
    }

    private static CompoundTag saveOptional(ItemStack stack, HolderLookup.Provider lookup) {
        return stack.isEmpty() ? new CompoundTag() : save(stack, lookup);
    }

    private static SlotInfo slotInfo(String key, String label, SlotKind kind) {
        return new SlotInfo(key, label, kind);
    }

    static SlotInfo curiosSlotInfo(String identifier, String inventoryName, int slot) {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(inventoryName, "inventoryName");
        SlotKind kind = switch (inventoryName) {
            case "Stacks" -> SlotKind.CURIOS_EQUIPMENT;
            case "Cosmetics" -> SlotKind.CURIOS_COSMETIC;
            default -> throw new IllegalArgumentException("Unknown Curios inventory: " + inventoryName);
        };
        String category = kind == SlotKind.CURIOS_COSMETIC ? "外观" : "功能";
        return slotInfo(
                "curios/" + identifier + '/' + inventoryName + '/' + slot,
                "Curios " + category + "槽 · " + identifier + " · #" + (slot + 1),
                kind);
    }

    static SlotInfo cosmeticArmorSlotInfo(int slot, int size) {
        return slotInfo(
                "cosmetic_armor/" + slot,
                cosmeticArmorLabel(slot, size),
                SlotKind.COSMETIC_ARMOR);
    }

    private static String vanillaSlotLabel(String shareId, int slot, int size) {
        return switch (shareId) {
            case "inventory_contents" -> slot < 9
                    ? "快捷栏 · #" + (slot + 1)
                    : "主背包 · #" + (slot - 8);
            case "armor_contents" -> size == 4
                    ? "盔甲槽 · " + armorPosition(slot)
                    : "盔甲槽 · #" + (slot + 1);
            case "off_hand" -> "副手槽";
            case "ender_chest" -> "末影箱 · #" + (slot + 1);
            default -> shareId + " · #" + (slot + 1);
        };
    }

    private static String cosmeticArmorLabel(int slot, int size) {
        return size == 4
                ? "时装盔甲槽 · " + armorPosition(slot)
                : "时装盔甲槽 · #" + (slot + 1);
    }

    /** Minecraft stores armor in feet-to-head order. */
    private static String armorPosition(int slot) {
        return switch (slot) {
            case 0 -> "靴子";
            case 1 -> "护腿";
            case 2 -> "胸甲";
            case 3 -> "头盔";
            default -> "#" + (slot + 1);
        };
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("Invalid inventory editor payload: " + detail);
    }

    record Document(CompoundTag payload, List<SlotReference> slots) {
        Document {
            payload = payload.copy();
            slots = List.copyOf(slots);
        }

        @Override
        public CompoundTag payload() {
            return payload.copy();
        }

        List<ItemStack> items() {
            return slots.stream().map(slot -> slot.stack().copy()).toList();
        }

        List<SlotInfo> slotInfos() {
            return slots.stream().map(SlotReference::info).toList();
        }

        CompoundTag patch(Map<Integer, ItemStack> replacements) {
            CompoundTag result = payload.copy();
            replacements.forEach((slot, stack) -> {
                if (slot < 0 || slot >= slots.size()) {
                    throw new IllegalArgumentException("Editable slot is out of range: " + slot);
                }
                slots.get(slot).writer().write(result, Objects.requireNonNull(stack, "stack").copy());
            });
            return result;
        }
    }

    enum SlotKind {
        STANDARD,
        CURIOS_EQUIPMENT,
        CURIOS_COSMETIC,
        COSMETIC_ARMOR
    }

    record SlotInfo(String key, String label, SlotKind kind) {
        SlotInfo {
            key = Objects.requireNonNull(key, "key");
            label = Objects.requireNonNull(label, "label");
            kind = Objects.requireNonNull(kind, "kind");
            if (key.isBlank() || label.isBlank()) {
                throw new IllegalArgumentException("Inventory editor slot metadata cannot be blank");
            }
        }
    }

    private record SlotReference(ItemStack stack, SlotInfo info, SlotWriter writer) {
        private SlotReference {
            stack = stack.copy();
            Objects.requireNonNull(info, "info");
            Objects.requireNonNull(writer, "writer");
        }
    }

    @FunctionalInterface
    private interface SlotWriter {
        void write(CompoundTag root, ItemStack stack);
    }

    @FunctionalInterface
    private interface EntryFactory {
        CompoundTag create(CompoundTag encodedStack);
    }
}
