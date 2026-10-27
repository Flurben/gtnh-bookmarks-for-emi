package com.emicraftingtree.bookmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

import com.emicraftingtree.Config;
import com.emicraftingtree.Keys;
import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.ChainStack;
import com.emicraftingtree.chain.ItemContainerResolver;
import com.emicraftingtree.chain.RecipeChainMath;
import com.emicraftingtree.chain.RecipeKey;
import com.emicraftingtree.chain.Stacks;
import com.emicraftingtree.mixin.RecipeScreenAccessor;
import com.emicraftingtree.network.ChatLinksClient;
import com.mojang.blaze3d.platform.InputConstants;

import dev.emi.emi.EmiUtil;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.input.EmiBind;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.screen.RecipeScreen;
import dev.emi.emi.screen.WidgetGroup;

/**
 * Key shortcuts on the hovered item or bookmark group (port of NEI's ShortcutInputHandler.handleKeyEvent): bookmarking
 * (EMI's favorite key, NEI's "A"), the recipe overlay, copying names/tags/ids, chat links, pulling items from storage
 * and auto-crafting.
 */
public final class ItemShortcuts {

    /** The stack under the mouse with the recipe NEI would associate with it. */
    private record Hovered(EmiStack stack, @Nullable EmiRecipe recipe, @Nullable BookmarksGridSlot slot,
            @Nullable EmiRecipe context) {}

    private static final Hotkeys.Memo ITEM_TIPS = new Hotkeys.Memo();

    private ItemShortcuts() {}

    private static boolean onlyControl() {
        return Screen.hasControlDown() && !Screen.hasShiftDown() && !Screen.hasAltDown();
    }

    private static boolean onlyShift() {
        return Screen.hasShiftDown() && !Screen.hasControlDown() && !Screen.hasAltDown();
    }

    /** The recipe under the mouse in EMI's recipe view (NEI's focused recipe in its recipe GUI). */
    public static @Nullable EmiRecipe focusedRecipe(double mouseX, double mouseY) {
        if (Minecraft.getInstance().screen instanceof RecipeScreen screen) {
            for (WidgetGroup group : ((RecipeScreenAccessor) screen).emicraftingtree$getCurrentPage()) {
                if (mouseX >= group.x && mouseY >= group.y && mouseX < group.x + group.width
                        && mouseY < group.y + group.height) {
                    return group.recipe;
                }
            }
        }
        return null;
    }

    /** The stack slot under the mouse in EMI's recipe view (EMI's own hover lookup does not see these). */
    private static @Nullable SlotWidget recipeSlot(double mouseX, double mouseY) {
        if (!(Minecraft.getInstance().screen instanceof RecipeScreen screen)) {
            return null;
        }

        for (WidgetGroup group : ((RecipeScreenAccessor) screen).emicraftingtree$getCurrentPage()) {
            final int x = (int) mouseX - group.x;
            final int y = (int) mouseY - group.y;

            for (Widget widget : group.widgets) {
                if (widget instanceof SlotWidget slot && widget.getBounds().contains(x, y)) {
                    return slot;
                }
            }
        }

        return null;
    }

    private static @Nullable Hovered hovered(double mouseX, double mouseY) {
        final BookmarksGridSlot slot = BookmarkPanel.hoveredSlot(mouseX, mouseY);

        if (slot != null) {
            final EmiStack stack = BookmarkPanel.stackWithQuantity(slot);
            return stack.isEmpty() ? null
                    : new Hovered(stack, slot.getEmiRecipeContext(), slot, slot.getEmiRecipeContext());
        }

        if (BookmarkPanel.spaceAt(mouseX, mouseY) != null) {
            return null;
        }

        final SlotWidget recipeSlot = recipeSlot(mouseX, mouseY);

        if (recipeSlot != null) {
            final List<EmiStack> stacks = recipeSlot.getStack().getEmiStacks();

            if (stacks.isEmpty() || stacks.get(0).isEmpty()) {
                return null;
            }

            final EmiRecipe focused = focusedRecipe(mouseX, mouseY);
            final EmiStack stack = stacks.get(0).copy().setAmount(recipeSlot.getStack().getAmount());
            return new Hovered(stack, focused, null, recipeSlot.getRecipe() != null ? recipeSlot.getRecipe() : focused);
        }

        final EmiStackInteraction interaction = EmiScreenManager.getHoveredStack((int) mouseX, (int) mouseY, true);

        if (interaction.isEmpty()) {
            return null;
        }

        final EmiIngredient ingredient = interaction.getStack() instanceof EmiFavorite favorite ? favorite.getStack()
                : interaction.getStack();
        final List<EmiStack> stacks = ingredient.getEmiStacks();

        if (stacks.isEmpty() || stacks.get(0).isEmpty()) {
            return null;
        }

        final EmiRecipe focused = focusedRecipe(mouseX, mouseY);
        return new Hovered(stacks.get(0), focused != null ? focused : interaction.getRecipeContext(), null,
                interaction.getRecipeContext());
    }

    /** NEI's item hotkey hints for a stack outside the bookmark panel (EMI's lists, the recipe view). */
    public static List<ClientTooltipComponent> itemHotkeyTips(EmiIngredient stack, double mouseX, double mouseY,
            @Nullable EmiRecipe context) {
        if (stack instanceof BookmarkSlotStack || !BookmarkPanel.isEmiReady()) {
            return List.of();
        }

        final EmiIngredient unwrapped = stack instanceof EmiFavorite favorite ? favorite.getStack() : stack;
        final EmiRecipe focused = focusedRecipe(mouseX, mouseY);
        final EmiRecipe recipe = focused != null ? focused : context;
        final boolean itemList = recipe == null && overItemList(mouseX, mouseY);

        return ITEM_TIPS.get(() -> itemHotkeyTips(unwrapped, recipe, itemList), unwrapped, recipe, itemList);
    }

    private static List<ClientTooltipComponent> itemHotkeyTips(EmiIngredient stack, @Nullable EmiRecipe recipe,
            boolean itemList) {
        // NEI's HoveredRecipe falls back to the favorite recipe; only looked up when the list is shown (Alt)
        if (recipe == null && itemList && Screen.hasAltDown() && !stack.getEmiStacks().isEmpty()) {
            recipe = FavoriteRecipes.getPreferred(stack.getEmiStacks().get(0));
        }

        return Hotkeys.item(stack.getAmount(), recipe);
    }

    public static boolean keyPressed(int keyCode, int scanCode, double mouseX, double mouseY) {
        final Hovered hovered = hovered(mouseX, mouseY);

        if (Keys.matches(Keys.OVERLAY, keyCode, scanCode) && hovered == null && RecipeOverlay.isActive()) {
            RecipeOverlay.clear();
            return true;
        }

        if (hovered == null) {
            final int groupId = BookmarkPanel.hoveredGroupId(mouseX, mouseY);

            if (groupId != -1) {
                final BookmarkGrid grid = BookmarkPanel.grid();

                if (matchesKey(EmiConfig.favorite, keyCode, scanCode) && onlyShift()) {
                    grid.removeGroup(groupId);
                    return true;
                }

                if (Keys.matches(Keys.PULL_ITEMS, keyCode, scanCode)) {
                    return ItemPuller.pull(grid.createRecipeChainMath(groupId), Screen.hasShiftDown());
                }

                if (Config.autocrafting() && Keys.matches(Keys.CRAFT_ITEMS, keyCode, scanCode)
                        && Screen.hasShiftDown() && grid.isCraftingMode(groupId)) {
                    return AutoCrafting.start(grid.createRecipeChainMath(groupId), !Screen.hasControlDown());
                }
            }

            return false;
        }

        if (Keys.matches(Keys.OVERLAY, keyCode, scanCode)) {
            return openOverlayRecipe(hovered);
        }

        if (Keys.matches(Keys.COPY_NAME, keyCode, scanCode) && onlyControl()) {
            setClipboard(ChatFormatting.stripFormatting(hovered.stack.getName().getString()));
            return true;
        }

        if (Keys.matches(Keys.COPY_IDENTIFIER, keyCode, scanCode) && onlyControl()) {
            setClipboard(String.valueOf(hovered.stack.getId()));
            return true;
        }

        if (Keys.matches(Keys.COPY_OREDICT, keyCode, scanCode) && onlyControl()) {
            setClipboard(tags(hovered.stack));
            return true;
        }

        if (Keys.matches(Keys.CHAT_LINK, keyCode, scanCode) && Screen.hasControlDown()) {
            return sendChatLink(hovered);
        }

        if (matchesKey(EmiConfig.favorite, keyCode, scanCode)) {
            return saveRecipeInBookmark(hovered, Screen.hasShiftDown(), Screen.hasControlDown(), mouseX, mouseY);
        }

        if (Keys.matches(Keys.PULL_ITEMS, keyCode, scanCode)) {
            return pullRecipeItems(hovered, Screen.hasShiftDown());
        }

        if (Config.autocrafting() && Keys.matches(Keys.CRAFT_ITEMS, keyCode, scanCode) && Screen.hasShiftDown()) {
            return runAutoCrafting(hovered, !Screen.hasControlDown());
        }

        return false;
    }

    static boolean matchesKey(EmiBind bind, int keyCode, int scanCode) {
        for (EmiBind.ModifiedKey key : bind.boundKeys) {
            if (key.isUnbound()) {
                continue;
            }

            final InputConstants.Key inputKey = key.key();

            if (inputKey.getType() == InputConstants.Type.KEYSYM && inputKey.getValue() == keyCode
                    || inputKey.getType() == InputConstants.Type.SCANCODE && inputKey.getValue() == scanCode) {
                return true;
            }
        }

        return false;
    }

    private static void setClipboard(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
    }

    /** NEI copies the ore dictionary names; tags are their 1.20 counterpart. */
    private static String tags(EmiStack stack) {
        final Object key = stack.getKey();

        if (key instanceof Item item) {
            return item.builtInRegistryHolder().tags().map(tag -> tag.location().toString())
                    .collect(Collectors.joining(","));
        }

        if (key instanceof Fluid fluid) {
            return fluid.builtInRegistryHolder().tags().map(tag -> tag.location().toString())
                    .collect(Collectors.joining(","));
        }

        return "";
    }

    private static boolean sendChatLink(Hovered hovered) {
        final ItemStack item = hovered.stack.getItemStack();

        if (item.isEmpty()) {
            return false;
        }

        final EmiRecipe recipe = Config.bookmarkItemsWithRecipe() ? hovered.recipe : null;
        final ItemStack link = item.copy();
        link.setCount((int) Math.max(1, Math.min(hovered.stack.getAmount(), Integer.MAX_VALUE)));

        ChatLinksClient.send(link, recipe != null && recipe.getId() != null ? recipe.getId().toString() : "");
        return true;
    }

    /** A bookmark sent through a chat link was clicked. */
    public static boolean addLinkedBookmark(ItemStack stack, String recipeId) {
        if (stack.isEmpty() || !BookmarkPanel.isEmiReady()) {
            return false;
        }

        RecipeKey recipe = null;

        if (!recipeId.isEmpty()) {
            final ResourceLocation location = ResourceLocation.tryParse(recipeId);
            final EmiRecipe emiRecipe = location != null ? EmiApi.getRecipeManager().getRecipe(location) : null;
            recipe = emiRecipe != null ? RecipeKey.of(emiRecipe) : null;
        }

        final ChainStack chainStack = Stacks.of(stack);
        return addItem(chainStack.withAmount(stack.getCount()), stack.getCount(), recipe,
                BookmarkGrid.DEFAULT_GROUP_ID);
    }

    private static boolean openOverlayRecipe(Hovered hovered) {
        final EmiRecipe recipe = hovered.recipe;

        // filling the grid is Shift + click on a bookmark instead of NEI's Shift + S
        if (recipe == null || Screen.hasShiftDown() || !RecipeOverlay.canUseOverlay(recipe)) {
            return false;
        }

        return RecipeOverlay.show(recipe);
    }

    /** EMI tried to add a favorite in a way the key handler did not see (a hovered recipe, a mouse binding). */
    public static boolean onEmiFavorite(EmiIngredient stack, @Nullable EmiRecipe context) {
        if (!BookmarkPanel.isEmiReady()) {
            return false;
        }

        if (stack instanceof BookmarkSlotStack) {
            return true;
        }

        final EmiIngredient unwrapped = stack instanceof EmiFavorite favorite ? favorite.getStack() : stack;

        if (unwrapped.getEmiStacks().isEmpty() || unwrapped.getEmiStacks().get(0).isEmpty()) {
            return true;
        }

        final Hovered hovered = new Hovered(unwrapped.getEmiStacks().get(0), context, null, context);
        return saveRecipeInBookmark(hovered, Screen.hasShiftDown(), Screen.hasControlDown(), EmiScreenManager.lastMouseX,
                EmiScreenManager.lastMouseY);
    }

    /** NEI's saveRecipeInBookmark: Shift adds the whole recipe, Ctrl keeps the amount, repeating it removes it. */
    private static boolean saveRecipeInBookmark(Hovered hovered, boolean saveIngredients, boolean saveStackSize,
            double mouseX, double mouseY) {
        final BookmarkGrid grid = BookmarkPanel.grid();

        if (hovered.slot != null) {
            removeSlot(hovered.slot, saveIngredients);
            return true;
        }

        EmiRecipe recipe = focusedRecipe(mouseX, mouseY);
        boolean fromItemList = false;

        // NEI's getFocusedRecipe: in the item list Shift + A takes the item's favorite recipe
        if (recipe == null && saveIngredients && overItemList(mouseX, mouseY)) {
            recipe = RecipeBookmarks.canBookmark(hovered.context) ? hovered.context
                    : FavoriteRecipes.getPreferred(hovered.stack);
            fromItemList = true;
        } else if (recipe == null && saveIngredients) {
            recipe = hovered.context;
        }

        if (!RecipeBookmarks.canBookmark(recipe)) {
            recipe = null;
        }

        final ChainStack stack = Stacks.of(hovered.stack, Math.max(1, hovered.stack.getAmount()));

        if (recipe != null && saveIngredients) {

            if (!grid.removeRecipe(RecipeKey.of(recipe), BookmarkGrid.DEFAULT_GROUP_ID)) {
                final long multiplier = saveStackSize || Config.bookmarkRecipeWithCount() ? 1 : 0;
                ChainStack result = stack;

                if (recipe.getOutputs().stream().noneMatch(output -> Stacks.keyOf(output).equals(stack.key))) {
                    result = recipe.getOutputs().isEmpty() ? null : Stacks.of(recipe.getOutputs().get(0));
                }

                List<ChainItem> items = RecipeBookmarks.build(recipe, BookmarkGrid.DEFAULT_GROUP_ID, multiplier,
                        result);

                if (fromItemList) {
                    items = FavoriteRecipes.preferFavoriteIngredients(items);
                }

                grid.addRecipe(items, BookmarkGrid.DEFAULT_GROUP_ID);
            }

        } else {
            final RecipeKey recipeId = recipe != null ? RecipeKey.of(recipe) : null;
            final boolean existsRecipe = recipeId != null
                    && grid.existsRecipe(recipeId, BookmarkGrid.DEFAULT_GROUP_ID)
                    || Config.bookmarkItemsWithRecipe();
            final RecipeKey linkedRecipe = existsRecipe ? recipeId : null;

            if (!removeItem(stack, linkedRecipe, BookmarkGrid.DEFAULT_GROUP_ID)) {
                final boolean keepSize = saveStackSize || saveIngredients
                        || grid.existsRecipe(recipeId, BookmarkGrid.DEFAULT_GROUP_ID);

                addItem(stack, keepSize ? stack.amount : 0, linkedRecipe, BookmarkGrid.DEFAULT_GROUP_ID);
            }
        }

        return true;
    }

    /** Over one of EMI's stack lists (index, craftables, ...): NEI's item panel. */
    private static boolean overItemList(double mouseX, double mouseY) {
        final EmiScreenManager.ScreenSpace space = EmiScreenManager.getHoveredSpace((int) mouseX, (int) mouseY);
        return space != null && space.getType() != SidebarType.FAVORITES;
    }

    /** NEI's BookmarkPanel.addItem. */
    static boolean addItem(ChainStack stack, long amount, @Nullable RecipeKey recipeId, int groupId) {
        final BookmarkGrid grid = BookmarkPanel.grid();

        if (grid.indexOf(groupId, stack, recipeId, false) != -1) {
            return false;
        }

        final boolean existsRecipe = recipeId != null && grid.existsRecipe(recipeId, BookmarkGrid.DEFAULT_GROUP_ID);
        final EmiRecipe recipe = recipeId != null ? recipeId.getRecipe() : null;
        final ChainStack single = stack.withAmount(Math.max(1, stack.amount));
        ChainItem item = recipe != null ? RecipeBookmarks.resultItem(recipe, groupId, single, 1) : null;

        if (item == null && recipeId != null) {
            item = new ChainItem(groupId, single, ChainItem.singlePermutation(single), recipeId, ChainItem.Type.ITEM,
                    1, single.amount, ChainItem.CHANCE_FULL);
        } else if (item == null) {
            item = ChainItem.ofItem(groupId, single.withAmount(1), amount);
        }

        if (existsRecipe) {
            item.type = ChainItem.Type.RESULT;

            for (ChainItem other : grid.getBookmarkItems()) {
                if (other.equalsRecipe(recipeId, groupId)) {
                    item.multiplier = other.getMultiplier();
                    break;
                }
            }

        } else {
            item.type = ChainItem.Type.ITEM;
        }

        grid.addItem(item, true);
        return true;
    }

    private static boolean removeItem(ChainStack stack, @Nullable RecipeKey recipeId, int groupId) {
        final BookmarkGrid grid = BookmarkPanel.grid();
        final int itemIndex = grid.indexOf(groupId, stack, recipeId, false);

        if (itemIndex != -1) {
            grid.removeRecipe(itemIndex, false);
            return true;
        }

        return false;
    }

    /** NEI's removeSlot: A removes the item (or its recipe when it is the only result), Shift + A the recipe. */
    private static boolean removeSlot(BookmarksGridSlot slot, boolean removeFullRecipe) {
        final BookmarkGrid grid = BookmarkPanel.grid();
        final int groupId = slot.getGroupId();
        final RecipeKey recipeId = slot.getType() == ChainItem.Type.ITEM ? null : slot.getRecipeId();
        final BookmarkGroup group = slot.getGroup();

        if (group.crafting != null && group.collapsed) {

            if (removeFullRecipe) {
                grid.removeGroup(groupId);
            }

            return true;
        }

        if (recipeId != null && group.crafting != null) {
            Set<RecipeKey> recipes = group.crafting.recipeRelations.getOrDefault(recipeId, Collections.emptySet());

            if (recipes.isEmpty()) {
                for (Map.Entry<RecipeKey, Set<RecipeKey>> entry : group.crafting.recipeRelations.entrySet()) {
                    if (entry.getValue().contains(recipeId)) {
                        recipes = entry.getValue();
                        break;
                    }
                }
            }

            if (!recipes.isEmpty()) {

                if (removeFullRecipe) {
                    for (RecipeKey relRecipeId : new ArrayList<>(recipes)) {
                        grid.removeRecipe(relRecipeId, groupId);
                    }
                }

                return true;
            }
        }

        if (recipeId == null || !removeFullRecipe) {
            grid.removeRecipe(slot.itemIndex, removeFullRecipe);
            return true;
        }

        return grid.removeRecipe(recipeId, groupId);
    }

    /** NEI's pullRecipeItems: pull the hovered item, in its bookmarked amount or a stack. */
    private static boolean pullRecipeItems(Hovered hovered, boolean onlyMissing) {
        if (!ItemPuller.isStorage(Minecraft.getInstance().screen)) {
            return false;
        }

        long amount = hovered.stack.getAmount();

        if (hovered.slot == null) {
            amount = hovered.stack.getItemStack().isEmpty() ? 1 : hovered.stack.getItemStack().getMaxStackSize();
        }

        final List<ChainItem> items = new ArrayList<>();
        items.add(ChainItem.ofItem(-3, Stacks.of(hovered.stack, 1), Math.max(1, amount)));
        return ItemPuller.pull(new RecipeChainMath(items, Collections.emptySet(), ItemContainerResolver.INSTANCE),
                onlyMissing);
    }

    /** NEI's runAutoCrafting: a bookmarked recipe (with the recipes it collapses), or the hovered item's recipe. */
    private static boolean runAutoCrafting(Hovered hovered, boolean ignoreInventory) {
        RecipeChainMath math = null;

        if (hovered.slot != null) {
            final BookmarksGridSlot slot = hovered.slot;
            final RecipeKey recipeId = slot.getRecipeId();

            if (recipeId != null && slot.getType() != ChainItem.Type.INGREDIENT) {
                math = slot.getGroup().crafting == null
                        ? singleRecipeMath(recipeId.getRecipe(), Math.max(1, slot.getMultiplier()))
                        : BookmarkPanel.grid().createRecipeChainMath(slot.getGroupId(), recipeId);
            }
        } else {
            EmiRecipe recipe = hovered.recipe;

            if (recipe == null && EmiScreenManager.lastPlayerInventory != null) {
                recipe = EmiUtil.getPreferredRecipe(hovered.stack, EmiScreenManager.lastPlayerInventory, false);
            }

            math = singleRecipeMath(recipe, 1);
        }

        return math != null && AutoCrafting.start(math, ignoreInventory);
    }

    private static @Nullable RecipeChainMath singleRecipeMath(@Nullable EmiRecipe recipe, long multiplier) {
        if (recipe == null || recipe.getId() == null) {
            return null;
        }

        return new RecipeChainMath(RecipeBookmarks.build(recipe, -1, multiplier, null), Collections.emptySet(),
                ItemContainerResolver.INSTANCE);
    }

}
