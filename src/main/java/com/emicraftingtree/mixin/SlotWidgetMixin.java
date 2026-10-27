package com.emicraftingtree.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

import com.emicraftingtree.EmiCraftingTree;
import com.emicraftingtree.bookmark.ItemShortcuts;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.screen.RecipeScreen;

/**
 * NEI's hotkey hints on stacks in EMI's recipe view.
 */
@Mixin(value = SlotWidget.class, remap = false)
public abstract class SlotWidgetMixin {

    @Inject(method = "getTooltip", at = @At("RETURN"))
    private void emicraftingtree$hotkeys(int mouseX, int mouseY,
            CallbackInfoReturnable<List<ClientTooltipComponent>> cir) {
        if (!(Minecraft.getInstance().screen instanceof RecipeScreen)) {
            return;
        }

        try {
            final SlotWidget slot = (SlotWidget) (Object) this;
            final EmiIngredient stack = slot.getStack();
            final List<ClientTooltipComponent> list = cir.getReturnValue();

            if (!stack.isEmpty() && !list.isEmpty()) {
                list.addAll(1, ItemShortcuts.itemHotkeyTips(stack, EmiScreenManager.lastMouseX,
                        EmiScreenManager.lastMouseY, slot.getRecipe()));
            }
        } catch (RuntimeException e) {
            EmiCraftingTree.LOGGER.debug("Failed to add hotkey hints", e);
        }
    }

}
