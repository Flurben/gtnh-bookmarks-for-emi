package com.emicraftingtree.bookmark;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphics;

import com.emicraftingtree.Config;
import com.emicraftingtree.bookmark.AmountText.Alignment;
import com.emicraftingtree.bookmark.RecipeChainDetails.CalculatedType;
import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.RecipeKey;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;

/**
 * One shown bookmark (port of NEI's BookmarksGridSlot): the amounts it can display and how it is drawn. Holding Shift
 * shows how much is still missing ("shift" amounts), holding Ctrl shows the bookmarked ("real") amounts; by default a
 * crafting chain shows the calculated amounts.
 */
public class BookmarksGridSlot {

    protected enum ShownItemType {
        REAL,
        SHIFT,
        CALCULATED
    }

    public static final int HIGHLIGHT_COLOR = 0xee555555;
    public static final int DRAG_COLOR = 0x66555555;

    public static final int GROUP_CHAIN_COLOR = 0x6645DA75;
    public static final int GROUP_NONE_COLOR = 0xff666666;
    public static final int RECIPE_COLOR = 0x99A033A0;
    public static final int PLACEHOLDER_COLOR = 0x66222222;

    protected static final int INGREDIENTS_COLOR = 0x6645DA75;

    protected static final int RESULTS_COLOR = 0x9966CCFF;
    protected static final int RESULTS_BG_COLOR = 0x3366CCFF;
    protected static final int RESULTS_BG_FLUID_COLOR = 0x5566CCFF;

    protected static final int REMAINDER_COLOR = 0x55A033A0;
    protected static final int REMAINDER_BG_COLOR = 0x33A033A0;
    protected static final int REMAINDER_BG_FLUID_COLOR = 0x44A033A0;

    public final int slotIndex;
    public final int itemIndex;

    public int borderColor = 0xff000000;
    public boolean borderLeft = false;
    public boolean borderTop = false;
    public boolean borderRight = false;
    public boolean borderBottom = false;

    protected final long realAmount;
    protected final long shiftAmount;
    protected final long calculatedAmount;

    protected final long realMultiplier;
    protected final long calculatedMultiplier;

    protected final BookmarkGroup group;
    protected final boolean isFluidDisplay;
    protected final @Nullable CalculatedType calculatedType;
    protected final CalculatedType realType;
    protected final ChainItem bookmarkItem;

    protected final boolean isOutputRecipe;
    protected final boolean isFirstOutput;
    protected final int rowIndex;

    private @Nullable BookmarkSlotStack emiStack;

    public BookmarksGridSlot(int slotIndex, int itemIndex, long realMultiplier, long shiftAmount,
            long calculatedMultiplier, ChainItem bookmarkItem, @Nullable CalculatedType calculatedType,
            BookmarkGroup group, GridLayout layout) {
        this.slotIndex = slotIndex;
        this.itemIndex = itemIndex;
        this.realAmount = bookmarkItem.getAmount(realMultiplier);
        this.shiftAmount = shiftAmount;
        this.calculatedAmount = calculatedType != null ? bookmarkItem.getAmount(calculatedMultiplier)
                : this.realAmount;

        this.group = group;
        this.calculatedType = calculatedType;
        this.bookmarkItem = bookmarkItem;

        this.isOutputRecipe = group.crafting != null && group.crafting.outputRecipes.contains(bookmarkItem.recipe);
        this.isFluidDisplay = bookmarkItem.stack.fluid;

        this.realMultiplier = realMultiplier;
        this.calculatedMultiplier = calculatedMultiplier;

        if (this.group.crafting != null && this.group.crafting.calculatedItems.containsKey(this.itemIndex)) {
            this.realType = this.group.crafting.calculatedItems.get(this.itemIndex).calculatedType
                    == CalculatedType.INGREDIENT ? CalculatedType.INGREDIENT : CalculatedType.RESULT;
        } else {
            this.realType = this.bookmarkItem.type == ChainItem.Type.INGREDIENT
                    || this.group.crafting != null && this.bookmarkItem.recipe == null ? CalculatedType.INGREDIENT
                            : CalculatedType.RESULT;
        }

        final ChainItem prevItem = layout.getCalculatedItem(
                layout.slotToItem.getOrDefault(layout.itemToSlot.get(this.itemIndex) - 1, -1));
        this.isFirstOutput = this.itemIndex >= 0 && this.bookmarkItem.type == ChainItem.Type.RESULT
                && ((this.slotIndex % layout.getColumns()) == 0 || group.viewMode == BookmarkViewMode.DEFAULT)
                && (prevItem == null || prevItem.type == ChainItem.Type.INGREDIENT
                        || !this.bookmarkItem.equalsRecipe(prevItem));
        this.rowIndex = this.slotIndex / layout.getColumns();
    }

    public long getMultiplier() {
        return this.calculatedType != null ? this.calculatedMultiplier : this.realMultiplier;
    }

    /** Amount shown when no modifier key is held. */
    public long getShownAmount() {
        return this.calculatedAmount;
    }

    public ChainItem getBookmarkItem() {
        return this.bookmarkItem;
    }

    public @Nullable RecipeKey getRecipeId() {
        return this.bookmarkItem.recipe;
    }

    public ChainItem.Type getType() {
        return this.bookmarkItem.type;
    }

    public BookmarkGroup getGroup() {
        return this.group;
    }

    public int getGroupId() {
        return this.bookmarkItem.groupId;
    }

    /** The EMI stack used for EMI's hover, tooltip and key handling. */
    public BookmarkSlotStack getEmiStack() {
        if (this.emiStack == null) {
            this.emiStack = new BookmarkSlotStack(this);
        }
        return this.emiStack;
    }

    protected @Nullable ShownItemType getShownItemType(@Nullable BookmarkMouseContext mouseContext) {

        if (mouseContext != null) {
            if (this.showShiftItem(mouseContext)) {
                return ShownItemType.SHIFT;
            } else if (this.showRealItem(mouseContext)) {
                return ShownItemType.REAL;
            }
        }

        if (this.calculatedType != null) {
            return ShownItemType.CALCULATED;
        }

        return null;
    }

    protected boolean showShiftItem(BookmarkMouseContext mouseContext) {

        if (!mouseContext.shiftKey || this.bookmarkItem.groupId != mouseContext.groupId) {
            return false;
        }

        if (this.slotIndex == mouseContext.slotIndex || this.group.crafting != null) {
            return true;
        }

        if (this.bookmarkItem.type == ChainItem.Type.ITEM && this.group.viewMode == BookmarkViewMode.TODO_LIST
                && mouseContext.rowIndex == this.rowIndex) {
            return true;
        }

        return this.bookmarkItem.type != ChainItem.Type.ITEM
                && this.bookmarkItem.equalsRecipe(mouseContext.recipeId, mouseContext.groupId);
    }

    protected boolean showRealItem(BookmarkMouseContext mouseContext) {

        if (!mouseContext.controlKey || this.bookmarkItem.groupId != mouseContext.groupId) {
            return false;
        }

        if (this.slotIndex == mouseContext.slotIndex || this.group.collapsed) {
            return true;
        }

        if (mouseContext.recipeId == null && this.group.viewMode == BookmarkViewMode.TODO_LIST
                && mouseContext.rowIndex == this.rowIndex) {
            return true;
        }

        if (this.bookmarkItem.type != ChainItem.Type.ITEM
                && this.bookmarkItem.equalsRecipe(mouseContext.recipeId, mouseContext.groupId)) {
            return true;
        }

        return this.group.crafting != null && mouseContext.recipeId != null
                && mouseContext.recipeId.equals(this.group.crafting.itemToRecipe.get(this.itemIndex));
    }

    public void drawItem(GuiGraphics graphics, int x, int y, float delta) {
        final EmiStack stack = this.bookmarkItem.stack.emi;

        if (stack != null) {
            stack.render(graphics, x + 1, y + 1, delta, EmiIngredient.RENDER_ICON);
        }
    }

    public void beforeDraw(GuiGraphics graphics, int x, int y, @Nullable BookmarkMouseContext mouseContext) {
        final ShownItemType shownItemType = getShownItemType(mouseContext);
        boolean showMarker = Config.showRecipeMarkerMode() == 2;
        CalculatedType calculatedType = null;

        if (shownItemType == ShownItemType.REAL) {
            calculatedType = this.realType;
        } else if (shownItemType == ShownItemType.SHIFT) {

            if (this.calculatedType != null) {
                calculatedType = this.shiftAmount > 0 ? this.calculatedType : null;
                showMarker = false;
            } else if (this.group.crafting == null) {
                calculatedType = this.realType;
            }

        }

        if (this.bookmarkItem.type != ChainItem.Type.INGREDIENT && this.bookmarkItem.recipe != null
                && calculatedType == null
                && showMarker
                && (mouseContext == null || mouseContext.slotIndex != this.slotIndex)) {
            drawMarkerBackground(graphics, x, y);
        }

        if (calculatedType == null || !drawBackground(graphics, x, y, calculatedType)) {
            if (mouseContext != null && mouseContext.slotIndex == this.slotIndex) {
                graphics.fill(x, y, x + GridGeometry.SLOT_SIZE, y + GridGeometry.SLOT_SIZE, HIGHLIGHT_COLOR);
            }
        }
    }

    protected boolean drawBackground(GuiGraphics graphics, int x, int y, CalculatedType calculatedType) {
        final int color = switch (calculatedType) {
            case INGREDIENT -> INGREDIENTS_COLOR;
            case RESULT -> RESULTS_COLOR;
            case REMAINDER -> REMAINDER_COLOR;
        };

        graphics.fill(x, y, x + GridGeometry.SLOT_SIZE, y + GridGeometry.SLOT_SIZE, color);
        return true;
    }

    protected void drawMarkerBackground(GuiGraphics graphics, int x, int y) {
        int color = this.isFluidDisplay ? RESULTS_BG_FLUID_COLOR : RESULTS_BG_COLOR;

        if (this.shiftAmount > 0 && this.calculatedType == CalculatedType.REMAINDER) {
            color = this.isFluidDisplay ? REMAINDER_BG_FLUID_COLOR : REMAINDER_BG_COLOR;
        }

        graphics.fill(x, y, x + GridGeometry.SLOT_SIZE, y + GridGeometry.SLOT_SIZE, color);
    }

    public void drawBorder(GuiGraphics graphics, int x, int y) {
        final int size = GridGeometry.SLOT_SIZE;

        if (this.borderLeft) {
            graphics.fill(x, y, x + 1, y + size, this.borderColor);
        }

        if (this.borderRight) {
            graphics.fill(x + size - 1, y, x + size, y + size, this.borderColor);
        }

        if (this.borderTop) {
            graphics.fill(x + (this.borderLeft ? 1 : 0), y, x + size - (this.borderRight ? 1 : 0), y + 1,
                    this.borderColor);
        }

        if (this.borderBottom) {
            graphics.fill(x + (this.borderLeft ? 1 : 0), y + size - 1, x + size - (this.borderRight ? 1 : 0),
                    y + size, this.borderColor);
        }
    }

    public void afterDraw(GuiGraphics graphics, int x, int y, @Nullable BookmarkMouseContext mouseContext,
            float delta) {
        final ShownItemType shownItemType = getShownItemType(mouseContext);

        drawStackSize(graphics, x, y, shownItemType);

        if (this.bookmarkItem.type == ChainItem.Type.INGREDIENT || this.bookmarkItem.recipe == null) {
            return;
        }

        if (this.isFirstOutput) {
            if (shownItemType == ShownItemType.REAL) {
                drawMarker(graphics, x, y, "x" + AmountText.compact(this.realMultiplier), 0xFFFFFF,
                        Alignment.TOP_LEFT);
            } else if (shownItemType == ShownItemType.SHIFT) {
                drawMarker(graphics, x, y, "x" + AmountText.compact(getMultiplier()), 0xFFFFFF, Alignment.TOP_LEFT);
            } else if (this.isOutputRecipe || this.realMultiplier > 1) {
                drawMarker(graphics, x, y, "x" + AmountText.compact(this.realMultiplier), Config.recipeMarkerColor(),
                        Alignment.TOP_LEFT);
            }
        }

        if (this.isFirstOutput || Config.showRecipeMarkerMode() == 1) {
            drawRecipeMarker(graphics, x, y, delta);
        }
    }

    protected void drawRecipeMarker(GuiGraphics graphics, int x, int y, float delta) {
        final EmiRecipeCategory category = Config.showRecipeHandlerIcon() ? RecipeBookmarks.categoryOf(
                this.bookmarkItem.recipe) : null;

        if (category != null) {
            final float scale = 0.4f;
            final PoseStack pose = graphics.pose();

            pose.pushPose();
            pose.translate(x + 2 + 16 - 16 * scale, y, 150);
            pose.scale(scale, scale, 1);
            category.render(graphics, 0, 0, delta);
            pose.popPose();
        } else if (Config.showRecipeMarkerMode() == 1) {
            drawMarker(graphics, x, y, "R", Config.recipeMarkerColor(), Alignment.TOP_RIGHT);
        }
    }

    protected void drawMarker(GuiGraphics graphics, int x, int y, String text, int color, Alignment alignment) {
        AmountText.draw(graphics, text, x + 1, y + 1, color, alignment);
    }

    protected void drawStackSize(GuiGraphics graphics, int x, int y, @Nullable ShownItemType shownItemType) {
        boolean drawChance = this.calculatedMultiplier > 0;
        long amount = this.calculatedAmount;

        if (shownItemType == null || shownItemType == ShownItemType.REAL || this.calculatedType == null) {
            amount = this.realAmount;
            drawChance = this.realMultiplier > 0;
        } else if (shownItemType == ShownItemType.SHIFT) {
            amount = this.shiftAmount;
            drawChance = false;
        }

        if (amount > 0 || this.bookmarkItem.chance != ChainItem.CHANCE_FULL && drawChance
                || shownItemType == ShownItemType.REAL && !this.bookmarkItem.emptyFactor()) {
            int color = 0xFFFFFF;
            String amountString = AmountText.compact(amount);

            if (this.isFluidDisplay) {
                amountString += "L";
            }

            if (this.bookmarkItem.chance != ChainItem.CHANCE_FULL) {
                amountString = "~" + amountString;
                color = 0xFFAA00;
            }

            AmountText.draw(graphics, amountString, x + 1, y + 1, color,
                    this.isFluidDisplay ? Alignment.BOTTOM_LEFT : Alignment.BOTTOM_RIGHT);
        }
    }

    /** The recipe EMI should treat this slot as (results and items bookmarked with their recipe). */
    public @Nullable EmiRecipe getEmiRecipeContext() {
        if (this.bookmarkItem.type == ChainItem.Type.INGREDIENT || this.bookmarkItem.recipe == null) {
            return null;
        }
        return this.bookmarkItem.recipe.getRecipe();
    }

}
