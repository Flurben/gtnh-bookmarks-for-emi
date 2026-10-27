package com.emicraftingtree.bookmark;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.ChainStack;
import com.emicraftingtree.chain.Stacks;

import dev.emi.emi.EmiUtil;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.screen.EmiScreenManager;

/**
 * NEI's FavoriteRecipes on top of EMI. NEI's manual favorites (the star in its recipe view) are EMI's default recipes
 * (the "set default recipe" button in EMI's recipe view); NEI's generated favorites are the items with only one
 * recipe.
 */
public final class FavoriteRecipes {

    private FavoriteRecipes() {}

    /** The favorite recipe of a stack: its default recipe, else its only recipe. */
    public static @Nullable EmiRecipe getFavorite(EmiStack stack) {
        if (stack.isEmpty()) {
            return null;
        }

        final EmiRecipe manual = BoM.getRecipe(stack);

        if (RecipeBookmarks.canBookmark(manual)) {
            return manual;
        }

        final List<EmiRecipe> recipes = recipesFor(stack);
        return recipes.size() == 1 ? recipes.get(0) : null;
    }

    public static boolean contains(EmiStack stack) {
        return getFavorite(stack) != null;
    }

    /**
     * The recipe Shift + A bookmarks for a stack in EMI's lists: the favorite, else the recipe EMI itself would pick
     * (craftable first, then crafting table recipes).
     */
    public static @Nullable EmiRecipe getPreferred(EmiStack stack) {
        final EmiRecipe favorite = getFavorite(stack);

        if (favorite != null) {
            return favorite;
        }

        final List<EmiRecipe> recipes = recipesFor(stack);

        if (recipes.isEmpty()) {
            return null;
        }

        if (EmiScreenManager.lastPlayerInventory != null) {
            final EmiRecipe preferred = EmiUtil.getPreferredRecipe(recipes, EmiScreenManager.lastPlayerInventory,
                    false);

            if (preferred != null) {
                return preferred;
            }
        }

        return recipes.get(0);
    }

    /** NEI selects the first accepted alternative of each ingredient that has a favorite recipe. */
    public static List<ChainItem> preferFavoriteIngredients(List<ChainItem> items) {
        final List<ChainItem> result = new ArrayList<>(items.size());

        for (ChainItem item : items) {
            result.add(item.type == ChainItem.Type.INGREDIENT && item.permutations.size() > 1
                    ? withFavoritePermutation(item)
                    : item);
        }

        return result;
    }

    private static ChainItem withFavoritePermutation(ChainItem item) {
        for (ChainStack perm : item.permutations.values()) {
            if (perm.emi != null && contains(perm.emi)) {
                return perm.sameType(item.stack) ? item : item.copyWithPerm(perm);
            }
        }

        return item;
    }

    private static List<EmiRecipe> recipesFor(EmiStack stack) {
        final String key = Stacks.keyOf(stack);
        final List<EmiRecipe> recipes = new ArrayList<>();

        for (EmiRecipe recipe : EmiApi.getRecipeManager().getRecipesByOutput(stack)) {
            if (RecipeBookmarks.canBookmark(recipe) && recipe.supportsRecipeTree()
                    && recipe.getOutputs().stream().anyMatch(output -> Stacks.keyOf(output).equals(key))) {
                recipes.add(recipe);
            }
        }

        return recipes;
    }

}
