package com.emicraftingtree.bookmark;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import com.emicraftingtree.Config;
import com.emicraftingtree.EmiCraftingTree;
import com.emicraftingtree.Keys;
import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.ChainStack;
import com.emicraftingtree.chain.Stacks;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.render.EmiTooltipComponents;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.config.SidebarSide;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.network.CreateItemC2SPacket;
import dev.emi.emi.network.EmiNetwork;
import dev.emi.emi.platform.EmiClient;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.screen.EmiScreenBase;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.screen.EmiScreenManager.ScreenSpace;
import dev.emi.emi.screen.EmiScreenManager.SidebarPanel;
import dev.emi.emi.screen.RecipeScreen;

/**
 * NEI's bookmark panel inside EMI's favorites sidebar (port of NEI's BookmarkPanel and PanelWidget). EMI keeps drawing
 * the sidebar frame, header and page buttons; this class draws the grid, the group gutter and the namespace footer, and
 * gets the first look at mouse and key input over them. Key shortcuts on hovered items are in {@link ItemShortcuts}.
 */
public final class BookmarkPanel {

    private static final long GROUP_DRAG_DELAY_MS = 250;
    private static final long ITEM_DRAG_DELAY_MS = 500;

    /** A favorites space EMI is currently showing. */
    public record PanelSpace(SidebarPanel panel, ScreenSpace space, GridGeometry geometry, boolean main) {

        public int page() {
            return this.main ? Math.max(0, this.panel.page) : 0;
        }

        public GridLayout layout() {
            return grid().getLayout(this.geometry);
        }
    }

    private static @Nullable SortableItem sortableItem;
    private static @Nullable SortableGroup sortableGroup;
    private static @Nullable GroupingItem groupingItem;

    private static @Nullable PanelSpace pressSpace;
    private static long pressTime;
    private static int mouseDownSlot = -1;
    private static boolean slotPress;
    private static boolean pressConsumed;
    private static boolean pressOnGutter;

    private static int recipeGeneration = Integer.MIN_VALUE;

    private static @Nullable List<ClientTooltipComponent> chainTooltip;
    private static String chainTooltipKey = "";
    private static long chainTooltipTime;

    private BookmarkPanel() {}

    public static BookmarkGrid grid() {
        return BookmarkStorage.grid();
    }

    public static @Nullable SortableGroup sortableGroup() {
        return sortableGroup;
    }

    /** EMI is loaded and showing its sidebars on the current screen. */
    static boolean isEmiReady() {
        if (!EmiReloadManager.isLoaded() || EmiScreenManager.isDisabled() || EmiScreenBase.getCurrent().isEmpty()) {
            return false;
        }

        BookmarkStorage.ensureLoaded();

        final int generation = currentRecipeGeneration();

        if (generation != recipeGeneration) {
            recipeGeneration = generation;
            grid().onGridChanged();
        }

        return true;
    }

    /** The panel is shown (NEI's bookmark.hide key toggles it). */
    private static boolean isReady() {
        return isEmiReady() && !Config.bookmarksHidden();
    }

    public static int currentRecipeGeneration() {
        return EmiReloadManager.isLoaded() ? System.identityHashCode(EmiApi.getRecipeManager()) : 0;
    }

    public static GridGeometry geometryOf(ScreenSpace space) {
        return new GridGeometry(space.tx, space.ty, space.tw, space.th, space.rtl, space.widths);
    }

    /** Favorites spaces of the visible EMI sidebars. */
    public static List<PanelSpace> spaces() {
        final List<PanelSpace> result = new ArrayList<>();

        for (SidebarSide side : SidebarSide.values()) {
            final SidebarPanel panel = EmiScreenManager.getPanelFor(side);

            if (panel == null || !panel.isVisible()) {
                continue;
            }

            for (ScreenSpace space : panel.getSpaces()) {
                if (space.getType() == SidebarType.FAVORITES && space.pageSize > 0 && space.tw > 0) {
                    result.add(new PanelSpace(panel, space, geometryOf(space), space == panel.space));
                }
            }
        }

        return result;
    }

    static @Nullable PanelSpace mainSpace() {
        for (PanelSpace space : spaces()) {
            if (space.main()) {
                return space;
            }
        }
        return null;
    }

    static @Nullable PanelSpace spaceAt(double mouseX, double mouseY) {
        for (PanelSpace space : spaces()) {
            if (space.geometry().containsGrid(mouseX, mouseY) || space.geometry().containsGutter(mouseX, mouseY)) {
                return space;
            }
        }
        return null;
    }

    private static @Nullable NamespaceFooter.Layout footer() {
        final PanelSpace space = mainSpace();
        return space != null ? NamespaceFooter.layout(space.geometry()) : null;
    }

    /** The header text between EMI's page buttons stands for the default group, like NEI's page label. */
    static boolean overHeaderLabel(double mouseX, double mouseY) {
        final PanelSpace space = mainSpace();

        if (space == null || !space.panel().header || grid().isEmpty()) {
            return false;
        }

        final SidebarPanel panel = space.panel();
        final int left = panel.cycle.getX() + panel.cycle.getWidth();
        final int right = panel.pageRight.getX();
        final int top = panel.pageLeft.getY();

        return right > left && mouseX >= left && mouseX < right && mouseY >= top
                && mouseY < top + panel.pageLeft.getHeight();
    }

    /** Whether the mouse is anywhere over the panel (grid, gutter, footer). */
    private static boolean overPanel(double mouseX, double mouseY) {
        final NamespaceFooter.Layout footer = footer();
        final PanelSpace space = mainSpace();

        return spaceAt(mouseX, mouseY) != null || footer != null && space != null && mouseY >= footer.y()
                && mouseY < footer.y() + NamespaceFooter.HEIGHT && mouseX >= space.geometry().x
                        - GridGeometry.GROUP_PANEL_WIDTH
                && mouseX < space.geometry().x + space.geometry().columns * GridGeometry.SLOT_SIZE;
    }

    public static boolean inEditingState() {
        return sortableItem != null || sortableGroup != null || !EmiScreenManager.draggedStack.isEmpty()
                || HeldStack.isHeld() || groupingItem != null && groupingItem.hasEndRow();
    }

    static void playClickSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    static boolean isTyping() {
        final Screen screen = Minecraft.getInstance().screen;
        return EmiScreenManager.search.canConsumeInput()
                || screen != null && screen.getFocused() instanceof EditBox box && box.canConsumeInput();
    }

    private static ItemStack carried() {
        return Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen
                ? screen.getMenu().getCarried()
                : ItemStack.EMPTY;
    }

    static @Nullable BookmarksGridSlot hoveredSlot(double mouseX, double mouseY) {
        if (!isReady()) {
            return null;
        }

        final PanelSpace space = spaceAt(mouseX, mouseY);
        return space != null ? space.layout().getSlotMouseOver(mouseX, mouseY, space.page()) : null;
    }

    /** NEI's getHoveredGroupId(true): a non-default group under the gutter, the default group for the label, or -1. */
    static int hoveredGroupId(double mouseX, double mouseY) {
        if (!isReady()) {
            return -1;
        }

        final PanelSpace space = spaceAt(mouseX, mouseY);

        if (space != null) {
            final GridLayout layout = space.layout();
            final int row = layout.getHoveredRowIndex(mouseX, mouseY, true, space.page());

            if (row != -1) {
                final int groupId = layout.getRowGroupId(row, space.page());
                return groupId == BookmarkGrid.DEFAULT_GROUP_ID ? -1 : groupId;
            }
        } else if (overHeaderLabel(mouseX, mouseY)) {
            return BookmarkGrid.DEFAULT_GROUP_ID;
        }

        return -1;
    }

    /** NEI's getDraggedStackWithQuantity: the bookmark's amount, or a full stack when it has none. */
    static EmiStack stackWithQuantity(BookmarksGridSlot slot) {
        final EmiStack stack = slot.getBookmarkItem().stack.emi;

        if (stack == null) {
            return EmiStack.EMPTY;
        }

        long amount = slot.getShownAmount();

        if (amount <= 0) {
            amount = stack.getItemStack().isEmpty() ? 1000 : stack.getItemStack().getMaxStackSize();
        }

        return stack.copy().setAmount(amount);
    }

    private static void switchNamespace(boolean next) {
        final PanelSpace space = mainSpace();

        if (space != null) {
            grid().page = space.panel().page;
        }

        if (next) {
            BookmarkStorage.nextNamespace();
        } else {
            BookmarkStorage.prevNamespace();
        }

        if (space != null) {
            space.panel().page = grid().page;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // EMI's list of favorites stacks

    /**
     * What EMI sees as the favorites sidebar contents: one entry per usable slot of every page, empty where the
     * grid has no bookmark. EMI uses it for page counts and to find the hovered stack.
     */
    public static List<? extends EmiIngredient> sidebarStacks() {
        if (!isReady()) {
            return List.of();
        }

        final PanelSpace space = mainSpace();

        if (space == null) {
            return List.of();
        }

        return new SidebarList(space.layout(), space.geometry().pageSize());
    }

    private static final class SidebarList extends AbstractList<EmiIngredient> {

        private final GridLayout layout;
        private final int pageSize;

        SidebarList(GridLayout layout, int pageSize) {
            this.layout = layout;
            this.pageSize = pageSize;
        }

        @Override
        public EmiIngredient get(int index) {
            if (this.pageSize <= 0 || index < 0 || index >= size()) {
                throw new IndexOutOfBoundsException(index);
            }

            final int slotIndex = this.layout.geometry.fromEmiOffset(index % this.pageSize);
            final BookmarksGridSlot slot = slotIndex < 0 ? null
                    : this.layout.getSlotBySlotIndex(index / this.pageSize, slotIndex);

            return slot != null ? slot.getEmiStack() : EmiStack.EMPTY;
        }

        @Override
        public int size() {
            return this.pageSize <= 0 ? 0 : this.layout.pageCount * this.pageSize;
        }
    }

    /** NEI's page label: the page numbers, in green brackets when the default group is a crafting chain. */
    public static Component headerText(Component text) {
        if (!isReady() || !grid().isCraftingMode(BookmarkGrid.DEFAULT_GROUP_ID)) {
            return text;
        }

        return Component.literal("§2[§r").append(text).append("§2]§r");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rendering

    /** Draws the bookmark grid in place of EMI's favorites space. */
    public static void render(ScreenSpace space, EmiDrawContext context, int mouseX, int mouseY, float delta,
            int startIndex) {
        if (!isReady()) {
            return;
        }

        final GuiGraphics graphics = context.raw();
        final GridGeometry geometry = geometryOf(space);
        final GridLayout layout = grid().getLayout(geometry);
        final int page = space.pageSize > 0 ? startIndex / space.pageSize : 0;
        final boolean editing = inEditingState();
        final SortableGroup sortGroup = sortableGroup != null && sortableGroup.geometry.equals(geometry)
                ? sortableGroup
                : null;
        final SortableItem sortItem = sortableItem != null && sortableItem.geometry.equals(geometry) ? sortableItem
                : null;
        final PanelSpace main = mainSpace();
        final boolean isMain = main != null && main.space() == space;

        if (isMain) {
            grid().page = page;
        }

        if (sortItem != null) {
            sortItem.update();
        }

        context.push();
        GroupGutter.draw(graphics, grid(), layout, page, groupingItem,
                sortGroup != null && sortGroup.grid == grid() ? sortGroup : null, editing, mouseX, mouseY);

        final BookmarkMouseContext mouseContext = editing ? null : mouseContext(layout, page, mouseX, mouseY);
        final List<BookmarksGridSlot> slots = layout.getSlots(page);

        if (editing) {
            final List<BookmarksGridSlot> borders = new ArrayList<>();

            for (BookmarksGridSlot slot : slots) {
                final int x = geometry.slotX(slot.slotIndex);
                final int y = geometry.slotY(slot.slotIndex);

                if (isDragged(slot, sortGroup, sortItem)) {
                    graphics.fill(x, y, x + GridGeometry.SLOT_SIZE, y + GridGeometry.SLOT_SIZE,
                            BookmarksGridSlot.DRAG_COLOR);
                }

                if (sortGroup == null || !sortGroup.matches(grid(), slot.getGroupId())) {
                    borders.add(slot);
                }
            }

            for (BookmarksGridSlot slot : borders) {
                slot.drawBorder(graphics, geometry.slotX(slot.slotIndex), geometry.slotY(slot.slotIndex));
            }
        } else {
            for (BookmarksGridSlot slot : slots) {
                slot.beforeDraw(graphics, geometry.slotX(slot.slotIndex), geometry.slotY(slot.slotIndex),
                        mouseContext);
            }

            for (BookmarksGridSlot slot : slots) {
                slot.drawBorder(graphics, geometry.slotX(slot.slotIndex), geometry.slotY(slot.slotIndex));
            }
        }

        for (BookmarksGridSlot slot : slots) {
            if (isDragged(slot, sortGroup, sortItem)) {
                continue;
            }

            final int x = geometry.slotX(slot.slotIndex);
            final int y = geometry.slotY(slot.slotIndex);
            final Float animation = Config.animation() ? grid().animation.get(slot.getBookmarkItem()) : null;

            if (animation != null && animation < 1) {
                final float scale = animation + BookmarkGrid.SCALE_SPEED;

                if (scale >= 1) {
                    grid().animation.remove(slot.getBookmarkItem());
                } else {
                    grid().animation.put(slot.getBookmarkItem(), scale);
                }

                final PoseStack pose = graphics.pose();
                pose.pushPose();
                pose.translate(x + GridGeometry.SLOT_SIZE / 2f, y + GridGeometry.SLOT_SIZE / 2f, 0);
                pose.scale(Math.min(1, scale), Math.min(1, scale), 1);
                pose.translate(-(x + GridGeometry.SLOT_SIZE / 2f), -(y + GridGeometry.SLOT_SIZE / 2f), 0);
                slot.drawItem(graphics, x, y, delta);
                pose.popPose();
            } else {
                slot.drawItem(graphics, x, y, delta);
            }
        }

        for (BookmarksGridSlot slot : slots) {
            if (!editing || !isDragged(slot, sortGroup, sortItem)) {
                slot.afterDraw(graphics, geometry.slotX(slot.slotIndex), geometry.slotY(slot.slotIndex),
                        mouseContext, delta);
            }
        }

        if (!EmiScreenManager.draggedStack.isEmpty() && !(EmiScreenManager.draggedStack instanceof BookmarkSlotStack)
                && (geometry.containsGrid(mouseX, mouseY) || geometry.containsGutter(mouseX, mouseY))) {
            final int slotIndex = nextFreeSlot(layout, page);

            if (slotIndex >= 0) {
                final int x = geometry.slotX(slotIndex);
                final int y = geometry.slotY(slotIndex);
                graphics.fill(x, y, x + GridGeometry.SLOT_SIZE, y + GridGeometry.SLOT_SIZE,
                        BookmarksGridSlot.HIGHLIGHT_COLOR);
            }
        }

        if (isMain) {
            final NamespaceFooter.Layout footer = NamespaceFooter.layout(geometry);

            if (footer != null) {
                NamespaceFooter.render(graphics, footer, mouseX, mouseY);
            }
        }

        context.pop();
    }

    private static boolean isDragged(BookmarksGridSlot slot, @Nullable SortableGroup sortGroup,
            @Nullable SortableItem sortItem) {
        return sortGroup != null && sortGroup.matches(grid(), slot.getGroupId())
                || sortItem != null && sortItem.grid == grid() && sortItem.items.contains(slot.itemIndex);
    }

    /** Where a stack dropped on the panel would go (NEI highlights the next free slot). */
    private static int nextFreeSlot(GridLayout layout, int page) {
        final GridGeometry geometry = layout.geometry;
        final boolean line = grid().getViewMode(BookmarkGrid.DEFAULT_GROUP_ID) == BookmarkViewMode.TODO_LIST;
        final List<BookmarksGridSlot> slots = layout.getSlots(page);
        int slotIndex = slots.isEmpty() ? 0 : slots.get(slots.size() - 1).slotIndex + 1;

        while (slotIndex < geometry.perPage()) {
            if ((!line || (slotIndex % geometry.columns) == 0) && !geometry.isInvalidSlot(slotIndex)) {
                return slotIndex;
            }
            slotIndex++;
        }

        return -1;
    }

    private static @Nullable BookmarkMouseContext mouseContext(GridLayout layout, int page, double mouseX,
            double mouseY) {
        final GridGeometry geometry = layout.geometry;
        final boolean control = Screen.hasControlDown();
        final boolean shift = Screen.hasShiftDown();
        final BookmarksGridSlot hovered = layout.getSlotMouseOver(mouseX, mouseY, page);

        if (hovered != null) {
            return new BookmarkMouseContext(hovered.slotIndex, hovered.slotIndex / geometry.columns,
                    hovered.slotIndex % geometry.columns, hovered.getGroupId(),
                    layout.itemToRecipe.getOrDefault(hovered.itemIndex, hovered.getRecipeId()), control, shift);
        }

        final int rowIndex = layout.getHoveredRowIndex(mouseX, mouseY, false, page);

        if (rowIndex != -1) {
            final int groupId = layout.getRowGroupId(rowIndex, page);
            final int rowItemIndex = layout.getRowItemIndex(rowIndex, false, page);

            if (rowItemIndex != Integer.MIN_VALUE) {
                final int columnIndex = geometry.getColumnIndex(mouseX);
                final BookmarkGroup group = grid().getGroup(groupId);
                com.emicraftingtree.chain.RecipeKey recipeId = null;

                if (group != null && !group.collapsed) {
                    final ChainItem rowItem = layout.getCalculatedItem(rowItemIndex);
                    recipeId = layout.itemToRecipe.getOrDefault(rowItemIndex,
                            rowItem != null ? rowItem.recipe : null);
                }

                return new BookmarkMouseContext(-1, rowIndex,
                        columnIndex < 0 || columnIndex > geometry.columns ? -1 : columnIndex, groupId, recipeId,
                        control, shift);
            }
        }

        return null;
    }

    /**
     * Drawn after EMI's own overlays: the recipe overlay, dragged bookmarks, the held stack and the tooltips of the
     * group gutter, the page label and the footer.
     */
    public static void renderOverlay(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        RecipeOverlay.render(screen, graphics, delta);
        StatusMessage.render(screen, graphics);

        if (!isReady()) {
            return;
        }

        HeldStack.render(screen, graphics, mouseX, mouseY, delta);

        if (sortableItem != null && pressSpace != null) {
            sortableItem.postDraw(graphics, mouseX, mouseY, pressSpace.page(), delta);
            return;
        }

        if (sortableGroup != null && pressSpace != null) {
            if (sortableGroup.grid == grid()) {
                sortableGroup.postDraw(graphics, mouseX, mouseY, pressSpace.page(), delta);
            }
            return;
        }

        if (inEditingState() || !carried().isEmpty()) {
            return;
        }

        final NamespaceFooter.Layout footer = footer();

        if (footer != null && footer.overPull(mouseX, mouseY)) {
            EmiRenderHelper.drawTooltip(screen, EmiDrawContext.wrap(graphics),
                    List.of(EmiTooltipComponents.of(Component.translatable("emicraftingtree.pull_bookmarked_items"))),
                    mouseX, mouseY);
            return;
        }

        int groupId = Integer.MIN_VALUE;
        boolean label = false;
        final PanelSpace space = spaceAt(mouseX, mouseY);

        if (space != null) {
            final GridLayout layout = space.layout();
            final int row = layout.getHoveredRowIndex(mouseX, mouseY, true, space.page());

            if (row != -1) {
                groupId = layout.getRowGroupId(row, space.page());
            }
        } else if (overHeaderLabel(mouseX, mouseY)) {
            groupId = BookmarkGrid.DEFAULT_GROUP_ID;
            label = true;
        }

        if (groupId == Integer.MIN_VALUE) {
            chainTooltip = null;
            return;
        }

        final List<ClientTooltipComponent> components = new ArrayList<>();
        components.add(EmiTooltipComponents.of(Component.translatable("emicraftingtree.group")));
        components.addAll(Hotkeys.group(groupId, label));

        if (label || groupId != BookmarkGrid.DEFAULT_GROUP_ID) {
            components.addAll(chainTooltip(groupId, screen));
        }

        EmiRenderHelper.drawTooltip(screen, EmiDrawContext.wrap(graphics), components, mouseX, mouseY);
    }

    private static List<ClientTooltipComponent> chainTooltip(int groupId, Screen screen) {
        final boolean shift = Screen.hasShiftDown();
        final boolean control = Screen.hasControlDown();
        final String key = BookmarkStorage.getActiveIndex() + ":" + groupId + ":" + grid().revision() + ":" + shift
                + ":" + control;
        final long now = System.currentTimeMillis();

        if (chainTooltip == null || !key.equals(chainTooltipKey) || shift && now - chainTooltipTime > 500) {
            chainTooltipKey = key;
            chainTooltipTime = now;

            try {
                chainTooltip = ChainTooltip.create(grid(), groupId, shift, control,
                        shift && !(screen instanceof RecipeScreen) ? InventoryStacks.of(screen) : Map.of());
            } catch (RuntimeException e) {
                EmiCraftingTree.LOGGER.error("Failed to build the crafting chain tooltip", e);
                chainTooltip = List.of();
            }
        }

        return chainTooltip;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Mouse input (called before EMI handles it)

    private static void resetGesture() {
        sortableItem = null;
        sortableGroup = null;
        groupingItem = null;
        pressSpace = null;
        mouseDownSlot = -1;
        slotPress = false;
        pressConsumed = false;
        pressOnGutter = false;
    }

    /** Clears EMI's own press/drag state for a gesture this panel took over. */
    private static void clearEmiPress() {
        EmiScreenManager.pressedStack = EmiStack.EMPTY;
        EmiScreenManager.draggedStack = EmiStack.EMPTY;
    }

    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isEmiReady()) {
            return false;
        }

        resetGesture();

        if (HeldStack.isHeld()) {
            pressConsumed = true;
            return HeldStack.click(mouseX, mouseY);
        }

        if (!isReady()) {
            return false;
        }

        final boolean shift = Screen.hasShiftDown();
        final boolean alt = Screen.hasAltDown();

        if (!carried().isEmpty()) {
            pressConsumed = overPanel(mouseX, mouseY);
            return pressConsumed;
        }

        final NamespaceFooter.Layout footer = footer();

        if (footer != null) {

            if (footer.overPrev(mouseX, mouseY) || footer.overNext(mouseX, mouseY)) {
                if (button == 0 && !inEditingState()) {
                    switchNamespace(footer.overNext(mouseX, mouseY));
                    playClickSound();
                }
                pressConsumed = true;
                return true;
            }

            if (footer.overPull(mouseX, mouseY)) {
                if (button == 0) {
                    ItemPuller.pull(grid().createRecipeChainMath(BookmarkGrid.DEFAULT_GROUP_ID), shift);
                    playClickSound();
                }
                pressConsumed = true;
                return true;
            }
        }

        if (overHeaderLabel(mouseX, mouseY)) {
            if (alt && button == 0) {
                grid().toggleCollapsedState(BookmarkGrid.DEFAULT_GROUP_ID);
                playClickSound();
            } else if (button == 0) {
                grid().toggleViewMode(BookmarkGrid.DEFAULT_GROUP_ID);
                playClickSound();
            } else if (button == 1) {
                grid().toggleCraftingMode(BookmarkGrid.DEFAULT_GROUP_ID);
                playClickSound();
            }

            pressConsumed = true;
            return true;
        }

        final PanelSpace space = spaceAt(mouseX, mouseY);

        if (space == null) {
            return false;
        }

        final GridLayout layout = space.layout();
        final GridGeometry geometry = space.geometry();
        final int page = space.page();
        final int gutterRow = layout.getHoveredRowIndex(mouseX, mouseY, true, page);

        pressSpace = space;
        pressTime = System.currentTimeMillis();

        if (gutterRow != -1) {
            final int groupId = layout.getRowGroupId(gutterRow, page);

            pressConsumed = true;
            pressOnGutter = true;

            if (button == 0 && shift && groupId != BookmarkGrid.DEFAULT_GROUP_ID) {
                int rowIndex = gutterRow;

                while (rowIndex >= 0 && groupId == layout.getRowGroupId(rowIndex, page)) {
                    rowIndex--;
                }

                sortableGroup = new SortableGroup(grid(), geometry, groupId);
                sortableGroup.shiftX = geometry.x - (int) mouseX;
                sortableGroup.shiftY = geometry.rowY(rowIndex + 1) - (int) mouseY;
            } else if (!shift && (button == 0 || button == 1)) {
                int targetGroupId = groupId;

                if (button == 1) {
                    targetGroupId = BookmarkGrid.DEFAULT_GROUP_ID;
                } else if (groupId == BookmarkGrid.DEFAULT_GROUP_ID) {
                    targetGroupId = Integer.MIN_VALUE;
                }

                groupingItem = new GroupingItem(layout, targetGroupId, gutterRow, page);

                if (groupingItem.startPoint.topRowIndex == -1 || groupingItem.startPoint.bottomRowIndex == -1) {
                    groupingItem = null;
                }
            }

            return true;
        }

        final BookmarksGridSlot slot = layout.getSlotMouseOver(mouseX, mouseY, page);

        if (slot == null) {
            return false;
        }

        pressConsumed = false;
        slotPress = true;

        if (button == 0 && alt) {
            final BookmarkGroup group = slot.getGroup();

            if (group.collapsed) {
                group.collapsed = false;
                playClickSound();
                grid().onItemsChanged();
            } else if (group.crafting != null && slot.getRecipeId() != null) {
                grid().toggleCollapsedRecipe(slot.getGroupId(), slot.getRecipeId());
                playClickSound();
            }

            slotPress = false;
            pressConsumed = true;
            return true;
        }

        if (button == 2) {
            HeldStack.hold(stackWithQuantity(slot));
            slotPress = false;
            pressConsumed = true;
            return true;
        }

        mouseDownSlot = slot.slotIndex;
        return true;
    }

    public static boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (!isReady() || pressSpace == null) {
            return pressConsumed;
        }

        final GridLayout layout = pressSpace.layout();
        final GridGeometry geometry = pressSpace.geometry();
        final int page = pressSpace.page();
        final long heldTime = System.currentTimeMillis() - pressTime;

        if (mouseDownSlot >= 0 && layout.getSlotBySlotIndex(page, mouseDownSlot) == null) {
            mouseDownSlot = -1;
        }

        if (groupingItem != null) {
            final int overRowIndex = geometry.getRowIndex(mouseY);

            if (groupingItem.hasEndRow() || overRowIndex != groupingItem.getStartSlotIndex(page)
                    || heldTime > GROUP_DRAG_DELAY_MS) {
                groupingItem.setEndPoint(
                        Math.max(0, Math.min(overRowIndex, groupingItem.layout.getLastRowIndex(page))), page);
            }

            return true;
        } else if (sortableGroup != null) {
            final int overRowIndex = Math.min(geometry.getRowIndex(mouseY), geometry.rows - 1);
            final NamespaceFooter.Layout footer = footer();

            if (overRowIndex >= 0 && (footer == null || !footer.overNamespaceControls(mouseX, mouseY))
                    && !sortableGroup.matches(grid(), layout.getRowGroupId(overRowIndex, page))) {
                grid().moveGroup(sortableGroup, overRowIndex, layout, page);
            }

            return true;
        } else if (sortableItem != null) {

            if (!sortableItem.isEmpty()) {
                sortableItem.mouseDragged(mouseX, mouseY, page);
            }

            return true;
        }

        if (pressConsumed) {
            return true;
        }

        if (!slotPress) {
            return false;
        }

        if (button == 0 && Screen.hasShiftDown() && mouseDownSlot >= 0) {
            final BookmarksGridSlot mouseOverSlot = layout.getSlotMouseOver(mouseX, mouseY, page);

            if (mouseOverSlot == null || mouseOverSlot.slotIndex != mouseDownSlot || heldTime > GROUP_DRAG_DELAY_MS) {
                final BookmarksGridSlot slot = layout.getSlotBySlotIndex(page, mouseDownSlot);

                if (slot != null && slot.itemIndex >= 0) {
                    sortableItem = new SortableItem(grid(), geometry, slot.getBookmarkItem());
                    grid().onGridChanged();
                }
            }

            return true;
        }

        if (mouseDownSlot >= 0 && !HeldStack.isHeld() && carried().isEmpty()) {
            final BookmarksGridSlot mouseOverSlot = layout.getSlotMouseOver(mouseX, mouseY, page);

            if (mouseOverSlot == null || mouseOverSlot.slotIndex != mouseDownSlot || heldTime > ITEM_DRAG_DELAY_MS) {
                final BookmarksGridSlot slot = layout.getSlotBySlotIndex(page, mouseDownSlot);

                if (slot != null) {
                    HeldStack.hold(stackWithQuantity(slot));
                }

                mouseDownSlot = -1;
            }
        }

        return true;
    }

    public static boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!isEmiReady()) {
            resetGesture();
            return false;
        }

        boolean handled = false;

        try {
            if (sortableItem != null || sortableGroup != null) {
                grid().onItemsChanged();
                handled = true;
            } else if (groupingItem != null && groupingItem.hasEndRow()) {
                grid().createGroup(groupingItem, groupingItem.layout);
                handled = true;
            } else if (pressConsumed) {

                if (pressOnGutter && pressSpace != null) {
                    final GridLayout layout = pressSpace.layout();
                    final int page = pressSpace.page();
                    final int rowIndex = layout.getHoveredRowIndex(mouseX, mouseY, true, page);
                    final int groupId = rowIndex != -1 ? layout.getRowGroupId(rowIndex, page)
                            : BookmarkGrid.DEFAULT_GROUP_ID;

                    if (groupId != BookmarkGrid.DEFAULT_GROUP_ID) {
                        if (button == 0 && Screen.hasAltDown()) {
                            grid().toggleCollapsedState(groupId);
                            playClickSound();
                        } else if (button == 0) {
                            grid().toggleViewMode(groupId);
                            playClickSound();
                        } else if (button == 1) {
                            grid().toggleCraftingMode(groupId);
                            playClickSound();
                        }
                    }
                }

                handled = true;
            } else if (slotPress && pressSpace != null) {
                final BookmarksGridSlot slot = pressSpace.layout().getSlotMouseOver(mouseX, mouseY,
                        pressSpace.page());

                if (slot != null && slot.slotIndex == mouseDownSlot && !HeldStack.isHeld()) {
                    clickSlot(slot, button);
                }

                handled = true;
            } else if (isReady() && !EmiScreenManager.draggedStack.isEmpty() && spaceAt(mouseX, mouseY) != null) {
                dropStack(EmiScreenManager.draggedStack);
                handled = true;
            }
        } finally {
            if (handled) {
                clearEmiPress();
            }

            resetGesture();
        }

        return handled;
    }

    /** NEI's PanelWidget.mouseUp: recipes/usages, or in cheat mode give the item (Shift still opens recipes). */
    private static void clickSlot(BookmarksGridSlot slot, int button) {
        final EmiStack stack = stackWithQuantity(slot);

        if (stack.isEmpty()) {
            return;
        }

        // Shift + click fills the crafting grid with the bookmarked recipe (Ctrl: with the bookmarked quantity)
        if (button == 0 && Screen.hasShiftDown() && fillCraftingGrid(slot)) {
            return;
        }

        if (Minecraft.getInstance().screen instanceof RecipeScreen || Screen.hasShiftDown() || !EmiApi.isCheatMode()
                || stack.getItemStack().isEmpty()) {

            if (button == 0) {
                EmiApi.displayRecipes(stack);

                if (slot.getEmiRecipeContext() != null) {
                    EmiApi.focusRecipe(slot.getEmiRecipeContext());
                }
            } else if (button == 1) {
                EmiApi.displayUses(stack);
            }

        } else {
            final ItemStack item = stack.getItemStack().copy();
            final int amount = button == 1 ? 1
                    : Screen.hasControlDown() ? item.getMaxStackSize()
                            : (int) Math.min(stack.getAmount(), item.getMaxStackSize());

            give(item, amount);
        }
    }

    static boolean canFillCraftingGrid(BookmarksGridSlot slot) {
        final EmiRecipe recipe = slot.getEmiRecipeContext();
        return recipe != null && !(Minecraft.getInstance().screen instanceof RecipeScreen)
                && RecipeOverlay.canFill(recipe);
    }

    private static boolean fillCraftingGrid(BookmarksGridSlot slot) {
        if (!canFillCraftingGrid(slot)) {
            return false;
        }

        final int multiplier = Screen.hasControlDown() ? (int) Math.min(64, slot.getMultiplier()) : 0;
        RecipeOverlay.fill(slot.getEmiRecipeContext(), multiplier);
        return true;
    }

    /** EMI's way of cheating in items: its packet when EMI is on the server, otherwise /give. */
    private static void give(ItemStack item, int amount) {
        final Minecraft minecraft = Minecraft.getInstance();

        if (item.isEmpty() || amount <= 0 || minecraft.player == null) {
            return;
        }

        item.setCount(amount);

        if (EmiClient.onServer) {
            EmiNetwork.sendToServer(new CreateItemC2SPacket(0, item));
        } else {
            String command = "give @s " + EmiPort.getItemRegistry().getKey(item.getItem());

            if (item.hasTag()) {
                command += item.getTag().toString();
            }

            command += " " + amount;

            if (command.length() < 256) {
                minecraft.player.connection.sendCommand(command);
            }
        }
    }

    /** A stack dragged from elsewhere in EMI and dropped on the panel becomes a bookmark (NEI's item panel drop). */
    private static void dropStack(EmiIngredient dragged) {
        if (dragged instanceof BookmarkSlotStack) {
            return;
        }

        final EmiIngredient ingredient = dragged instanceof EmiFavorite favorite ? favorite.getStack() : dragged;
        final List<EmiStack> stacks = ingredient.getEmiStacks();

        if (!stacks.isEmpty() && !stacks.get(0).isEmpty()) {
            ItemShortcuts.addItem(Stacks.of(stacks.get(0), 1), 0, null, BookmarkGrid.DEFAULT_GROUP_ID);
        }
    }

    public static boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!isReady() || amount == 0) {
            return false;
        }

        final int shift = amount > 0 ? 1 : -1;
        final NamespaceFooter.Layout footer = footer();

        if ((!inEditingState() || sortableGroup != null) && footer != null
                && footer.overNamespaceControls(mouseX, mouseY)) {
            switchNamespace(shift < 0);
            return true;
        }

        final boolean header = overHeaderLabel(mouseX, mouseY);
        final PanelSpace space = spaceAt(mouseX, mouseY);

        if (space == null && !header) {
            return false;
        }

        if (!inEditingState() && Screen.hasControlDown()) {

            if (header) {
                grid().shiftGroupAmount(BookmarkGrid.DEFAULT_GROUP_ID, (Screen.hasAltDown() ? 64 : 1) * shift);
                return true;
            }

            final GridLayout layout = space.layout();
            final int page = space.page();
            final BookmarksGridSlot slot = layout.getSlotMouseOver(mouseX, mouseY, page);

            if (slot != null) {
                final long multiplier = Screen.hasAltDown() ? scrollMultiplier(slot.getBookmarkItem().stack) : 1;
                grid().shiftItemAmount(slot.itemIndex, multiplier * shift, layout);
                return true;
            }

            final int groupId = hoveredGroupId(mouseX, mouseY);

            if (groupId != -1) {
                grid().shiftGroupAmount(groupId, (Screen.hasAltDown() ? 64 : 1) * shift);
                return true;
            }
        }

        if (!inEditingState() && Screen.hasShiftDown() && space != null) {
            final BookmarksGridSlot slot = space.layout().getSlotMouseOver(mouseX, mouseY, space.page());

            if (slot != null && cyclePermutation(slot, shift)) {
                return true;
            }
        }

        return false;
    }

    /** NEI's getScrollMultiplier: a stack for items, 144 for fluids. */
    private static long scrollMultiplier(ChainStack stack) {
        if (stack.fluid) {
            return 144;
        }

        return stack.emi != null && !stack.emi.getItemStack().isEmpty() ? stack.emi.getItemStack().getMaxStackSize()
                : 64;
    }

    /** Shift + scroll over an ingredient with alternatives switches the alternative used (NEI's accepts tooltip). */
    private static boolean cyclePermutation(BookmarksGridSlot slot, int shift) {
        final ChainItem item = slot.getBookmarkItem();

        if (item.type != ChainItem.Type.INGREDIENT || item.recipe == null || item.permutations.size() <= 1) {
            return false;
        }

        final int targetItemIndex = Math.abs(slot.itemIndex);
        final ChainItem oldItem = grid().getBookmarkItem(targetItemIndex);

        if (oldItem == null) {
            return false;
        }

        final List<ChainStack> items = new ArrayList<>(oldItem.permutations.values());
        int stackIndex = 0;

        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).sameType(oldItem.stack)) {
                stackIndex = i;
                break;
            }
        }

        final ChainStack activeStack = items.get(Math.floorMod(stackIndex - shift, items.size()));
        grid().replaceBookmarkItem(targetItemIndex, oldItem.copyWithPerm(activeStack));
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Keys (called before EMI handles them)

    public static boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isEmiReady() || isTyping()) {
            return false;
        }

        if (Keys.matches(Keys.HIDE, keyCode, scanCode)) {
            Config.setBookmarksHidden(!Config.bookmarksHidden());
            resetGesture();
            HeldStack.clear();
            return true;
        }

        final double mouseX = EmiScreenManager.lastMouseX;
        final double mouseY = EmiScreenManager.lastMouseY;

        if (isReady() && overPanel(mouseX, mouseY)) {
            final PanelSpace space = mainSpace();

            if (space != null && Keys.matches(Keys.NEXT_PAGE, keyCode, scanCode)) {
                space.panel().scroll(1);
                return true;
            }

            if (space != null && Keys.matches(Keys.PREV_PAGE, keyCode, scanCode)) {
                space.panel().scroll(-1);
                return true;
            }
        }

        return ItemShortcuts.keyPressed(keyCode, scanCode, mouseX, mouseY);
    }

}
