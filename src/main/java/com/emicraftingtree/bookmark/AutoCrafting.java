package com.emicraftingtree.bookmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;

import com.emicraftingtree.EmiCraftingTree;
import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.RecipeChainMath;
import com.emicraftingtree.chain.RecipeKey;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.registry.EmiRecipeFiller;

/**
 * Crafts a bookmarked recipe or a whole crafting chain in the open crafting screen (NEI's AutoCraftingManager). Each
 * step works out from the current inventory which crafts are still needed and fills the grid for a batch of the
 * deepest recipe that can be made in this screen, using EMI's own recipe filling. Then, like a player, it waits for the
 * result to show up and shift-clicks it out (crafting in the same instant as the fill fails with mods that compute the
 * result later, such as FastWorkbench), and waits for the inventory to change before the next step. Leftovers such as
 * tools go back to the inventory at the end. When nothing can be crafted, a short message says why.
 */
public final class AutoCrafting {

    private static final int MAX_BATCH = 64;
    private static final int MAX_WAIT_TICKS = 40;
    private static final int MAX_STEPS = 4096;

    private static final class Job {

        final RecipeChainMath math;
        final List<ChainItem> initialItems;
        final AbstractContainerScreen<?> screen;
        /** Recipes that could not be crafted, with the reason; cleared whenever a craft changes the inventory. */
        final Map<RecipeKey, Component> failed = new LinkedHashMap<>();

        /** The grid was filled; waiting for its result to appear in {@link #output}. */
        boolean awaitingResult;
        int resultTicks;
        @Nullable Slot output;
        List<Slot> grid = List.of();

        boolean waiting;
        int waitTicks;
        /** The raw inventory before the last craft, to see when it has gone through. */
        Map<String, InventoryStacks.Entry> before;
        @Nullable RecipeKey lastRecipe;
        boolean crafted;
        int steps;

        Job(RecipeChainMath math, List<ChainItem> initialItems, AbstractContainerScreen<?> screen) {
            this.math = math;
            this.initialItems = initialItems;
            this.screen = screen;
        }
    }

    private static @Nullable Job job;

    private AutoCrafting() {}

    public static boolean processing() {
        return job != null;
    }

    public static void stop() {
        job = null;
    }

    /**
     * @param ignoreInventory craft the full amounts even when the inventory already holds some of the outputs
     *                        (Shift + C); otherwise only what is missing (Ctrl + Shift + C)
     */
    public static boolean start(@Nullable RecipeChainMath math, boolean ignoreInventory) {
        final AbstractContainerScreen<?> screen = EmiApi.getHandledScreen();

        if (math == null) {
            return false;
        }

        if (screen == null) {
            StatusMessage.show(Component.translatable("emicraftingtree.autocraft.no_screen"));
            return true;
        }

        if (math.outputRecipes.values().stream().allMatch(multiplier -> multiplier == 0)) {
            StatusMessage.show(Component.translatable("emicraftingtree.autocraft.no_quantity"));
            return true;
        }

        if (Minecraft.getInstance().screen != screen) {
            // from EMI's recipe view: craft in the container behind it, as EMI's own craft buttons do
            Minecraft.getInstance().setScreen(screen);
        }

        math.createMasterRoot();

        final Map<String, InventoryStacks.Entry> inventory = inventory(InventoryStacks.of(screen), math,
                math.initialItems);

        if (ignoreInventory) {
            ignoreInventory(math, inventory);
        }

        job = new Job(math, prepareInitialItems(math, inventory), screen);
        return true;
    }

    /** Called every client tick. */
    public static void tick() {
        final Job current = job;

        if (current == null) {
            return;
        }

        if (Minecraft.getInstance().screen != current.screen || Minecraft.getInstance().player == null) {
            job = null;
            return;
        }

        if (current.awaitingResult) {
            if (!current.output.hasItem() && ++current.resultTicks < MAX_WAIT_TICKS) {
                return;
            }

            current.awaitingResult = false;

            if (current.output.hasItem()) {
                click(current.screen, current.output, ClickType.QUICK_MOVE);
                current.waiting = true;
                current.waitTicks = 0;
                return;
            }

            EmiCraftingTree.LOGGER.info("Auto-crafting: the grid of {} was filled for {} but showed no result",
                    current.screen.getClass().getName(), current.lastRecipe.id);
            current.failed.put(current.lastRecipe, reason("emicraftingtree.autocraft.no_result", current.lastRecipe));
            clearGrid(current);
        }

        if (current.waiting) {
            final Map<String, InventoryStacks.Entry> now = InventoryStacks.of(current.screen, current.output);

            if (sameCounts(now, current.before) && ++current.waitTicks < MAX_WAIT_TICKS) {
                return;
            }

            if (sameCounts(now, current.before) && current.lastRecipe != null) {
                EmiCraftingTree.LOGGER.info("Auto-crafting: {} was filled in {} but the inventory did not change",
                        current.lastRecipe.id, current.screen.getClass().getName());
                current.failed.put(current.lastRecipe, reason("emicraftingtree.autocraft.rejected", current.lastRecipe));
            } else {
                current.failed.clear();
            }

            current.waiting = false;
        }

        try {
            step(current);
        } catch (RuntimeException e) {
            EmiCraftingTree.LOGGER.error("Auto-crafting stopped", e);
            job = null;
        }
    }

    private static void step(Job current) {
        final RecipeChainMath math = current.math;
        final Map<String, InventoryStacks.Entry> raw = InventoryStacks.of(current.screen, current.output);
        final Map<String, InventoryStacks.Entry> inventory = inventory(raw, math, current.initialItems);

        math.initialItems.clear();
        math.initialItems.addAll(current.initialItems);

        for (InventoryStacks.Entry entry : inventory.values()) {
            math.initialItems.add(ChainItem.ofItem(-1, entry.stack, entry.amount));
        }

        math.refresh();

        final Map<RecipeKey, Long> needed = new LinkedHashMap<>();

        for (ChainItem item : math.recipeResults) {
            if (item.recipe != null && !RecipeChainMath.ROOT_RECIPE.equals(item.recipe) && item.multiplier > 0) {
                needed.merge(item.recipe, item.multiplier, Math::max);
            }
        }

        if (needed.isEmpty() && !current.crafted) {
            StatusMessage.show(Component.translatable("emicraftingtree.autocraft.nothing_missing"));
        }

        if (++current.steps <= MAX_STEPS) {
            for (RecipeKey key : deepestFirst(math, needed)) {
                if (current.failed.containsKey(key)) {
                    continue;
                }

                final EmiRecipe recipe = key.getRecipe();

                if (recipe == null) {
                    current.failed.put(key, Component.translatable("emicraftingtree.autocraft.unknown_recipe",
                            String.valueOf(key.id)));
                    continue;
                }

                final int amount = (int) Math.min(MAX_BATCH, needed.get(key));

                if (EmiRecipeFiller.getFirstValidHandler(recipe, current.screen) == null) {
                    EmiCraftingTree.LOGGER.info("Auto-crafting: no EMI recipe handler for {} in {}", key.id,
                            current.screen.getClass().getName());
                    current.failed.put(key, reason("emicraftingtree.autocraft.no_handler", key));
                    continue;
                }

                // grids with a result slot: fill only, then take the result like a player once it shows up
                final StandardRecipeHandler<?> grid = gridHandler(recipe, current.screen);
                final Slot output = grid != null ? outputSlot(grid, current.screen) : null;

                if (EmiRecipeFiller.performFill(recipe, current.screen, EmiCraftContext.Type.CRAFTABLE,
                        output != null ? EmiCraftContext.Destination.NONE : EmiCraftContext.Destination.INVENTORY,
                        amount)) {
                    EmiCraftingTree.LOGGER.debug("Auto-crafting: filled {} x{}", key.id, amount);
                    current.before = InventoryStacks.of(current.screen, output);
                    current.lastRecipe = key;
                    current.crafted = true;

                    if (output != null) {
                        current.output = output;
                        current.grid = craftingSlots(grid, current.screen);
                        current.awaitingResult = true;
                        current.resultTicks = 0;
                    } else {
                        current.waiting = true;
                        current.waitTicks = 0;
                    }

                    return;
                }

                final EmiIngredient missing = missingInput(recipe, current.screen);
                EmiCraftingTree.LOGGER.info("Auto-crafting: EMI could not fill {} x{} (missing {})", key.id, amount,
                        missing != null ? missing.getEmiStacks().get(0).getId() : null);
                current.failed.put(key, missing != null
                        ? Component.translatable("emicraftingtree.autocraft.missing", outputName(key),
                                missing.getEmiStacks().get(0).getName())
                        : reason("emicraftingtree.autocraft.missing_unknown", key));
            }
        }

        job = null;
        clearGrid(current);

        if (!current.failed.isEmpty()) {
            StatusMessage.show(current.failed.values().iterator().next());
        }

        if (current.crafted) {
            Minecraft.getInstance().getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }

    private static @Nullable StandardRecipeHandler<?> gridHandler(EmiRecipe recipe, AbstractContainerScreen<?> screen) {
        final Object handler = EmiRecipeFiller.getFirstValidHandler(recipe, screen);
        return handler instanceof StandardRecipeHandler<?> standard ? standard : null;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static @Nullable Slot outputSlot(StandardRecipeHandler handler, AbstractContainerScreen<?> screen) {
        return handler.getOutputSlot(screen.getMenu());
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static List<Slot> craftingSlots(StandardRecipeHandler handler, AbstractContainerScreen<?> screen) {
        final List<Slot> slots = handler.getCraftingSlots(screen.getMenu());
        return slots != null ? slots : List.of();
    }

    private static void click(AbstractContainerScreen<?> screen, Slot slot, ClickType type) {
        final Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.gameMode != null && minecraft.player != null) {
            minecraft.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, slot.index, 0, type,
                    minecraft.player);
        }
    }

    /** Puts whatever is left in the grid (a tool, a partial batch) back into the inventory. */
    private static void clearGrid(Job current) {
        if (Minecraft.getInstance().screen != current.screen || !current.screen.getMenu().getCarried().isEmpty()) {
            return;
        }

        for (Slot slot : current.grid) {
            if (slot != null && slot.hasItem()) {
                click(current.screen, slot, ClickType.QUICK_MOVE);
            }
        }
    }

    /** The open container's items, counted under the chain's keys wherever EMI sees them as the same item. */
    private static Map<String, InventoryStacks.Entry> inventory(Map<String, InventoryStacks.Entry> raw,
            RecipeChainMath math, List<ChainItem> initialItems) {
        final List<ChainItem> items = new ArrayList<>(math.recipeIngredients);
        items.addAll(initialItems);
        return InventoryStacks.forChain(raw, items);
    }

    /** The first input of a recipe that nothing in the container matches (the way EMI matches stacks). */
    private static @Nullable EmiIngredient missingInput(EmiRecipe recipe, AbstractContainerScreen<?> screen) {
        final Player player = Minecraft.getInstance().player;

        for (EmiIngredient input : recipe.getInputs()) {
            if (input.isEmpty()) {
                continue;
            }

            boolean found = false;

            for (Slot slot : screen.getMenu().slots) {
                if (!slot.getItem().isEmpty() && !(slot instanceof ResultSlot) && slot.mayPickup(player)) {
                    final EmiStack stack = EmiStack.of(slot.getItem());

                    if (input.getEmiStacks().stream().anyMatch(alternative -> alternative.isEqual(stack))) {
                        found = true;
                        break;
                    }
                }
            }

            if (!found) {
                return input;
            }
        }

        return null;
    }

    private static Component reason(String key, RecipeKey recipe) {
        return Component.translatable(key, outputName(recipe));
    }

    private static Component outputName(RecipeKey key) {
        final EmiRecipe recipe = key.getRecipe();

        if (recipe != null && !recipe.getOutputs().isEmpty()) {
            return recipe.getOutputs().get(0).getName();
        }

        return Component.literal(String.valueOf(key.id));
    }

    /** Recipes whose ingredients are not crafted by other needed recipes first, so sub-components come first. */
    private static List<RecipeKey> deepestFirst(RecipeChainMath math, Map<RecipeKey, Long> needed) {
        final Map<RecipeKey, Integer> depth = new HashMap<>();
        final List<RecipeKey> order = new ArrayList<>(needed.keySet());

        for (RecipeKey key : order) {
            depth.put(key, depth(math, key, needed, new HashSet<>()));
        }

        order.sort(Comparator.comparingInt(depth::get));
        return order;
    }

    private static int depth(RecipeChainMath math, RecipeKey key, Map<RecipeKey, Long> needed, Set<RecipeKey> path) {
        if (!path.add(key)) {
            return 0;
        }

        int depth = 0;

        for (ChainItem ingredient : math.getIngredients(key)) {
            final ChainItem producer = math.preferredItems.get(ingredient);

            if (producer != null && producer.recipe != null && needed.containsKey(producer.recipe)) {
                depth = Math.max(depth, depth(math, producer.recipe, needed, path) + 1);
            }
        }

        path.remove(key);
        return depth;
    }

    private static boolean sameCounts(Map<String, InventoryStacks.Entry> a, Map<String, InventoryStacks.Entry> b) {
        if (a.size() != b.size()) {
            return false;
        }

        for (Map.Entry<String, InventoryStacks.Entry> entry : a.entrySet()) {
            final InventoryStacks.Entry other = b.get(entry.getKey());
            if (other == null || other.amount != entry.getValue().amount) {
                return false;
            }
        }

        return true;
    }

    private static List<ChainItem> prepareInitialItems(RecipeChainMath math, Map<String, InventoryStacks.Entry> inv) {
        final List<ChainItem> initialItems = new ArrayList<>();

        for (ChainItem item : math.initialItems) {
            final long amount = Math.max(0, item.getAmount() - InventoryStacks.count(inv, item.stack.key));

            if (amount > 0) {
                initialItems.add(item.copyWithAmount(amount));
            }
        }

        return initialItems;
    }

    /** NEI's autocraftingIgnoreInventory: outputs already in the inventory do not count towards the target. */
    private static void ignoreInventory(RecipeChainMath math, Map<String, InventoryStacks.Entry> inventory) {
        final RecipeKey rootRecipeId = RecipeChainMath.ROOT_RECIPE;

        for (int i = 0; i < math.recipeIngredients.size(); i++) {
            final ChainItem item = math.recipeIngredients.get(i);

            if (item.getAmount() > 0 && rootRecipeId.equals(item.recipe)) {
                final long amount = InventoryStacks.count(inventory, item.stack.key);

                if (amount >= item.getAmount()) {
                    final ChainItem replacement = item
                            .copyWithFactor(amount + item.getAmount() - (amount % item.getAmount()),
                                    ChainItem.CHANCE_FULL)
                            .copyWithMultiplier(1);
                    math.recipeIngredients.set(i, replacement);
                }
            }
        }

        math.rebuildRecipeIndex();
    }

}
