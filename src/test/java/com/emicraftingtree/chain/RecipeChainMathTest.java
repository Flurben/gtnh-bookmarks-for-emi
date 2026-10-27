package com.emicraftingtree.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

public class RecipeChainMathTest {

    public static final RecipeKey TORCH = RecipeKey.of("torch", "crafting");
    public static final RecipeKey STICK = RecipeKey.of("stick", "crafting");
    public static final RecipeKey PLANK = RecipeKey.of("plank", "crafting");

    /** Builds the result and ingredient entries NEI creates when a recipe is bookmarked. */
    public static List<ChainItem> recipe(int groupId, RecipeKey key, long multiplier, Object... spec) {
        final List<ChainItem> items = new ArrayList<>();
        boolean results = true;

        for (int i = 0; i < spec.length; i += 2) {

            if ("->".equals(spec[i])) {
                results = false;
                i--;
                continue;
            }

            final String stackKey = (String) spec[i];
            final long amount = ((Number) spec[i + 1]).longValue();
            final ChainStack stack = ChainStack.of(stackKey, amount);

            items.add(ChainItem.ofRecipe(groupId, stack, ChainItem.singlePermutation(stack), key,
                    results ? ChainItem.Type.RESULT : ChainItem.Type.INGREDIENT, multiplier,
                    amount * ChainItem.CHANCE_FULL));
        }

        return items;
    }

    public static List<ChainItem> torchChain(int groupId, long torchMultiplier) {
        final List<ChainItem> items = new ArrayList<>();
        items.addAll(recipe(groupId, TORCH, torchMultiplier, "torch", 4, "->", "coal", 1, "stick", 1));
        items.addAll(recipe(groupId, STICK, 1, "stick", 4, "->", "plank", 2));
        items.addAll(recipe(groupId, PLANK, 1, "plank", 4, "->", "log", 1));
        return items;
    }

    private static long leftover(RecipeChainMath math, String key) {
        long total = 0;
        for (ChainItem item : math.recipeResults) {
            if (item.stack.key.equals(key)) {
                total += item.getAmount() - math.requiredAmount.getOrDefault(item, 0L);
            }
        }
        return total;
    }

    private static long missing(RecipeChainMath math, String key) {
        long total = 0;
        for (ChainItem item : math.recipeIngredients) {
            if (item.stack.key.equals(key)) {
                total += math.requiredAmount.containsKey(math.preferredItems.get(item)) ? 0
                        : math.requiredAmount.getOrDefault(item, item.getAmount());
            }
        }
        return total;
    }

    private static long crafts(RecipeChainMath math, RecipeKey recipe) {
        long crafts = 0;
        for (ChainItem item : math.getResults(recipe)) {
            crafts = Math.max(crafts, item.multiplier);
        }
        return crafts;
    }

    @Test
    void chainResolvesIntermediateRecipes() {
        final RecipeChainMath math = new RecipeChainMath(torchChain(0, 1), Collections.emptySet(),
                ContainerResolver.NONE).refresh();

        assertEquals(Set.of(TORCH), math.outputRecipes.keySet());
        assertEquals(1, missing(math, "coal"));
        assertEquals(1, missing(math, "log"));
        assertEquals(0, missing(math, "stick"));
        assertEquals(4, leftover(math, "torch"));
        assertEquals(3, leftover(math, "stick"));
        assertEquals(2, leftover(math, "plank"));
    }

    @Test
    void multiplierScalesTheWholeChain() {
        final RecipeChainMath math = new RecipeChainMath(torchChain(0, 5), Collections.emptySet(),
                ContainerResolver.NONE).refresh();

        assertEquals(5L, math.outputRecipes.get(TORCH));
        assertEquals(5, missing(math, "coal"));
        // 5 sticks -> 2 stick crafts -> 4 planks -> 1 plank craft -> 1 log
        assertEquals(1, missing(math, "log"));
        assertEquals(20, leftover(math, "torch"));
        assertEquals(3, leftover(math, "stick"));
        assertEquals(0, leftover(math, "plank"));
    }

    @Test
    void inventoryItemsCoverRequirements() {
        final RecipeChainMath math = new RecipeChainMath(torchChain(0, 1), Collections.emptySet(),
                ContainerResolver.NONE);
        math.initialItems.add(ChainItem.ofItem(ChainStack.of("stick", 1), 1));
        math.refresh();

        assertEquals(0, missing(math, "log"));
        assertEquals(0, crafts(math, STICK));
        assertEquals(1, math.requiredAmount.get(math.initialItems.get(0)));
    }

    @Test
    void middleRecipeWithHigherMultiplierAddsExtraOutput() {
        final List<ChainItem> items = new ArrayList<>();
        items.addAll(recipe(0, TORCH, 1, "torch", 4, "->", "coal", 1, "stick", 1));
        items.addAll(recipe(0, STICK, 3, "stick", 4, "->", "plank", 2));

        final RecipeChainMath math = new RecipeChainMath(items, Collections.emptySet(), ContainerResolver.NONE)
                .refresh();

        // the stick recipe feeds the torch recipe and also runs 2 extra times as its own output
        assertEquals(2L, math.outputRecipes.get(STICK));
        assertEquals(1L, math.outputRecipes.get(TORCH));
        assertEquals(6, missing(math, "plank"));
    }

    @Test
    void recipeLoopsTerminate() {
        final RecipeKey a = RecipeKey.of("a", "x");
        final RecipeKey b = RecipeKey.of("b", "x");
        final List<ChainItem> items = new ArrayList<>();
        items.addAll(recipe(0, a, 1, "y", 2, "->", "x", 1));
        items.addAll(recipe(0, b, 1, "x", 1, "->", "y", 1));

        final RecipeChainMath math = new RecipeChainMath(items, Collections.emptySet(), ContainerResolver.NONE)
                .refresh();

        assertTrue(math.outputRecipes.size() >= 1);
    }

    @Test
    void chancedOutputsRoundConservatively() {
        final RecipeKey sieve = RecipeKey.of("sieve", "sieve");
        final ChainStack flint = ChainStack.of("flint", 1);
        final List<ChainItem> items = new ArrayList<>();
        items.addAll(recipe(0, TORCH, 1, "torch", 1, "->", "flint", 3));
        // 25% chance of one flint per run
        items.add(ChainItem.ofRecipe(flint, Map.of("flint", flint), sieve, ChainItem.Type.RESULT, 1,
                ChainItem.CHANCE_FULL / 4));
        items.addAll(recipe(0, sieve, 1, "->", "gravel", 1));

        final RecipeChainMath math = new RecipeChainMath(items, Collections.emptySet(), ContainerResolver.NONE)
                .refresh();

        // 3 flint at 25% -> 12 runs -> 12 gravel
        assertEquals(12, missing(math, "gravel"));
    }

    @Test
    void collapsedRecipeBecomesAnOutput() {
        final RecipeChainMath math = new RecipeChainMath(torchChain(0, 1), new HashSet<>(Set.of(STICK)),
                ContainerResolver.NONE).refresh();

        assertTrue(math.outputRecipes.containsKey(STICK));
        assertTrue(math.outputRecipes.containsKey(TORCH));
    }

    @Test
    void alternativesMatchProducers() {
        final RecipeKey door = RecipeKey.of("door", "crafting");
        final ChainStack oak = ChainStack.of("oak_planks", 6);
        final ChainStack birch = ChainStack.of("birch_planks", 6);
        final Map<String, ChainStack> perms = new LinkedHashMap<>();
        perms.put(oak.key, oak);
        perms.put(birch.key, birch);

        final List<ChainItem> items = new ArrayList<>();
        items.addAll(recipe(0, door, 1, "door", 3));
        items.add(ChainItem.ofRecipe(oak, perms, door, ChainItem.Type.INGREDIENT, 1, 6 * ChainItem.CHANCE_FULL));
        items.addAll(recipe(0, PLANK, 1, "birch_planks", 4, "->", "birch_log", 1));

        final RecipeChainMath math = new RecipeChainMath(items, Collections.emptySet(), ContainerResolver.NONE)
                .refresh();

        // the oak-or-birch slot is fed by the birch plank recipe: 6 planks -> 2 crafts -> 2 logs
        assertEquals(2, missing(math, "birch_log"));
        assertEquals(0, missing(math, "oak_planks"));
    }

    @Test
    void masterRootLetsTheInventoryCoverOutputs() {
        final RecipeChainMath math = new RecipeChainMath(torchChain(0, 1), Collections.emptySet(),
                ContainerResolver.NONE);
        math.createMasterRoot();
        math.refresh();

        assertEquals(1, crafts(math, TORCH));
        assertEquals(1, crafts(math, STICK));
        assertEquals(1, crafts(math, PLANK));

        math.initialItems.add(ChainItem.ofItem(-1, ChainStack.of("torch", 1), 4));
        math.refresh();

        assertEquals(0, crafts(math, TORCH));
        assertEquals(0, crafts(math, STICK));
    }

}
