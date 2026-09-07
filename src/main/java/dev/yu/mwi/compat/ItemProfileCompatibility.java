package dev.yu.mwi.compat;

import dev.yu.mwi.config.MwiConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Side-effect-free preparation of every profile format that carries an ItemStack. */
public final class ItemProfileCompatibility {
    private static final int MAX_SLOTS = 4096;

    private ItemProfileCompatibility() {
    }

    public static boolean supports(String shareId) {
        return switch (shareId) {
            case "inventory_contents", "armor_contents", "off_hand", "ender_chest",
                    "curios", "cosmetic_armor", "trash_slot" -> true;
            default -> false;
        };
    }

    public static ItemPayloadPreparation prepare(
            String shareId,
            CompoundTag payload,
            HolderLookup.Provider lookup,
            MwiConfig.ItemMigration migration) {
        Objects.requireNonNull(shareId, "shareId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(lookup, "lookup");
        Context context = new Context(lookup, null, migration);
        return prepare(shareId, payload, context);
    }

    /** Restores one archived stack without overwriting an occupied logical slot. */
    public static CompoundTag restoreStack(
            String shareId,
            CompoundTag payload,
            String logicalSlot,
            CompoundTag archivedStack,
            HolderLookup.Provider lookup,
            MwiConfig.ItemMigration migration) {
        ItemPayloadPreparation current = prepare(shareId, payload, lookup, migration);
        if (current.report().changed()) {
            throw semantics("target profile must be repaired before restoring an archive");
        }
        ItemStackCompatibilityDecoder.Result decoded = ItemStackCompatibilityDecoder.decode(
                archivedStack, logicalSlot, lookup, migration);
        return restoreDecoded(shareId, current, logicalSlot, decoded);
    }

    static CompoundTag restoreStack(
            String shareId,
            CompoundTag payload,
            String logicalSlot,
            CompoundTag archivedStack,
            ItemStackCompatibilityDecoder.Environment environment,
            MwiConfig.ItemMigration migration) {
        ItemPayloadPreparation current = prepare(shareId, payload, environment, migration);
        ItemStackCompatibilityDecoder.Result decoded = ItemStackCompatibilityDecoder.decode(
                archivedStack, logicalSlot, environment, migration);
        return restoreDecoded(shareId, current, logicalSlot, decoded);
    }

    private static CompoundTag restoreDecoded(
            String shareId,
            ItemPayloadPreparation current,
            String logicalSlot,
            ItemStackCompatibilityDecoder.Result decoded) {
        if (decoded.quarantined()) {
            throw invalid("archived stack still references unavailable registry entries");
        }
        CompoundTag result = current.payload();
        if (shareId.equals("trash_slot")) {
            if (!result.getCompound("item").isEmpty()) {
                throw semantics("trash_slot/0 is occupied");
            }
            result.put("item", decoded.encodedStack());
            return result;
        }
        if (shareId.equals("cosmetic_armor")) {
            int slot = parseTerminalSlot(logicalSlot, "cosmetic_armor/");
            CompoundTag inventory = result.getCompound("inventory");
            ListTag items = compoundList(inventory, "Items", "cosmetic_armor");
            for (int index = 0; index < items.size(); index++) {
                CompoundTag entry = items.getCompound(index);
                if (entry.getInt("Slot") == slot) {
                    if (entry.contains("id")) {
                        throw semantics(logicalSlot + " is occupied");
                    }
                    merge(entry, decoded.encodedStack());
                    return result;
                }
            }
            CompoundTag entry = decoded.encodedStack();
            entry.putInt("Slot", slot);
            items.add(entry);
            return result;
        }
        if (shareId.equals("curios")) {
            restoreCurios(result, logicalSlot, decoded.encodedStack());
            return result;
        }
        int slot = parseTerminalSlot(logicalSlot, shareId + '/');
        int size = boundedSize(result, "size", shareId, 1);
        if (slot < 0 || slot >= size) {
            throw semantics(logicalSlot + " is outside the target container");
        }
        ListTag items = compoundList(result, "items", shareId);
        for (int index = 0; index < items.size(); index++) {
            if (items.getCompound(index).getInt("slot") == slot) {
                throw semantics(logicalSlot + " is occupied");
            }
        }
        CompoundTag entry = new CompoundTag();
        entry.putInt("slot", slot);
        entry.put("stack", decoded.encodedStack());
        items.add(entry);
        return result;
    }

    private static void restoreCurios(CompoundTag payload, String logicalSlot, CompoundTag stack) {
        String[] parts = logicalSlot.split("/", 5);
        if (parts.length != 4 || !parts[0].equals("curios")
                || (!parts[2].equals("Stacks") && !parts[2].equals("Cosmetics"))) {
            throw invalid("invalid Curios logical slot " + logicalSlot);
        }
        int slot;
        try {
            slot = Integer.parseInt(parts[3]);
        } catch (NumberFormatException invalid) {
            throw invalid("invalid Curios slot index " + logicalSlot);
        }
        ListTag types;
        boolean modern = payload.get("capability") instanceof CompoundTag;
        if (modern) {
            types = compoundList(payload.getCompound("capability"), "Curios", "curios capability");
        } else {
            types = compoundList(payload, "inventory", "curios legacy inventory");
        }
        for (int typeIndex = 0; typeIndex < types.size(); typeIndex++) {
            CompoundTag type = types.getCompound(typeIndex);
            if (!parts[1].equals(type.getString("Identifier"))) {
                continue;
            }
            CompoundTag handler = modern ? type.getCompound("StacksHandler") : type;
            CompoundTag inventory = handler.getCompound(parts[2]);
            int size = boundedSize(inventory, "Size", logicalSlot, 0);
            if (slot < 0 || slot >= size) {
                throw semantics(logicalSlot + " is outside the target container");
            }
            ListTag items = compoundList(inventory, "Items", logicalSlot);
            for (int index = 0; index < items.size(); index++) {
                if (items.getCompound(index).getInt("Slot") == slot) {
                    throw semantics(logicalSlot + " is occupied");
                }
            }
            CompoundTag entry = stack.copy();
            entry.putInt("Slot", slot);
            items.add(entry);
            return;
        }
        throw invalid("Curios slot type is unavailable: " + parts[1]);
    }

    private static int parseTerminalSlot(String logicalSlot, String prefix) {
        if (!logicalSlot.startsWith(prefix)) {
            throw invalid("logical slot does not belong to share: " + logicalSlot);
        }
        try {
            return Integer.parseInt(logicalSlot.substring(prefix.length()));
        } catch (NumberFormatException invalid) {
            throw invalid("invalid logical slot " + logicalSlot);
        }
    }

    static ItemPayloadPreparation prepare(
            String shareId,
            CompoundTag payload,
            ItemStackCompatibilityDecoder.Environment environment,
            MwiConfig.ItemMigration migration) {
        return prepare(shareId, payload, new Context(null, environment, migration));
    }

    private static ItemPayloadPreparation prepare(
            String shareId, CompoundTag payload, Context context) {
        CompoundTag sanitized = payload.copy();
        switch (shareId) {
            case "inventory_contents", "armor_contents", "off_hand", "ender_chest" ->
                    sanitizeVanilla(sanitized, shareId, context);
            case "curios" -> sanitizeCurios(sanitized, context);
            case "cosmetic_armor" -> sanitizeCosmeticArmor(sanitized, context);
            case "trash_slot" -> sanitizeTrashSlot(sanitized, context);
            default -> throw new IllegalArgumentException("share does not carry item stacks: " + shareId);
        }
        return new ItemPayloadPreparation(
                sanitized, new ItemCompatibilityReport(context.issues, context.aliasesApplied));
    }

    private static void sanitizeVanilla(CompoundTag payload, String shareId, Context context) {
        int size = boundedSize(payload, "size", shareId, 1);
        ListTag items = compoundList(payload, "items", shareId);
        payload.put("items", sanitizeIndexedItems(
                items, "slot", size, shareId + '/', "stack", false, context));
    }

    private static void sanitizeTrashSlot(CompoundTag payload, Context context) {
        Tag raw = payload.get("item");
        if (!(raw instanceof CompoundTag item)) {
            throw invalid("trash_slot item must be a compound");
        }
        if (item.isEmpty()) {
            return;
        }
        ItemStackCompatibilityDecoder.Result decoded = ItemStackCompatibilityDecoder.decode(
                item, "trash_slot/0", context.environment(), context.migration);
        context.record(decoded);
        payload.put("item", decoded.quarantined() ? new CompoundTag() : decoded.encodedStack());
    }

    private static void sanitizeCosmeticArmor(CompoundTag payload, Context context) {
        Tag raw = payload.get("inventory");
        if (!(raw instanceof CompoundTag inventory)) {
            throw invalid("cosmetic_armor inventory must be a compound");
        }
        int size = boundedSize(inventory, "Size", "cosmetic_armor", 1);
        ListTag items = compoundList(inventory, "Items", "cosmetic_armor");
        Set<Integer> occupied = new HashSet<>();
        ListTag sanitized = new ListTag();
        for (int index = 0; index < items.size(); index++) {
            CompoundTag entry = items.getCompound(index);
            int slot = requireSlot(entry, "Slot", size, "cosmetic_armor", occupied);
            CompoundTag copy = entry.copy();
            if (entry.contains("id")) {
                ItemStackCompatibilityDecoder.Result decoded = ItemStackCompatibilityDecoder.decode(
                        entry, "cosmetic_armor/" + slot, context.environment(), context.migration);
                context.record(decoded);
                if (decoded.quarantined()) {
                    removeStackFields(copy);
                    if (copy.getBoolean("isSkinArmor")) {
                        sanitized.add(copy);
                    }
                    continue;
                }
                removeStackFields(copy);
                merge(copy, decoded.encodedStack());
            } else if (!entry.getBoolean("isSkinArmor")) {
                throw invalid("cosmetic_armor slot " + slot + " contains neither an item nor a skin flag");
            }
            sanitized.add(copy);
        }
        inventory.put("Items", sanitized);
        if (inventory.contains("Hidden") && !inventory.contains("Hidden", Tag.TAG_STRING)) {
            throw invalid("cosmetic_armor Hidden must be a string");
        }
    }

    private static void sanitizeCurios(CompoundTag payload, Context context) {
        if (payload.get("capability") instanceof CompoundTag capability) {
            sanitizeCuriosTypes(compoundList(capability, "Curios", "curios capability"), true, context);
            return;
        }
        if (payload.get("inventory") instanceof ListTag legacy
                && (legacy.isEmpty() || legacy.getElementType() == Tag.TAG_COMPOUND)) {
            sanitizeCuriosTypes(legacy, false, context);
            return;
        }
        throw invalid("curios payload has neither a modern capability nor a legacy inventory");
    }

    private static void sanitizeCuriosTypes(ListTag types, boolean modern, Context context) {
        Set<String> identifiers = new HashSet<>();
        for (int typeIndex = 0; typeIndex < types.size(); typeIndex++) {
            CompoundTag type = types.getCompound(typeIndex);
            if (!type.contains("Identifier", Tag.TAG_STRING) || type.getString("Identifier").isBlank()) {
                throw invalid("curios entry " + typeIndex + " has no Identifier");
            }
            String identifier = type.getString("Identifier");
            if (!identifiers.add(identifier)) {
                throw invalid("curios payload has duplicate identifier " + identifier);
            }
            CompoundTag handler;
            if (modern) {
                if (!(type.get("StacksHandler") instanceof CompoundTag value)) {
                    throw invalid("curios " + identifier + " has no StacksHandler");
                }
                handler = value;
            } else {
                handler = type;
            }
            sanitizeCuriosInventory(handler, identifier, "Stacks", context);
            sanitizeCuriosInventory(handler, identifier, "Cosmetics", context);
        }
    }

    private static void sanitizeCuriosInventory(
            CompoundTag handler, String identifier, String name, Context context) {
        Tag raw = handler.get(name);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof CompoundTag inventory)) {
            throw invalid("curios " + identifier + ' ' + name + " must be a compound");
        }
        int size = boundedSize(inventory, "Size", "curios " + identifier + ' ' + name, 0);
        ListTag items = compoundList(inventory, "Items", "curios " + identifier + ' ' + name);
        inventory.put("Items", sanitizeIndexedItems(
                items,
                "Slot",
                size,
                "curios/" + identifier + '/' + name + '/',
                null,
                true,
                context));
    }

    private static ListTag sanitizeIndexedItems(
            ListTag items,
            String slotKey,
            int size,
            String pathPrefix,
            String nestedStackKey,
            boolean stackSharesEntry,
            Context context) {
        Set<Integer> occupied = new HashSet<>();
        ListTag sanitized = new ListTag();
        for (int index = 0; index < items.size(); index++) {
            CompoundTag entry = items.getCompound(index);
            int slot = requireSlot(entry, slotKey, size, pathPrefix, occupied);
            CompoundTag encoded;
            if (stackSharesEntry) {
                encoded = entry;
            } else {
                Tag rawStack = entry.get(nestedStackKey);
                if (!(rawStack instanceof CompoundTag stack) || stack.isEmpty()) {
                    throw invalid(pathPrefix + slot + " has no encoded stack");
                }
                encoded = stack;
            }
            ItemStackCompatibilityDecoder.Result decoded = ItemStackCompatibilityDecoder.decode(
                    encoded, pathPrefix + slot, context.environment(), context.migration);
            context.record(decoded);
            if (decoded.quarantined()) {
                continue;
            }
            CompoundTag copy = entry.copy();
            if (stackSharesEntry) {
                removeStackFields(copy);
                merge(copy, decoded.encodedStack());
            } else {
                copy.put(nestedStackKey, decoded.encodedStack());
            }
            sanitized.add(copy);
        }
        return sanitized;
    }

    private static int requireSlot(
            CompoundTag entry, String key, int size, String context, Set<Integer> occupied) {
        if (!entry.contains(key, Tag.TAG_ANY_NUMERIC)) {
            throw invalid(context + " item has no " + key);
        }
        int slot = entry.getInt(key);
        if (slot < 0 || slot >= size) {
            throw semantics(context + " slot " + slot + " is out of range");
        }
        if (!occupied.add(slot)) {
            throw semantics(context + " contains duplicate slot " + slot);
        }
        return slot;
    }

    private static int boundedSize(CompoundTag data, String key, String context, int minimum) {
        if (!data.contains(key, Tag.TAG_ANY_NUMERIC)) {
            throw invalid(context + " has no numeric slot count");
        }
        int size = data.getInt(key);
        if (size < minimum || size > MAX_SLOTS) {
            throw semantics(context + " slot count is out of range: " + size);
        }
        return size;
    }

    private static ListTag compoundList(CompoundTag data, String key, String context) {
        Tag raw = data.get(key);
        if (!(raw instanceof ListTag list)
                || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
            throw invalid(context + ' ' + key + " must be a compound list");
        }
        return list;
    }

    private static void removeStackFields(CompoundTag tag) {
        tag.remove("id");
        tag.remove("count");
        tag.remove("components");
    }

    private static void merge(CompoundTag target, CompoundTag source) {
        for (String key : source.getAllKeys()) {
            target.put(key, Objects.requireNonNull(source.get(key)).copy());
        }
    }

    private static ItemPayloadException invalid(String detail) {
        return new ItemPayloadException(
                ItemCompatibilityReason.MALFORMED_NBT,
                "invalid item profile payload: " + detail);
    }

    private static ItemPayloadException semantics(String detail) {
        return new ItemPayloadException(
                ItemCompatibilityReason.INVALID_STACK_SEMANTICS,
                "invalid item profile payload: " + detail);
    }

    private static final class Context {
        private final HolderLookup.Provider lookup;
        private final ItemStackCompatibilityDecoder.Environment configuredEnvironment;
        private final MwiConfig.ItemMigration migration;
        private final List<ItemCompatibilityIssue> issues = new ArrayList<>();
        private int aliasesApplied;

        private Context(
                HolderLookup.Provider lookup,
                ItemStackCompatibilityDecoder.Environment configuredEnvironment,
                MwiConfig.ItemMigration migration) {
            this.lookup = lookup;
            this.configuredEnvironment = configuredEnvironment != null
                    ? configuredEnvironment
                    : productionEnvironment(Objects.requireNonNull(lookup, "lookup"));
            this.migration = Objects.requireNonNull(migration, "migration");
        }

        private ItemStackCompatibilityDecoder.Environment environment() {
            return configuredEnvironment;
        }

        private static ItemStackCompatibilityDecoder.Environment productionEnvironment(
                HolderLookup.Provider lookup) {
            return new ItemStackCompatibilityDecoder.Environment() {
                @Override
                public boolean itemExists(net.minecraft.resources.ResourceLocation id) {
                    return lookup.lookup(net.minecraft.core.registries.Registries.ITEM)
                            .flatMap(found -> found.get(net.minecraft.resources.ResourceKey.create(
                                    net.minecraft.core.registries.Registries.ITEM, id)))
                            .isPresent();
                }

                @Override
                public boolean componentExists(net.minecraft.resources.ResourceLocation id) {
                    return lookup.lookup(net.minecraft.core.registries.Registries.DATA_COMPONENT_TYPE)
                            .flatMap(found -> found.get(net.minecraft.resources.ResourceKey.create(
                                    net.minecraft.core.registries.Registries.DATA_COMPONENT_TYPE, id)))
                            .isPresent();
                }

                @Override
                public void validate(CompoundTag candidate, int count, String logicalSlot) {
                    net.minecraft.world.item.ItemStack stack = net.minecraft.world.item.ItemStack.parse(
                                    lookup, candidate)
                            .filter(decoded -> !decoded.isEmpty())
                            .orElseThrow(() -> semantics(logicalSlot
                                    + " codec rejected a stack with present registry keys"));
                    if (stack.getCount() != count || count > stack.getMaxStackSize()) {
                        throw semantics(logicalSlot + " count violates current item limits");
                    }
                }
            };
        }

        private void record(ItemStackCompatibilityDecoder.Result result) {
            if (result.quarantined()) {
                issues.add(result.issue());
            } else if (result.aliasApplied()) {
                aliasesApplied++;
            }
        }
    }
}
