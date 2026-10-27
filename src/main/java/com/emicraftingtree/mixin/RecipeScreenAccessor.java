package com.emicraftingtree.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import dev.emi.emi.screen.RecipeScreen;
import dev.emi.emi.screen.WidgetGroup;

/**
 * The recipes on the current page of EMI's recipe view, to find the recipe under the mouse (NEI's focused recipe).
 */
@Mixin(value = RecipeScreen.class, remap = false)
public interface RecipeScreenAccessor {

    @Accessor("currentPage")
    List<WidgetGroup> emicraftingtree$getCurrentPage();

}
