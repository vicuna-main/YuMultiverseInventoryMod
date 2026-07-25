package dev.yu.mwi.integration.trashslot;

import dev.yu.mwi.api.AdapterIds;
import dev.yu.mwi.api.SharableAdapter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** Soft-loaded adapter for TrashSlot's recoverable server-side trash item. */
public final class TrashSlotInventoryAdapter implements SharableAdapter {
    private static final int SCHEMA_VERSION = 1;
    private static final String SCHEMA = "schema";
    private static final String ITEM = "item";

    private final Method getTrashItem;
    private final Method setTrashItem;
    private final Constructor<?> syncPayload;

    public TrashSlotInventoryAdapter() {
        try {
            Class<?> helper = Class.forName("net.blay09.mods.trashslot.TrashHelper");
            Class<?> payload = Class.forName("net.blay09.mods.trashslot.network.MessageTrashSlotContent");
            getTrashItem = helper.getMethod("getTrashItem", Player.class);
            setTrashItem = helper.getMethod("setTrashItem", Player.class, ItemStack.class);
            syncPayload = payload.getConstructor(ItemStack.class);
            if (!CustomPacketPayload.class.isAssignableFrom(payload)) {
                throw new IllegalStateException("TrashSlot sync payload is not a CustomPacketPayload");
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "TrashSlot API is incompatible; expected TrashHelper/MessageTrashSlotContent 21.1 API",
                    failure);
        }
    }

    @Override
    public ResourceLocation id() {
        return AdapterIds.TRASH_SLOT;
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        ItemStack stack = get(player).copy();
        CompoundTag result = new CompoundTag();
        result.putInt(SCHEMA, SCHEMA_VERSION);
        result.put(ITEM, stack.saveOptional(lookup));
        return result;
    }

    @Override
    public void clear(ServerPlayer player) {
        set(player, ItemStack.EMPTY);
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        if (data == null) {
            throw invalid("payload is null");
        }
        if (data.contains(SCHEMA, Tag.TAG_ANY_NUMERIC) && data.getInt(SCHEMA) != SCHEMA_VERSION) {
            throw invalid("unsupported schema " + data.getInt(SCHEMA));
        }
        Tag raw = data.get(ITEM);
        if (!(raw instanceof CompoundTag item)) {
            throw invalid("missing compound '" + ITEM + "'");
        }
        ItemStack stack = ItemStack.EMPTY;
        if (!item.isEmpty()) {
            stack = ItemStack.parse(lookup, item)
                    .orElseThrow(() -> invalid("trash item cannot be decoded"));
        }
        set(player, stack.copy());
    }

    @Override
    public void sync(ServerPlayer player) {
        try {
            CustomPacketPayload payload = (CustomPacketPayload) syncPayload.newInstance(get(player).copy());
            PacketDistributor.sendToPlayer(player, payload);
        } catch (InstantiationException | IllegalAccessException failure) {
            throw new IllegalStateException("Cannot create TrashSlot synchronization payload", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("TrashSlot synchronization failed", cause);
        }
    }

    private ItemStack get(ServerPlayer player) {
        Object value = invoke(getTrashItem, null, player);
        if (!(value instanceof ItemStack stack)) {
            throw new IllegalStateException("TrashSlot returned a non-ItemStack trash value");
        }
        return stack;
    }

    private void set(ServerPlayer player, ItemStack stack) {
        invoke(setTrashItem, null, player, stack);
    }

    private static Object invoke(Method method, Object owner, Object... arguments) {
        try {
            return method.invoke(owner, arguments);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot access TrashSlot API", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("TrashSlot API call failed", cause);
        }
    }

    private IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("invalid " + id() + " payload: " + detail);
    }
}
