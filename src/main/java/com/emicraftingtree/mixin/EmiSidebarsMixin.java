package com.emicraftingtree.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.emicraftingtree.EmiCraftingTree;
import com.emicraftingtree.bookmark.BookmarkPanel;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.runtime.EmiSidebars;

/**
 * The favorites sidebar lists the bookmark grid's slots, so EMI's paging and hover detection follow the grid.
 */
@Mixin(value = EmiSidebars.class, remap = false)
public abstract class EmiSidebarsMixin {

    @Inject(method = "getStacks", at = @At("HEAD"), cancellable = true)
    private static void emicraftingtree$getStacks(SidebarType type,
            CallbackInfoReturnable<List<? extends EmiIngredient>> cir) {
        if (type == SidebarType.FAVORITES) {
            try {
                cir.setReturnValue(BookmarkPanel.sidebarStacks());
            } catch (RuntimeException e) {
                EmiCraftingTree.LOGGER.error("Failed to list bookmarks for EMI", e);
                cir.setReturnValue(List.of());
            }
        }
    }

}
