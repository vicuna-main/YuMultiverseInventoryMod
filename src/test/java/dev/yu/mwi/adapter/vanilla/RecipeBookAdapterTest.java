package dev.yu.mwi.adapter.vanilla;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeBookAdapterTest {
    @Test
    void recipeAndHighlightListOrderIsNotState() {
        CompoundTag first = snapshot(
                new String[] {"minecraft:iron_pickaxe", "minecraft:stick"},
                new String[] {"minecraft:iron_pickaxe"},
                true);
        CompoundTag second = snapshot(
                new String[] {"minecraft:stick", "minecraft:iron_pickaxe"},
                new String[] {"minecraft:iron_pickaxe"},
                true);

        assertTrue(RecipeBookAdapter.sameSnapshot(first, second));
    }

    @Test
    void differentRecipeMembershipStillFailsVerification() {
        CompoundTag first = snapshot(
                new String[] {"minecraft:iron_pickaxe"},
                new String[0],
                false);
        CompoundTag second = snapshot(
                new String[] {"minecraft:stone_pickaxe"},
                new String[0],
                false);

        assertFalse(RecipeBookAdapter.sameSnapshot(first, second));
    }

    @Test
    void recipeBookUiSettingsRemainStrictState() {
        CompoundTag first = snapshot(
                new String[] {"minecraft:iron_pickaxe"},
                new String[0],
                false);
        CompoundTag second = snapshot(
                new String[] {"minecraft:iron_pickaxe"},
                new String[0],
                true);

        assertFalse(RecipeBookAdapter.sameSnapshot(first, second));
    }

    private static CompoundTag snapshot(String[] recipes, String[] highlighted, boolean open) {
        CompoundTag snapshot = new CompoundTag();
        snapshot.put("recipes", stringList(recipes));
        snapshot.put("toBeDisplayed", stringList(highlighted));
        snapshot.putBoolean("isGuiOpen", open);
        snapshot.putBoolean("isFilteringCraftable", false);
        return snapshot;
    }

    private static ListTag stringList(String[] values) {
        ListTag list = new ListTag();
        for (String value : values) {
            list.add(StringTag.valueOf(value));
        }
        return list;
    }
}
