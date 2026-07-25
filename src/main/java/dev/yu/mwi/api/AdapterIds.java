package dev.yu.mwi.api;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Canonical resource identifiers for built-in share adapters. */
public final class AdapterIds {
    public static final String NAMESPACE = "yu_multiverse_inventory";

    public static final ResourceLocation INVENTORY_CONTENTS = id("inventory_contents");
    public static final ResourceLocation ARMOR_CONTENTS = id("armor_contents");
    public static final ResourceLocation OFF_HAND = id("off_hand");
    public static final ResourceLocation ENDER_CHEST = id("ender_chest");
    public static final ResourceLocation XP = id("xp");
    public static final ResourceLocation LVL = id("lvl");
    public static final ResourceLocation HEALTH = id("health");
    public static final ResourceLocation HUNGER = id("hunger");
    public static final ResourceLocation POTION_EFFECTS = id("potion_effects");
    public static final ResourceLocation BED_SPAWN = id("bed_spawn");
    public static final ResourceLocation LAST_LOCATION = id("last_location");
    public static final ResourceLocation RECIPES = id("recipes");
    public static final ResourceLocation ADVANCEMENTS = id("advancements");
    public static final ResourceLocation STATISTICS = id("statistics");
    public static final ResourceLocation CURIOS = id("curios");
    public static final ResourceLocation COSMETIC_ARMOR = id("cosmetic_armor");
    public static final ResourceLocation TRASH_SLOT = id("trash_slot");

    private AdapterIds() {
    }

    public static ResourceLocation id(String shareId) {
        Objects.requireNonNull(shareId, "shareId");
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, shareId);
    }
}
