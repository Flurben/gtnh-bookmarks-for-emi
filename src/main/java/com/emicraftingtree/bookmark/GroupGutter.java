package com.emicraftingtree.bookmark;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the group brackets in the gutter left of the grid (the drawing part of NEI's BookmarkGrid.draw).
 */
public final class GroupGutter {

    private GroupGutter() {}

    public static void draw(GuiGraphics graphics, BookmarkGrid grid, GridLayout layout, int page,
            @Nullable GroupingItem groupingItem, @Nullable SortableGroup sortableGroup, boolean editing,
            double mouseX, double mouseY) {
        final GridGeometry geometry = layout.geometry;

        if (grid.isEmpty() || layout.maxAbsoluteSlotIndex <= 0) {
            return;
        }

        final int sortableGroupId = sortableGroup != null ? sortableGroup.groupId : -3;
        final int rows = geometry.rows;
        final int rowShift = page * rows;
        final int gutterX = geometry.x - GridGeometry.GROUP_PANEL_WIDTH;
        Map<Integer, Integer> groups = layout.rowToGroupId;

        if (groupingItem != null && groupingItem.hasEndRow() && groupingItem.layout == layout) {
            groups = groupingItem.prepareGroups(groups);
        } else if (sortableGroupId != -3) {

            for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
                if (groups.getOrDefault(rowShift + rowIndex, BookmarkGrid.DEFAULT_GROUP_ID) == sortableGroupId) {
                    final int y = geometry.rowY(rowIndex);
                    graphics.fill(gutterX, y, geometry.x, y + GridGeometry.SLOT_SIZE, BookmarksGridSlot.DRAG_COLOR);
                }
            }

        } else if (!editing) {
            final int focusedRowIndex = layout.getHoveredRowIndex(mouseX, mouseY, true, page);

            if (focusedRowIndex != -1) {
                final int y = geometry.rowY(focusedRowIndex);
                graphics.fill(gutterX, y, geometry.x, y + GridGeometry.SLOT_SIZE, BookmarksGridSlot.HIGHLIGHT_COLOR);
            }
        }

        if (!groups.containsKey(rowShift)) {
            return;
        }

        final int previousPageGroupId = groups.getOrDefault(rowShift - 1, BookmarkGrid.DEFAULT_GROUP_ID);
        final int nextPageGroupId = groups.getOrDefault(rowShift + rows, BookmarkGrid.DEFAULT_GROUP_ID);
        int previousGroupId = BookmarkGrid.DEFAULT_GROUP_ID;
        int groupStartIndex = -2;

        if (previousPageGroupId == groups.get(rowShift)) {
            previousGroupId = previousPageGroupId;
            groupStartIndex = -1;
        }

        for (int rowIndex = 0; rowIndex < rows && groups.containsKey(rowShift + rowIndex); rowIndex++) {
            final int groupId = groups.get(rowShift + rowIndex);

            if (groupStartIndex != -2 && previousGroupId != groupId) {

                if (previousGroupId != BookmarkGrid.DEFAULT_GROUP_ID && previousGroupId != sortableGroupId) {
                    drawGroup(graphics, grid, geometry, previousGroupId, groupStartIndex, rowIndex - 1);
                } else {
                    drawShadowGroup(graphics, geometry, groupStartIndex, rowIndex - 1);
                }

                groupStartIndex = -2;
            }

            if (groupStartIndex == -2) {
                groupStartIndex = rowIndex;
            }

            previousGroupId = groupId;
        }

        if (groupStartIndex != -2) {
            final int rowIndex = nextPageGroupId != BookmarkGrid.DEFAULT_GROUP_ID
                    && nextPageGroupId == groups.getOrDefault(rowShift + rows - 1, BookmarkGrid.DEFAULT_GROUP_ID)
                            ? rows
                            : layout.getLastRowIndex(page);

            if (previousGroupId != BookmarkGrid.DEFAULT_GROUP_ID && previousGroupId != sortableGroupId) {
                drawGroup(graphics, grid, geometry, previousGroupId, groupStartIndex, rowIndex);
            } else {
                drawShadowGroup(graphics, geometry, groupStartIndex, rowIndex);
            }
        }
    }

    public static void drawGroup(GuiGraphics graphics, BookmarkGrid grid, GridGeometry geometry, int groupId,
            int rowIndexStart, int rowIndexEnd) {
        final int halfWidth = GridGeometry.GROUP_PANEL_WIDTH / 2;
        final int heightPadding = GridGeometry.SLOT_SIZE / 4;
        final int leftPosition = geometry.x - halfWidth - 1;
        final BookmarkGroup group = grid.getGroup(groupId);
        final int color = groupId > BookmarkGrid.DEFAULT_GROUP_ID && group != null && group.crafting != null
                ? BookmarksGridSlot.GROUP_CHAIN_COLOR
                : BookmarksGridSlot.GROUP_NONE_COLOR;
        int height = (Math.min(rowIndexEnd, geometry.rows - 1) - Math.max(0, rowIndexStart) + 1)
                * GridGeometry.SLOT_SIZE;
        int top = geometry.y + Math.max(0, rowIndexStart) * GridGeometry.SLOT_SIZE;

        if (rowIndexStart >= 0) {
            final int tickY = geometry.y + rowIndexStart * GridGeometry.SLOT_SIZE + heightPadding;
            graphics.fill(leftPosition, tickY, leftPosition + halfWidth, tickY + 1, color);
            top += heightPadding + 1;
            height -= heightPadding + 1;
        }

        if (rowIndexEnd < geometry.rows) {
            final int tickY = geometry.y + (rowIndexEnd + 1) * GridGeometry.SLOT_SIZE - heightPadding;
            graphics.fill(leftPosition, tickY, leftPosition + halfWidth, tickY + 1, color);
            height -= heightPadding;
        }

        graphics.fill(leftPosition, top, leftPosition + 1, top + height, color);
    }

    private static void drawShadowGroup(GuiGraphics graphics, GridGeometry geometry, int rowIndexStart,
            int rowIndexEnd) {
        final int top = geometry.y + Math.max(0, rowIndexStart) * GridGeometry.SLOT_SIZE;
        final int height = (Math.min(rowIndexEnd, geometry.rows - 1) - Math.max(0, rowIndexStart) + 1)
                * GridGeometry.SLOT_SIZE;
        final int x = geometry.x - GridGeometry.GROUP_PANEL_WIDTH / 2 - 1;

        graphics.fill(x, top + 2, x + 1, top + height - 2, BookmarksGridSlot.PLACEHOLDER_COLOR);
    }

}
