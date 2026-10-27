package com.emicraftingtree.bookmark;

import org.jetbrains.annotations.Nullable;

import com.emicraftingtree.chain.RecipeKey;

/**
 * What the mouse is over in the bookmark grid, and which modifier keys are held (NEI's BookmarkMouseContext).
 */
public final class BookmarkMouseContext {

    public final int slotIndex;
    public final int rowIndex;
    public final int columnIndex;
    public final int groupId;
    public final @Nullable RecipeKey recipeId;
    public final boolean controlKey;
    public final boolean shiftKey;

    public BookmarkMouseContext(int slotIndex, int rowIndex, int columnIndex, int groupId,
            @Nullable RecipeKey recipeId, boolean controlKey, boolean shiftKey) {
        this.slotIndex = slotIndex;
        this.rowIndex = rowIndex;
        this.columnIndex = columnIndex;
        this.groupId = groupId;
        this.recipeId = recipeId;
        this.controlKey = controlKey;
        this.shiftKey = shiftKey;
    }

}
