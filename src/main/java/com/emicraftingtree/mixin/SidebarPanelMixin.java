package com.emicraftingtree.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import com.emicraftingtree.bookmark.BookmarkPanel;

import dev.emi.emi.config.SidebarType;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.screen.EmiScreenManager.SidebarPanel;

/**
 * Switching a sidebar to or from favorites re-lays it out (for the group gutter and namespace footer), and the
 * favorites header shows NEI's page label.
 */
@Mixin(value = SidebarPanel.class, remap = false)
public abstract class SidebarPanelMixin {

    @Unique
    private boolean emicraftingtree$wasFavorites;

    @Inject(method = "setSidebarPage", at = @At("HEAD"))
    private void emicraftingtree$beforePageChange(int page, CallbackInfo ci) {
        this.emicraftingtree$wasFavorites = ((SidebarPanel) (Object) this).getType() == SidebarType.FAVORITES;
    }

    @Inject(method = "setSidebarPage", at = @At("TAIL"))
    private void emicraftingtree$afterPageChange(int page, CallbackInfo ci) {
        final boolean favorites = ((SidebarPanel) (Object) this).getType() == SidebarType.FAVORITES;

        if (favorites != this.emicraftingtree$wasFavorites && Minecraft.getInstance().screen != null) {
            EmiScreenManager.forceRecalculate();
        }
    }

    /** NEI shows the page numbers in green brackets when the default group is a crafting chain. */
    @ModifyVariable(method = "drawHeader", at = @At("STORE"), name = "text", require = 0)
    private Component emicraftingtree$headerText(Component text) {
        if (((SidebarPanel) (Object) this).getType() != SidebarType.FAVORITES) {
            return text;
        }

        return BookmarkPanel.headerText(text);
    }

}
