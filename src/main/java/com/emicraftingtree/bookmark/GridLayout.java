package com.emicraftingtree.bookmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

import com.emicraftingtree.bookmark.RecipeChainDetails.BookmarkChainItem;
import com.emicraftingtree.bookmark.RecipeChainDetails.CalculatedType;
import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.RecipeKey;

/**
 * Where every bookmark goes for one screen geometry (port of NEI's BookmarkGridGenerator). Slot indexes are absolute
 * across pages ({@code page * rows * columns + row * columns + column}). Collapsed recipes of crafting groups show
 * "shadow" items with negative item indexes.
 */
public class GridLayout {

    public final BookmarkGrid grid;
    public final GridGeometry geometry;

    public final Map<Integer, Integer> rowToGroupId = new HashMap<>();
    public final Map<Integer, RecipeKey> itemToRecipe = new HashMap<>();

    public final Map<Integer, Integer> slotToItem = new LinkedHashMap<>();
    public final Map<Integer, Integer> itemToSlot = new LinkedHashMap<>();

    public final Map<Integer, BookmarkChainItem> calculatedItems = new HashMap<>();
    private final Map<Integer, String> borderGrid = new HashMap<>();
    private final Map<Integer, List<BookmarksGridSlot>> pageSlots = new HashMap<>();

    public int pageCount = 0;
    public int maxAbsoluteSlotIndex = 0;

    public GridLayout(BookmarkGrid grid, GridGeometry geometry) {
        this.grid = grid;
        this.geometry = geometry;
    }

    public int getRows() {
        return this.geometry.rows;
    }

    public int getColumns() {
        return this.geometry.columns;
    }

    public int getRowGroupId(int rowIndex, int page) {
        final int absoluteRowIndex = rowIndex + page * this.geometry.rows;
        return this.rowToGroupId.getOrDefault(absoluteRowIndex, BookmarkGrid.DEFAULT_GROUP_ID);
    }

    void generate() {
        final Map<Integer, String> borders = this.borderGrid;
        final int gridColumns = this.geometry.columns;
        final int size = this.grid.size();
        ChainItem prevBookmarkItem = null;
        int absoluteSlotIndex = -2;
        int itemIndex = 0;

        if (this.grid.isEmpty() || this.geometry.perPage() == 0) {
            return;
        }

        while (itemIndex < size && absoluteSlotIndex != -1) {
            final ChainItem bookmarkItem = this.grid.getBookmarkItem(itemIndex);
            final BookmarkGroup group = this.grid.getGroup(bookmarkItem.groupId);
            final boolean isCollapsedRecipeAnchor = group.crafting != null && bookmarkItem.recipe != null
                    && bookmarkItem.type != ChainItem.Type.INGREDIENT
                    && group.collapsedRecipes.contains(bookmarkItem.recipe);

            if (group.crafting != null && !isCollapsedRecipeAnchor
                    && (!group.crafting.calculatedItems.containsKey(itemIndex)
                            || bookmarkItem.recipe != null && !bookmarkItem.recipe.equals(
                                    group.crafting.itemToRecipe.getOrDefault(itemIndex, bookmarkItem.recipe)))) {
                itemIndex++;
            } else if (group.collapsed) {
                final List<Integer> results = getGroupResults(bookmarkItem.groupId, itemIndex, size);
                final String groupKey = "group:" + bookmarkItem.groupId;

                for (int index = 0; index < results.size(); index++) {
                    final int collapsedItemIndex = results.get(index);
                    final ChainItem item = this.grid.getBookmarkItem(collapsedItemIndex);
                    final int nextSlotIndex = nextSlotIndex(
                            absoluteSlotIndex + 1,
                            prevBookmarkItem,
                            item,
                            BookmarkViewMode.DEFAULT);

                    if (nextSlotIndex == -1 || index > 0 && (nextSlotIndex % gridColumns) == 0) {
                        break;
                    }

                    prevBookmarkItem = item;
                    absoluteSlotIndex = nextSlotIndex;
                    this.slotToItem.put(absoluteSlotIndex, collapsedItemIndex);
                    this.itemToSlot.put(collapsedItemIndex, absoluteSlotIndex);
                    this.calculatedItems.put(collapsedItemIndex, getItem(group, collapsedItemIndex));
                    borders.put(absoluteSlotIndex, groupKey);
                }

                while (itemIndex < size && this.grid.getBookmarkItem(itemIndex).groupId == bookmarkItem.groupId) {
                    itemIndex++;
                }

            } else if (group.crafting != null && group.viewMode == BookmarkViewMode.DEFAULT) {
                final List<Integer> results = getGroupResults(bookmarkItem.groupId, itemIndex, size);
                List<Integer> shadows;

                for (int index = 0; index < results.size() && absoluteSlotIndex != -1; index++) {
                    final int collapsedItemIndex = results.get(index);
                    final ChainItem item = this.grid.getBookmarkItem(collapsedItemIndex);

                    if ((absoluteSlotIndex = nextSlotIndex(
                            absoluteSlotIndex + 1,
                            prevBookmarkItem,
                            item,
                            BookmarkViewMode.DEFAULT)) != -1) {
                        prevBookmarkItem = item;
                        this.slotToItem.put(absoluteSlotIndex, collapsedItemIndex);
                        this.itemToSlot.put(collapsedItemIndex, absoluteSlotIndex);
                        this.calculatedItems.put(collapsedItemIndex, getItem(group, collapsedItemIndex));

                        if (item.recipe != null && !(shadows = getShadows(group, item.recipe)).isEmpty()) {
                            borders.put(absoluteSlotIndex, "recipe:" + shadows.get(0));
                        }
                    }
                }

                while (itemIndex < size && this.grid.getBookmarkItem(itemIndex).groupId == bookmarkItem.groupId) {
                    itemIndex++;
                }

            } else if (group.crafting != null && group.crafting.itemToRecipe.containsValue(bookmarkItem.recipe)) {
                absoluteSlotIndex = generateCollapsedRecipe(prevBookmarkItem, itemIndex, absoluteSlotIndex, borders);
                prevBookmarkItem = bookmarkItem;

                while (itemIndex < size && bookmarkItem.equalsRecipe(this.grid.getBookmarkItem(itemIndex))) {
                    itemIndex++;
                }

            } else if ((absoluteSlotIndex = nextSlotIndex(
                    absoluteSlotIndex + 1,
                    prevBookmarkItem,
                    bookmarkItem,
                    group.viewMode)) != -1) {
                prevBookmarkItem = bookmarkItem;
                this.slotToItem.put(absoluteSlotIndex, itemIndex);
                this.itemToSlot.put(itemIndex, absoluteSlotIndex);
                this.calculatedItems.put(itemIndex, getItem(group, itemIndex));
                itemIndex++;
            }
        }

        this.maxAbsoluteSlotIndex = this.slotToItem.keySet().stream().mapToInt(index -> index).max().orElse(-1) + 1;
        this.pageCount = (int) Math.ceil(this.maxAbsoluteSlotIndex / (float) this.geometry.perPage());
        generateGroups();
    }

    private List<Integer> getGroupResults(int groupId, int itemIndex, int size) {
        final BookmarkGroup group = this.grid.getGroup(groupId);
        final List<Integer> results = new ArrayList<>();

        while (itemIndex < size) {
            final ChainItem bookmarkItem = this.grid.getBookmarkItem(itemIndex);

            if (bookmarkItem == null || bookmarkItem.groupId != groupId) {
                break;
            }

            if (bookmarkItem.type != ChainItem.Type.INGREDIENT
                    && (group.crafting == null || group.crafting.calculatedItems.containsKey(itemIndex))) {
                results.add(itemIndex);
            }

            itemIndex++;
        }

        return results;
    }

    private int generateCollapsedRecipe(ChainItem prevBookmarkItem, int itemIndex, int absoluteSlotIndex,
            Map<Integer, String> borders) {
        final ChainItem bookmarkItem = this.grid.getBookmarkItem(itemIndex);
        final BookmarkGroup group = this.grid.getGroup(bookmarkItem.groupId);
        final List<Integer> shadows = getShadows(group, bookmarkItem.recipe);
        final String borderId = "recipe:" + shadows.get(0);

        shadows.sort(generateSortingComparator(group.crafting.calculatedItems));

        for (int shadowIndex : shadows) {
            final boolean isIngredient = shadows.get(0) != shadowIndex
                    || group.crafting.calculatedItems.get(shadowIndex).calculatedType == CalculatedType.INGREDIENT;

            final ChainItem shadowBookmarkItem = new ChainItem(bookmarkItem.groupId, bookmarkItem.stack,
                    Collections.emptyMap(), bookmarkItem.recipe,
                    isIngredient ? ChainItem.Type.INGREDIENT : ChainItem.Type.RESULT, 1, bookmarkItem.stack.amount,
                    ChainItem.CHANCE_FULL);

            if ((absoluteSlotIndex = nextSlotIndex(
                    absoluteSlotIndex + 1,
                    prevBookmarkItem,
                    shadowBookmarkItem,
                    group.viewMode)) != -1) {
                this.slotToItem.put(absoluteSlotIndex, shadowIndex);
                this.itemToSlot.put(shadowIndex, absoluteSlotIndex);
                this.calculatedItems.put(shadowIndex, getItem(group, shadowIndex));
                this.itemToRecipe.put(shadowIndex, bookmarkItem.recipe);
                borders.put(absoluteSlotIndex, borderId);
                prevBookmarkItem = shadowBookmarkItem;
            }

        }

        return absoluteSlotIndex;
    }

    private List<Integer> getShadows(BookmarkGroup group, RecipeKey recipeId) {
        return group.crafting.itemToRecipe.entrySet().stream().filter(entry -> recipeId.equals(entry.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toList());
    }

    private int nextSlotIndex(int index, ChainItem previousMeta, ChainItem meta, BookmarkViewMode viewMode) {
        final int gridColumns = this.geometry.columns;
        final int maxIndex = this.geometry.perPage();
        int j = 0;

        index = Math.max(0, index);

        while (j++ < maxIndex) {
            final boolean isFirstColumn = (index % gridColumns) == 0;

            if (this.geometry.isInvalidSlot(index % maxIndex)) {
                index++;
            } else if (!isFirstColumn && (previousMeta == null || previousMeta.groupId != meta.groupId)) {
                // new group must start on a new line
                index++;
            } else if (viewMode == BookmarkViewMode.DEFAULT) {
                return index;
            } else if (isFirstColumn && (meta.recipe == null || previousMeta == null
                    || previousMeta.groupId != meta.groupId
                    || meta.type != ChainItem.Type.INGREDIENT
                    || !meta.recipe.equals(previousMeta.recipe)
                    || index + 1 < maxIndex && this.geometry.isInvalidSlot((index + 1) % maxIndex))) {
                // In first column must be an item without recipe, a recipe result, or an ingredient
                // if the second column is occupied
                return index;
            } else if (!isFirstColumn && meta.type != ChainItem.Type.ITEM
                    && previousMeta.type != ChainItem.Type.ITEM
                    && meta.groupId == previousMeta.groupId
                    && meta.recipe != null
                    && meta.recipe.equals(previousMeta.recipe)) {
                return index;
            } else {
                index++;
            }

        }

        return -1;
    }

    private void generateGroups() {
        final int gridColumns = this.geometry.columns;
        int previousGroupId = BookmarkGrid.DEFAULT_GROUP_ID;

        for (int absoluteSlotIndex = 0; absoluteSlotIndex < this.maxAbsoluteSlotIndex; absoluteSlotIndex++) {
            int absoluteRowIndex = absoluteSlotIndex / gridColumns;
            if (this.rowToGroupId.getOrDefault(absoluteRowIndex, BookmarkGrid.DEFAULT_GROUP_ID)
                    == BookmarkGrid.DEFAULT_GROUP_ID) {
                int groupId = BookmarkGrid.DEFAULT_GROUP_ID;

                if (this.slotToItem.containsKey(absoluteSlotIndex)) {
                    groupId = this.calculatedItems.get(this.slotToItem.get(absoluteSlotIndex)).getItem().groupId;
                }

                this.rowToGroupId.put(absoluteRowIndex, groupId);

                if (groupId != BookmarkGrid.DEFAULT_GROUP_ID && previousGroupId == (previousGroupId = groupId)) {
                    while ((--absoluteRowIndex) >= 0
                            && this.rowToGroupId.get(absoluteRowIndex) == BookmarkGrid.DEFAULT_GROUP_ID) {
                        this.rowToGroupId.put(absoluteRowIndex, groupId);
                    }
                }

            }
        }

    }

    private Comparator<Integer> generateSortingComparator(Map<Integer, BookmarkChainItem> calculatedItems) {
        return Comparator.comparingInt((Integer index) -> calculatedItems.get(index).calculatedType.toInt())
                .thenComparing(index -> calculatedItems.get(index).getItem().stack.fluid)
                .thenComparing(index -> calculatedItems.get(index).getItem().stack.key);
    }

    private BookmarkChainItem getItem(BookmarkGroup group, int itemIndex) {
        if (group.crafting != null) {
            return group.crafting.calculatedItems.get(itemIndex);
        } else {
            final BookmarkChainItem item = BookmarkChainItem.of(this.grid.getBookmarkItem(itemIndex));
            item.setRealMultiplier(item.getCalculatedMultiplier());
            return item;
        }
    }

    /** The slots shown on a page, in slot order. */
    public List<BookmarksGridSlot> getSlots(int page) {
        return this.pageSlots.computeIfAbsent(page, this::createPageSlots);
    }

    private List<BookmarksGridSlot> createPageSlots(int page) {
        final List<BookmarksGridSlot> slots = new ArrayList<>();
        final int perPage = this.geometry.perPage();

        if (perPage == 0) {
            return slots;
        }

        for (int absoluteSlotIndex = page * perPage; absoluteSlotIndex < Math.min(this.maxAbsoluteSlotIndex,
                (page + 1) * perPage); absoluteSlotIndex++) {
            final Integer itemIndex = this.slotToItem.get(absoluteSlotIndex);

            if (itemIndex != null) {
                slots.add(createSlot(absoluteSlotIndex % perPage, itemIndex));
            }
        }

        calculateGroupBorders(slots, page);
        return slots;
    }

    private BookmarksGridSlot createSlot(int slotIndex, int itemIndex) {
        final BookmarkChainItem chainItem = this.calculatedItems.get(itemIndex);
        final BookmarkGroup group = this.grid.getGroup(chainItem.getItem().groupId);

        if (group.crafting == null) {
            return new BookmarksGridSlot(slotIndex, itemIndex, chainItem.getRealMultiplier(), 0, 0,
                    chainItem.getItem(), null, group, this);
        } else {
            return new BookmarksGridSlot(slotIndex, itemIndex, chainItem.getRealMultiplier(),
                    chainItem.getShiftAmount(), chainItem.getCalculatedMultiplier(), chainItem.getItem(),
                    chainItem.calculatedType, group, this);
        }
    }

    private void calculateGroupBorders(List<BookmarksGridSlot> slots, int page) {
        final int gridColumns = this.geometry.columns;
        final int shiftIndex = page * this.geometry.perPage();

        for (BookmarksGridSlot slot : slots) {
            final String groupIndex = this.borderGrid.get(shiftIndex + slot.slotIndex);

            if (groupIndex == null) {
                continue;
            }

            final int column = slot.slotIndex % gridColumns;
            final int row = slot.slotIndex / gridColumns;
            final int prevSlotIndex = shiftIndex + (row - 1) * gridColumns + column;
            final int nextSlotIndex = shiftIndex + (row + 1) * gridColumns + column;
            final BookmarkGroup group = slot.getGroup();

            if (group.crafting == null) {
                slot.borderColor = BookmarksGridSlot.GROUP_NONE_COLOR;
            } else if (group.collapsed) {
                slot.borderColor = BookmarksGridSlot.GROUP_CHAIN_COLOR;
            } else {
                slot.borderColor = BookmarksGridSlot.RECIPE_COLOR;
            }

            slot.borderLeft = column == 0 || !groupIndex.equals(this.borderGrid.get(shiftIndex + slot.slotIndex - 1));
            slot.borderRight = column + 1 == gridColumns
                    || !groupIndex.equals(this.borderGrid.get(shiftIndex + slot.slotIndex + 1));

            slot.borderTop = !groupIndex.equals(this.borderGrid.get(prevSlotIndex));
            slot.borderBottom = !groupIndex.equals(this.borderGrid.get(nextSlotIndex));
        }

    }

    public @Nullable BookmarksGridSlot getSlotBySlotIndex(int page, int slotIndex) {
        for (BookmarksGridSlot slot : getSlots(page)) {
            if (slot.slotIndex == slotIndex) {
                return slot;
            }
        }
        return null;
    }

    public @Nullable BookmarksGridSlot getSlotMouseOver(double mouseX, double mouseY, int page) {
        final int slotIndex = this.geometry.getSlotIndex(mouseX, mouseY);
        return slotIndex == -1 ? null : getSlotBySlotIndex(page, slotIndex);
    }

    /** The item shown for an item index (including shadow items of collapsed recipes), or null. */
    public @Nullable ChainItem getCalculatedItem(int itemIndex) {
        final BookmarkChainItem item = this.calculatedItems.get(itemIndex);
        return item != null ? item.getItem() : null;
    }

    public int getAbsoluteSlotIndex(int itemIndex) {
        return this.itemToSlot.getOrDefault(itemIndex, -1);
    }

    /** Item indexes in display order. */
    public List<Integer> getSortedItems() {
        return new ArrayList<>(this.slotToItem.values());
    }

    /** First (or last) item index of a page row, or {@link Integer#MIN_VALUE}. */
    public int getRowItemIndex(int rowIndex, boolean firstItemIndex, int page) {
        final int columns = this.geometry.columns;
        final int absoluteSlotIndex = (page * this.geometry.rows + rowIndex) * columns
                + (firstItemIndex ? 0 : columns - 1);

        for (int index = 0; index < columns; index++) {
            final Integer itemIndex = this.slotToItem.get(absoluteSlotIndex + index * (firstItemIndex ? 1 : -1));
            if (itemIndex != null) {
                return itemIndex;
            }
        }

        return Integer.MIN_VALUE;
    }

    /** Last used row of a page (page-local). */
    public int getLastRowIndex(int page) {
        final List<BookmarksGridSlot> slots = getSlots(page);
        final int columns = this.geometry.columns;

        return columns > 0 && !slots.isEmpty()
                ? (int) Math.ceil((float) (slots.get(slots.size() - 1).slotIndex + 1) / columns) - 1
                : 0;
    }

    /**
     * Page row under the mouse, or -1. With {@code groupPanel} only the group gutter left of the grid counts,
     * otherwise only valid slots of the grid.
     */
    public int getHoveredRowIndex(double mouseX, double mouseY, boolean groupPanel, int page) {
        final int leftBorder = this.geometry.x;
        final int r = this.geometry.getRowIndex(mouseY);

        if (this.grid.isEmpty() || r < 0 || r > getLastRowIndex(page)) {
            return -1;
        }

        if (groupPanel) {
            return mouseX >= leftBorder - GridGeometry.GROUP_PANEL_WIDTH && mouseX < leftBorder ? r : -1;
        }

        final int column = this.geometry.getColumnIndex(mouseX);

        if (column >= 0 && column < this.geometry.columns
                && !this.geometry.isInvalidSlot(this.geometry.columns * r + column)) {
            return r;
        }

        return -1;
    }

}
