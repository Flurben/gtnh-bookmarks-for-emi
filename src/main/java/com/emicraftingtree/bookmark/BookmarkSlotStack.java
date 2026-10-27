package com.emicraftingtree.bookmark;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;

import com.emicraftingtree.Config;
import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.ChainStack;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.render.EmiTooltipComponents;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.screen.tooltip.RecipeTooltipComponent;

/**
 * What EMI sees in a bookmark slot. Extending {@link EmiFavorite} lets EMI's recipe and use keys work on bookmarks; the
 * tooltip is built like NEI's: hotkeys, chance, accepted alternatives and (depending on the recipe tooltip mode) the
 * recipe preview.
 */
public class BookmarkSlotStack extends EmiFavorite {

    private static final DecimalFormat CHANCE_FORMAT = new DecimalFormat("##0.##%");

    private final BookmarksGridSlot slot;

    public BookmarkSlotStack(BookmarksGridSlot slot) {
        super(displayStack(slot), slot.getEmiRecipeContext());
        this.slot = slot;
    }

    private static EmiIngredient displayStack(BookmarksGridSlot slot) {
        final EmiStack stack = slot.getBookmarkItem().stack.emi;

        if (stack == null) {
            return EmiStack.EMPTY;
        }

        return stack.copy().setAmount(Math.max(1, slot.getShownAmount()));
    }

    @Override
    public void render(GuiGraphics raw, int x, int y, float delta, int flags) {
        this.stack.render(raw, x, y, delta, flags & ~EmiIngredient.RENDER_AMOUNT);
    }

    /** NEI's showRecipeTooltipInBookmarks: by default only in the horizontal (default) view. */
    private boolean showRecipeTooltip() {
        final int mode = Config.recipeTooltipsMode();
        final BookmarkViewMode viewMode = this.slot.getGroup().viewMode;
        return mode == 3 || viewMode == BookmarkViewMode.DEFAULT && mode == 1
                || viewMode == BookmarkViewMode.TODO_LIST && mode == 2;
    }

    @Override
    public List<ClientTooltipComponent> getTooltip() {
        final List<ClientTooltipComponent> list = new ArrayList<>(this.stack.getTooltip());
        final ChainItem item = this.slot.getBookmarkItem();
        final EmiRecipe recipe = this.slot.getEmiRecipeContext();

        if (item.chance != ChainItem.CHANCE_FULL) {
            final String chance = CHANCE_FORMAT.format(item.chance / (float) ChainItem.CHANCE_FULL);
            list.add(Math.min(1, list.size()), EmiTooltipComponents.of(Component.translatable(
                    item.type == ChainItem.Type.INGREDIENT ? "emicraftingtree.chance.consume"
                            : "emicraftingtree.chance.output",
                    chance).withStyle(ChatFormatting.GRAY)));
        }

        if (item.type == ChainItem.Type.INGREDIENT && item.recipe != null && item.permutations.size() > 1) {
            final List<EmiStack> accepted = new ArrayList<>();

            for (ChainStack perm : item.permutations.values()) {
                if (perm.emi != null) {
                    accepted.add(perm.emi.copy().setAmount(perm.amount));
                }
            }

            list.add(new ItemsTooltipComponent(Component.translatable("emicraftingtree.accepts"), accepted, null,
                    item.stack.emi));
        }

        if (recipe != null && showRecipeTooltip()) {
            list.add(new RecipeTooltipComponent(recipe, true));
        }

        list.addAll(Math.min(1, list.size()), Hotkeys.slot(this.slot));
        return list;
    }

    @Override
    public boolean isUnbatchable() {
        return true;
    }

}
