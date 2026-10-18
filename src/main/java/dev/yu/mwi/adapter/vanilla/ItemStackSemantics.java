package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.RestoreVerification;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Deliberately narrow, versioned representation rules; list order is significant everywhere else. */
public final class ItemStackSemantics {
    public static final String RULE = "item-codec+sfm-4.34.0-label-position-set-v1";
    private ItemStackSemantics() {}

    public static RestoreVerification verify(CompoundTag expected, CompoundTag actual, HolderLookup.Provider lookup) {
        try {
            ItemStack left = decode(expected, lookup), right = decode(actual, lookup);
            if (expected.equals(actual)) return RestoreVerification.strict(true, actual);
            if (left.getCount() != right.getCount() || !ItemStack.isSameItemSameComponents(left, right))
                return different("item, count or components differ");
            boolean sfmSupported = net.neoforged.fml.ModList.get() != null
                    && net.neoforged.fml.ModList.get().getModContainerById("sfm")
                    .map(mod -> "4.34.0".equals(mod.getModInfo().getVersion().toString())).orElse(false);
            if (!sameRepresentation(expected, actual, sfmSupported))
                return different("difference outside the supported component representation rules");
            return new RestoreVerification(RestoreVerification.Status.SEMANTIC_EQUIVALENT, RULE, "", actual);
        } catch (IllegalArgumentException failure) {
            return new RestoreVerification(RestoreVerification.Status.UNSUPPORTED, RULE, failure.getMessage(), null);
        }
    }

    public static ItemStack decode(CompoundTag tag, HolderLookup.Provider lookup) {
        var result = ItemStack.CODEC.parse(lookup.createSerializationContext(NbtOps.INSTANCE), tag);
        if (result.error().isPresent()) throw new IllegalArgumentException("ItemStack codec rejected payload");
        ItemStack stack = result.result().orElseThrow(() -> new IllegalArgumentException("ItemStack codec produced no value"));
        if (stack.isEmpty()) throw new IllegalArgumentException("Encoded nonempty item decoded to empty");
        return stack;
    }

    /** Also used against private regression fixtures without requiring production registry contents. */
    public static boolean sameRepresentation(CompoundTag left, CompoundTag right, boolean sfm4340) {
        return normalizedItem(left, sfm4340, 0).equals(normalizedItem(right, sfm4340, 0));
    }

    private static CompoundTag normalizedItem(CompoundTag item, boolean sfm4340, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Nested item depth exceeds verification budget");
        CompoundTag copy = item.copy();
        if (!(copy.get("components") instanceof CompoundTag components)) return copy;
        if (sfm4340 && components.get("sfm:labels") instanceof CompoundTag labels) normalizeLabels(labels);
        for (String key : List.of("ae2:storage_cell_inv", "minecraft:bundle_contents", "minecraft:charged_projectiles")) {
            if (!(components.get(key) instanceof ListTag items)) continue;
            for (int i = 0; i < items.size(); i++) {
                if (!(items.get(i) instanceof CompoundTag nested)) throw new IllegalArgumentException("Invalid nested item list");
                items.set(i, normalizedItem(nested, sfm4340, depth + 1));
            }
        }
        if (components.get("minecraft:container") instanceof ListTag entries) {
            for (Tag entry : entries) {
                if (!(entry instanceof CompoundTag container) || !(container.get("item") instanceof CompoundTag nested))
                    throw new IllegalArgumentException("Invalid container slot");
                container.put("item", normalizedItem(nested, sfm4340, depth + 1));
            }
        }
        return copy;
    }

    private static void normalizeLabels(CompoundTag component) {
        if (!(component.get("labels") instanceof CompoundTag labels)) throw new IllegalArgumentException("Invalid SFM labels map");
        for (String name : labels.getAllKeys()) {
            if (!(labels.get(name) instanceof ListTag positions) || positions.size() > 65_536)
                throw new IllegalArgumentException("Invalid SFM position set");
            List<int[]> sorted = new ArrayList<>();
            Set<List<Integer>> distinct = new HashSet<>();
            for (Tag position : positions) {
                if (!(position instanceof IntArrayTag array) || array.getAsIntArray().length != 3)
                    throw new IllegalArgumentException("SFM position must be a typed three-integer coordinate");
                int[] xyz = array.getAsIntArray();
                if (!distinct.add(List.of(xyz[0], xyz[1], xyz[2]))) throw new IllegalArgumentException("Duplicate SFM coordinate");
                sorted.add(xyz.clone());
            }
            sorted.sort(Arrays::compare);
            ListTag normalized = new ListTag(); sorted.forEach(xyz -> normalized.add(new IntArrayTag(xyz)));
            labels.put(name, normalized);
        }
    }

    private static RestoreVerification different(String detail) {
        return new RestoreVerification(RestoreVerification.Status.DIFFERENT, RULE, detail, null);
    }
}
