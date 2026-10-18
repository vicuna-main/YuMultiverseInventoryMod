package dev.yu.mwi.api;

import net.minecraft.nbt.*;
import java.util.*;

/** Bounded field paths for recovery diagnostics; never dumps an inventory or an entire player record. */
public final class NbtDifference {
    private NbtDifference() {}
    public static String first(Tag expected, Tag actual) { return first(expected, actual, "$", 0); }
    private static String first(Tag expected, Tag actual, String path, int depth) {
        if (Objects.equals(expected, actual)) return "";
        if (depth >= 32) return path + ": nested values differ";
        if (expected == null || actual == null) return path + ": " + (expected == null ? "unexpected field" : "missing field");
        if (expected.getId() != actual.getId()) return path + ": NBT type " + expected.getId() + " -> " + actual.getId();
        if (expected instanceof CompoundTag before && actual instanceof CompoundTag after) {
            Set<String> keys = new TreeSet<>(before.getAllKeys()); keys.addAll(after.getAllKeys());
            for (String key : keys) {
                if (!Objects.equals(before.get(key), after.get(key))) return first(before.get(key), after.get(key),
                        path + "." + key.substring(0, Math.min(key.length(), 80)), depth + 1);
            }
        }
        if (expected instanceof ListTag before && actual instanceof ListTag after) {
            if (before.size() != after.size()) return path + ": list length " + before.size() + " -> " + after.size();
            for (int index = 0; index < before.size(); index++) {
                if (!Objects.equals(before.get(index), after.get(index))) return first(before.get(index), after.get(index), path + "[" + index + "]", depth + 1);
            }
        }
        if (expected instanceof NumericTag && actual instanceof NumericTag)
            return path + ": " + expected.getAsString() + " -> " + actual.getAsString();
        return path + ": value differs";
    }
}
