package com.emicraftingtree.bookmark;

import static com.emicraftingtree.chain.RecipeChainMathTest.PLANK;
import static com.emicraftingtree.chain.RecipeChainMathTest.STICK;
import static com.emicraftingtree.chain.RecipeChainMathTest.TORCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.emicraftingtree.bookmark.RecipeChainDetails.BookmarkChainItem;
import com.emicraftingtree.bookmark.RecipeChainDetails.CalculatedType;
import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.ChainStack;
import com.emicraftingtree.chain.ContainerResolver;
import com.emicraftingtree.chain.RecipeChainMathTest;
import com.emicraftingtree.chain.RecipeKey;

class BookmarkGridTest {

    private static final GridGeometry GEOMETRY = new GridGeometry(10, 20, 5, 8, false,
            new int[] { 5, 5, 5, 5, 5, 5, 5, 5 });

    private static BookmarkGrid chainGrid(BookmarkViewMode viewMode, boolean crafting) {
        final BookmarkGrid grid = new BookmarkGrid(ContainerResolver.NONE);
        final int groupId = grid.addGroup(new BookmarkGroup(viewMode, crafting));
        grid.loadItems(RecipeChainMathTest.torchChain(groupId, 1));
        return grid;
    }

    private static String keyAt(GridLayout layout, int slot) {
        final Integer itemIndex = layout.slotToItem.get(slot);
        return itemIndex == null ? null : layout.getCalculatedItem(itemIndex).stack.key;
    }

    private static BookmarkChainItem calculated(BookmarkGrid grid, int groupId, String key, ChainItem.Type type) {
        final BookmarkGroup group = grid.getGroup(groupId);
        final List<ChainItem> items = grid.getBookmarkItems();

        for (int index = 0; index < items.size(); index++) {
            final ChainItem item = items.get(index);
            if (item.stack.key.equals(key) && item.type == type && group.crafting.calculatedItems.containsKey(index)) {
                return group.crafting.calculatedItems.get(index);
            }
        }

        throw new AssertionError("no calculated " + key);
    }

    @Test
    void craftingGroupCalculatesTheChain() {
        final BookmarkGrid grid = chainGrid(BookmarkViewMode.TODO_LIST, true);
        grid.ensureClean();
        final BookmarkGroup group = grid.getGroup(1);

        assertEquals(Set.of(TORCH), group.crafting.outputRecipes);
        assertEquals(Set.of(STICK, PLANK), group.crafting.recipeInMiddle);

        final BookmarkChainItem torch = calculated(grid, 1, "torch", ChainItem.Type.RESULT);
        assertEquals(CalculatedType.RESULT, torch.calculatedType);
        assertEquals(4, torch.getShiftAmount());

        final BookmarkChainItem stick = calculated(grid, 1, "stick", ChainItem.Type.RESULT);
        assertEquals(CalculatedType.REMAINDER, stick.calculatedType);
        assertEquals(3, stick.getShiftAmount());

        // the stick ingredient is crafted in the chain, coal is not
        assertEquals(0, calculated(grid, 1, "stick", ChainItem.Type.INGREDIENT).getShiftAmount());
        assertEquals(1, calculated(grid, 1, "coal", ChainItem.Type.INGREDIENT).getShiftAmount());
    }

    @Test
    void todoListPutsEachRecipeOnItsOwnRow() {
        final GridLayout layout = chainGrid(BookmarkViewMode.TODO_LIST, false).getLayout(GEOMETRY);

        assertEquals("torch", keyAt(layout, 0));
        assertEquals("coal", keyAt(layout, 1));
        assertEquals("stick", keyAt(layout, 2));
        assertEquals("stick", keyAt(layout, 5));
        assertEquals("plank", keyAt(layout, 6));
        assertEquals("plank", keyAt(layout, 10));
        assertEquals("log", keyAt(layout, 11));
        assertEquals(1, layout.getRowGroupId(0, 0));
        assertEquals(1, layout.getRowGroupId(2, 0));
    }

    @Test
    void defaultModePacksItemsAndGroupsStartOnANewRow() {
        final BookmarkGrid grid = new BookmarkGrid(ContainerResolver.NONE);
        final List<ChainItem> items = new ArrayList<>();
        items.add(ChainItem.ofItem(0, ChainStack.of("diamond", 1), 0));
        final int groupId = grid.addGroup(new BookmarkGroup(BookmarkViewMode.DEFAULT));
        items.addAll(RecipeChainMathTest.torchChain(groupId, 1));
        grid.loadItems(items);

        final GridLayout layout = grid.getLayout(GEOMETRY);

        assertEquals("diamond", keyAt(layout, 0));
        assertEquals(null, keyAt(layout, 1));
        // the group starts at the next row and fills it edge to edge
        assertEquals("torch", keyAt(layout, 5));
        assertEquals("coal", keyAt(layout, 6));
        assertEquals("plank", keyAt(layout, 10));
        assertEquals(0, layout.getRowGroupId(0, 0));
        assertEquals(groupId, layout.getRowGroupId(1, 0));
    }

    @Test
    void craftingChainInDefaultModeShowsOnlyResults() {
        final GridLayout layout = chainGrid(BookmarkViewMode.DEFAULT, true).getLayout(GEOMETRY);

        assertEquals("torch", keyAt(layout, 0));
        assertEquals("stick", keyAt(layout, 1));
        assertEquals("plank", keyAt(layout, 2));
        assertEquals(null, keyAt(layout, 3));
    }

    @Test
    void draggingInTheGutterCreatesAGroup() {
        final BookmarkGrid grid = new BookmarkGrid(ContainerResolver.NONE);
        grid.setViewMode(0, BookmarkViewMode.TODO_LIST);
        final List<ChainItem> items = new ArrayList<>(RecipeChainMathTest.torchChain(0, 1));
        items.add(ChainItem.ofItem(0, ChainStack.of("diamond", 1), 0));
        grid.loadItems(items);

        final GridLayout layout = grid.getLayout(GEOMETRY);
        final GroupingItem grouping = new GroupingItem(layout, Integer.MIN_VALUE, 0, 0);
        grouping.setEndPoint(1, 0);
        grid.createGroup(grouping, layout);

        final List<ChainItem> result = grid.getBookmarkItems();
        final int newGroup = result.get(0).groupId;

        assertNotEquals(0, newGroup);
        assertEquals(newGroup, result.stream().filter(item -> item.recipe != null && item.recipe.equals(STICK))
                .findFirst().get().groupId);
        assertEquals(0, result.stream().filter(item -> item.recipe != null && item.recipe.equals(PLANK))
                .findFirst().get().groupId);
        assertEquals(0, result.stream().filter(item -> item.stack.key.equals("diamond")).findFirst().get().groupId);
    }

    @Test
    void ctrlScrollChangesTheRecipeQuantity() {
        final BookmarkGrid grid = chainGrid(BookmarkViewMode.TODO_LIST, true);
        final GridLayout layout = grid.getLayout(GEOMETRY);

        grid.shiftItemAmount(layout.slotToItem.get(0), 1, layout);
        grid.ensureClean();

        assertEquals(2, grid.getBookmarkItems().get(0).multiplier);
        assertEquals(2, calculated(grid, 1, "coal", ChainItem.Type.INGREDIENT).getShiftAmount());

        grid.shiftGroupAmount(1, 64);
        grid.ensureClean();
        assertEquals(64, grid.getBookmarkItems().get(0).multiplier);
    }

    @Test
    void collapsedRecipeFoldsItsSubChain() {
        final BookmarkGrid grid = chainGrid(BookmarkViewMode.TODO_LIST, true);

        grid.toggleCollapsedRecipe(1, STICK);
        grid.ensureClean();
        final BookmarkGroup group = grid.getGroup(1);

        assertTrue(group.collapsedRecipes.contains(STICK));
        assertEquals(Set.of(STICK, PLANK), group.crafting.recipeRelations.get(STICK));

        final GridLayout layout = grid.getLayout(GEOMETRY);
        // the stick row now shows the folded sub-chain: stick result, then the log it needs in the end
        final List<String> row = new ArrayList<>();
        for (int slot = 5; slot < 10; slot++) {
            row.add(keyAt(layout, slot));
        }

        assertEquals("stick", row.get(0));
        assertTrue(row.contains("log"));
        assertFalse(row.contains("plank") && row.indexOf("plank") == 1 && !row.contains("log"));

        grid.toggleCollapsedRecipe(1, STICK);
        grid.ensureClean();
        assertTrue(group.collapsedRecipes.isEmpty());
    }

    @Test
    void removingARecipeKeepsTheRestOfTheGroup() {
        final BookmarkGrid grid = chainGrid(BookmarkViewMode.TODO_LIST, true);

        assertTrue(grid.removeRecipe(PLANK, 1));
        grid.ensureClean();

        assertTrue(grid.getBookmarkItems().stream().noneMatch(item -> PLANK.equals(item.recipe)));
        assertEquals(Set.of(STICK), grid.getGroup(1).crafting.recipeInMiddle);
    }

    /**
     * Shuffled bookmarks end up exactly as with NEI's sortIngredients (kept below as the reference): same order, same
     * plain items for incomplete recipes, same collapsed recipes kept.
     */
    @Test
    void sortingMatchesNei() {
        final Random random = new Random(42);

        for (int round = 0; round < 500; round++) {
            final BookmarkGrid grid = new BookmarkGrid(ContainerResolver.NONE);
            final int groups = 1 + random.nextInt(5);

            for (int group = 0; group < groups; group++) {
                grid.addGroup(new BookmarkGroup(
                        random.nextBoolean() ? BookmarkViewMode.TODO_LIST : BookmarkViewMode.DEFAULT,
                        random.nextInt(4) == 0));
            }

            final List<ChainItem> items = new ArrayList<>();

            for (int index = 0, count = 1 + random.nextInt(60); index < count; index++) {
                final int groupId = random.nextInt(groups + 1);
                final ChainStack stack = ChainStack.of("item" + index, 1);

                if (random.nextInt(3) == 0) {
                    items.add(ChainItem.ofItem(groupId, stack, 1));
                } else {
                    items.add(ChainItem.ofRecipe(groupId, stack, ChainItem.singlePermutation(stack),
                            RecipeKey.of("recipe" + random.nextInt(8), ""),
                            random.nextBoolean() ? ChainItem.Type.RESULT : ChainItem.Type.INGREDIENT, 1, 1));
                }
            }

            final Map<Integer, Set<RecipeKey>> collapsed = new HashMap<>();

            for (Map.Entry<Integer, BookmarkGroup> entry : grid.getGroups().entrySet()) {
                for (int recipe = 0; recipe < 8; recipe++) {
                    if (random.nextInt(4) == 0) {
                        entry.getValue().collapsedRecipes.add(RecipeKey.of("recipe" + recipe, ""));
                    }
                }
                collapsed.put(entry.getKey(), new java.util.HashSet<>(entry.getValue().collapsedRecipes));
            }

            final List<ChainItem> expected = neiSort(
                    items.stream().map(ChainItem::copy).collect(Collectors.toList()), grid, collapsed);
            grid.loadItems(items);
            final List<ChainItem> actual = grid.getBookmarkItems();

            assertEquals(describe(expected), describe(actual), "round " + round);

            for (Map.Entry<Integer, BookmarkGroup> entry : grid.getGroups().entrySet()) {
                assertEquals(collapsed.get(entry.getKey()), entry.getValue().collapsedRecipes, "round " + round);
            }
        }
    }

    private static List<String> describe(List<ChainItem> items) {
        return items.stream().map(item -> item.stack.key + " " + item.type + " " + item.recipe + " g" + item.groupId)
                .collect(Collectors.toList());
    }

    /** NEI's BookmarkGrid.sortIngredients without the chain refresh, as this mod had it before. */
    private static List<ChainItem> neiSort(List<ChainItem> list, BookmarkGrid grid,
            Map<Integer, Set<RecipeKey>> collapsed) {
        for (int itemIndex = 0; itemIndex < list.size(); itemIndex++) {
            final ChainItem item = list.get(itemIndex);

            if (item.groupId != BookmarkGrid.DEFAULT_GROUP_ID) {
                final List<ChainItem> items = list.stream().filter(itm -> itm.groupId == item.groupId)
                        .collect(Collectors.toList());
                list.removeAll(items);
                list.addAll(itemIndex, items);
                itemIndex += items.size() - 1;
            }
        }

        for (Map.Entry<Integer, BookmarkGroup> entry : grid.getGroups().entrySet()) {
            final Map<RecipeKey, Integer> recipeState = new HashMap<>();
            final int groupId = entry.getKey();

            if (entry.getValue().viewMode == BookmarkViewMode.TODO_LIST || entry.getValue().crafting != null) {
                final int size = list.size();
                int itemIndex = 0;

                while (itemIndex < size) {
                    final ChainItem item = list.get(itemIndex);

                    if (item.groupId == groupId && item.recipe != null) {
                        final Map<Integer, Integer> sortingRank = new HashMap<>();
                        final List<ChainItem> sortedItems = new ArrayList<>();
                        final List<Integer> indexes = new ArrayList<>();

                        for (int index = itemIndex; index < size; index++) {
                            if (item.equalsRecipe(list.get(index))) {
                                sortingRank.put(index, list.get(index).type == ChainItem.Type.INGREDIENT ? 1 : -1);
                                indexes.add(index);
                            }
                        }

                        indexes.sort((a, b) -> sortingRank.get(a) - sortingRank.get(b));

                        for (int index : indexes) {
                            sortedItems.add(list.get(index));
                        }

                        list.removeAll(sortedItems);
                        list.addAll(itemIndex, sortedItems);
                        itemIndex += sortedItems.size();
                    } else {
                        itemIndex++;
                    }
                }
            }

            for (ChainItem item : list) {
                if (item.recipe == null && item.type != ChainItem.Type.ITEM) {
                    item.type = ChainItem.Type.ITEM;
                } else if (item.groupId == groupId && item.recipe != null && item.type != ChainItem.Type.ITEM) {
                    recipeState.put(item.recipe, recipeState.getOrDefault(item.recipe, 0)
                            | (item.type == ChainItem.Type.INGREDIENT ? 1 : 2));
                }
            }

            for (ChainItem item : list) {
                if (item.groupId == groupId && item.type != ChainItem.Type.ITEM
                        && recipeState.getOrDefault(item.recipe, 0) != 3) {
                    if (item.type == ChainItem.Type.INGREDIENT) {
                        item.recipe = null;
                    }
                    recipeState.remove(item.recipe);
                    item.type = ChainItem.Type.ITEM;
                }
            }

            collapsed.get(groupId).removeIf(recipeId -> !recipeState.containsKey(recipeId));
        }

        return list;
    }

}
