package com.emicraftingtree.bookmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.ChainStack;
import com.emicraftingtree.chain.ContainerResolver;
import com.emicraftingtree.chain.RecipeChainMath;
import com.emicraftingtree.chain.RecipeKey;

/**
 * All bookmarks and their groups (port of NEI's BookmarkGrid). Bookmarks are a flat list of items, each tagged with
 * a group id; a bookmarked recipe is stored as its result items followed by its ingredient items. The on-screen
 * arrangement is computed per screen geometry by {@link GridLayout}.
 */
public class BookmarkGrid {

    public static final int DEFAULT_GROUP_ID = 0;
    /** Every recipe and item on its own row; new groups take the default group's mode. */
    public static final BookmarkViewMode DEFAULT_VIEW_MODE = BookmarkViewMode.TODO_LIST;
    public static final float SCALE_SPEED = 0.1f;
    private static final int MAX_CACHED_LAYOUTS = 4;

    protected List<ChainItem> bookmarkItems = new ArrayList<>();
    protected final Map<Integer, BookmarkGroup> groups = new HashMap<>();
    public final WeakHashMap<ChainItem, Float> animation = new WeakHashMap<>();

    private final ContainerResolver containers;
    private final Map<GridGeometry, GridLayout> layouts = new LinkedHashMap<>(8, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<GridGeometry, GridLayout> eldest) {
            return size() > MAX_CACHED_LAYOUTS;
        }
    };

    /** The page shown when this grid is the active namespace again. */
    public int page = 0;

    private boolean isDirty = false;
    private int revision = 0;
    private Runnable changeListener = () -> {};

    public BookmarkGrid(ContainerResolver containers) {
        this.containers = containers;
        this.groups.put(DEFAULT_GROUP_ID, new BookmarkGroup(DEFAULT_VIEW_MODE));
    }

    public void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener;
    }

    public ContainerResolver containers() {
        return this.containers;
    }

    /** Bumped on every change, so derived data (layouts, tooltips) knows when to recompute. */
    public int revision() {
        return this.revision;
    }

    public BookmarkViewMode getViewMode(int groupId) {
        return this.groups.get(groupId).viewMode;
    }

    public void setViewMode(int groupId, BookmarkViewMode mode) {
        if (this.groups.get(groupId).viewMode != mode) {
            this.groups.get(groupId).viewMode = mode;
            onItemsChanged();
        }
    }

    public void toggleViewMode(int groupId) {
        this.groups.get(groupId).toggleViewMode();
        onItemsChanged();
    }

    public void toggleCollapsedState(int groupId) {
        this.groups.get(groupId).toggleCollapsedState();
        onItemsChanged();
    }

    public void toggleCollapsedRecipe(int groupId, RecipeKey recipeId) {
        ensureClean();
        final BookmarkGroup group = getGroup(groupId);
        final RecipeKey collapsedRecipeId = group.crafting.recipeRelations.entrySet().stream()
                .filter(entry -> entry.getValue().contains(recipeId)).map(Map.Entry::getKey).findAny()
                .orElse(recipeId);

        group.toggleCollapsedRecipe(collapsedRecipeId);

        if (group.crafting.recipeInMiddle.contains(collapsedRecipeId)) {

            if (group.collapsedRecipes.contains(collapsedRecipeId)) {

                for (ChainItem item : this.bookmarkItems) {
                    if (item.groupId == groupId && collapsedRecipeId.equals(item.recipe) && item.multiplier == 1) {
                        item.multiplier = 0;
                    }
                }

            } else {

                for (ChainItem item : this.bookmarkItems) {
                    if (item.groupId == groupId && collapsedRecipeId.equals(item.recipe) && item.multiplier == 0) {
                        item.multiplier = 1;
                    }
                }

            }

        }

        onItemsChanged();
    }

    public boolean isCraftingMode(int groupId) {
        final BookmarkGroup group = this.groups.get(groupId);
        return group != null && group.crafting != null;
    }

    public void setCraftingMode(int groupId, boolean on) {
        this.groups.get(groupId).setCraftingMode(on);
        onItemsChanged();
    }

    public void toggleCraftingMode(int groupId) {
        this.groups.get(groupId).toggleCraftingMode();
        onItemsChanged();
    }

    public BookmarkGroup getGroup(int groupId) {
        return this.groups.get(groupId);
    }

    public Map<Integer, BookmarkGroup> getGroups() {
        return this.groups;
    }

    public int addGroup(BookmarkGroup group) {
        final int groupId = getNextGroupId();
        this.groups.put(groupId, group);
        return groupId;
    }

    /** Puts a group under a known id (loading). */
    public void putGroup(int groupId, BookmarkGroup group) {
        this.groups.put(groupId, group);
    }

    public int getNextGroupId() {
        final int fromItems = this.bookmarkItems.stream().mapToInt(item -> item.groupId).max()
                .orElse(DEFAULT_GROUP_ID);
        final int fromGroups = this.groups.keySet().stream().mapToInt(Integer::intValue).max()
                .orElse(DEFAULT_GROUP_ID);
        return Math.max(fromItems, fromGroups) + 1;
    }

    public int size() {
        return this.bookmarkItems.size();
    }

    public boolean isEmpty() {
        return this.bookmarkItems.isEmpty();
    }

    public List<ChainItem> getBookmarkItems() {
        ensureClean();
        return Collections.unmodifiableList(this.bookmarkItems);
    }

    /** Applies pending changes: removes duplicates, keeps group members together and refreshes chain groups. */
    public void ensureClean() {
        if (this.isDirty) {
            this.isDirty = false;
            this.bookmarkItems = new ArrayList<>(new LinkedHashSet<>(this.bookmarkItems));
            sortIngredients();
        }
    }

    /** The arrangement of the bookmarks for a screen geometry. */
    public GridLayout getLayout(GridGeometry geometry) {
        ensureClean();
        GridLayout layout = this.layouts.get(geometry);

        if (layout == null) {
            layout = new GridLayout(this, geometry);
            layout.generate();
            this.layouts.put(geometry, layout);
        }

        return layout;
    }

    /**
     * NEI's sortIngredients: groups are kept together, recipes of to-do lists and chains are kept together (results
     * first), a recipe missing its results or its ingredients turns into plain items, and chains are recalculated.
     * Done in a few passes over the whole list, since thousands of bookmarks are common.
     */
    private void sortIngredients() {
        groupItemsTogether();
        sortItemsInsideGroups();

        final Map<Integer, Map<RecipeKey, Integer>> recipeStates = new HashMap<>();

        for (ChainItem item : this.bookmarkItems) {
            if (item.recipe == null && item.type != ChainItem.Type.ITEM) {
                item.type = ChainItem.Type.ITEM;
            } else if (item.recipe != null && item.type != ChainItem.Type.ITEM
                    && this.groups.containsKey(item.groupId)) {
                recipeStates.computeIfAbsent(item.groupId, k -> new HashMap<>()).merge(item.recipe,
                        item.type == ChainItem.Type.INGREDIENT ? 1 : 2, (a, b) -> a | b);
            }
        }

        final Map<Integer, Map<Integer, ChainItem>> chainItems = new HashMap<>();

        for (int itemIndex = 0; itemIndex < this.bookmarkItems.size(); itemIndex++) {
            final ChainItem item = this.bookmarkItems.get(itemIndex);
            final BookmarkGroup group = this.groups.get(item.groupId);

            if (group == null) {
                continue;
            }

            final Map<RecipeKey, Integer> recipeState = recipeStates.get(item.groupId);

            if (item.type != ChainItem.Type.ITEM
                    && (recipeState == null || recipeState.getOrDefault(item.recipe, 0) != 3)) {

                if (item.type == ChainItem.Type.INGREDIENT) {
                    item.recipe = null;
                }

                if (recipeState != null && item.recipe != null) {
                    recipeState.remove(item.recipe);
                }

                item.type = ChainItem.Type.ITEM;
            }

            if (group.crafting != null) {
                chainItems.computeIfAbsent(item.groupId, k -> new HashMap<>()).put(itemIndex, item.copy());
            }
        }

        for (Entry<Integer, BookmarkGroup> entry : this.groups.entrySet()) {
            final BookmarkGroup group = entry.getValue();
            final Map<RecipeKey, Integer> recipeState = recipeStates.getOrDefault(entry.getKey(), Map.of());

            group.collapsedRecipes.removeIf(recipeId -> !recipeState.containsKey(recipeId));

            if (group.crafting != null) {
                group.crafting.refresh(chainItems.getOrDefault(entry.getKey(), new HashMap<>()),
                        group.collapsedRecipes, this.containers);
            }
        }
    }

    /** Every group's items move up to where the group first appears. */
    private void groupItemsTogether() {
        final Map<Integer, List<ChainItem>> groupItems = new HashMap<>();

        for (ChainItem item : this.bookmarkItems) {
            if (item.groupId != DEFAULT_GROUP_ID) {
                groupItems.computeIfAbsent(item.groupId, k -> new ArrayList<>()).add(item);
            }
        }

        if (groupItems.isEmpty()) {
            return;
        }

        final List<ChainItem> sorted = new ArrayList<>(this.bookmarkItems.size());

        for (ChainItem item : this.bookmarkItems) {
            if (item.groupId == DEFAULT_GROUP_ID) {
                sorted.add(item);
            } else {
                final List<ChainItem> items = groupItems.remove(item.groupId);

                if (items != null) {
                    sorted.addAll(items);
                }
            }
        }

        replaceItems(sorted);
    }

    /**
     * In to-do lists and chains, each recipe moves up to where it first appears: its results first, then its
     * ingredients.
     */
    private void sortItemsInsideGroups() {
        final Map<Integer, Map<RecipeKey, List<ChainItem>>> recipeItems = new HashMap<>();

        for (ChainItem item : this.bookmarkItems) {
            if (item.recipe != null && isSortedByRecipe(item.groupId)) {
                recipeItems.computeIfAbsent(item.groupId, k -> new HashMap<>())
                        .computeIfAbsent(item.recipe, k -> new ArrayList<>()).add(item);
            }
        }

        if (recipeItems.isEmpty()) {
            return;
        }

        final List<ChainItem> sorted = new ArrayList<>(this.bookmarkItems.size());

        for (ChainItem item : this.bookmarkItems) {
            final Map<RecipeKey, List<ChainItem>> recipes = item.recipe != null ? recipeItems.get(item.groupId) : null;

            if (recipes == null) {
                sorted.add(item);
                continue;
            }

            final List<ChainItem> items = recipes.remove(item.recipe);

            if (items != null) {
                for (ChainItem recipeItem : items) {
                    if (recipeItem.type != ChainItem.Type.INGREDIENT) {
                        sorted.add(recipeItem);
                    }
                }

                for (ChainItem recipeItem : items) {
                    if (recipeItem.type == ChainItem.Type.INGREDIENT) {
                        sorted.add(recipeItem);
                    }
                }
            }
        }

        replaceItems(sorted);
    }

    private boolean isSortedByRecipe(int groupId) {
        final BookmarkGroup group = this.groups.get(groupId);
        return group != null && (group.viewMode == BookmarkViewMode.TODO_LIST || group.crafting != null);
    }

    private void replaceItems(List<ChainItem> items) {
        this.bookmarkItems.clear();
        this.bookmarkItems.addAll(items);
    }

    public void onItemsChanged() {
        this.isDirty = true;
        this.revision++;
        this.layouts.clear();
        this.changeListener.run();
    }

    /** Re-arranges without a data change (drag state changed). */
    public void onGridChanged() {
        this.layouts.clear();
    }

    public Map<Integer, ChainItem> createChainItems(int groupId) {
        final Map<Integer, ChainItem> chainItems = new HashMap<>();

        for (int itemIndex = 0; itemIndex < this.bookmarkItems.size(); itemIndex++) {
            final ChainItem item = this.bookmarkItems.get(itemIndex);
            if (item.groupId == groupId) {
                chainItems.put(itemIndex, item.copy());
            }
        }

        return chainItems;
    }

    public @Nullable RecipeChainMath createRecipeChainMath(int groupId) {
        return createRecipeChainMath(groupId, null);
    }

    /** The chain math of a group, or of one recipe of a crafting group together with the recipes it collapses. */
    public @Nullable RecipeChainMath createRecipeChainMath(int groupId, @Nullable RecipeKey recipeId) {
        ensureClean();
        final BookmarkGroup group = getGroup(groupId);

        if (group == null) {
            return null;
        }

        final List<ChainItem> chainItems = new ArrayList<>();
        final Set<RecipeKey> collapsedRecipes = group.crafting != null && recipeId == null ? group.collapsedRecipes
                : Collections.emptySet();
        Set<RecipeKey> recipeRelations = null;

        if (group.collapsed) {
            recipeId = null;
        }

        if (group.crafting != null && recipeId != null) {
            for (Map.Entry<RecipeKey, Set<RecipeKey>> entry : group.crafting.recipeRelations.entrySet()) {
                if (entry.getValue().contains(recipeId)) {
                    recipeId = entry.getKey();
                    break;
                }
            }
        }

        if (group.crafting != null && group.crafting.recipeRelations.containsKey(recipeId)) {
            recipeRelations = group.crafting.recipeRelations.get(recipeId);
        } else if (recipeId != null) {
            recipeRelations = new HashSet<>(Arrays.asList(recipeId));
        }

        for (int itemIndex = 0; itemIndex < this.bookmarkItems.size(); itemIndex++) {
            final ChainItem item = this.bookmarkItems.get(itemIndex);
            if (item.groupId == groupId && (recipeRelations == null || recipeRelations.contains(item.recipe))) {

                if (recipeId != null && group.crafting != null
                        && group.crafting.calculatedItems.containsKey(itemIndex)
                        && recipeId.equals(item.recipe)) {
                    chainItems.add(
                            item.copyWithMultiplier(
                                    group.crafting.calculatedItems.get(itemIndex).getCalculatedMultiplier()));
                } else {
                    chainItems.add(item.copy());
                }

            }
        }

        if (group.crafting == null) {
            for (ChainItem item : chainItems) {
                item.type = ChainItem.Type.ITEM;
                item.recipe = null;
            }
        }

        return new RecipeChainMath(chainItems, collapsedRecipes, this.containers);
    }

    public void createGroup(GroupingItem groupingItem, GridLayout layout) {
        final Map<Integer, List<ChainItem>> rowItems = collectItemsToRows(layout);
        final Map<Integer, Integer> newGroups = new HashMap<>();
        boolean change = false;

        for (Map.Entry<Integer, Integer> entry : groupingItem.prepareGroups(layout.rowToGroupId).entrySet()) {
            if (!entry.getValue().equals(layout.rowToGroupId.get(entry.getKey()))) {
                int groupId = entry.getValue();

                if (groupId == Integer.MIN_VALUE) {
                    groupId = newGroups.computeIfAbsent(
                            groupId,
                            grId -> addGroup(new BookmarkGroup(getViewMode(DEFAULT_GROUP_ID))));
                } else if (groupId < 0) {
                    groupId = newGroups
                            .computeIfAbsent(groupId, grId -> addGroup(new BookmarkGroup(getViewMode(Math.abs(grId)))));
                }

                for (ChainItem item : rowItems.getOrDefault(entry.getKey(), Collections.emptyList())) {
                    item.groupId = groupId;
                }

                change = true;
            }
        }

        if (change) {
            this.bookmarkItems = rowItems.values().stream().flatMap(List::stream).collect(Collectors.toList());

            final Set<Integer> usedGroupIds = new HashSet<>();
            for (ChainItem item : this.bookmarkItems) {
                usedGroupIds.add(item.groupId);
            }

            this.groups.keySet().removeIf(k -> k != DEFAULT_GROUP_ID && !usedGroupIds.contains(k));

            onItemsChanged();
        }

    }

    private Map<Integer, List<ChainItem>> collectItemsToRows(GridLayout layout) {
        final Map<Integer, List<ChainItem>> results = new LinkedHashMap<>();
        final int columns = layout.geometry.columns;
        final int totalRows = (int) Math.ceil(layout.maxAbsoluteSlotIndex / (float) columns);
        final List<ChainItem> allItems = new ArrayList<>(this.bookmarkItems);

        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            int itemIndex = Integer.MIN_VALUE;

            for (int index = 0; index < columns; index++) {
                if (layout.slotToItem.containsKey(rowIndex * columns + index)) {
                    itemIndex = layout.slotToItem.get(rowIndex * columns + index);
                    break;
                }
            }

            if (itemIndex != Integer.MIN_VALUE) {
                final ChainItem item = layout.getCalculatedItem(itemIndex);
                final BookmarkGroup group = getGroup(item.groupId);
                final List<ChainItem> items = new ArrayList<>();

                if (group.collapsed || group.crafting != null && group.viewMode == BookmarkViewMode.DEFAULT) {

                    for (ChainItem itm : allItems) {
                        if (itm.groupId == item.groupId) {
                            items.add(itm);
                        }
                    }

                } else if (item.recipe != null && group.crafting != null
                        && group.crafting.itemToRecipe.containsKey(itemIndex)) {
                    final Set<RecipeKey> recipeRelations = group.crafting.recipeRelations
                            .getOrDefault(item.recipe, Collections.singleton(item.recipe));

                    for (ChainItem itm : allItems) {
                        if (itm.groupId == item.groupId && recipeRelations.contains(itm.recipe)) {
                            items.add(itm);
                        }
                    }

                } else {

                    for (int index = 0; index < columns; index++) {
                        if (layout.slotToItem.containsKey(rowIndex * columns + index)) {
                            final int idx = layout.slotToItem.get(rowIndex * columns + index);
                            if (idx >= 0 && idx < this.bookmarkItems.size()) {
                                final ChainItem itm = this.bookmarkItems.get(idx);
                                if (allItems.contains(itm)) {
                                    items.add(itm);
                                }
                            }
                        }
                    }

                }

                results.put(rowIndex, items);
                allItems.removeAll(items);
            }

        }

        results.put(totalRows, allItems);

        return results;
    }

    public void removeGroup(int groupId) {
        this.bookmarkItems.removeIf(item -> item.groupId == groupId);

        if (groupId != DEFAULT_GROUP_ID) {
            this.groups.remove(groupId);
        }

        onItemsChanged();
    }

    /** Moves a dragged group so it starts at the given row of the page. */
    public void moveGroup(SortableGroup sortableGroup, int overRowIndex, GridLayout layout, int page) {
        final int columns = layout.geometry.columns;
        int absoluteRowIndex = page * layout.geometry.rows + overRowIndex;
        int absoluteSlotIndex = absoluteRowIndex * columns;
        int overGroupId = layout.getRowGroupId(overRowIndex, page);

        // move the group into this grid (it was dragged here from another namespace)
        if (this != sortableGroup.grid) {
            final BookmarkGroup sourceGroup = sortableGroup.grid.getGroup(sortableGroup.groupId);
            final List<ChainItem> itemsToMove = sortableGroup.getBookmarkItems();

            sortableGroup.grid.removeGroup(sortableGroup.groupId);

            final int groupId = this.addGroup(sourceGroup);
            for (ChainItem item : itemsToMove) {
                item.groupId = groupId;
                this.bookmarkItems.add(item);
            }

            sortableGroup.update(this, groupId);
            this.onItemsChanged();
            layout = getLayout(layout.geometry);
            overGroupId = layout.getRowGroupId(overRowIndex, page);
        }

        if (sortableGroup.groupId == overGroupId) {
            return;
        }

        boolean mouseDown = false;

        for (int rowId = 0; rowId < absoluteRowIndex && !mouseDown; rowId++) {
            if (layout.rowToGroupId.getOrDefault(rowId, DEFAULT_GROUP_ID) == sortableGroup.groupId) {
                mouseDown = true;
            }
        }

        if (mouseDown) {
            absoluteRowIndex += 1;
            absoluteSlotIndex += columns;
        }

        if (mouseDown && absoluteSlotIndex >= layout.maxAbsoluteSlotIndex) {
            final int itemIndex = layout.slotToItem.getOrDefault(layout.maxAbsoluteSlotIndex - 1, Integer.MIN_VALUE);

            if (itemIndex == Integer.MIN_VALUE || layout.getCalculatedItem(itemIndex).groupId == sortableGroup.groupId) {
                return;
            }

            moveItems(null, sortableGroup.getBookmarkItems());
        } else if (layout.rowToGroupId.containsKey(absoluteRowIndex)) {
            overGroupId = layout.rowToGroupId.get(absoluteRowIndex);

            if (overGroupId != DEFAULT_GROUP_ID
                    && overGroupId == layout.rowToGroupId.getOrDefault(absoluteRowIndex - 1, DEFAULT_GROUP_ID)) {
                return;
            }

            final int itemIndex = layout.slotToItem.getOrDefault(absoluteSlotIndex, Integer.MIN_VALUE);
            ChainItem bookmarkItem = layout.getCalculatedItem(itemIndex);

            if (bookmarkItem == null) {
                return;
            }

            if (bookmarkItem.groupId != DEFAULT_GROUP_ID) {
                for (ChainItem item : this.bookmarkItems) {
                    if (item.groupId == bookmarkItem.groupId) {
                        bookmarkItem = item;
                        break;
                    }
                }
            }

            moveItems(bookmarkItem, sortableGroup.getBookmarkItems());
        }

    }

    /** Index of a bookmark in the group with the same stack type and recipe, or -1. */
    public int indexOf(int groupId, ChainStack stack, @Nullable RecipeKey recipeId, boolean ingredient) {

        for (int itemIndex = 0; itemIndex < this.bookmarkItems.size(); itemIndex++) {
            final ChainItem item = this.bookmarkItems.get(itemIndex);
            if (item.groupId == groupId && ingredient == (item.type == ChainItem.Type.INGREDIENT)
                    && (recipeId == item.recipe || recipeId != null && recipeId.equals(item.recipe))
                    && stack.sameType(item.stack)) {
                return itemIndex;
            }
        }

        return -1;
    }

    public @Nullable ChainItem getBookmarkItem(int itemIndex) {

        if (itemIndex < 0 || itemIndex >= this.bookmarkItems.size()) {
            return null;
        }

        return this.bookmarkItems.get(itemIndex);
    }

    public void replaceBookmarkItem(int itemIndex, ChainItem newItem) {

        if (itemIndex < 0 || itemIndex >= this.bookmarkItems.size()) {
            return;
        }

        this.bookmarkItems.set(itemIndex, newItem);
        onItemsChanged();
    }

    public void addItem(ChainItem item, boolean animate) {
        this.bookmarkItems.add(item);

        if (animate) {
            this.animation.put(item, 0f);
        }

        onItemsChanged();
    }

    /** Adds the items of a bookmarked recipe (results first, then ingredients). */
    public void addRecipe(List<ChainItem> recipeItems, int groupId) {

        for (ChainItem item : recipeItems) {
            if (item.type == ChainItem.Type.RESULT) {
                item.groupId = groupId;
                addItem(item, true);
            }
        }

        for (ChainItem item : recipeItems) {
            if (item.type != ChainItem.Type.RESULT) {
                item.groupId = groupId;
                addItem(item, true);
            }
        }
    }

    /** Loads items without change notifications or animation. */
    public void loadItems(List<ChainItem> items) {
        this.bookmarkItems = new ArrayList<>(items);
        this.isDirty = true;
        this.revision++;
        this.layouts.clear();
    }

    public void clear() {
        this.bookmarkItems.clear();
        this.groups.clear();
        this.groups.put(DEFAULT_GROUP_ID, new BookmarkGroup(DEFAULT_VIEW_MODE));
        this.isDirty = true;
        this.revision++;
        this.layouts.clear();
    }

    public void removeRecipe(int itemIndex, boolean removeFullRecipe) {
        final ChainItem item = getBookmarkItem(itemIndex);

        if (item == null) {
            return;
        }

        if (!removeFullRecipe && item.recipe != null && item.type == ChainItem.Type.RESULT) {
            removeFullRecipe = this.bookmarkItems.stream()
                    .noneMatch(m -> m.type == ChainItem.Type.RESULT && !m.equals(item) && item.equalsRecipe(m));
        }

        if (item.recipe != null && item.type != ChainItem.Type.ITEM && removeFullRecipe) {
            removeRecipe(item.recipe, item.groupId);
        } else if (itemIndex >= 0) {
            this.bookmarkItems.remove(itemIndex);
            onItemsChanged();
        }
    }

    public boolean removeRecipe(RecipeKey recipeId, int groupId) {

        if (this.bookmarkItems.removeIf(item -> item.equalsRecipe(recipeId, groupId))) {
            onItemsChanged();
            return true;
        }

        return false;
    }

    public boolean existsRecipe(@Nullable RecipeKey recipeId, int groupId) {
        return recipeId != null && this.bookmarkItems.stream()
                .anyMatch(item -> item.type != ChainItem.Type.ITEM && item.equalsRecipe(recipeId, groupId));
    }

    /** Moves the dragged items next to the row of another group (item dragged across groups in a to-do list). */
    public void moveItem(SortableItem sortableItem, int overRowIndex, int overGroupId, boolean moveAfter,
            GridLayout layout, int page) {
        final BookmarkGroup overGroup = getGroup(overGroupId);
        final int columns = layout.geometry.columns;
        int absoluteSlotIndex = (page * layout.geometry.rows + overRowIndex) * columns;

        if (sortableItem.isCollapsedRecipe && sortableItem.bookmarkItem.groupId != overGroupId || overGroup.collapsed
                || overGroup.viewMode == BookmarkViewMode.DEFAULT) {
            return;
        }

        if (sortableItem.bookmarkItem.groupId != overGroupId) {

            for (ChainItem item : this.bookmarkItems) {
                if (sortableItem.containsItem(item)) {
                    item.groupId = overGroupId;
                }
            }

            sortableItem.bookmarkItem.groupId = overGroupId;
            onItemsChanged();
            layout = sortableItem.refreshLayout();
        }

        if (moveAfter) {
            absoluteSlotIndex += columns;
        }

        if (moveAfter && absoluteSlotIndex >= layout.maxAbsoluteSlotIndex) {
            final int itemIndex = layout.slotToItem.getOrDefault(layout.maxAbsoluteSlotIndex - 1, Integer.MIN_VALUE);

            if (itemIndex == Integer.MIN_VALUE || sortableItem.items.contains(itemIndex)) {
                return;
            }

            moveItems(null, sortableItem.getBookmarkItems());
        } else if (layout.slotToItem.containsKey(absoluteSlotIndex)) {
            final int itemIndex = layout.slotToItem.get(absoluteSlotIndex);
            final List<Integer> sortedIndexes = layout.getSortedItems();
            ChainItem bookmarkItem = layout.getCalculatedItem(itemIndex);
            final BookmarkGroup group = getGroup(bookmarkItem.groupId);
            final int sortIndex = sortedIndexes.indexOf(itemIndex);

            if (sortIndex == -1 || sortableItem.items.contains(itemIndex)
                    || sortIndex > 0 && sortableItem.items.contains(sortedIndexes.get(sortIndex - 1))) {
                return;
            }

            if (group.crafting != null && sortIndex > 0) {
                final RecipeKey recipeId = group.crafting.itemToRecipe.getOrDefault(itemIndex, bookmarkItem.recipe);

                if (recipeId != null) {
                    if (recipeId.equals(layout.itemToRecipe.get(sortedIndexes.get(sortIndex - 1)))) {
                        return;
                    }

                    for (ChainItem item : this.bookmarkItems) {
                        if (item.groupId == bookmarkItem.groupId && recipeId.equals(item.recipe)) {
                            bookmarkItem = item;
                            break;
                        }
                    }
                }
            }

            moveItems(bookmarkItem, sortableItem.getBookmarkItems());
        }

    }

    /** Moves the dragged items before (or after) the item at {@code itemIndex} of the same group. */
    public void moveItem(SortableItem sortableItem, int itemIndex, GridLayout layout) {
        final List<Integer> sortedIndexes = layout.getSortedItems();
        int sortIndex = sortedIndexes.indexOf(itemIndex);
        final boolean moveAfter = sortIndex > sortedIndexes.indexOf(sortableItem.getItemIndex());
        final BookmarkGroup group = getGroup(sortableItem.bookmarkItem.groupId);
        ChainItem bookmarkItem = layout.getCalculatedItem(itemIndex);

        if (bookmarkItem == null || sortIndex == -1 || sortableItem.items.contains(itemIndex) || group.collapsed) {
            return;
        }

        if (moveAfter) {
            sortIndex = findNextBookmarkItem(itemIndex, layout);

            if (sortIndex == -1) {
                return;
            }

            if (sortIndex < sortedIndexes.size()) {
                bookmarkItem = layout.getCalculatedItem(sortedIndexes.get(sortIndex));
            } else {
                bookmarkItem = null;
            }
        }

        if (bookmarkItem != null && (group.crafting != null && group.viewMode == BookmarkViewMode.DEFAULT
                || group.viewMode == BookmarkViewMode.TODO_LIST
                        && sortableItem.bookmarkItem.type == ChainItem.Type.RESULT)) {
            ChainItem prevBookmarkItem = sortIndex > 1 ? layout.getCalculatedItem(sortedIndexes.get(sortIndex - 1))
                    : null;

            while (sortIndex > 0 && prevBookmarkItem != null
                    && prevBookmarkItem.type != ChainItem.Type.ITEM
                    && bookmarkItem.equalsRecipe(prevBookmarkItem)) {
                prevBookmarkItem = sortIndex > 1 ? layout.getCalculatedItem(sortedIndexes.get(sortIndex - 1)) : null;
                sortIndex--;
            }

            bookmarkItem = layout.getCalculatedItem(sortedIndexes.get(sortIndex));
        }

        moveItems(bookmarkItem, sortableItem.getBookmarkItems());
    }

    private int findNextBookmarkItem(int itemIndex, GridLayout layout) {
        final List<Integer> sortedIndexes = layout.getSortedItems();
        final int sortIndex = sortedIndexes.indexOf(itemIndex);
        final ChainItem target = layout.getCalculatedItem(itemIndex);
        final BookmarkGroup group = getGroup(target.groupId);
        final int columns = layout.geometry.columns;
        int nextSortIndex = sortIndex + 1;

        if (group.viewMode == BookmarkViewMode.TODO_LIST) {
            final int rowId = layout.itemToSlot.get(itemIndex) / columns;

            while (nextSortIndex < sortedIndexes.size()
                    && (layout.itemToSlot.get(sortedIndexes.get(nextSortIndex)) / columns) == rowId) {
                nextSortIndex++;
            }
        }

        if (nextSortIndex < sortedIndexes.size()) {
            final int nextItemIndex = sortedIndexes.get(nextSortIndex);

            if (group.viewMode == BookmarkViewMode.TODO_LIST && target.type == ChainItem.Type.RESULT
                    && target.equalsRecipe(layout.getCalculatedItem(nextItemIndex))) {
                return -1;
            }

            if (group.crafting != null) {
                final RecipeKey recipeId = group.crafting.itemToRecipe.get(itemIndex);

                if (recipeId != null && recipeId.equals(layout.itemToRecipe.get(nextItemIndex))) {
                    return -1;
                }
            }

            return nextSortIndex;
        }

        return Integer.MAX_VALUE;
    }

    private void moveItems(@Nullable ChainItem bookmarkItem, List<ChainItem> items) {
        this.bookmarkItems.removeAll(items);
        int index = bookmarkItem == null ? this.bookmarkItems.size() : this.bookmarkItems.indexOf(bookmarkItem);

        if (index < 0) {
            index = this.bookmarkItems.size();
        }

        this.bookmarkItems.addAll(index, items);
        onItemsChanged();
    }

    public void shiftGroupAmount(int targetGroupId, long shift) {
        ensureClean();
        final BookmarkGroup group = getGroup(targetGroupId);

        if (group.crafting == null) {
            final List<ChainItem> items = new ArrayList<>();
            final Set<Long> sizes = new HashSet<>();

            for (ChainItem item : this.bookmarkItems) {
                if (item.groupId == targetGroupId && item.getAmount() > 0) {
                    sizes.add(Math.max(item.getAmount(), item.getAmount(1)));
                    items.add(item);
                }
            }

            final long gcd = sizes.stream().reduce(0L, BookmarkGrid::gcd);
            long multiplier = Integer.MAX_VALUE;

            for (ChainItem item : items) {
                final long amount = item.getAmount();
                final long customFactor = (amount + gcd - 1) / gcd;
                final long customMultiplier = customFactor > 0 ? ((amount + customFactor - 1) / customFactor) : 0;
                multiplier = Math.min(shiftMultiplier(customMultiplier, shift, 1), multiplier);
            }

            for (ChainItem item : items) {
                final long amount = item.getAmount();
                final long customFactor = (amount + gcd - 1) / gcd;
                item.multiplier = item.getMultiplierFromAmount(customFactor * multiplier);
            }
        } else {
            final Map<RecipeKey, Long> recipeMultipliers = new HashMap<>();
            final Set<RecipeKey> outputRecipes = group.crafting.outputRecipes;
            final List<ChainItem> items = new ArrayList<>();

            for (ChainItem item : this.bookmarkItems) {
                if (!item.emptyFactor()
                        && outputRecipes.stream().anyMatch(recipeId -> item.equalsRecipe(recipeId, targetGroupId))) {
                    final long multiplier = recipeMultipliers.getOrDefault(item.recipe, (long) Integer.MAX_VALUE);
                    final boolean recipeInMiddle = group.crafting.recipeInMiddle.contains(item.recipe);

                    recipeMultipliers.put(
                            item.recipe,
                            Math.min(
                                    shiftMultiplier(
                                            Math.max(recipeInMiddle ? 1 : 0, item.multiplier),
                                            shift,
                                            recipeInMiddle ? 1 : 0),
                                    multiplier));
                    items.add(item);
                }
            }

            for (ChainItem item : items) {
                item.multiplier = recipeMultipliers.getOrDefault(item.recipe, 0L);
            }
        }

        this.onItemsChanged();
    }

    public void shiftItemAmount(int targetItemIndex, long shift, GridLayout layout) {
        final ChainItem targetItem = layout.getCalculatedItem(targetItemIndex);

        if (targetItem == null) {
            return;
        }

        final BookmarkGroup group = getGroup(targetItem.groupId);

        if (group.collapsed) {
            shiftGroupAmount(targetItem.groupId, shift);
        } else if (targetItem.recipe != null && targetItem.type != ChainItem.Type.ITEM) {
            shiftRecipeAmount(
                    targetItem.groupId,
                    layout.itemToRecipe.getOrDefault(targetItemIndex, targetItem.recipe),
                    shift);
        } else {

            for (ChainItem item : this.bookmarkItems) {
                if (targetItem.equals(item)) {
                    item.multiplier = shiftMultiplier(item.multiplier, shift, 0);
                    break;
                }
            }

            this.onItemsChanged();
        }

    }

    public void shiftRecipeAmount(int targetGroupId, RecipeKey recipeId, long shift) {
        ensureClean();
        final BookmarkGroup group = getGroup(targetGroupId);
        final boolean recipeInMiddle = group != null && group.crafting != null
                && group.crafting.recipeInMiddle.contains(recipeId);
        long multiplier = Integer.MAX_VALUE;

        for (ChainItem item : this.bookmarkItems) {
            if (item.equalsRecipe(recipeId, targetGroupId) && !item.emptyFactor()) {
                multiplier = Math.min(
                        shiftMultiplier(
                                Math.max(recipeInMiddle ? 1 : 0, item.multiplier),
                                shift,
                                recipeInMiddle ? 1 : 0),
                        multiplier);
            }
        }

        for (ChainItem item : this.bookmarkItems) {
            if (item.equalsRecipe(recipeId, targetGroupId) && !item.emptyFactor()) {
                item.multiplier = multiplier;
            }
        }

        this.onItemsChanged();
    }

    static long shiftMultiplier(long multiplier, long shift, int minMultiplier) {
        final long currentMultiplier = ((multiplier + shift) / shift) * shift;
        return Math.min(
                Integer.MAX_VALUE,
                currentMultiplier <= 0 && multiplier > 1 ? 1 : Math.max(minMultiplier, currentMultiplier));
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

}
