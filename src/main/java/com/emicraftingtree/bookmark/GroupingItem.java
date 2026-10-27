package com.emicraftingtree.bookmark;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.RecipeKey;

/**
 * A drag in the group gutter (port of NEI's GroupingItem): left-drag includes the dragged rows in a group (or creates
 * one), right-drag takes them out of their group. Rows are absolute (across pages).
 */
public class GroupingItem {

    public static class PointEntry {

        public int cursorRowIndex;
        public int topRowIndex;
        public int bottomRowIndex;

        public PointEntry(int clickSlotIndex, int topSlotIndex, int bottomSlotIndex) {
            this.cursorRowIndex = clickSlotIndex;
            this.topRowIndex = topSlotIndex;
            this.bottomRowIndex = bottomSlotIndex;
        }
    }

    public final GridLayout layout;

    public int groupId = -1;
    public PointEntry startPoint;
    public PointEntry endPoint;

    public GroupingItem(GridLayout layout, int groupId, int overRowIndex, int page) {
        this.groupId = groupId;
        this.layout = layout;
        setStartPoint(overRowIndex, page);
    }

    public void setStartPoint(int overRowIndex, int page) {

        if (this.layout.getRowItemIndex(overRowIndex, true, page) != Integer.MIN_VALUE) {
            this.startPoint = new PointEntry(
                    overRowIndex + getRowShift(page),
                    getTopRowIndex(overRowIndex, page),
                    getBottomRowIndex(overRowIndex, page));
        } else {
            this.startPoint = new PointEntry(overRowIndex + getRowShift(page), -1, -1);
        }

    }

    public void setEndPoint(int overRowIndex, int page) {
        if (this.layout.getRowItemIndex(overRowIndex, true, page) != Integer.MIN_VALUE) {
            this.endPoint = new PointEntry(
                    overRowIndex + getRowShift(page),
                    getTopRowIndex(overRowIndex, page),
                    getBottomRowIndex(overRowIndex, page));
        }
    }

    public int getStartSlotIndex(int page) {
        return this.startPoint.cursorRowIndex - getRowShift(page);
    }

    public boolean hasEndRow() {
        return this.endPoint != null;
    }

    private int getRowShift(int page) {
        return page * this.layout.getRows();
    }

    public Map<Integer, Integer> prepareGroups(Map<Integer, Integer> groups) {
        final int topRowIndex = Math.min(this.startPoint.topRowIndex, this.endPoint.topRowIndex);
        final int bottomRowIndex = Math.max(this.startPoint.bottomRowIndex, this.endPoint.bottomRowIndex);
        groups = new HashMap<>(groups);

        if (this.groupId != BookmarkGrid.DEFAULT_GROUP_ID) {

            if (this.startPoint.cursorRowIndex > this.endPoint.cursorRowIndex) {
                int rowIndex = this.endPoint.topRowIndex - 1;

                while (groups.containsKey(rowIndex) && groups.get(rowIndex) == this.groupId) {
                    groups.put(rowIndex--, BookmarkGrid.DEFAULT_GROUP_ID);
                }

            } else if (this.startPoint.cursorRowIndex < this.endPoint.cursorRowIndex) {
                int rowIndex = this.endPoint.bottomRowIndex + 1;

                while (groups.containsKey(rowIndex) && groups.get(rowIndex) == this.groupId) {
                    groups.put(rowIndex++, BookmarkGrid.DEFAULT_GROUP_ID);
                }
            }

        } else if (groups.containsKey(topRowIndex - 1)
                && Objects.equals(groups.get(topRowIndex - 1), groups.get(bottomRowIndex + 1))) {
            final int groupId = groups.get(bottomRowIndex);
            int rowIndex = bottomRowIndex + 1;

            while (groups.containsKey(rowIndex) && groups.get(rowIndex) == groupId) {
                groups.put(rowIndex, -1 * groups.get(rowIndex));
                rowIndex++;
            }
        }

        for (int rowIndex = topRowIndex; rowIndex <= bottomRowIndex && groups.containsKey(rowIndex); rowIndex++) {
            groups.put(rowIndex, this.groupId);
        }

        return groups;
    }

    protected int getTopRowIndex(int overRowIndex, int page) {
        final int itemIndex = this.layout.getRowItemIndex(overRowIndex, true, page);

        if (itemIndex == Integer.MIN_VALUE) {
            return -1;
        }

        final ChainItem item = this.layout.getCalculatedItem(itemIndex);

        if (item.recipe != null && item.type != ChainItem.Type.ITEM) {
            final List<Integer> sortedItems = this.layout.getSortedItems();
            final Set<RecipeKey> recipeRelations = recipeRelations(item, itemIndex);
            final boolean isCollapsedGroup = isCollapsedGroup(item);

            for (Integer rItemIndex : sortedItems) {
                final ChainItem rItem = this.layout.getCalculatedItem(rItemIndex);

                if (rItem.groupId == item.groupId && (isCollapsedGroup
                        || rItem.type != ChainItem.Type.ITEM && recipeRelations.contains(rItem.recipe))) {
                    return this.layout.getAbsoluteSlotIndex(rItemIndex) / this.layout.getColumns();
                }
            }

            return -1;
        }

        return getRowShift(page) + overRowIndex;
    }

    protected int getBottomRowIndex(int overRowIndex, int page) {
        final int itemIndex = this.layout.getRowItemIndex(overRowIndex, false, page);

        if (itemIndex == Integer.MIN_VALUE) {
            return -1;
        }

        final ChainItem item = this.layout.getCalculatedItem(itemIndex);

        if (item.recipe != null && item.type != ChainItem.Type.ITEM) {
            final List<Integer> sortedItems = this.layout.getSortedItems();
            final Set<RecipeKey> recipeRelations = recipeRelations(item, itemIndex);
            final boolean isCollapsedGroup = isCollapsedGroup(item);

            for (int index = sortedItems.size() - 1; index >= 0; index--) {
                final int rItemIndex = sortedItems.get(index);
                final ChainItem rItem = this.layout.getCalculatedItem(rItemIndex);

                if (rItem.groupId == item.groupId && (isCollapsedGroup
                        || rItem.type != ChainItem.Type.ITEM && recipeRelations.contains(rItem.recipe))) {
                    return this.layout.getAbsoluteSlotIndex(rItemIndex) / this.layout.getColumns();
                }
            }

            return -1;
        }

        return getRowShift(page) + overRowIndex;
    }

    private boolean isCollapsedGroup(ChainItem item) {
        final BookmarkGroup group = this.layout.grid.getGroup(item.groupId);
        return group.collapsed || group.crafting != null && group.viewMode == BookmarkViewMode.DEFAULT;
    }

    private Set<RecipeKey> recipeRelations(ChainItem item, int itemIndex) {
        final BookmarkGroup group = this.layout.grid.getGroup(item.groupId);
        final RecipeKey recipeId = group.crafting != null
                ? group.crafting.itemToRecipe.getOrDefault(itemIndex, item.recipe)
                : item.recipe;

        return group.crafting != null
                ? group.crafting.recipeRelations.getOrDefault(recipeId, Collections.singleton(recipeId))
                : Collections.singleton(recipeId);
    }

}
