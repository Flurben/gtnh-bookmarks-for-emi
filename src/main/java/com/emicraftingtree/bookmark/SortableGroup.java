package com.emicraftingtree.bookmark;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;

import com.emicraftingtree.chain.ChainItem;

import dev.emi.emi.api.stack.EmiIngredient;

/**
 * A whole group being moved with Shift + drag on its gutter (port of NEI's SortableGroup).
 */
public class SortableGroup {

    public BookmarkGrid grid;
    public final GridGeometry geometry;
    public int groupId;
    public boolean aloneInGrid;

    public int shiftX = 0;
    public int shiftY = 0;

    public SortableGroup(BookmarkGrid grid, GridGeometry geometry, int groupId) {
        this.geometry = geometry;
        update(grid, groupId);
    }

    public void update(BookmarkGrid grid, int groupId) {
        this.grid = grid;
        this.groupId = groupId;
        this.aloneInGrid = grid.bookmarkItems.stream().allMatch(item -> item.groupId == groupId);
    }

    public boolean matches(BookmarkGrid grid, int groupId) {
        return this.grid == grid && this.groupId == groupId;
    }

    public GridLayout layout() {
        return this.grid.getLayout(this.geometry);
    }

    public void postDraw(GuiGraphics graphics, int mouseX, int mouseY, int page, float delta) {
        final GridLayout layout = layout();
        int startX = Integer.MIN_VALUE;
        int startY = 0;

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);

        for (BookmarksGridSlot slot : layout.getSlots(page)) {
            if (slot.getGroupId() == this.groupId) {
                final int x = this.geometry.slotX(slot.slotIndex);
                final int y = this.geometry.slotY(slot.slotIndex);

                if (startX == Integer.MIN_VALUE) {
                    startX = x - this.shiftX;
                    startY = y - this.shiftY;
                }

                final ChainItem item = slot.getBookmarkItem();

                if (item.stack.emi != null) {
                    item.stack.emi.render(graphics, mouseX + x - startX + 1, mouseY + y - startY + 1, delta,
                            EmiIngredient.RENDER_ICON);
                }
            }
        }

        if (startX != Integer.MIN_VALUE) {
            int startRowIndex = -1;
            int endRowIndex = 0;

            for (int rowIndex = 0; rowIndex < this.geometry.rows; rowIndex++) {
                if (layout.getRowGroupId(rowIndex, page) == this.groupId) {

                    if (startRowIndex == -1) {
                        startRowIndex = rowIndex;
                    }

                    endRowIndex = rowIndex;
                }
            }

            graphics.pose().translate(mouseX - startX, mouseY - startY, 0);
            GroupGutter.drawGroup(graphics, this.grid, this.geometry, this.groupId, startRowIndex, endRowIndex);
        }

        graphics.pose().popPose();
    }

    public List<ChainItem> getBookmarkItems() {
        final List<ChainItem> items = new ArrayList<>();

        for (ChainItem item : this.grid.bookmarkItems) {
            if (item.groupId == this.groupId) {
                items.add(item);
            }
        }

        return items;
    }
}
