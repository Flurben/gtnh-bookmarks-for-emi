package com.emicraftingtree.chain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.item.ItemStack;

import com.emicraftingtree.chain.ContainerResolver.ContainerItemResult;

/**
 * Resolves a group of bookmarked recipes into a crafting chain: which recipe produces each ingredient, how many times
 * every recipe has to run and what is left over. Port of NEI's RecipeChainMath.
 */
public class RecipeChainMath {

    public static final RecipeKey ROOT_RECIPE = RecipeKey.of("emicraftingtree:root", "");
    private static final ChainStack ROOT_STACK = ChainStack.of("root", 1);

    public final Map<RecipeKey, Long> outputRecipes = new LinkedHashMap<>();

    public final List<ChainItem> initialItems = new ArrayList<>();
    public final List<ChainItem> recipeIngredients = new ArrayList<>();
    public final List<ChainItem> recipeResults = new ArrayList<>();

    protected final Map<RecipeKey, List<ChainItem>> ingredientsByRecipe = new HashMap<>();
    protected final Map<RecipeKey, List<ChainItem>> resultsByRecipe = new HashMap<>();

    public final Map<ChainItem, ChainItem> preferredItems = new HashMap<>();

    public final Map<ChainItem, Long> requiredAmount = new HashMap<>();

    public final List<ItemStack> containerItemsCrafting = new ArrayList<>();
    public final List<ItemStack> containerItemsInventory = new ArrayList<>();

    private final List<ChainStack> containerItemsBlacklist = new ArrayList<>();

    protected final ContainerResolver containers;

    public RecipeChainMath(List<ChainItem> recipeItems, Set<RecipeKey> collapsedRecipes,
            ContainerResolver containers) {
        final Map<RecipeKey, Long> multipliers = new LinkedHashMap<>();
        this.containers = containers;

        for (ChainItem item : recipeItems) {
            if (item.recipe == null || item.type == ChainItem.Type.ITEM) {
                this.initialItems.add(item.copy());
            } else if (item.type == ChainItem.Type.INGREDIENT) {
                this.recipeIngredients.add(item.copyWithMultiplier(0));
            } else {
                this.recipeResults.add(item.copyWithMultiplier(0));
                multipliers.put(item.recipe, Math.max(multipliers.getOrDefault(item.recipe, 0L), item.getMultiplier()));
            }
        }

        rebuildRecipeIndex();

        for (Map.Entry<RecipeKey, Long> entry : multipliers.entrySet()) {
            if (entry.getValue() > 1 || collapsedRecipes.contains(entry.getKey())) {
                collectPreferredItems(entry.getKey(), this.preferredItems, new HashSet<>());
                removeLoop(entry.getKey(), this.preferredItems, new HashSet<>());
                this.outputRecipes.put(entry.getKey(), entry.getValue());
            }
        }

        while (true) {
            Map<ChainItem, ChainItem> maxReference = Collections.emptyMap();
            RecipeKey maxRecipe = null;
            long maxMultiplier = 0;
            int maxDepth = 0;

            for (Map.Entry<RecipeKey, Long> entry : multipliers.entrySet()) {
                final RecipeKey recipe = entry.getKey();
                if (!this.outputRecipes.containsKey(recipe) && !isProducedInChain(recipe)) {
                    final Map<ChainItem, ChainItem> references = new HashMap<>(this.preferredItems);
                    collectPreferredItems(recipe, references, new HashSet<>());
                    removeLoop(recipe, references, new HashSet<>());
                    final int depth = getMaxDepth(recipe, references);

                    if (maxDepth < depth || maxDepth == depth && entry.getValue() > maxMultiplier) {
                        maxMultiplier = entry.getValue();
                        maxReference = references;
                        maxRecipe = recipe;
                        maxDepth = depth;
                    }
                }
            }

            if (maxReference.isEmpty()) {
                break;
            }

            this.preferredItems.putAll(maxReference);
            this.outputRecipes.put(maxRecipe, multipliers.get(maxRecipe));
        }

        for (Map.Entry<RecipeKey, Long> entry : multipliers.entrySet()) {
            final RecipeKey recipe = entry.getKey();
            final boolean isOutputRecipe = this.outputRecipes.containsKey(recipe);
            final boolean recipeInMiddle = isProducedInChain(recipe);

            if (!isOutputRecipe && !recipeInMiddle) {
                this.outputRecipes.put(recipe, entry.getValue());
            } else if (isOutputRecipe && recipeInMiddle) {
                this.outputRecipes.put(recipe, Math.max(0, entry.getValue() - 1));
            }
        }

    }

    private boolean isProducedInChain(RecipeKey recipe) {
        for (ChainItem resItem : this.preferredItems.values()) {
            if (recipe.equals(resItem.recipe)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Turns the chain's outputs into the ingredients of a single virtual "root" recipe, so items already in the
     * inventory count towards the outputs too (used by auto-crafting).
     */
    public RecipeKey createMasterRoot() {
        final List<ChainItem> rootIngredients = new ArrayList<>();

        for (ChainItem item : this.recipeResults) {
            if (this.outputRecipes.containsKey(item.recipe) && !ROOT_RECIPE.equals(item.recipe)) {
                final long amount = item.getAmount(this.outputRecipes.get(item.recipe));
                final ChainStack stack = item.stack.withAmount(amount);

                rootIngredients.add(new ChainItem(-1, stack, ChainItem.singlePermutation(stack), ROOT_RECIPE,
                        ChainItem.Type.INGREDIENT, 1, amount, ChainItem.CHANCE_FULL));
            }
        }

        this.outputRecipes.clear();
        this.outputRecipes.put(ROOT_RECIPE, 1L);
        this.recipeResults.removeIf(item -> ROOT_RECIPE.equals(item.recipe));
        this.recipeResults.add(new ChainItem(-1, ROOT_STACK, ChainItem.singlePermutation(ROOT_STACK), ROOT_RECIPE,
                ChainItem.Type.RESULT, 0, 1, ChainItem.CHANCE_FULL));
        this.recipeIngredients.addAll(rootIngredients);
        rebuildRecipeIndex();

        return ROOT_RECIPE;
    }

    public void rebuildRecipeIndex() {
        this.ingredientsByRecipe.clear();
        this.resultsByRecipe.clear();

        for (ChainItem item : this.recipeIngredients) {
            this.ingredientsByRecipe.computeIfAbsent(item.recipe, k -> new ArrayList<>()).add(item);
        }

        for (ChainItem item : this.recipeResults) {
            this.resultsByRecipe.computeIfAbsent(item.recipe, k -> new ArrayList<>()).add(item);
        }
    }

    private void collectPreferredItems(RecipeKey recipe, Map<ChainItem, ChainItem> preferredItems,
            Set<RecipeKey> visited) {

        visited.add(recipe);

        for (ChainItem ingrItem : this.ingredientsByRecipe.getOrDefault(recipe, Collections.emptyList())) {
            if (!ingrItem.emptyFactor() && !preferredItems.containsKey(ingrItem)) {
                ChainItem activeItem = null;
                ChainItem prefItem = null;

                for (ChainItem item : this.recipeResults) {
                    if (!item.emptyFactor() && !visited.contains(item.recipe) && item.containsItems(ingrItem)) {

                        if ((activeItem == null || item.getAmount(1) > activeItem.getAmount(1))
                                && ingrItem.stack.sameType(item.stack)) {
                            activeItem = item;
                        } else if (prefItem == null || item.getAmount(1) > prefItem.getAmount(1)) {
                            prefItem = item;
                        }

                    }
                }

                if (activeItem != null) {
                    prefItem = activeItem;
                }

                if (prefItem != null) {
                    preferredItems.put(ingrItem, prefItem);
                    collectPreferredItems(prefItem.recipe, preferredItems, visited);
                }
            }
        }

        visited.remove(recipe);
    }

    private int getMaxDepth(RecipeKey recipe, Map<ChainItem, ChainItem> preferredItems) {
        int maxDepth = 0;

        for (ChainItem ingrItem : preferredItems.keySet()) {
            if (!ingrItem.emptyFactor() && recipe.equals(ingrItem.recipe)) {
                maxDepth = Math.max(maxDepth, getMaxDepth(preferredItems.get(ingrItem).recipe, preferredItems) + 1);
            }
        }

        return maxDepth;
    }

    private void removeLoop(RecipeKey recipe, Map<ChainItem, ChainItem> preferredItems, Set<RecipeKey> visited) {
        visited.add(recipe);

        for (ChainItem ingrItem : this.ingredientsByRecipe.getOrDefault(recipe, Collections.emptyList())) {
            if (!ingrItem.emptyFactor() && preferredItems.containsKey(ingrItem)) {
                final ChainItem prefItem = preferredItems.get(ingrItem);

                if (visited.contains(prefItem.recipe)) {
                    preferredItems.remove(ingrItem);
                } else {
                    removeLoop(prefItem.recipe, preferredItems, visited);
                }

            }
        }

        visited.remove(recipe);
    }

    private List<RecipeKey> getOutputRecipeProcessingOrder() {

        if (this.outputRecipes.size() <= 1) {
            return new ArrayList<>(this.outputRecipes.keySet());
        }

        final Map<RecipeKey, Set<RecipeKey>> dependents = new HashMap<>();
        final Map<RecipeKey, Integer> inDegree = new HashMap<>();

        for (RecipeKey recipe : this.outputRecipes.keySet()) {
            inDegree.put(recipe, 0);
        }

        for (RecipeKey recipe : this.outputRecipes.keySet()) {
            final Set<RecipeKey> dependencies = new HashSet<>();
            collectBoxDependencies(recipe, recipe, dependencies, new HashSet<>());
            dependents.put(recipe, dependencies);

            for (RecipeKey dependency : dependencies) {
                inDegree.merge(dependency, 1, Integer::sum);
            }
        }

        final List<RecipeKey> order = new ArrayList<>();
        final ArrayDeque<RecipeKey> queue = new ArrayDeque<>();

        for (RecipeKey recipe : this.outputRecipes.keySet()) {
            if (inDegree.get(recipe) == 0) {
                queue.add(recipe);
            }
        }

        while (!queue.isEmpty()) {
            final RecipeKey recipe = queue.poll();
            order.add(recipe);

            for (RecipeKey dependency : dependents.getOrDefault(recipe, Collections.emptySet())) {
                if (inDegree.merge(dependency, -1, Integer::sum) == 0) {
                    queue.add(dependency);
                }
            }
        }

        if (order.size() != this.outputRecipes.size()) {
            for (RecipeKey recipe : this.outputRecipes.keySet()) {
                if (!order.contains(recipe)) {
                    order.add(recipe);
                }
            }
        }

        return order;
    }

    private void collectBoxDependencies(RecipeKey boxRecipe, RecipeKey currentRecipe, Set<RecipeKey> dependencies,
            Set<RecipeKey> visited) {

        if (!visited.add(currentRecipe)) {
            return;
        }

        for (ChainItem ingrItem : this.ingredientsByRecipe.getOrDefault(currentRecipe, Collections.emptyList())) {
            if (ingrItem.emptyFactor()) {
                continue;
            }

            final ChainItem prefItem = this.preferredItems.get(ingrItem);
            if (prefItem == null) {
                continue;
            }

            if (this.outputRecipes.containsKey(prefItem.recipe) && !prefItem.recipe.equals(boxRecipe)) {
                dependencies.add(prefItem.recipe);
            } else {
                collectBoxDependencies(boxRecipe, prefItem.recipe, dependencies, visited);
            }
        }

        visited.remove(currentRecipe);
    }

    /** Ingredients that are produced by another output recipe are taken from that recipe's own sub-tree. */
    protected boolean isForeignBoxBoundary(ChainItem ingrItem) {
        final ChainItem prefItem = this.preferredItems.get(ingrItem);
        return prefItem != null && ingrItem.type != ChainItem.Type.RESULT
                && this.outputRecipes.containsKey(prefItem.recipe);
    }

    private void resetCalculation() {

        for (ChainItem item : this.recipeIngredients) {
            item.multiplier = 0;
        }

        for (ChainItem item : this.recipeResults) {
            item.multiplier = 0;
        }

        this.preferredItems.clear();
        this.requiredAmount.clear();
        this.containerItemsCrafting.clear();
        this.containerItemsInventory.clear();
        this.containerItemsBlacklist.clear();

        for (RecipeKey recipe : this.outputRecipes.keySet()) {
            collectPreferredItems(recipe, this.preferredItems, new HashSet<>());
            removeLoop(recipe, this.preferredItems, new HashSet<>());
        }
    }

    public RecipeChainMath refresh() {
        resetCalculation();

        for (RecipeKey recipe : getOutputRecipeProcessingOrder()) {
            final long prefMultiplier = this.outputRecipes.get(recipe);

            for (ChainItem prefItem : this.resultsByRecipe.getOrDefault(recipe, Collections.emptyList())) {
                if (prefItem.emptyFactor()) {
                    continue;
                }

                if (this.containers.hasContainerItem(prefItem.stack)) {
                    this.containerItemsBlacklist.add(prefItem.stack);
                }

                this.preferredItems.put(prefItem, prefItem);
                calculateSuitableRecipe(prefItem, prefMultiplier, new ArrayList<>());
                this.preferredItems.remove(prefItem);
            }
        }

        for (ChainItem prefItem : this.recipeResults) {
            if (!prefItem.emptyFactor() && this.outputRecipes.containsKey(prefItem.recipe)
                    && this.requiredAmount.containsKey(prefItem)) {
                final long prefAmount = prefItem.getAmount(this.outputRecipes.get(prefItem.recipe));
                this.requiredAmount.put(prefItem, this.requiredAmount.get(prefItem) - prefAmount);
            }
        }

        return this;
    }

    protected void calculateSuitableRecipe(ChainItem ingrItem, long ingrMultiplier, List<RecipeKey> visited) {
        final ChainItem prefItem = this.preferredItems.get(ingrItem);
        long ingrAmount = ingrItem.getAmount(ingrMultiplier, prefItem != null ? prefItem.stack : ingrItem.stack);
        final long requested = ingrAmount;
        long containerAmount = 0;
        long inventoryAmount = 0;

        // calculate existing containers
        if (ingrAmount > 0) {
            for (ChainStack stack : ingrItem.permutations.values()) {
                if (hasContainerItem(stack)) {
                    long stackSize = ingrAmount;
                    long shiftSize = shiftContainerItems(stack, stackSize, this.containerItemsCrafting);

                    if (stackSize != shiftSize) {
                        containerAmount += ingrAmount - shiftSize;
                        if ((ingrAmount = shiftSize) == 0) {
                            break;
                        }
                    }

                    stackSize = shiftSize;
                    shiftSize = shiftContainerItems(stack, stackSize, this.containerItemsInventory);

                    if (stackSize != shiftSize) {
                        inventoryAmount += ingrAmount - shiftSize;
                        if ((ingrAmount = shiftSize) == 0) {
                            break;
                        }
                    }

                }
            }
        }

        // calculate existing initial items
        if (ingrAmount > 0) {
            for (ChainItem item : this.initialItems) {
                if (item.containsItems(ingrItem)) {
                    final long newIngrAmount = addRequiredAmount(
                            item,
                            ingrAmount,
                            item.getAmount(),
                            this.containerItemsInventory);
                    inventoryAmount += ingrAmount - newIngrAmount;
                    if ((ingrAmount = newIngrAmount) == 0) {
                        break;
                    }
                }
            }
        }

        // shift amount
        final ChainItem producer = prefItem != null ? prefItem : ingrItem;
        final long beforeShift = this.requiredAmount.getOrDefault(producer, 0L);

        addRequiredAmount(producer, ingrAmount, Long.MAX_VALUE, this.containerItemsCrafting);

        calculationStepTrigger(
                ingrItem,
                prefItem,
                requested,
                containerAmount,
                inventoryAmount,
                ingrAmount,
                this.requiredAmount.getOrDefault(producer, 0L) - beforeShift);

        if (prefItem != null && !isForeignBoxBoundary(ingrItem) && !visited.contains(prefItem.recipe)) {
            final long multiplier = Math
                    .max(0, prefItem.getMultiplierFromAmount(this.requiredAmount.get(prefItem)) - prefItem.multiplier);

            if (multiplier > 0) {
                addShift(prefItem.recipe, multiplier);
                visited.add(prefItem.recipe);
                prepareIngredients(prefItem.recipe, multiplier, visited);
                visited.remove(prefItem.recipe);
            }
        }

    }

    protected void calculationStepTrigger(ChainItem ingrItem, ChainItem prefItem, long requested,
            long containerAmount, long inventoryAmount, long ingrAmount, long produced) {}

    protected void prepareIngredients(RecipeKey recipe, long multiplier, List<RecipeKey> visited) {

        for (ChainItem item : this.ingredientsByRecipe.getOrDefault(recipe, Collections.emptyList())) {
            if (!item.emptyFactor()) {
                calculateSuitableRecipe(item, multiplier, visited);
            }
        }

    }

    protected long addRequiredAmount(ChainItem prefItem, long ingrAmount, long maxAmount,
            List<ItemStack> containerItems) {
        long shiftAmount = this.requiredAmount.getOrDefault(prefItem, 0L);
        final ItemStack single = hasContainerItem(prefItem.stack) ? this.containers.single(prefItem.stack) : null;

        if (single != null) {

            while (ingrAmount > 0 && shiftAmount < maxAmount) {
                long multiplier = 1;

                ingrAmount = shiftContainerItems(prefItem.stack, ingrAmount, containerItems);

                if (ingrAmount > 0) {
                    long steps = ingrAmount;
                    final ContainerItemResult result = this.containers.use(single, steps);

                    if (result.stack == null) {
                        final long stepsPerReplacement = Math.max(1, steps - result.leftSteps);
                        multiplier = Math.max(1, Math.min(steps / stepsPerReplacement, maxAmount - shiftAmount));
                        steps = Math.max(0, steps - multiplier * stepsPerReplacement);
                    } else {
                        steps = result.leftSteps;
                    }

                    if (result.containerItem != null) {
                        long stackSize = this.containers.getCount(result.containerItem) * multiplier;

                        while (stackSize > Integer.MAX_VALUE) {
                            containerItems.add(this.containers.withCount(result.containerItem, Integer.MAX_VALUE));
                            stackSize -= Integer.MAX_VALUE;
                        }

                        containerItems.add(this.containers.withCount(result.containerItem, (int) stackSize));
                    }

                    if (result.stack != null) {
                        containerItems.add(result.stack);
                    }

                    ingrAmount = steps;
                }

                shiftAmount += multiplier;
            }

        } else {
            final long initAmount = Math.min(ingrAmount, maxAmount - shiftAmount);

            shiftAmount += initAmount;
            ingrAmount -= initAmount;
        }

        this.requiredAmount.put(prefItem, shiftAmount);

        return ingrAmount;
    }

    private long shiftContainerItems(ChainStack aStack, long steps, List<ItemStack> containerItems) {
        final int initialSize = containerItems.size();

        for (int i = 0; i < initialSize && steps > 0; i++) {
            final ItemStack bStack = containerItems.get(i);

            if (bStack != null && this.containers.sameType(aStack, bStack)) {
                final ContainerItemResult result = this.containers.use(bStack, steps);

                steps = result.leftSteps;

                if (result.containerItem != null) {
                    containerItems.add(result.containerItem);
                }

                containerItems.set(i, result.stack);
            }

        }

        containerItems.removeIf(stack -> stack == null);

        return steps;
    }

    protected boolean hasContainerItem(ChainStack stack) {

        if (this.containers.hasContainerItem(stack)) {

            for (ChainStack blacklisted : this.containerItemsBlacklist) {
                if (stack.sameType(blacklisted)) {
                    return false;
                }
            }

            return true;
        }

        return false;
    }

    private void addShift(RecipeKey recipe, long shift) {
        for (ChainItem item : this.ingredientsByRecipe.getOrDefault(recipe, Collections.emptyList())) {
            item.multiplier += shift;
        }

        for (ChainItem item : this.resultsByRecipe.getOrDefault(recipe, Collections.emptyList())) {
            item.multiplier += shift;
        }
    }

    public List<ChainItem> getIngredients(RecipeKey recipe) {
        return this.ingredientsByRecipe.getOrDefault(recipe, Collections.emptyList());
    }

    public List<ChainItem> getResults(RecipeKey recipe) {
        return this.resultsByRecipe.getOrDefault(recipe, Collections.emptyList());
    }

}
