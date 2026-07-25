package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import dev.yu.mwi.api.ItemEditSlotPolicy;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Captures the four vanilla armor slots in Inventory's native slot order. */
public final class ArmorAdapter extends AbstractItemListAdapter implements ItemEditSlotPolicy {
    public ArmorAdapter() {
        super(AdapterIds.ARMOR_CONTENTS);
    }

    @Override
    protected List<ItemStack> slots(ServerPlayer player) {
        return player.getInventory().armor;
    }

    @Override
    protected void setChanged(ServerPlayer player) {
        player.getInventory().setChanged();
    }

    @Override
    public boolean requiresAdvancementListenerSuspension() {
        // detectEquipmentUpdates() posts equipment-change events and runs enchantment effects.
        return true;
    }

    @Override
    public void validateItemEditSlot(ServerPlayer player, String slotKey, ItemStack replacement) {
        if (replacement.isEmpty()) {
            return;
        }
        int slot = parseSlot(slotKey, "armor_contents/");
        EquipmentSlot expected = switch (slot) {
            case 0 -> EquipmentSlot.FEET;
            case 1 -> EquipmentSlot.LEGS;
            case 2 -> EquipmentSlot.CHEST;
            case 3 -> EquipmentSlot.HEAD;
            default -> throw new IllegalArgumentException("无效的盔甲槽编号：" + (slot + 1));
        };
        if (replacement.getCount() != 1) {
            throw new IllegalArgumentException("盔甲槽每格只能放置 1 件物品");
        }
        if (player.getEquipmentSlotForItem(replacement) != expected) {
            throw new IllegalArgumentException("该物品不能放入" + armorName(expected) + "槽");
        }
    }

    private static int parseSlot(String key, String prefix) {
        if (!key.startsWith(prefix)) {
            throw new IllegalArgumentException("盔甲编辑槽标识无效");
        }
        try {
            return Integer.parseInt(key.substring(prefix.length()));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("盔甲编辑槽标识无效", failure);
        }
    }

    private static String armorName(EquipmentSlot slot) {
        return switch (slot) {
            case FEET -> "靴子";
            case LEGS -> "护腿";
            case CHEST -> "胸甲";
            case HEAD -> "头盔";
            default -> slot.getName();
        };
    }
}
