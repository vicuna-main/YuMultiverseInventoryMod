package dev.yu.mwi.compat;

import dev.yu.mwi.config.MwiConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Strict stack decoder that only recovers registry absence proven from the original NBT. */
public final class ItemStackCompatibilityDecoder {
    private ItemStackCompatibilityDecoder() {
    }

    public static Result decode(
            CompoundTag original,
            String logicalSlot,
            HolderLookup.Provider lookup,
            MwiConfig.ItemMigration migration) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(logicalSlot, "logicalSlot");
        Objects.requireNonNull(lookup, "lookup");
        Environment environment = new Environment() {
            @Override
            public boolean itemExists(ResourceLocation id) {
                return registryContains(lookup, Registries.ITEM, id);
            }

            @Override
            public boolean componentExists(ResourceLocation id) {
                return registryContains(lookup, Registries.DATA_COMPONENT_TYPE, id);
            }

            @Override
            public void validate(CompoundTag candidate, int count, String slot) {
                net.minecraft.world.item.ItemStack stack = net.minecraft.world.item.ItemStack.parse(lookup, candidate)
                        .filter(decoded -> !decoded.isEmpty())
                        .orElseThrow(() -> semantics(slot,
                                "stack codec rejected data after all referenced registry keys were verified"));
                if (stack.getCount() != count || count > stack.getMaxStackSize()) {
                    throw semantics(slot, "stack count violates current item limits");
                }
            }
        };
        return decode(original, logicalSlot, environment, migration);
    }

    static Result decode(
            CompoundTag original,
            String logicalSlot,
            Environment environment,
            MwiConfig.ItemMigration migration) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(logicalSlot, "logicalSlot");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(migration, "migration");
        if (original.isEmpty()) {
            throw malformed(logicalSlot, "stack compound is empty");
        }
        if (!original.contains("id", Tag.TAG_STRING)) {
            throw malformed(logicalSlot, "stack id is missing or is not a string");
        }
        ResourceLocation sourceId = parseId(original.getString("id"), logicalSlot + " item id");
        if (original.contains("count") && !original.contains("count", Tag.TAG_ANY_NUMERIC)) {
            throw malformed(logicalSlot, "stack count is not numeric");
        }
        int count = original.contains("count", Tag.TAG_ANY_NUMERIC) ? original.getInt("count") : 1;
        if (count < 1) {
            throw semantics(logicalSlot, "stack count must be positive");
        }

        CompoundTag candidate = original.copy();
        ResourceLocation targetId = migration.resolve(sourceId);
        boolean aliased = !targetId.equals(sourceId);
        if (aliased) {
            candidate.putString("id", targetId.toString());
        }

        if (!environment.itemExists(targetId)) {
            return recoverable(original, logicalSlot, ItemCompatibilityReason.MISSING_ITEM_REGISTRY_ENTRY,
                    sourceId, List.of(), migration);
        }

        List<ResourceLocation> missingComponents = new ArrayList<>();
        if (candidate.contains("components")) {
            Tag rawComponents = candidate.get("components");
            if (!(rawComponents instanceof CompoundTag components)) {
                throw malformed(logicalSlot, "stack components is not a compound");
            }
            for (String encodedComponent : components.getAllKeys()) {
                String registryName = encodedComponent.startsWith("!")
                        ? encodedComponent.substring(1)
                        : encodedComponent;
                ResourceLocation componentId = parseId(
                        registryName, logicalSlot + " component id");
                if (!environment.componentExists(componentId)) {
                    missingComponents.add(componentId);
                }
            }
        }
        if (!missingComponents.isEmpty()) {
            return recoverable(original, logicalSlot, ItemCompatibilityReason.MISSING_DATA_COMPONENT_TYPE,
                    sourceId, missingComponents, migration);
        }

        environment.validate(candidate, count, logicalSlot);
        return new Result(candidate, null, aliased);
    }

    private static Result recoverable(
            CompoundTag original,
            String logicalSlot,
            ItemCompatibilityReason reason,
            ResourceLocation itemId,
            List<ResourceLocation> components,
            MwiConfig.ItemMigration migration) {
        if (migration.missingItemPolicy() == MwiConfig.MissingItemPolicy.STRICT_FAIL) {
            throw new ItemPayloadException(
                    reason, "invalid item payload at " + logicalSlot
                            + ": registry drift is forbidden by strict_fail");
        }
        return new Result(null,
                new ItemCompatibilityIssue(logicalSlot, reason, itemId, components, original), false);
    }

    private static ResourceLocation parseId(String encoded, String context) {
        try {
            return ResourceLocation.parse(encoded);
        } catch (RuntimeException invalid) {
            throw new ItemPayloadException(
                    ItemCompatibilityReason.MALFORMED_NBT,
                    "invalid item payload: " + context + " is malformed", invalid);
        }
    }

    private static <T> boolean registryContains(
            HolderLookup.Provider lookup,
            ResourceKey<? extends net.minecraft.core.Registry<T>> registry,
            ResourceLocation id) {
        return lookup.lookup(registry)
                .flatMap(found -> found.get(ResourceKey.create(registry, id)))
                .isPresent();
    }

    private static ItemPayloadException malformed(String slot, String detail) {
        return new ItemPayloadException(
                ItemCompatibilityReason.MALFORMED_NBT,
                "invalid item payload at " + slot + ": " + detail);
    }

    private static ItemPayloadException semantics(String slot, String detail) {
        return new ItemPayloadException(
                ItemCompatibilityReason.INVALID_STACK_SEMANTICS,
                "invalid item payload at " + slot + ": " + detail);
    }

    public record Result(
            CompoundTag encodedStack,
            ItemCompatibilityIssue issue,
            boolean aliasApplied) {
        public Result {
            encodedStack = encodedStack == null ? null : encodedStack.copy();
        }

        public boolean quarantined() {
            return issue != null;
        }

        @Override
        public CompoundTag encodedStack() {
            return encodedStack == null ? null : encodedStack.copy();
        }

    }

    interface Environment {
        boolean itemExists(ResourceLocation id);

        boolean componentExists(ResourceLocation id);

        void validate(CompoundTag candidate, int count, String logicalSlot);
    }
}
