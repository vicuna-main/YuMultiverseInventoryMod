package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.RestoreVerification;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import java.util.*;

/** Compares item slots individually and leaves all non-item metadata strictly typed and ordered. */
public final class ItemPayloadVerification {
    private ItemPayloadVerification() {}

    public static RestoreVerification cosmetic(CompoundTag expected, CompoundTag actual, HolderLookup.Provider lookup) {
        try {
            CompoundTag left = expected.copy(), right = actual.copy();
            RestoreVerification items = inventory(compound(left,"inventory"), compound(right,"inventory"), lookup, true, true);
            if (!items.accepted()) return items;
            if (!left.equals(right)) return different("Cosmetic metadata or skin flags differ");
            return new RestoreVerification(expected.equals(actual) ? RestoreVerification.Status.EXACT
                    : RestoreVerification.Status.SEMANTIC_EQUIVALENT, ItemStackSemantics.RULE, "", actual);
        } catch (IllegalArgumentException failure) { return unsupported(failure.getMessage()); }
    }

    public static RestoreVerification trash(CompoundTag expected, CompoundTag actual, HolderLookup.Provider lookup) {
        try {
            CompoundTag left = expected.copy(), right = actual.copy();
            CompoundTag a = compound(left,"item"), b = compound(right,"item");
            if (!a.isEmpty() || !b.isEmpty()) {
                if (a.isEmpty() || b.isEmpty()) return different("Trash item presence differs");
                RestoreVerification item = ItemStackSemantics.verify(a,b,lookup);
                if (!item.accepted()) return item;
                left.put("item", b.copy());
            }
            if (!left.equals(right)) return different("Trash metadata differs");
            return new RestoreVerification(expected.equals(actual) ? RestoreVerification.Status.EXACT
                    : RestoreVerification.Status.SEMANTIC_EQUIVALENT, ItemStackSemantics.RULE, "", actual);
        } catch (IllegalArgumentException failure) { return unsupported(failure.getMessage()); }
    }

    public static RestoreVerification verify(CompoundTag expected, CompoundTag actual,
            HolderLookup.Provider lookup, boolean curios) {
        if (expected == null || actual == null) return unsupported("Missing adapter payload");
        try {
            CompoundTag left = expected.copy(), right = actual.copy();
            if (curios) {
                ListTag a = compoundList(compound(left, "capability"), "Curios");
                ListTag b = compoundList(compound(right, "capability"), "Curios");
                if (a.size() != b.size()) return different("Curios slot types differ");
                Set<String> identifiers = new HashSet<>();
                for (int i = 0; i < a.size(); i++) {
                    CompoundTag first = a.getCompound(i), second = b.getCompound(i);
                    String id = first.getString("Identifier");
                    if (id.isBlank() || !identifiers.add(id) || !id.equals(second.getString("Identifier")))
                        return different("Curios slot identity/order differs");
                    CompoundTag x = compound(first, "StacksHandler"), y = compound(second, "StacksHandler");
                    for (String kind : List.of("Stacks", "Cosmetics")) {
                        if (!x.contains(kind) && !y.contains(kind)) continue;
                        RestoreVerification result = inventory(compound(x, kind), compound(y, kind), lookup, true);
                        if (!result.accepted()) return result;
                    }
                }
            } else {
                RestoreVerification result = inventory(left, right, lookup, false);
                if (!result.accepted()) return result;
            }
            if (!left.equals(right)) return different("Non-item metadata, slot identity or capacity differs");
            return new RestoreVerification(expected.equals(actual) ? RestoreVerification.Status.EXACT
                    : RestoreVerification.Status.SEMANTIC_EQUIVALENT, ItemStackSemantics.RULE, "", actual);
        } catch (IllegalArgumentException failure) { return unsupported(failure.getMessage()); }
    }

    private static RestoreVerification inventory(CompoundTag left, CompoundTag right, HolderLookup.Provider lookup, boolean curios) {
        return inventory(left, right, lookup, curios, false);
    }

    private static RestoreVerification inventory(CompoundTag left, CompoundTag right, HolderLookup.Provider lookup, boolean curios, boolean skinFlags) {
        String sizeKey = curios ? "Size" : "size", itemsKey = curios ? "Items" : "items", slotKey = curios ? "Slot" : "slot";
        if (!left.contains(sizeKey, Tag.TAG_INT) || !right.contains(sizeKey, Tag.TAG_INT)
                || left.getInt(sizeKey) != right.getInt(sizeKey) || left.getInt(sizeKey) < 0 || left.getInt(sizeKey) > 1024)
            return different("Invalid or changed inventory capacity");
        ListTag a = compoundList(left, itemsKey), b = compoundList(right, itemsKey);
        if (a.size() != b.size()) return different("Occupied slot count differs");
        Set<Integer> occupied = new HashSet<>();
        for (int i = 0; i < a.size(); i++) {
            CompoundTag first = a.getCompound(i), second = b.getCompound(i);
            int slot = first.getInt(slotKey);
            if (!first.contains(slotKey, Tag.TAG_INT) || !second.contains(slotKey, Tag.TAG_INT)
                    || slot < 0 || slot >= left.getInt(sizeKey) || !occupied.add(slot) || slot != second.getInt(slotKey))
                return different("Invalid or changed inventory slot");
            CompoundTag x = curios ? first.copy() : compound(first, "stack");
            CompoundTag y = curios ? second.copy() : compound(second, "stack");
            if (curios) { x.remove(slotKey); y.remove(slotKey); }
            if (skinFlags) {
                if (!Objects.equals(x.get("isSkinArmor"),y.get("isSkinArmor"))) return different("Cosmetic skin flag differs");
                if (!x.contains("id") || !y.contains("id")) {
                    if (!x.equals(y) || !x.getBoolean("isSkinArmor")) return different("Invalid cosmetic flag-only slot");
                    continue;
                }
                x.remove("isSkinArmor"); y.remove("isSkinArmor");
            }
            RestoreVerification verified = ItemStackSemantics.verify(x, y, lookup);
            if (!verified.accepted()) return new RestoreVerification(verified.status(), verified.rule(),
                    "slot " + slot + ": " + verified.detail(), null);
            if (curios) {
                a.set(i, second.copy());
            } else {
                first.put("stack", y.copy());
            }
        }
        return RestoreVerification.strict(true, right);
    }

    private static CompoundTag compound(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof CompoundTag value)) throw new IllegalArgumentException("Missing compound: " + key);
        return value;
    }
    private static ListTag compoundList(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof ListTag list) || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Invalid compound list: " + key);
        return list;
    }
    private static RestoreVerification different(String detail) {
        return new RestoreVerification(RestoreVerification.Status.DIFFERENT, ItemStackSemantics.RULE, detail, null);
    }
    private static RestoreVerification unsupported(String detail) {
        return new RestoreVerification(RestoreVerification.Status.UNSUPPORTED, ItemStackSemantics.RULE, detail, null);
    }
}
