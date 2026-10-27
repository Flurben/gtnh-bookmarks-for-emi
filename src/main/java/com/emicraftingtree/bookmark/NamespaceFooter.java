package com.emicraftingtree.bookmark;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * The footer under the bookmark grid (NEI's resizeFooter): "&lt;" and "&gt;" switch between bookmark namespaces, the
 * label shows which one is active, and "P" pulls the default group's items out of an open chest.
 */
public final class NamespaceFooter {

    public static final int HEIGHT = 18;
    private static final int BUTTON_SIZE = 16;
    private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");

    /** Positions of the footer controls for a grid. */
    public record Layout(int prevX, int nextX, int pullX, int y, int labelX, String label, boolean visible,
            boolean pullVisible) {

        boolean overPrev(double mouseX, double mouseY) {
            return over(this.prevX, mouseX, mouseY);
        }

        boolean overNext(double mouseX, double mouseY) {
            return over(this.nextX, mouseX, mouseY);
        }

        boolean overPull(double mouseX, double mouseY) {
            return this.pullVisible && over(this.pullX, mouseX, mouseY);
        }

        /** NEI's isOverNamespaceControls: from the left arrow to the right arrow. */
        boolean overNamespaceControls(double mouseX, double mouseY) {
            return this.visible && mouseX >= this.prevX && mouseX < this.nextX + BUTTON_SIZE && mouseY >= this.y
                    && mouseY < this.y + BUTTON_SIZE;
        }

        private boolean over(int x, double mouseX, double mouseY) {
            return this.visible && mouseX >= x && mouseX < x + BUTTON_SIZE && mouseY >= this.y
                    && mouseY < this.y + BUTTON_SIZE;
        }
    }

    private NamespaceFooter() {}

    public static @Nullable Layout layout(GridGeometry geometry) {
        if (geometry.perPage() == 0) {
            return null;
        }

        final int leftBorder = geometry.x - GridGeometry.GROUP_PANEL_WIDTH;
        final int rightBorder = geometry.x + geometry.columns * GridGeometry.SLOT_SIZE;
        final int center = leftBorder + Math.max(0, (rightBorder - leftBorder) / 2);
        final int y = geometry.y + geometry.rows * GridGeometry.SLOT_SIZE + 2;
        final boolean wide = rightBorder - leftBorder >= 70;
        final int labelWidth = wide ? 36 : 18;
        final String active = String.valueOf(BookmarkStorage.getActiveIndex() + 1);
        final String label = wide ? active + "/" + BookmarkStorage.getNamespaceSize() : active;
        final boolean visible = !BookmarkStorage.isEmpty();

        return new Layout(
                center - labelWidth / 2 - 2 - BUTTON_SIZE,
                center + labelWidth / 2 + 2,
                center + 2 * labelWidth / 2 + 2,
                y,
                center,
                label,
                visible,
                visible && !BookmarkStorage.grid().isEmpty()
                        && ItemPuller.isStorage(Minecraft.getInstance().screen));
    }

    public static void render(GuiGraphics graphics, Layout layout, double mouseX, double mouseY) {
        if (!layout.visible()) {
            return;
        }

        final Font font = Minecraft.getInstance().font;

        drawButton(graphics, layout.prevX(), layout.y(), "<", layout.overPrev(mouseX, mouseY));
        drawButton(graphics, layout.nextX(), layout.y(), ">", layout.overNext(mouseX, mouseY));

        if (layout.pullVisible()) {
            drawButton(graphics, layout.pullX(), layout.y(), "P", layout.overPull(mouseX, mouseY));
        }

        graphics.drawString(font, layout.label(), layout.labelX() - font.width(layout.label()) / 2, layout.y() + 5,
                0xFFFFFF, true);
    }

    private static void drawButton(GuiGraphics graphics, int x, int y, String text, boolean hovered) {
        final Font font = Minecraft.getInstance().font;

        graphics.blitNineSliced(WIDGETS, x, y, BUTTON_SIZE, BUTTON_SIZE, 20, 4, 200, 20, 0, hovered ? 86 : 66);
        graphics.drawString(font, text, x + (BUTTON_SIZE - font.width(text) + 1) / 2, y + (BUTTON_SIZE - 8) / 2,
                hovered ? 0xFFFFA0 : 0xE0E0E0, true);
    }

}
