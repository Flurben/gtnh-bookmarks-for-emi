package com.emicraftingtree.bookmark;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.ChainStack;
import com.emicraftingtree.chain.RecipeKey;
import com.emicraftingtree.chain.Stacks;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;

/**
 * Turns EMI recipes into bookmark items the way NEI bookmarks a recipe: one result item per distinct output and one
 * ingredient item per distinct input, with the amounts of one craft. Catalysts are not consumed and are left out.
 */
public final class RecipeBookmarks {

    private static final Map<String, EmiRecipeCategory> CATEGORIES = new HashMap<>();
    private static Object categoriesSource;

    private RecipeBookmarks() {}

    private static final class Merged {

        final ChainStack stack;
        final Map<String, ChainStack> permutations;
        long amountTimesChance;

        Merged(ChainStack stack, Map<String, ChainStack> permutations) {
            this.stack = stack;
            this.permutations = permutations;
        }
    }

    /**
     * All items of a recipe bookmark.
     *
     * @param onlyResult when set, only outputs of this type become results (NEI bookmarks only the hovered output)
     */
    public static List<ChainItem> build(EmiRecipe recipe, int groupId, long multiplier, @Nullable ChainStack onlyResult) {
        final RecipeKey key = RecipeKey.of(recipe);
        final List<ChainItem> items = new ArrayList<>();
        final Map<String, Merged> results = new LinkedHashMap<>();
        final Map<String, Merged> ingredients = new LinkedHashMap<>();

        for (EmiStack output : recipe.getOutputs()) {
            if (output.isEmpty() || output.getAmount() <= 0) {
                continue;
            }

            final ChainStack stack = Stacks.of(output);

            if (onlyResult != null && !onlyResult.sameType(stack)) {
                continue;
            }

            final Merged merged = results.computeIfAbsent(stack.key,
                    k -> new Merged(stack, ChainItem.singlePermutation(stack)));
            merged.amountTimesChance += output.getAmount() * Stacks.chance(output.getChance());
        }

        for (EmiIngredient input : recipe.getInputs()) {
            if (input == null || input.isEmpty()) {
                continue;
            }

            final Map<String, ChainStack> permutations = new LinkedHashMap<>();

            for (EmiStack perm : input.getEmiStacks()) {
                if (!perm.isEmpty()) {
                    final long amount = perm.getAmount() > 0 ? perm.getAmount() : Math.max(1, input.getAmount());
                    final ChainStack stack = Stacks.of(perm, amount);
                    permutations.putIfAbsent(stack.key, stack);
                }
            }

            if (permutations.isEmpty()) {
                continue;
            }

            final ChainStack stack = permutations.values().iterator().next();
            final Merged merged = ingredients.computeIfAbsent(stack.key, k -> new Merged(stack, permutations));

            merged.amountTimesChance += stack.amount * Stacks.chance(input.getChance());
        }

        for (Merged merged : results.values()) {
            items.add(ChainItem.ofRecipe(groupId, merged.stack, merged.permutations, key, ChainItem.Type.RESULT,
                    multiplier, merged.amountTimesChance));
        }

        for (Merged merged : ingredients.values()) {
            items.add(ChainItem.ofRecipe(groupId, merged.stack, merged.permutations, key, ChainItem.Type.INGREDIENT,
                    multiplier, merged.amountTimesChance));
        }

        return items;
    }

    /** The result item a plain stack would have in a recipe (NEI's builder with recipe and RESULT type). */
    public static @Nullable ChainItem resultItem(EmiRecipe recipe, int groupId, ChainStack stack, long multiplier) {
        for (ChainItem item : build(recipe, groupId, multiplier, stack)) {
            if (item.type == ChainItem.Type.RESULT) {
                return item;
            }
        }
        return null;
    }

    /** Whether an EMI recipe can be bookmarked (it needs a stable id to be saved). */
    public static boolean canBookmark(@Nullable EmiRecipe recipe) {
        return recipe != null && recipe.getId() != null;
    }

    public static @Nullable EmiRecipeCategory categoryOf(@Nullable RecipeKey key) {

        if (key == null) {
            return null;
        }

        final EmiRecipe recipe = key.getRecipe();

        if (recipe != null) {
            return recipe.getCategory();
        }

        if (key.handlerName.isEmpty()) {
            return null;
        }

        final Object source = EmiApi.getRecipeManager();

        if (source != categoriesSource) {
            categoriesSource = source;
            CATEGORIES.clear();

            for (EmiRecipeCategory category : EmiApi.getRecipeManager().getCategories()) {
                CATEGORIES.put(String.valueOf(category.getId()), category);
            }
        }

        return CATEGORIES.get(key.handlerName);
    }

}
