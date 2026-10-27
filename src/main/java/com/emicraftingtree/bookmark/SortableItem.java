package com.emicraftingtree.bookmark;

import static com.emicraftingtree.bookmark.BookmarkGrid.DEFAULT_GROUP_ID;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphics;

import com.emicraftingtree.bookmark.RecipeChainDetails.BookmarkChainItem;
import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.RecipeKey;

/**
 * A bookmark (or a whole bookmarked recipe) being moved with Shift + drag (port of NEI's SortableItem).
 */
public class SortableItem {

    public final BookmarkGrid grid;
    public final GridGeometry geometry;
    public final ChainItem bookmarkItem;
    public final boolean moveRecipe;
    public final boolean crossGroup;
    public final boolean isCollapsedRecipe;
    public List<Integer> items = new ArrayList<>();

    public int shiftX = -8;
    public int shiftY = -8;

    private int revision = -1;

    public SortableItem(BookmarkGrid grid, GridGeometry geometry, ChainItem bookmarkItem) {
        final BookmarkGroup group = grid.getGroup(bookmarkItem.groupId);

        this.grid = grid;
        this.geometry = geometry;
        this.bookmarkItem = bookmarkItem.copy();
        this.moveRecipe = bookmarkItem.recipe != null && bookmarkItem.type == ChainItem.Type.RESULT
                && (group.viewMode == BookmarkViewMode.TODO_LIST || group.crafting != null);
        this.crossGroup = !group.collapsed && group.viewMode == BookmarkViewMode.TODO_LIST
                && bookmarkItem.type != ChainItem.Type.INGREDIENT;
        this.isCollapsedRecipe = group.crafting != null && group.crafting.itemToRecipe.values().stream()
                .anyMatch(recipeId -> this.bookmarkItem.equalsRecipe(recipeId, this.bookmarkItem.groupId));

        this.update();
    }

    public GridLayout layout() {
        return this.grid.getLayout(this.geometry);
    }

    /** The layout after a change, with the dragged items looked up again. */
    public GridLayout refreshLayout() {
        update();
        return layout();
    }

    public void postDraw(GuiGraphics graphics, int mouseX, int mouseY, int page, float delta) {
        final GridLayout layout = layout();
        final int perPage = this.geometry.perPage();
        int startX = Integer.MIN_VALUE;
        int startY = 0;

        update();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);

        for (Integer itemIndex : layout.getSortedItems()) {
            if (this.items.contains(itemIndex)) {
                final ChainItem item = layout.getCalculatedItem(itemIndex);
                final int slotIndex = layout.itemToSlot.get(itemIndex) % perPage;
                final int x = this.geometry.slotX(slotIndex);
                final int y = this.geometry.slotY(slotIndex);

                if (startX == Integer.MIN_VALUE) {
                    startX = x - this.shiftX;
                    startY = y - this.shiftY;
                }

                if (item != null && item.stack.emi != null) {
                    item.stack.emi.render(graphics, mouseX + x - startX + 1, mouseY + y - startY + 1, delta,
                            dev.emi.emi.api.stack.EmiIngredient.RENDER_ICON);
                }
            }
        }

        graphics.pose().popPose();
    }

    public void update() {
        if (this.revision == this.grid.revision()) {
            return;
        }

        final BookmarkGroup group = this.grid.getGroup(this.bookmarkItem.groupId);
        final GridLayout layout = layout();

        this.revision = this.grid.revision();
        this.items.clear();

        if (group == null || group.collapsed) {
            return;
        }

        for (Map.Entry<Integer, BookmarkChainItem> entry : layout.calculatedItems.entrySet()) {
            if (containsItem(entry.getValue().getItem())) {
                this.items.add(entry.getKey());
            }
        }

        if (this.isCollapsedRecipe && group.crafting != null) {
            for (Map.Entry<Integer, RecipeKey> entry : group.crafting.itemToRecipe.entrySet()) {
                if (this.bookmarkItem.equalsRecipe(entry.getValue(), this.bookmarkItem.groupId)) {
                    this.items.add(entry.getKey());
                }
            }
        }

    }

    public boolean isEmpty() {
        return this.items.isEmpty();
    }

    public int getItemIndex() {
        return this.items.isEmpty() ? -1 : this.items.get(0);
    }

    private boolean canInsert(BookmarkGroup group) {
        return group.viewMode == BookmarkViewMode.TODO_LIST && !group.collapsed;
    }

    public void mouseDragged(double mouseX, double mouseY, int page) {
        update();
        GridLayout layout = layout();
        final BookmarkViewMode sortViewMode = this.grid.getViewMode(this.bookmarkItem.groupId);
        BookmarksGridSlot mouseOverSlot = layout.getSlotMouseOver(mouseX, mouseY, page);

        if (this.crossGroup) {
            final int topLeftX = this.geometry.x;
            mouseOverSlot = layout.getSlotMouseOver(topLeftX, mouseY, page);

            if (mouseOverSlot != null) {
                float ySlot = (float) (mouseY - this.geometry.y) / GridGeometry.SLOT_SIZE;
                final int overRowIndex = (int) ySlot;
                int beforeGroupId = layout.getRowGroupId(overRowIndex - 1, page);
                int afterGroupId = layout.getRowGroupId(overRowIndex + 1, page);
                final int overGroupId = mouseOverSlot.getGroupId();
                ySlot -= overRowIndex;

                if (overGroupId == this.bookmarkItem.groupId && !this.items.contains(mouseOverSlot.itemIndex)) {
                    this.grid.moveItem(this, mouseOverSlot.itemIndex, layout);
                } else if (ySlot <= 0.25) {
                    final BookmarkGroup group = this.grid.getGroup(beforeGroupId);

                    if (beforeGroupId != overGroupId && canInsert(group)
                            && !existsRecipe(this.bookmarkItem.recipe, beforeGroupId)) {
                        this.grid.moveItem(this, overRowIndex, beforeGroupId, false, layout, page);
                    }

                } else if (ySlot > 0.25 && ySlot <= 0.5) {

                    if (beforeGroupId == overGroupId) {
                        return;
                    }

                    if (overGroupId != DEFAULT_GROUP_ID && beforeGroupId != DEFAULT_GROUP_ID) {
                        beforeGroupId = DEFAULT_GROUP_ID;
                    }

                    if (overGroupId == DEFAULT_GROUP_ID && afterGroupId != DEFAULT_GROUP_ID) {
                        return;
                    }

                    if (canInsert(this.grid.getGroup(beforeGroupId))
                            && !existsRecipe(this.bookmarkItem.recipe, beforeGroupId)) {
                        this.grid.moveItem(this, overRowIndex, beforeGroupId, false, layout, page);
                    }

                } else if (ySlot > 0.5 && ySlot < 0.75) {

                    if (afterGroupId == overGroupId) {
                        return;
                    }

                    if (overGroupId != DEFAULT_GROUP_ID && afterGroupId != DEFAULT_GROUP_ID) {
                        afterGroupId = DEFAULT_GROUP_ID;
                    }

                    if (overGroupId == DEFAULT_GROUP_ID && beforeGroupId != DEFAULT_GROUP_ID) {
                        return;
                    }

                    if (canInsert(this.grid.getGroup(afterGroupId))
                            && !existsRecipe(this.bookmarkItem.recipe, afterGroupId)) {
                        this.grid.moveItem(this, overRowIndex, afterGroupId, true, layout, page);
                    }

                } else if (ySlot >= 0.75) {
                    final BookmarkGroup group = this.grid.getGroup(afterGroupId);

                    if (afterGroupId != overGroupId && canInsert(group)
                            && !existsRecipe(this.bookmarkItem.recipe, afterGroupId)) {
                        this.grid.moveItem(this, overRowIndex, afterGroupId, true, layout, page);
                    }

                }

            } else {
                final int overRowIndex = layout.getHoveredRowIndex(mouseX, mouseY, false, page);
                final int overGroupId = layout.getRowGroupId(overRowIndex, page);

                if (overRowIndex != -1 && overGroupId == this.bookmarkItem.groupId) {
                    final int absoluteSlotIndex = (page * this.geometry.rows + overRowIndex) * this.geometry.columns;

                    if (absoluteSlotIndex < layout.itemToSlot.getOrDefault(getItemIndex(), -1)) {
                        this.grid.moveItem(this, overRowIndex, overGroupId, true, layout, page);
                    } else {
                        this.grid.moveItem(this, overRowIndex, overGroupId, false, layout, page);
                    }

                }

            }

        } else if (mouseOverSlot != null && !this.items.contains(mouseOverSlot.itemIndex)
                && mouseOverSlot.getGroupId() == this.bookmarkItem.groupId
                && (sortViewMode == BookmarkViewMode.DEFAULT || sortViewMode == BookmarkViewMode.TODO_LIST
                        && mouseOverSlot.getBookmarkItem().equalsRecipe(this.bookmarkItem))) {
            this.grid.moveItem(this, mouseOverSlot.itemIndex, layout);
        }

        update();
    }

    private boolean existsRecipe(RecipeKey recipeId, int groupId) {
        if (recipeId == null) return false;

        final List<ChainItem> bookmarkItems = this.grid.bookmarkItems;

        for (int itemIndex = 0; itemIndex < bookmarkItems.size(); itemIndex++) {
            if (!this.items.contains(itemIndex) && bookmarkItems.get(itemIndex).equalsRecipe(recipeId, groupId)) {
                return true;
            }
        }

        return false;
    }

    public List<ChainItem> getBookmarkItems() {
        final BookmarkGroup group = this.grid.getGroup(this.bookmarkItem.groupId);
        final List<ChainItem> items = new ArrayList<>();
        final List<RecipeKey> recipeRelations = new ArrayList<>();

        if (this.isCollapsedRecipe && group.crafting != null) {
            recipeRelations.addAll(
                    group.crafting.recipeRelations.getOrDefault(this.bookmarkItem.recipe, Collections.emptySet()));
        }

        for (ChainItem item : this.grid.bookmarkItems) {
            if (containsItem(item)
                    || item.groupId == this.bookmarkItem.groupId && recipeRelations.contains(item.recipe)) {
                items.add(item);
            }
        }

        return items;
    }

    public boolean containsItem(ChainItem slotItem) {
        return this.moveRecipe ? this.bookmarkItem.equalsRecipe(slotItem) : this.bookmarkItem.equals(slotItem);
    }

}
