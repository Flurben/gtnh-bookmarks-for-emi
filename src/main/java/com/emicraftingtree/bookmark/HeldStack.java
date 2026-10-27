package com.emicraftingtree.bookmark;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.registry.EmiDragDropHandlers;
import dev.emi.emi.screen.EmiScreenBase;

/**
 * A bookmark picked up by dragging it off its slot or with the middle mouse button (NEI's draggedStack). It follows the
 * cursor until the next click, which drops it on a target that accepts stacks (like ghost slots) or, outside the GUI,
 * lets it go.
 */
public final class HeldStack {

    private static @Nullable EmiIngredient stack;
    private static @Nullable Screen screen;

    private HeldStack() {}

    public static boolean isHeld() {
        return stack != null && screen == Minecraft.getInstance().screen;
    }

    public static void hold(EmiIngredient held) {
        stack = held;
        screen = Minecraft.getInstance().screen;
    }

    public static void clear() {
        stack = null;
        screen = null;
    }

    public static void render(Screen current, GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (stack == null) {
            return;
        }

        if (current != screen) {
            clear();
            return;
        }

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        stack.render(graphics, mouseX - 8, mouseY - 8, delta, EmiIngredient.RENDER_ICON);
        graphics.pose().popPose();
    }

    /** NEI's handleDraggedClick: drop on a target, let go outside the GUI, otherwise keep holding. */
    public static boolean click(double mouseX, double mouseY) {
        if (!isHeld()) {
            clear();
            return false;
        }

        final Screen current = Minecraft.getInstance().screen;

        if (EmiDragDropHandlers.dropStack(current, stack, (int) mouseX, (int) mouseY)) {
            clear();
            return true;
        }

        if (!insideGui(current, mouseX, mouseY)) {
            clear();
        }

        return true;
    }

    private static boolean insideGui(Screen current, double mouseX, double mouseY) {
        if (current instanceof AbstractContainerScreen<?> containerScreen) {
            return mouseX >= containerScreen.getGuiLeft() && mouseY >= containerScreen.getGuiTop()
                    && mouseX < containerScreen.getGuiLeft() + containerScreen.getXSize()
                    && mouseY < containerScreen.getGuiTop() + containerScreen.getYSize();
        }

        final Bounds bounds = EmiScreenBase.of(current).bounds();
        return bounds.contains((int) mouseX, (int) mouseY);
    }

}
