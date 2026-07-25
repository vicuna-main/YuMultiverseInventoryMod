package dev.yu.mwi.integration.cosmeticarmor;

import dev.yu.mwi.api.AdapterIds;
import dev.yu.mwi.api.ItemEditSlotPolicy;
import dev.yu.mwi.api.SharableAdapter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Soft-loaded adapter for Cosmetic Armor Reworked.
 *
 * <p>The mod owns a server-side {@code CAStacksBase} containing the cosmetic items, skin-armor
 * flags and per-mod hidden flags. Its public API exposes a complete NBT codec; using that codec is
 * important because copying only the visible item slots would leave appearance flags shared across
 * worlds.</p>
 */
public final class CosmeticArmorInventoryAdapter implements SharableAdapter, ItemEditSlotPolicy {
    private static final int SCHEMA_VERSION = 1;
    private static final String SCHEMA = "schema";
    private static final String INVENTORY = "inventory";

    private final Method getInventory;
    private final Method serialize;
    private final Method deserialize;
    private final Method getSlots;

    public CosmeticArmorInventoryAdapter() {
        try {
            Class<?> api = Class.forName("lain.mods.cos.api.CosArmorAPI");
            Class<?> stacks = Class.forName("lain.mods.cos.api.inventory.CAStacksBase");
            getInventory = api.getMethod("getCAStacks", java.util.UUID.class);
            serialize = stacks.getMethod("serializeNBT", HolderLookup.Provider.class);
            deserialize = stacks.getMethod("deserializeNBT", HolderLookup.Provider.class, CompoundTag.class);
            getSlots = stacks.getMethod("getSlots");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Cosmetic Armor Reworked API is incompatible; expected CosArmorAPI/CAStacksBase 1.21.1 API",
                    failure);
        }
    }

    @Override
    public ResourceLocation id() {
        return AdapterIds.COSMETIC_ARMOR;
    }

    @Override
    public boolean available(ServerPlayer player) {
        Object inventory = findInventory(player);
        return inventory != null && slotCount(inventory) > 0;
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        CompoundTag encoded = serialize(inventory(player), lookup);
        validateInventory(encoded, lookup);
        CompoundTag result = new CompoundTag();
        result.putInt(SCHEMA, SCHEMA_VERSION);
        result.put(INVENTORY, encoded.copy());
        return result;
    }

    @Override
    public void clear(ServerPlayer player) {
        Object inventory = inventory(player);
        CompoundTag empty = new CompoundTag();
        empty.putInt("Size", slotCount(inventory));
        empty.put("Items", new ListTag());
        empty.putString("Hidden", "");
        deserialize(inventory, player.registryAccess(), empty);
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        CompoundTag encoded = requirePayload(data);
        validateInventory(encoded, lookup);
        deserialize(inventory(player), lookup, encoded.copy());
    }

    @Override
    public void sync(ServerPlayer player) {
        // InventoryCosArmor.deserializeNBT invokes onLoad(), and the mod's registered listeners send
        // every slot, skin-armor bit and hidden flag to tracking clients. The generic transaction
        // coordinator broadcasts the open vanilla menu afterwards as well.
    }

    @Override
    public void validateItemEditSlot(ServerPlayer player, String slotKey, ItemStack replacement) {
        if (replacement.isEmpty()) {
            return;
        }
        int slot = parseSlot(slotKey);
        EquipmentSlot expected = switch (slot) {
            case 0 -> EquipmentSlot.FEET;
            case 1 -> EquipmentSlot.LEGS;
            case 2 -> EquipmentSlot.CHEST;
            case 3 -> EquipmentSlot.HEAD;
            default -> throw invalid("时装盔甲槽编号无效：" + (slot + 1));
        };
        if (replacement.getCount() != 1) {
            throw invalid("时装盔甲槽每格只能放置 1 件物品");
        }
        if (player.getEquipmentSlotForItem(replacement) != expected) {
            throw invalid("该物品不能放入对应的时装盔甲槽：" + slotKey);
        }
    }

    private static int parseSlot(String slotKey) {
        String prefix = "cosmetic_armor/";
        if (slotKey == null || !slotKey.startsWith(prefix)) {
            throw new IllegalArgumentException("时装盔甲编辑槽标识无效");
        }
        try {
            return Integer.parseInt(slotKey.substring(prefix.length()));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("时装盔甲编辑槽标识无效", failure);
        }
    }

    private CompoundTag requirePayload(CompoundTag data) {
        if (data == null) {
            throw invalid("payload is null");
        }
        if (data.contains(SCHEMA, Tag.TAG_ANY_NUMERIC) && data.getInt(SCHEMA) != SCHEMA_VERSION) {
            throw invalid("unsupported schema " + data.getInt(SCHEMA));
        }
        Tag raw = data.get(INVENTORY);
        if (!(raw instanceof CompoundTag inventory)) {
            throw invalid("missing compound '" + INVENTORY + "'");
        }
        return inventory;
    }

    private void validateInventory(CompoundTag inventory, HolderLookup.Provider lookup) {
        if (!inventory.contains("Size", Tag.TAG_ANY_NUMERIC)) {
            throw invalid("Cosmetic Armor data has no Size");
        }
        int size = inventory.getInt("Size");
        if (size < 1 || size > 256) {
            throw invalid("Cosmetic Armor slot count is out of range: " + size);
        }
        Tag rawItems = inventory.get("Items");
        if (!(rawItems instanceof ListTag items)
                || (!items.isEmpty() && items.getElementType() != Tag.TAG_COMPOUND)) {
            throw invalid("Cosmetic Armor Items must be a compound list");
        }
        Set<Integer> occupied = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            CompoundTag entry = items.getCompound(index);
            if (!entry.contains("Slot", Tag.TAG_ANY_NUMERIC)) {
                throw invalid("Cosmetic Armor item " + index + " has no Slot");
            }
            int slot = entry.getInt("Slot");
            if (slot < 0 || slot >= size) {
                throw invalid("Cosmetic Armor slot " + slot + " is outside 0.." + (size - 1));
            }
            if (!occupied.add(slot)) {
                throw invalid("duplicate Cosmetic Armor slot " + slot);
            }
            if (entry.contains("id")) {
                ItemStack.parse(lookup, entry).orElseThrow(
                        () -> invalid("Cosmetic Armor slot " + slot + " contains an invalid item"));
            } else if (!entry.getBoolean("isSkinArmor")) {
                throw invalid("Cosmetic Armor slot " + slot + " contains neither an item nor a skin flag");
            }
        }
        if (inventory.contains("Hidden") && !inventory.contains("Hidden", Tag.TAG_STRING)) {
            throw invalid("Cosmetic Armor Hidden must be a string");
        }
    }

    private Object inventory(ServerPlayer player) {
        Object inventory = findInventory(player);
        if (inventory == null) {
            throw new IllegalStateException(
                    "Cosmetic Armor Reworked inventory is unavailable for player " + player.getUUID());
        }
        return inventory;
    }

    private Object findInventory(ServerPlayer player) {
        return invoke(getInventory, null, player.getUUID());
    }

    private CompoundTag serialize(Object inventory, HolderLookup.Provider lookup) {
        Object value = invoke(serialize, inventory, lookup);
        if (!(value instanceof CompoundTag tag)) {
            throw new IllegalStateException("Cosmetic Armor Reworked returned non-compound inventory data");
        }
        return tag;
    }

    private void deserialize(Object inventory, HolderLookup.Provider lookup, CompoundTag data) {
        invoke(deserialize, inventory, lookup, data);
    }

    private int slotCount(Object inventory) {
        Object value = invoke(getSlots, inventory);
        if (!(value instanceof Integer slots)) {
            throw new IllegalStateException("Cosmetic Armor Reworked returned a non-integer slot count");
        }
        return slots;
    }

    private static Object invoke(Method method, Object owner, Object... arguments) {
        try {
            return method.invoke(owner, arguments);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot access Cosmetic Armor Reworked API", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Cosmetic Armor Reworked API call failed", cause);
        }
    }

    private IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("invalid " + id() + " payload: " + detail);
    }
}
