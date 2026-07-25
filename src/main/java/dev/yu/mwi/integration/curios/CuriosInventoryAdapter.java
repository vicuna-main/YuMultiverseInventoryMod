package dev.yu.mwi.integration.curios;

import dev.yu.mwi.api.AdapterIds;
import dev.yu.mwi.api.ItemEditSlotPolicy;
import dev.yu.mwi.api.SharableAdapter;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

/**
 * Isolates the complete Curios capability, including equipment, cosmetic stacks, render flags,
 * active states, dynamic sizes and persistent slot modifiers.
 */
public final class CuriosInventoryAdapter implements SharableAdapter, ItemEditSlotPolicy {
    private static final int SCHEMA_VERSION = 2;
    private static final String SCHEMA = "schema";
    private static final String CAPABILITY = "capability";
    private static final String LEGACY_INVENTORY = "inventory";

    @Override
    public ResourceLocation id() {
        return AdapterIds.CURIOS;
    }

    @Override
    public boolean available(ServerPlayer player) {
        return CuriosApi.getCuriosInventory(player).isPresent();
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        ICuriosItemHandler handler = handler(player);
        Tag encoded = handler.writeTag();
        if (!(encoded instanceof CompoundTag capability)) {
            throw new IllegalStateException("Curios returned non-compound capability data for " + player.getUUID());
        }
        validateCapability(handler, capability, lookup);
        CompoundTag data = new CompoundTag();
        data.putInt(SCHEMA, SCHEMA_VERSION);
        data.put(CAPABILITY, capability.copy());
        return data;
    }

    @Override
    public void clear(ServerPlayer player) {
        CompoundTag emptyCapability = new CompoundTag();
        emptyCapability.put("Curios", new ListTag());
        installCapability(handler(player), emptyCapability);
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        if (data == null) {
            throw invalid("payload is null");
        }
        ICuriosItemHandler handler = handler(player);

        // Schema 1 profiles from the unfinished adapter stored saveInventory(false). Keep them
        // readable and transparently upgrade them the next time the profile is saved.
        if (!data.contains(CAPABILITY) && data.contains(LEGACY_INVENTORY, Tag.TAG_LIST)) {
            ListTag inventory = requireCompoundList(data, LEGACY_INVENTORY);
            validateLegacyInventory(handler, inventory, lookup);
            handler.saveInventory(true);
            handler.loadInventory(inventory.copy());
            markForSynchronization(handler);
            return;
        }

        if (data.contains(SCHEMA, Tag.TAG_ANY_NUMERIC)) {
            int schema = data.getInt(SCHEMA);
            if (schema != SCHEMA_VERSION) {
                throw invalid("unsupported schema " + schema);
            }
        }
        Tag raw = data.get(CAPABILITY);
        if (!(raw instanceof CompoundTag capability)) {
            throw invalid("missing compound '" + CAPABILITY + "'");
        }
        validateCapability(handler, capability, lookup);
        installCapability(handler, capability.copy());
    }

    @Override
    public void sync(ServerPlayer player) {
        markForSynchronization(handler(player));
        // Curios consumes getUpdatingInventories() and sends its standard synchronization packets
        // from the normal entity post-tick event. This avoids depending on Curios internal packet
        // classes and still updates equipment, cosmetics, render flags and modifiers together.
    }

    @Override
    public void validateItemEditSlot(ServerPlayer player, String slotKey, ItemStack replacement) {
        if (replacement.isEmpty()) {
            return;
        }
        CuriosSlot slot = CuriosSlot.parse(slotKey);
        ICurioStacksHandler stacks = handler(player).getStacksHandler(slot.identifier()).orElseThrow(
                () -> invalid("Curios 槽位类型已不存在：" + slot.identifier()));
        var inventory = slot.cosmetic() ? stacks.getCosmeticStacks() : stacks.getStacks();
        if (slot.index() < 0 || slot.index() >= inventory.getSlots()) {
            throw invalid("Curios 槽位编号已不存在：" + slotKey);
        }
        if (replacement.getCount() > inventory.getSlotLimit(slot.index())) {
            throw invalid("Curios 槽位最多允许 " + inventory.getSlotLimit(slot.index()) + " 个物品");
        }
        if (!inventory.isItemValid(slot.index(), replacement)) {
            throw invalid("物品不适用于 Curios " + slot.identifier()
                    + (slot.cosmetic() ? " 外观槽" : " 功能槽") + " #" + (slot.index() + 1));
        }
    }

    private static void installCapability(ICuriosItemHandler handler, CompoundTag capability) {
        handler.readTag(capability);
        handler.reset();
        markForSynchronization(handler);
    }

    private static void markForSynchronization(ICuriosItemHandler handler) {
        Set<ICurioStacksHandler> updates = handler.getUpdatingInventories();
        for (ICurioStacksHandler stacks : handler.getCurios().values()) {
            stacks.update();
            updates.add(stacks);
        }
    }

    private static ICuriosItemHandler handler(ServerPlayer player) {
        return CuriosApi.getCuriosInventory(player).orElseThrow(
                () -> new IllegalStateException("Curios inventory capability is unavailable for player "
                        + player.getUUID()));
    }

    private void validateCapability(
            ICuriosItemHandler handler, CompoundTag capability, HolderLookup.Provider lookup) {
        ListTag curios = requireCompoundList(capability, "Curios");
        Set<String> identifiers = new HashSet<>();
        for (int index = 0; index < curios.size(); index++) {
            CompoundTag entry = curios.getCompound(index);
            if (!entry.contains("Identifier", Tag.TAG_STRING)) {
                throw invalid("Curios capability entry " + index + " has no Identifier");
            }
            String identifier = entry.getString("Identifier");
            if (identifier.isBlank()) {
                throw invalid("Curios capability entry " + index + " has a blank Identifier");
            }
            if (!identifiers.add(identifier)) {
                throw invalid("duplicate Curios identifier " + identifier);
            }
            if (handler.getStacksHandler(identifier).isEmpty()) {
                throw invalid("Curios slot type is unavailable: " + identifier);
            }
            Tag rawHandler = entry.get("StacksHandler");
            if (!(rawHandler instanceof CompoundTag stacksHandler)) {
                throw invalid("Curios slot type " + identifier + " has no StacksHandler");
            }
            validateStackInventory(stacksHandler, "Stacks", identifier, lookup);
            validateStackInventory(stacksHandler, "Cosmetics", identifier, lookup);
        }
    }

    private void validateStackInventory(
            CompoundTag stacksHandler,
            String key,
            String identifier,
            HolderLookup.Provider lookup) {
        if (!stacksHandler.contains(key)) {
            return;
        }
        Tag raw = stacksHandler.get(key);
        if (!(raw instanceof CompoundTag inventory)) {
            throw invalid("Curios " + identifier + ' ' + key + " is not a compound");
        }
        ItemStackHandler decoded = new ItemStackHandler();
        try {
            decoded.deserializeNBT(lookup, inventory);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "invalid " + id() + " payload: cannot decode " + identifier + ' ' + key, failure);
        }
        if (decoded.getSlots() < 0 || decoded.getSlots() > 1024) {
            throw invalid("Curios " + identifier + ' ' + key + " has unreasonable size " + decoded.getSlots());
        }
    }

    private void validateLegacyInventory(
            ICuriosItemHandler handler, ListTag inventory, HolderLookup.Provider lookup) {
        Set<String> identifiers = new HashSet<>();
        for (int index = 0; index < inventory.size(); index++) {
            CompoundTag entry = inventory.getCompound(index);
            if (!entry.contains("Identifier", Tag.TAG_STRING)) {
                throw invalid("legacy entry " + index + " has no Identifier");
            }
            String identifier = entry.getString("Identifier");
            if (!identifiers.add(identifier)) {
                throw invalid("legacy payload has duplicate identifier " + identifier);
            }
            ICurioStacksHandler live = handler.getStacksHandler(identifier).orElseThrow(
                    () -> invalid("Curios slot type is unavailable: " + identifier));
            validateLegacySlots(entry, "Stacks", live.getStacks().getSlots(), identifier, lookup);
            validateLegacySlots(entry, "Cosmetics", live.getCosmeticStacks().getSlots(), identifier, lookup);
        }
    }

    private void validateLegacySlots(
            CompoundTag entry,
            String key,
            int liveSlots,
            String identifier,
            HolderLookup.Provider lookup) {
        if (!entry.contains(key, Tag.TAG_COMPOUND)) {
            throw invalid(identifier + " has no " + key + " inventory");
        }
        ItemStackHandler saved = new ItemStackHandler();
        try {
            saved.deserializeNBT(lookup, entry.getCompound(key));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "invalid " + id() + " payload: cannot decode " + identifier + ' ' + key, failure);
        }
        if (saved.getSlots() > liveSlots) {
            throw invalid("Curios slot count shrank for " + identifier + ' ' + key
                    + ": saved=" + saved.getSlots() + ", live=" + liveSlots);
        }
    }

    private ListTag requireCompoundList(CompoundTag data, String key) {
        Tag raw = data.get(key);
        if (!(raw instanceof ListTag list)
                || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
            throw invalid("'" + key + "' must be a compound list");
        }
        return list;
    }

    private IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("invalid " + id() + " payload: " + detail);
    }

    private record CuriosSlot(String identifier, boolean cosmetic, int index) {
        private static CuriosSlot parse(String key) {
            if (key == null || !key.startsWith("curios/")) {
                throw new IllegalArgumentException("Curios 编辑槽标识无效");
            }
            int inventorySeparator = key.lastIndexOf('/', key.lastIndexOf('/') - 1);
            int slotSeparator = key.lastIndexOf('/');
            if (inventorySeparator <= "curios/".length() || slotSeparator <= inventorySeparator) {
                throw new IllegalArgumentException("Curios 编辑槽标识无效");
            }
            String identifier = key.substring("curios/".length(), inventorySeparator);
            String inventory = key.substring(inventorySeparator + 1, slotSeparator);
            boolean cosmetic = switch (inventory) {
                case "Stacks" -> false;
                case "Cosmetics" -> true;
                default -> throw new IllegalArgumentException("Curios 编辑槽类型无效");
            };
            try {
                return new CuriosSlot(identifier, cosmetic, Integer.parseInt(key.substring(slotSeparator + 1)));
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Curios 编辑槽编号无效", failure);
            }
        }
    }
}
