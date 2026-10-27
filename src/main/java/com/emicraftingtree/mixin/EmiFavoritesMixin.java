package com.emicraftingtree.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.emicraftingtree.EmiCraftingTree;
import com.emicraftingtree.bookmark.ItemShortcuts;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.runtime.EmiFavorites;

/**
 * Any favorite EMI adds by itself (for example with the favorite key over a recipe in the recipe view) becomes a
 * bookmark instead.
 */
@Mixin(value = EmiFavorites.class, remap = false)
public abstract class EmiFavoritesMixin {

    @Inject(method = "addFavorite(Ldev/emi/emi/api/stack/EmiIngredient;Ldev/emi/emi/api/recipe/EmiRecipe;)V",
            at = @At("HEAD"), cancellable = true)
    private static void emicraftingtree$addFavorite(EmiIngredient stack, EmiRecipe context, CallbackInfo ci) {
        try {
            if (ItemShortcuts.onEmiFavorite(stack, context)) {
                ci.cancel();
            }
        } catch (RuntimeException e) {
            EmiCraftingTree.LOGGER.error("Failed to bookmark an EMI favorite", e);
        }
    }

}
