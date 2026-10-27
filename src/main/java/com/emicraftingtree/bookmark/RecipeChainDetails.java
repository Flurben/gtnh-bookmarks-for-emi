package com.emicraftingtree.bookmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.ContainerResolver;
import com.emicraftingtree.chain.RecipeChainMath;
import com.emicraftingtree.chain.RecipeKey;

/**
 * The calculated state of a crafting chain group (port of NEI's RecipeChainDetails): which items are shown, with what
 * amounts, and how collapsed recipes fold their sub-chains into one row.
 */
public class RecipeChainDetails {

    public enum CalculatedType {

        INGREDIENT,
        RESULT,
        REMAINDER;

        public int toInt() {
            switch (this) {
                case RESULT:
                    return 0;
                case REMAINDER:
                    return 1;
                case INGREDIENT:
                    return 2;
            }
            return -1;
        }
    }

    public static class BookmarkChainItem {

        private final ChainItem item;

        private long realMultiplier = 0L;
        private long shiftAmount = 0L;
        private long calculatedMultiplier = 0L;

        public CalculatedType calculatedType;

        protected BookmarkChainItem(ChainItem item, long shiftItem, long calculatedMultiplier,
                CalculatedType calculatedType) {
            this.item = item.copy();
            this.shiftAmount = shiftItem;
            this.calculatedMultiplier = calculatedMultiplier;
            this.calculatedType = calculatedType;
        }

        public static BookmarkChainItem of(ChainItem item, long shiftItem, long calculatedMultiplier,
                CalculatedType calculatedType) {
            return new BookmarkChainItem(item, shiftItem, calculatedMultiplier, calculatedType);
        }

        public static BookmarkChainItem of(ChainItem item, long shiftItem, CalculatedType calculatedType) {
            return new BookmarkChainItem(item, shiftItem, item.getMultiplier(), calculatedType);
        }

        public static BookmarkChainItem of(ChainItem item) {
            final long amount = item.getAmount();
            return new BookmarkChainItem(
                    item,
                    amount,
                    item.getMultiplier(),
                    item.type == ChainItem.Type.INGREDIENT ? CalculatedType.INGREDIENT : CalculatedType.RESULT);
        }

        public void setRealMultiplier(long multiplier) {
            this.realMultiplier = multiplier;
        }

        public long getRealMultiplier() {
            return this.realMultiplier;
        }

        public long getCalculatedMultiplier() {
            return this.calculatedMultiplier;
        }

        public long getShiftAmount() {
            return this.shiftAmount;
        }

        public ChainItem getItem() {
            return this.item;
        }

        public void append(long amount, long calculatedAmount) {
            this.shiftAmount += amount;
            this.calculatedMultiplier += this.item.getMultiplierFromAmount(calculatedAmount);
            this.item.multiplier = this.calculatedMultiplier;
        }
    }

    public final Map<Integer, BookmarkChainItem> calculatedItems = new HashMap<>();
    public final Map<Integer, RecipeKey> itemToRecipe = new HashMap<>();
    public final Set<RecipeKey> outputRecipes = new HashSet<>();
    public final Set<RecipeKey> recipeInMiddle = new HashSet<>();

    public final Map<RecipeKey, Set<RecipeKey>> recipeRelations = new HashMap<>();

    public void refresh(Map<Integer, ChainItem> chainItems, Set<RecipeKey> collapsedRecipes,
            ContainerResolver containers) {
        final Map<ChainItem, Integer> sortingChainItems = new HashMap<>();

        for (Map.Entry<Integer, ChainItem> entry : chainItems.entrySet()) {
            sortingChainItems.put(entry.getValue(), entry.getKey());
        }

        this.itemToRecipe.clear();
        this.outputRecipes.clear();
        this.recipeInMiddle.clear();
        this.calculatedItems.clear();
        this.recipeRelations.clear();
        final RecipeChainMath math = new RecipeChainMath(new ArrayList<>(chainItems.values()), collapsedRecipes,
                containers).refresh();

        this.outputRecipes.addAll(math.outputRecipes.keySet());

        for (ChainItem item : math.preferredItems.values()) {
            if (item != null && item.recipe != null) {
                this.recipeInMiddle.add(item.recipe);
            }
        }

        for (ChainItem item : math.initialItems) {
            this.calculatedItems.put(
                    sortingChainItems.get(item),
                    BookmarkChainItem.of(item, math.requiredAmount.getOrDefault(item, 0L), CalculatedType.INGREDIENT));
        }

        if (collapsedRecipes.isEmpty()) {

            for (ChainItem item : math.recipeResults) {
                generateResult(math, item, sortingChainItems.get(item));
            }

            for (ChainItem item : math.recipeIngredients) {
                generateIngredient(math, item, sortingChainItems.get(item));
            }

        } else {
            final Set<RecipeKey> topLevelRecipes = findTopLevelRecipes(math, collapsedRecipes);

            for (RecipeKey recipeId : collapsedRecipes) {
                this.recipeRelations.put(
                        recipeId,
                        getRecipeRelations(math, recipeId, topLevelRecipes, new HashSet<>(Arrays.asList(recipeId))));
                generateCollapsedRecipe(math, recipeId, topLevelRecipes, sortingChainItems);
                generateShadowItems(recipeId, chainItems.values(), sortingChainItems, containers);
            }

            for (ChainItem item : math.recipeResults) {
                if (!collapsedRecipes.contains(item.recipe) && topLevelRecipes.contains(item.recipe)) {
                    generateResult(math, item, sortingChainItems.get(item));
                }
            }

            for (ChainItem item : math.recipeIngredients) {
                if (!collapsedRecipes.contains(item.recipe) && topLevelRecipes.contains(item.recipe)) {
                    generateIngredient(math, item, sortingChainItems.get(item));
                }
            }

        }

        for (Map.Entry<Integer, ChainItem> entry : chainItems.entrySet()) {
            final int itemIndex = entry.getKey();
            if (itemIndex >= 0 && this.calculatedItems.containsKey(itemIndex)) {
                final ChainItem item = entry.getValue();
                if (this.recipeInMiddle.contains(item.recipe)) {
                    this.calculatedItems.get(itemIndex).setRealMultiplier(Math.max(0, item.getMultiplier() - 1));
                } else {
                    this.calculatedItems.get(itemIndex).setRealMultiplier(item.getMultiplier());
                }
            }
        }

    }

    private Set<RecipeKey> getRecipeRelations(RecipeChainMath math, RecipeKey recipeId, Set<RecipeKey> topLevelRecipes,
            Set<RecipeKey> recipes) {

        for (ChainItem item : math.recipeIngredients) {
            if (item.recipe.equals(recipeId)) {
                final ChainItem prefItem = math.preferredItems.get(item);
                if (prefItem != null && !topLevelRecipes.contains(prefItem.recipe) && recipes.add(prefItem.recipe)) {
                    getRecipeRelations(math, prefItem.recipe, topLevelRecipes, recipes);
                }
            }
        }

        return recipes;
    }

    private void generateCollapsedRecipe(RecipeChainMath math, RecipeKey recipeId, Set<RecipeKey> topLevelRecipes,
            Map<ChainItem, Integer> sortingChainItems) {

        final List<BookmarkChainItem> items = collectItems(
                math,
                recipeId,
                this.recipeRelations.get(recipeId),
                topLevelRecipes);

        for (BookmarkChainItem value : items) {
            int itemIndex = sortingChainItems.get(value.getItem());

            if (value.getItem().type == ChainItem.Type.INGREDIENT || !recipeId.equals(value.getItem().recipe)) {
                itemIndex *= -1;
            }

            if (value.getItem().type == ChainItem.Type.RESULT
                    && !math.outputRecipes.containsKey(value.getItem().recipe)) {
                value.calculatedType = CalculatedType.REMAINDER;
            }

            this.itemToRecipe.put(itemIndex, recipeId);
            this.calculatedItems.put(itemIndex, value);
        }
    }

    private void generateShadowItems(RecipeKey recipeId, Iterable<ChainItem> chainItems,
            Map<ChainItem, Integer> sortingChainItems, ContainerResolver containers) {
        final Set<RecipeKey> recipeRelations = this.recipeRelations.get(recipeId);
        final List<ChainItem> subChainItems = new ArrayList<>();

        for (ChainItem item : chainItems) {
            if (recipeRelations.contains(item.recipe)) {
                subChainItems.add(item.copyWithMultiplier(recipeId.equals(item.recipe) ? item.multiplier : 0));
            }
        }

        final RecipeChainMath math = new RecipeChainMath(subChainItems, Collections.emptySet(), containers)
                .refresh();
        final List<BookmarkChainItem> items = collectItems(
                math,
                recipeId,
                this.recipeRelations.get(recipeId),
                Collections.emptySet());

        for (BookmarkChainItem value : items) {
            int itemIndex = sortingChainItems.get(value.getItem());

            if (value.getItem().type == ChainItem.Type.INGREDIENT || !recipeId.equals(value.getItem().recipe)) {
                itemIndex *= -1;
            }

            if (this.calculatedItems.containsKey(itemIndex)) {
                this.calculatedItems.get(itemIndex)
                        .setRealMultiplier(value.getItem().getMultiplierFromAmount(value.getShiftAmount()));
            }
        }

    }

    private List<BookmarkChainItem> collectItems(RecipeChainMath math, RecipeKey recipeId,
            Set<RecipeKey> recipeRelations, Set<RecipeKey> topLevelRecipes) {
        final Map<String, BookmarkChainItem> items = new HashMap<>();
        final Map<RecipeKey, Long> multiplier = new HashMap<>();

        for (RecipeKey relRecipeId : recipeRelations) {
            for (ChainItem item : math.recipeResults) {
                if (item.recipe.equals(relRecipeId)) {
                    final long amount = item.getAmount() - math.requiredAmount.getOrDefault(item, 0L);

                    if (item.recipe.equals(recipeId)) {
                        multiplier.put(item.recipe, item.getMultiplier());
                        items.computeIfAbsent(
                                item.stack.key,
                                is -> BookmarkChainItem.of(item, 0, 0, CalculatedType.RESULT))
                                .append(amount, item.getAmount());
                    } else if (amount > 0) {
                        items.computeIfAbsent(
                                item.stack.key,
                                is -> BookmarkChainItem.of(item, 0, 0, CalculatedType.RESULT))
                                .append(amount, item.getAmount());
                    }
                }
            }

            for (ChainItem item : math.recipeIngredients) {
                if (item.recipe.equals(relRecipeId)) {
                    final ChainItem prefItem = math.preferredItems.get(item);
                    final long amount = math.requiredAmount.containsKey(prefItem) ? 0
                            : math.requiredAmount.getOrDefault(item, item.getAmount());
                    final long refAmount = prefItem != null && !topLevelRecipes.contains(prefItem.recipe)
                            ? math.requiredAmount.getOrDefault(prefItem, 0L)
                            : 0;

                    if (amount != 0 || item.getAmount() > refAmount && math.requiredAmount.containsKey(prefItem)
                            || recipeId.equals(item.recipe) && multiplier.getOrDefault(recipeId, 0L) == 0) {
                        items.computeIfAbsent(
                                item.stack.key,
                                is -> BookmarkChainItem.of(item, 0, 0, CalculatedType.INGREDIENT))
                                .append(amount, item.getAmount() - refAmount);
                    }
                }
            }
        }

        return new ArrayList<>(items.values());
    }

    private void generateResult(RecipeChainMath math, ChainItem item, int itemIndex) {
        final long amount = item.getAmount() - math.requiredAmount.getOrDefault(item, 0L);
        final CalculatedType calculatedType = math.outputRecipes.containsKey(item.recipe) ? CalculatedType.RESULT
                : CalculatedType.REMAINDER;

        this.calculatedItems.put(itemIndex, BookmarkChainItem.of(item, amount, calculatedType));
    }

    private void generateIngredient(RecipeChainMath math, ChainItem item, int itemIndex) {
        final ChainItem prefItem = math.preferredItems.get(item);
        final long amount = math.requiredAmount.containsKey(prefItem) ? 0
                : math.requiredAmount.getOrDefault(item, item.getAmount());

        this.calculatedItems.put(itemIndex, BookmarkChainItem.of(item, amount, CalculatedType.INGREDIENT));
    }

    private Set<RecipeKey> findTopLevelRecipes(RecipeChainMath math, Set<RecipeKey> collapsedRecipes) {
        final Set<RecipeKey> result = new HashSet<>(math.outputRecipes.keySet());

        for (ChainItem item : math.recipeResults) {
            if (!result.contains(item.recipe)) {
                final Set<RecipeKey> parents = getRecipeParents(
                        math,
                        item.recipe,
                        new HashSet<>(),
                        new HashSet<>(Arrays.asList(item.recipe)));

                if (parents.size() != 1 || parents.stream().noneMatch(collapsedRecipes::contains)) {
                    result.add(item.recipe);
                }
            }
        }

        return result;
    }

    private Set<RecipeKey> getRecipeParents(RecipeChainMath math, RecipeKey recipeId, Set<RecipeKey> parents,
            Set<RecipeKey> visited) {
        final Set<RecipeKey> recipeIds = math.preferredItems.entrySet().stream()
                .filter(entry -> entry.getValue() != null && recipeId.equals(entry.getValue().recipe))
                .map(entry -> entry.getKey().recipe).collect(Collectors.toSet());

        if (recipeIds.isEmpty()) {
            parents.add(recipeId);
        } else {

            for (RecipeKey ingrRecipeId : recipeIds) {
                if (visited.add(ingrRecipeId)) {
                    if (math.outputRecipes.containsKey(ingrRecipeId)) {
                        parents.add(ingrRecipeId);
                    } else {
                        getRecipeParents(math, ingrRecipeId, parents, visited);
                    }
                }
            }

        }

        return parents;
    }

}
