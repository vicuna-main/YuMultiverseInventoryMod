package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.item.crafting.RecipeManager;

/** Captures known/highlighted recipes and all recipe-book UI settings. */
public final class RecipeBookAdapter extends AbstractVanillaAdapter {
    private static final String RECIPES = "recipes";
    private static final String HIGHLIGHTED = "toBeDisplayed";

    public RecipeBookAdapter() {
        super(AdapterIds.RECIPES);
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        return canonicalSnapshot(player.getRecipeBook().toNbt());
    }

    @Override
    public boolean equivalent(CompoundTag first, CompoundTag second) {
        return first != null && second != null && sameSnapshot(first, second);
    }

    @Override
    public void clear(ServerPlayer player) {
        replace(player, new CompoundTag());
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        NbtPayload.requireData(data, id().toString());
        RecipeManager manager = player.server.getRecipeManager();
        Set<ResourceLocation> known = validateRecipeList(data, RECIPES, manager);
        Set<ResourceLocation> highlighted = validateRecipeList(data, HIGHLIGHTED, manager);
        if (!known.containsAll(highlighted)) {
            throw NbtPayload.invalid(id().toString(), "highlighted recipes must also be known recipes");
        }
        replace(player, data);
    }

    @Override
    public void sync(ServerPlayer player) {
        // INIT replaces the client's complete recipe-book snapshot. It does not use the ADD/REMOVE
        // unlock paths that can replay unlock notifications and recipe-acquired side effects.
        player.getRecipeBook().sendInitialRecipeBook(player);
    }

    @Override
    public boolean requiresAdvancementListenerSuspension() {
        return false;
    }

    /**
     * ServerRecipeBook#fromNbt loads entries into the receiver and is not a clearing operation.
     * Decode into a fresh book, then use vanilla's snapshot-copy method to replace known recipes,
     * highlights, and UI settings together without add/remove gameplay packets.
     */
    private void replace(ServerPlayer player, CompoundTag data) {
        RecipeManager manager = player.server.getRecipeManager();
        ServerRecipeBook replacement = new ServerRecipeBook();
        replacement.fromNbt(data, manager);
        CompoundTag expected = replacement.toNbt();

        player.getRecipeBook().copyOverData(replacement);
        CompoundTag actual = player.getRecipeBook().toNbt();
        if (!sameSnapshot(actual, expected)) {
            throw new IllegalStateException("recipe book snapshot replacement failed for player "
                    + player.getUUID() + ": expected=" + describeSnapshot(expected)
                    + ", actual=" + describeSnapshot(actual));
        }
    }

    /**
     * Recipe ids are held in sets internally. Their NBT lists are therefore not ordered state:
     * a decode/copy/encode round trip may emit the same ids in a different order. Compare those
     * two lists as sets, while keeping every recipe-book UI setting byte-for-byte strict.
     */
    static boolean sameSnapshot(CompoundTag first, CompoundTag second) {
        if (!recipeIds(first, RECIPES).equals(recipeIds(second, RECIPES))) {
            return false;
        }
        if (!recipeIds(first, HIGHLIGHTED).equals(recipeIds(second, HIGHLIGHTED))) {
            return false;
        }

        return settings(first).equals(settings(second));
    }

    private static Set<String> recipeIds(CompoundTag snapshot, String key) {
        ListTag list = snapshot.getList(key, Tag.TAG_STRING);
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < list.size(); index++) {
            ids.add(list.getString(index));
        }
        return ids;
    }

    private static CompoundTag canonicalSnapshot(CompoundTag snapshot) {
        CompoundTag canonical = snapshot.copy();
        canonical.put(RECIPES, sortedRecipeList(snapshot, RECIPES));
        canonical.put(HIGHLIGHTED, sortedRecipeList(snapshot, HIGHLIGHTED));
        return canonical;
    }

    private static ListTag sortedRecipeList(CompoundTag snapshot, String key) {
        ListTag sorted = new ListTag();
        recipeIds(snapshot, key).stream().sorted().map(StringTag::valueOf).forEach(sorted::add);
        return sorted;
    }

    private static CompoundTag settings(CompoundTag snapshot) {
        CompoundTag settings = snapshot.copy();
        settings.remove(RECIPES);
        settings.remove(HIGHLIGHTED);
        return settings;
    }

    private static String describeSnapshot(CompoundTag snapshot) {
        return "recipes=" + recipeIds(snapshot, RECIPES).size()
                + ", highlighted=" + recipeIds(snapshot, HIGHLIGHTED).size()
                + ", settings=" + settings(snapshot);
    }

    private Set<ResourceLocation> validateRecipeList(CompoundTag data, String key, RecipeManager manager) {
        ListTag list = NbtPayload.requireStringList(data, key, id().toString());
        Set<ResourceLocation> recipes = new HashSet<>();
        for (int index = 0; index < list.size(); index++) {
            String encoded = list.getString(index);
            ResourceLocation recipe;
            try {
                recipe = ResourceLocation.parse(encoded);
            } catch (RuntimeException exception) {
                throw NbtPayload.invalid(id().toString(), "invalid recipe id '" + encoded + "'");
            }
            if (!recipes.add(recipe)) {
                throw NbtPayload.invalid(id().toString(), "duplicate recipe " + recipe + " in '" + key + "'");
            }
            if (manager.byKey(recipe).isEmpty()) {
                throw NbtPayload.invalid(id().toString(), "unknown recipe " + recipe);
            }
        }
        return recipes;
    }
}
