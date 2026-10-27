package com.emicraftingtree.bookmark;

import java.util.List;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;

import com.emicraftingtree.bookmark.AmountText.Alignment;
import com.emicraftingtree.chain.Stacks;

import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;

/**
 * A labelled grid of stacks with amounts inside a tooltip (NEI's ItemsTooltipLineHandler), used for the crafting chain
 * summary of a group and for the alternatives an ingredient accepts.
 */
public class ItemsTooltipComponent implements ClientTooltipComponent {

    private static final int MAX_COLUMNS = 10;
    private static final int MAX_ROWS = 5;
    private static final int LABEL_HEIGHT = 11;

    private final Component label;
    private final List<EmiStack> stacks;
    private final @Nullable Function<EmiStack, EmiRecipeCategory> badges;
    private final @Nullable EmiStack highlighted;
    private final int columns;
    private final int rows;
    private final int hidden;

    public ItemsTooltipComponent(Component label, List<EmiStack> stacks,
            @Nullable Function<EmiStack, EmiRecipeCategory> badges, @Nullable EmiStack highlighted) {
        this.label = label;
        this.stacks = stacks;
        this.badges = badges;
        this.highlighted = highlighted;
        this.columns = Math.max(1, Math.min(MAX_COLUMNS, stacks.size()));

        final int neededRows = (stacks.size() + this.columns - 1) / this.columns;
        this.rows = Math.min(MAX_ROWS, neededRows);
        this.hidden = Math.max(0, stacks.size() - this.rows * this.columns);
    }

    public boolean isEmpty() {
        return this.stacks.isEmpty();
    }

    @Override
    public int getHeight() {
        return LABEL_HEIGHT + this.rows * 18 + 2;
    }

    @Override
    public int getWidth(Font font) {
        return Math.max(font.width(this.label) + (this.hidden > 0 ? font.width(" +" + this.hidden) : 0),
                this.columns * 18);
    }

    @Override
    public void renderText(Font font, int x, int y, Matrix4f matrix, MultiBufferSource.BufferSource bufferSource) {
        font.drawInBatch(this.label, x, y, -1, true, matrix, bufferSource, Font.DisplayMode.NORMAL, 0, 0xF000F0);

        if (this.hidden > 0) {
            font.drawInBatch(" +" + this.hidden, x + font.width(this.label), y, 0xAAAAAA, true, matrix, bufferSource,
                    Font.DisplayMode.NORMAL, 0, 0xF000F0);
        }
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
        final float delta = Minecraft.getInstance().getFrameTime();
        final int limit = Math.min(this.stacks.size(), this.rows * this.columns);

        for (int index = 0; index < limit; index++) {
            final EmiStack stack = this.stacks.get(index);
            final int sx = x + (index % this.columns) * 18;
            final int sy = y + LABEL_HEIGHT + (index / this.columns) * 18;

            if (this.highlighted != null && Stacks.keyOf(stack).equals(Stacks.keyOf(this.highlighted))) {
                graphics.fill(sx, sy, sx + 18, sy + 18, 0x66FFFFFF);
            }

            stack.render(graphics, sx + 1, sy + 1, delta, EmiIngredient.RENDER_ICON);

            if (stack.getAmount() > 0) {
                final boolean fluid = Stacks.isFluid(stack);
                AmountText.draw(graphics, AmountText.compact(stack.getAmount()) + (fluid ? "L" : ""), sx + 1, sy + 1,
                        0xFFFFFF, fluid ? Alignment.BOTTOM_LEFT : Alignment.BOTTOM_RIGHT);
            }

            if (this.badges != null) {
                final EmiRecipeCategory category = this.badges.apply(stack);

                if (category != null) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(sx - 1, sy - 1, 210);
                    graphics.pose().scale(0.5f, 0.5f, 1);
                    category.render(graphics, 0, 0, delta);
                    graphics.pose().popPose();
                }
            }
        }
    }

}
