package com.emicraftingtree.bookmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.RecipeChainMath;
import com.emicraftingtree.chain.RecipeKey;
import com.emicraftingtree.chain.Stacks;

import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiStack;

/**
 * The "Crafting Chain" part of a group's tooltip (port of NEI's RecipeChainTooltipLineHandler). Normally it lists what
 * the chain makes and what it needs; with Shift it uses the inventory: what is still missing, what is available and
 * which crafts are required.
 */
public final class ChainTooltip {

    private ChainTooltip() {}

    private static final class Line {

        final Map<String, EmiStack> stacks = new LinkedHashMap<>();
        final Map<String, RecipeKey> recipes = new HashMap<>();

        void add(@Nullable EmiStack stack, long amount, @Nullable RecipeKey recipe) {
            if (stack == null || amount <= 0) {
                return;
            }

            final String key = Stacks.keyOf(stack);
            final EmiStack existing = this.stacks.get(key);

            if (existing != null) {
                existing.setAmount(existing.getAmount() + amount);
            } else {
                this.stacks.put(key, stack.copy().setAmount(amount));
            }

            if (recipe != null) {
                this.recipes.putIfAbsent(key, recipe);
            }
        }

        void add(ChainItem item, long amount) {
            add(item.stack.emi, amount, null);
        }

        List<EmiStack> sorted() {
            final List<EmiStack> list = new ArrayList<>(this.stacks.values());
            list.sort(Comparator.comparing(Stacks::isFluid).thenComparing(stack -> -stack.getAmount()));
            return list;
        }
    }

    /**
     * @param inventory stack key to amount, used when {@code shift} is held
     */
    public static List<ClientTooltipComponent> create(BookmarkGrid grid, int groupId, boolean shift, boolean control,
            Map<String, InventoryStacks.Entry> carried) {
        final List<ClientTooltipComponent> components = new ArrayList<>();
        final RecipeChainMath math = grid.createRecipeChainMath(groupId);

        if (math == null) {
            return components;
        }

        final List<ChainItem> initialItems = new ArrayList<>(math.initialItems);
        final List<ChainItem> chainItems = new ArrayList<>(math.recipeIngredients);
        chainItems.addAll(initialItems);
        final Map<String, InventoryStacks.Entry> inventory = InventoryStacks.forChain(carried, chainItems);
        final Map<RecipeKey, Long> outputRecipes = new LinkedHashMap<>(math.outputRecipes);
        final Line available = new Line();
        final Line inputs = new Line();
        final Line outputs = new Line();
        final Line remainder = new Line();
        final Line craftingNeeded = new Line();

        if (outputRecipes.isEmpty() && !shift) {
            return components;
        }

        if (!outputRecipes.isEmpty()) {
            math.initialItems.clear();
            math.outputRecipes.clear();
            math.outputRecipes.putAll(outputRecipes);

            if (shift) {

                if (!control) {
                    for (ChainItem item : math.recipeResults) {
                        if (!item.emptyFactor() && math.outputRecipes.containsKey(item.recipe)) {
                            final InventoryStacks.Entry entry = inventory.get(item.stack.key);
                            long amount = entry != null ? entry.amount : 0;

                            if (amount >= item.getAmount()) {
                                final long itemAmount = item.getAmount(math.outputRecipes.get(item.recipe));

                                if (itemAmount > 0) {
                                    amount += itemAmount - amount % itemAmount;
                                }

                                math.outputRecipes.put(item.recipe, Math.max(math.outputRecipes.get(item.recipe),
                                        item.getMultiplierFromAmount(amount)));
                            }
                        }
                    }
                }

                for (InventoryStacks.Entry entry : inventory.values()) {
                    math.initialItems.add(ChainItem.ofItem(-1, entry.stack, entry.amount));
                }

            } else {
                math.initialItems.addAll(initialItems);
            }

            math.refresh();

            for (ChainItem item : math.initialItems) {
                final long amount = math.requiredAmount.getOrDefault(item, 0L);

                if (amount > 0) {
                    (shift ? available : inputs).add(item, amount);
                }
            }

            for (ChainItem item : math.recipeIngredients) {
                final long amount = math.requiredAmount.containsKey(math.preferredItems.get(item)) ? 0
                        : math.requiredAmount.getOrDefault(item, item.getAmount());

                inputs.add(item, amount);
            }

            for (ChainItem item : math.recipeResults) {
                final long amount = item.getAmount() - math.requiredAmount.getOrDefault(item, 0L);
                (math.outputRecipes.containsKey(item.recipe) ? outputs : remainder).add(item, amount);
            }

            for (ItemStack stack : math.containerItemsInventory) {
                remainder.add(EmiStack.of(stack), stack.getCount(), null);
            }

            for (ItemStack stack : math.containerItemsCrafting) {
                remainder.add(EmiStack.of(stack), stack.getCount(), null);
            }

        } else {

            for (ChainItem item : math.initialItems) {
                final InventoryStacks.Entry entry = inventory.get(item.stack.key);

                if (entry != null) {
                    inputs.add(item, item.getAmount() - entry.amount);
                    available.add(item, Math.min(item.getAmount(), entry.amount));
                } else {
                    inputs.add(item, item.getAmount());
                }
            }
        }

        if (shift) {
            for (Map.Entry<ChainItem, Long> entry : math.requiredAmount.entrySet()) {
                final ChainItem item = entry.getKey();

                if (item.type == ChainItem.Type.RESULT && entry.getValue() != 0
                        && !RecipeChainMath.ROOT_RECIPE.equals(item.recipe)) {
                    craftingNeeded.add(item.stack.emi, entry.getValue(), item.recipe);
                }
            }

            for (EmiStack stack : remainder.stacks.values()) {
                outputs.add(stack, stack.getAmount(), null);
            }
        }

        addLine(components, "emicraftingtree.chain", ChatFormatting.AQUA, outputs, false);
        addLine(components, "emicraftingtree.chain.needed", ChatFormatting.BLUE, craftingNeeded, true);

        if (shift) {
            addLine(components, "emicraftingtree.chain.missing", ChatFormatting.RED, inputs, false);
        }

        addLine(components, "emicraftingtree.chain.input", ChatFormatting.YELLOW, shift ? available : inputs, false);

        return components;
    }

    private static void addLine(List<ClientTooltipComponent> components, String label, ChatFormatting color,
            Line line, boolean badges) {
        if (line.stacks.isEmpty()) {
            return;
        }

        components.add(new ItemsTooltipComponent(Component.translatable(label).withStyle(color), line.sorted(),
                badges ? stack -> categoryOf(line, stack) : null, null));
    }

    private static @Nullable EmiRecipeCategory categoryOf(Line line, EmiStack stack) {
        return RecipeBookmarks.categoryOf(line.recipes.get(Stacks.keyOf(stack)));
    }

}
