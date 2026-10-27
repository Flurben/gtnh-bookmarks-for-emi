package com.emicraftingtree.bookmark;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.registry.EmiRecipeFiller;

/**
 * NEI's recipe overlay (the overlay key, S by default): shows a recipe's ingredients as ghost items in the crafting
 * slots of the open screen until the key is pressed again with nothing hovered. With Shift the grid is filled instead.
 */
public final class RecipeOverlay {

    private static @Nullable EmiRecipe recipe;
    private static @Nullable Screen screen;

    private RecipeOverlay() {}

    public static boolean isActive() {
        return recipe != null && screen == Minecraft.getInstance().screen;
    }

    public static void clear() {
        recipe = null;
        screen = null;
    }

    private static @Nullable AbstractContainerScreen<?> containerScreen() {
        return Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> containerScreen ? containerScreen
                : null;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static @Nullable List<Slot> craftingSlots(EmiRecipe recipe, AbstractContainerScreen<?> screen) {
        final EmiRecipeHandler handler = EmiRecipeFiller.getFirstValidHandler(recipe, (AbstractContainerScreen) screen);

        if (handler instanceof StandardRecipeHandler standard) {
            final List<Slot> slots = standard.getCraftingSlots(recipe, (AbstractContainerMenu) screen.getMenu());
            return slots == null || slots.isEmpty() ? null : slots;
        }

        return null;
    }

    /** NEI's canUseOverlayRenderer. */
    public static boolean canUseOverlay(@Nullable EmiRecipe recipe) {
        final AbstractContainerScreen<?> screen = containerScreen();
        return recipe != null && screen != null && craftingSlots(recipe, screen) != null;
    }

    /** NEI's canFillCraftingGrid. */
    public static boolean canFill(@Nullable EmiRecipe recipe) {
        final AbstractContainerScreen<?> screen = containerScreen();
        return recipe != null && screen != null && EmiRecipeFiller.getFirstValidHandler(recipe, screen) != null;
    }

    public static boolean show(EmiRecipe shown) {
        if (!canUseOverlay(shown)) {
            return false;
        }

        recipe = shown;
        screen = Minecraft.getInstance().screen;
        return true;
    }

    /** Moves the ingredients of {@code amount} crafts into the crafting grid. */
    public static boolean fill(EmiRecipe filled, int amount) {
        final AbstractContainerScreen<?> screen = containerScreen();

        if (screen == null) {
            return false;
        }

        return EmiRecipeFiller.performFill(filled, screen, EmiCraftContext.Type.FILL_BUTTON,
                EmiCraftContext.Destination.NONE, Math.max(1, amount));
    }

    public static void render(Screen current, GuiGraphics graphics, float delta) {
        if (recipe == null) {
            return;
        }

        if (current != screen || !(current instanceof AbstractContainerScreen<?> containerScreen)) {
            clear();
            return;
        }

        final List<Slot> slots = craftingSlots(recipe, containerScreen);

        if (slots == null) {
            return;
        }

        final List<EmiIngredient> inputs = recipe.getInputs();

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 100);

        for (int index = 0; index < Math.min(slots.size(), inputs.size()); index++) {
            final Slot slot = slots.get(index);
            final EmiIngredient input = inputs.get(index);

            if (slot == null || input.isEmpty() || slot.hasItem()) {
                continue;
            }

            final int x = containerScreen.getGuiLeft() + slot.x;
            final int y = containerScreen.getGuiTop() + slot.y;

            graphics.fill(x, y, x + 16, y + 16, 0x30FF0000);
            input.render(graphics, x, y, delta, EmiIngredient.RENDER_ICON);
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 200);
            graphics.fill(x, y, x + 16, y + 16, 0x80888888);
            graphics.pose().popPose();
        }

        graphics.pose().popPose();
    }

}
