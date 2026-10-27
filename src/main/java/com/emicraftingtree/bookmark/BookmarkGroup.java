package com.emicraftingtree.bookmark;

import java.util.HashSet;
import java.util.Set;

import com.emicraftingtree.chain.RecipeKey;

/**
 * Settings of one bookmark group (port of NEI's BookmarkGroup). A group with {@link #crafting} set is a crafting
 * chain: its recipes are linked together and the amounts shown are the calculated ones.
 */
public class BookmarkGroup {

    public RecipeChainDetails crafting = null;
    public BookmarkViewMode viewMode;
    public boolean collapsed = false;
    public Set<RecipeKey> collapsedRecipes = new HashSet<>();

    public BookmarkGroup(BookmarkViewMode viewMode) {
        this.viewMode = viewMode;
    }

    public BookmarkGroup(BookmarkViewMode viewMode, boolean crafting) {
        this.viewMode = viewMode;
        setCraftingMode(crafting);
    }

    public void toggleViewMode() {
        if (this.viewMode == BookmarkViewMode.DEFAULT) {
            this.viewMode = BookmarkViewMode.TODO_LIST;
        } else {
            this.viewMode = BookmarkViewMode.DEFAULT;
        }
    }

    public void setCraftingMode(boolean crafting) {
        if ((this.crafting != null) != crafting) {
            this.crafting = crafting ? new RecipeChainDetails() : null;
        }
    }

    public void toggleCraftingMode() {
        setCraftingMode(this.crafting == null);
    }

    public void toggleCollapsedState() {
        this.collapsed = !this.collapsed;
    }

    public void toggleCollapsedRecipe(RecipeKey recipeId) {
        if (this.collapsedRecipes.contains(recipeId)) {
            this.collapsedRecipes.remove(recipeId);
        } else {
            this.collapsedRecipes.add(recipeId);
        }
    }

    public BookmarkGroup copy() {
        return new BookmarkGroup(this.viewMode);
    }
}
