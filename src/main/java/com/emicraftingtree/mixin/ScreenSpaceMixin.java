package com.emicraftingtree.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.emicraftingtree.EmiCraftingTree;
import com.emicraftingtree.bookmark.BookmarkPanel;

import dev.emi.emi.config.SidebarType;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.screen.EmiScreenManager.ScreenSpace;

/**
 * Draws the bookmark grid instead of EMI's favorites list.
 */
@Mixin(value = ScreenSpace.class, remap = false)
public abstract class ScreenSpaceMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void emicraftingtree$render(EmiDrawContext context, int mouseX, int mouseY, float delta, int startIndex,
            CallbackInfo ci) {
        final ScreenSpace space = (ScreenSpace) (Object) this;

        if (space.getType() == SidebarType.FAVORITES) {
            ci.cancel();

            try {
                BookmarkPanel.render(space, context, mouseX, mouseY, delta, startIndex);
            } catch (RuntimeException e) {
                EmiCraftingTree.LOGGER.error("Failed to draw the bookmark panel", e);
            }
        }
    }

}
