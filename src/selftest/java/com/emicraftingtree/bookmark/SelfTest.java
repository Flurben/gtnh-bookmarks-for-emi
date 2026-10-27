package com.emicraftingtree.bookmark;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.emicraftingtree.EmiCraftingTree;
import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.RecipeKey;
import com.emicraftingtree.chain.Stacks;
import com.emicraftingtree.mixin.RecipeScreenAccessor;
import com.emicraftingtree.selftest.TestInput;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.config.SidebarSide;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.screen.EmiScreenManager.SidebarPanel;
import dev.emi.emi.screen.RecipeScreen;
import dev.emi.emi.screen.WidgetGroup;

/**
 * Dev-only scripted run (./gradlew runClient -Pselftest): creates a flat survival world, sets up bookmark groups,
 * drives the panel through EMI's real input entry points, auto-crafts a torch chain in the inventory and checks that
 * bookmarks survive a reload. Screenshots go to run/screenshots. Not part of the released jar.
 */
@Mod.EventBusSubscriber(modid = EmiCraftingTree.MOD_ID, value = Dist.CLIENT)
public final class SelfTest {

    private static final boolean ENABLED = Boolean.getBoolean("emicraftingtree.selftest");
    private static final String WORLD = "emicraftingtree-selftest";
    private static final String BAMBOO_STICK = "minecraft:stick_from_bamboo_item";
    private static final String AXE_PLANKS = "emicraftingtree:selftest_axe_planks";

    private static boolean worldRequested;
    private static int ticks = -1;
    private static int step;
    private static int wait;
    private static int chainGroup;
    private static int axeGroup;
    private static long planksBefore;
    private static final List<String> failures = new ArrayList<>();

    private SelfTest() {}

    private static void check(boolean condition, String message) {
        EmiCraftingTree.LOGGER.info("[selftest] {} {}", condition ? "PASS" : "FAIL", message);
        if (!condition) {
            failures.add(message);
        }
    }

    private static EmiRecipe recipe(String id) {
        final EmiRecipe recipe = EmiApi.getRecipeManager().getRecipe(new ResourceLocation(id));

        if (recipe == null) {
            throw new IllegalStateException("Missing recipe " + id);
        }

        return recipe;
    }

    private static void screenshot(String name) {
        final Minecraft minecraft = Minecraft.getInstance();
        Screenshot.grab(minecraft.gameDirectory, "selftest-" + name + ".png", minecraft.getMainRenderTarget(),
                message -> EmiCraftingTree.LOGGER.info("[selftest] {}", message.getString()));
    }

    /** Puts the mouse at a GUI position, as if the player moved it there. */
    private static void moveMouse(double guiX, double guiY) throws ReflectiveOperationException {
        final Minecraft minecraft = Minecraft.getInstance();
        final double scale = minecraft.getWindow().getGuiScale();
        final MouseHandler mouse = minecraft.mouseHandler;
        final Field xpos = MouseHandler.class.getDeclaredField("xpos");
        final Field ypos = MouseHandler.class.getDeclaredField("ypos");

        xpos.setAccessible(true);
        ypos.setAccessible(true);
        xpos.setDouble(mouse, guiX * scale);
        ypos.setDouble(mouse, guiY * scale);
        EmiScreenManager.lastMouseX = (int) guiX;
        EmiScreenManager.lastMouseY = (int) guiY;
    }

    private static BookmarkPanel.PanelSpace panel() {
        for (BookmarkPanel.PanelSpace space : BookmarkPanel.spaces()) {
            if (space.main()) {
                return space;
            }
        }
        throw new IllegalStateException("No favorites sidebar visible");
    }

    private static double gutterX(BookmarkPanel.PanelSpace space) {
        return space.geometry().x - 3;
    }

    private static double rowY(BookmarkPanel.PanelSpace space, int row) {
        return space.geometry().rowY(row) + 9;
    }

    private static int firstRowOf(BookmarkPanel.PanelSpace space, int groupId) {
        final GridLayout layout = space.layout();
        for (int row = 0; row < space.geometry().rows; row++) {
            if (layout.getRowGroupId(row, space.page()) == groupId) {
                return row;
            }
        }
        return -1;
    }

    private static long countInInventory(net.minecraft.world.item.Item item) {
        final Minecraft minecraft = Minecraft.getInstance();
        long count = 0;
        for (ItemStack stack : minecraft.player.getInventory().items) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void addRecipe(BookmarkGrid grid, String id, int groupId, long multiplier) {
        grid.addRecipe(RecipeBookmarks.build(recipe(id), groupId, multiplier, null), groupId);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {

        if (!ENABLED || event.phase != TickEvent.Phase.END) {
            return;
        }

        final Minecraft minecraft = Minecraft.getInstance();

        try {

            if (!worldRequested) {
                if (minecraft.getOverlay() == null && minecraft.level == null) {
                    worldRequested = true;
                    minecraft.createWorldOpenFlows().createFreshLevel(
                            WORLD,
                            new LevelSettings(WORLD, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true,
                                    new GameRules(), WorldDataConfiguration.DEFAULT),
                            new WorldOptions(0L, false, false),
                            registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                                    .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
                }
                return;
            }

            if (minecraft.player == null || !EmiReloadManager.isLoaded()) {
                return;
            }

            ticks++;

            if (ticks < 40 || ticks % 20 != 0) {
                return;
            }

            if (wait > 0) {
                wait--;
                return;
            }

            EmiCraftingTree.LOGGER.info("[selftest] step {}", step);
            final BookmarkGrid grid = BookmarkStorage.grid();

            switch (step++) {
                case 0 -> {
                    minecraft.getSingleplayerServer().execute(() -> {
                        final ServerPlayer player = minecraft.getSingleplayerServer().getPlayerList().getPlayers()
                                .get(0);
                        player.getInventory().clearContent();
                        player.getInventory().add(new ItemStack(Items.OAK_LOG, 2));
                        player.getInventory().add(new ItemStack(Items.COAL, 4));
                        player.inventoryMenu.broadcastChanges();
                    });

                    final SidebarPanel left = EmiScreenManager.getPanelFor(SidebarSide.LEFT);
                    check(left != null && left.supportsType(dev.emi.emi.config.SidebarType.FAVORITES),
                            "left sidebar can show favorites");
                    minecraft.setScreen(new InventoryScreen(minecraft.player));
                }
                case 1 -> {
                    BookmarkStorage.ensureLoaded();
                    grid.clear();

                    grid.addItem(ChainItem.ofItem(0, Stacks.of(EmiStack.of(Items.DIAMOND), 1), 0), true);

                    chainGroup = grid.addGroup(new BookmarkGroup(BookmarkViewMode.TODO_LIST, true));
                    addRecipe(grid, "minecraft:torch", chainGroup, 4);
                    addRecipe(grid, "minecraft:stick", chainGroup, 1);
                    addRecipe(grid, "minecraft:oak_planks", chainGroup, 1);

                    final int cakeGroup = grid.addGroup(new BookmarkGroup(BookmarkViewMode.TODO_LIST, false));
                    addRecipe(grid, "minecraft:cake", cakeGroup, 1);
                    addRecipe(grid, "minecraft:sugar_from_sugar_cane", cakeGroup, 1);

                    final GridLayout layout = panel().layout();
                    check(layout.getRowGroupId(0, 0) == 0, "default group on the first row");
                    check(firstRowOf(panel(), chainGroup) == 1, "chain group starts on the second row");
                    moveMouse(5, 5);
                }
                case 2 -> {
                    screenshot("01-groups");
                    final BookmarkGroup group = grid.getGroup(chainGroup);
                    grid.ensureClean();
                    check(group.crafting.outputRecipes.size() == 1, "chain has one output recipe (torch)");
                    check(group.crafting.recipeInMiddle.size() == 2, "stick and planks are crafted in the chain");

                    final BookmarkPanel.PanelSpace space = panel();
                    moveMouse(gutterX(space), rowY(space, firstRowOf(space, chainGroup)));
                }
                case 3 -> screenshot("02-group-tooltip");
                case 4 -> {
                    // LMB on the gutter toggles the view mode, like NEI
                    final BookmarkPanel.PanelSpace space = panel();
                    final double x = gutterX(space);
                    final double y = rowY(space, firstRowOf(space, chainGroup));
                    check(EmiScreenManager.mouseClicked(x, y, 0), "gutter click consumed");
                    check(EmiScreenManager.mouseReleased(x, y, 0), "gutter release consumed");
                    check(grid.getViewMode(chainGroup) == BookmarkViewMode.DEFAULT, "LMB switched to DEFAULT mode");
                    moveMouse(5, 5);
                }
                case 5 -> {
                    screenshot("03-chain-default-mode");
                    final BookmarkPanel.PanelSpace space = panel();
                    final double x = gutterX(space);
                    final double y = rowY(space, firstRowOf(space, chainGroup));
                    EmiScreenManager.mouseClicked(x, y, 0);
                    EmiScreenManager.mouseReleased(x, y, 0);
                    check(grid.getViewMode(chainGroup) == BookmarkViewMode.TODO_LIST, "LMB switched back");
                }
                case 6 -> {
                    // RMB on a default row's gutter does nothing; LMB-drag from the chain group's first row up over
                    // the diamond row includes that row in the group (NEI's "Create/Include Group")
                    final BookmarkPanel.PanelSpace space = panel();
                    final double x = gutterX(space);
                    EmiScreenManager.mouseClicked(x, rowY(space, 0), 1);
                    EmiScreenManager.mouseReleased(x, rowY(space, 0), 1);
                    check(grid.isCraftingMode(chainGroup), "RMB on the default row does not touch the chain");

                    final int chainRow = firstRowOf(space, chainGroup);
                    EmiScreenManager.mouseClicked(x, rowY(space, chainRow), 0);
                    EmiScreenManager.mouseDragged(x, rowY(space, 0), 0, 0, 0);
                    EmiScreenManager.mouseReleased(x, rowY(space, 0), 0);
                    check(diamond(grid).groupId == chainGroup, "dragging in the gutter added the diamond row to the group");
                }
                case 7 -> {
                    screenshot("04-after-grouping");
                    final BookmarkPanel.PanelSpace space = panel();
                    final double x = gutterX(space);
                    final int row = firstRowOf(space, chainGroup);
                    // RMB-drag on the first row takes the diamond back out of the group
                    EmiScreenManager.mouseClicked(x, rowY(space, row), 1);
                    EmiScreenManager.mouseDragged(x, rowY(space, row), 1, 0, 0);
                    pressHeld();
                    EmiScreenManager.mouseDragged(x, rowY(space, row), 1, 0, 0);
                    EmiScreenManager.mouseReleased(x, rowY(space, row), 1);
                    check(diamond(grid).groupId != chainGroup, "RMB drag excluded the diamond row again");
                }
                case 8 -> {
                    // the favorite key (A) over an index stack adds it, over the bookmark removes it
                    final SidebarPanel right = EmiScreenManager.getPanelFor(SidebarSide.RIGHT);
                    final double x = right.space.tx + 9;
                    final double y = right.space.ty + 9;
                    moveMouse(x, y);
                    final int before = grid.size();
                    check(EmiScreenManager.keyPressed(GLFW.GLFW_KEY_A, 30, 0), "A over the index consumed");
                    check(grid.size() == before + 1, "A over the index added a bookmark");
                }
                case 9 -> {
                    screenshot("05-added-from-index");
                    final int before = grid.size();
                    final ChainItem last = grid.getBookmarkItems().get(grid.size() - 1);
                    final BookmarkPanel.PanelSpace space = panel();
                    final GridLayout layout = space.layout();
                    final int itemIndex = grid.getBookmarkItems().indexOf(last);
                    final int slot = layout.getAbsoluteSlotIndex(itemIndex) % space.geometry().perPage();
                    moveMouse(space.geometry().slotX(slot) + 9, space.geometry().slotY(slot) + 9);
                    check(EmiScreenManager.keyPressed(GLFW.GLFW_KEY_A, 30, 0), "A over a bookmark consumed");
                    check(grid.size() == before - 1, "A over the bookmark removed it");
                }
                case 10 -> {
                    // hover the torch result: EMI shows our tooltip and knows its recipe
                    final BookmarkPanel.PanelSpace space = panel();
                    final int row = firstRowOf(space, chainGroup);
                    moveMouse(space.geometry().x + 9, rowY(space, row));
                    final var hovered = EmiApi.getHoveredStack(false);
                    check(hovered.getStack() instanceof BookmarkSlotStack, "EMI sees the bookmark under the mouse");
                    check(hovered.getRecipeContext() != null
                            && "minecraft:torch".equals(String.valueOf(hovered.getRecipeContext().getId())),
                            "the bookmark carries the torch recipe for EMI");
                }
                case 11 -> {
                    screenshot("06-slot-tooltip");
                    check(AutoCrafting.start(grid.createRecipeChainMath(chainGroup), false),
                            "auto-crafting started");
                }
                case 12, 13, 14, 15, 16, 17 -> {
                    if (AutoCrafting.processing()) {
                        return;
                    }
                    step = 18;
                    final long torches = countInInventory(Items.TORCH);
                    check(torches == 16, "auto-crafting made 16 torches (got " + torches + ")");
                    check(countInInventory(Items.COAL) == 0, "all coal was used");
                    check(countInInventory(Items.OAK_LOG) == 1, "one log was left");
                    check(countInInventory(Items.OAK_PLANKS) == 2, "two planks were left over");
                    moveMouse(5, 5);
                }
                case 18 -> {
                    screenshot("07-after-autocraft");
                    final int size = grid.size();
                    final int groups = (int) grid.getBookmarkItems().stream().mapToInt(item -> item.groupId)
                            .distinct().count();
                    final long torchMultiplier = torch(grid).multiplier;
                    BookmarkStorage.unload();
                    BookmarkStorage.ensureLoaded();
                    final BookmarkGrid reloaded = BookmarkStorage.grid();
                    check(reloaded.size() == size,
                            "bookmarks reloaded from disk (" + reloaded.size() + "/" + size + ")");
                    check(reloaded.getBookmarkItems().stream().mapToInt(item -> item.groupId).distinct()
                            .count() == groups, "groups reloaded from disk");
                    check(reloaded.isCraftingMode(chainGroup), "crafting chain mode reloaded");
                    check(torch(reloaded).multiplier == torchMultiplier, "quantities reloaded");
                    final RecipeKey torch = torch(reloaded).recipe;
                    check(torch != null && torch.getRecipe() != null, "recipes resolve after reload");
                }
                case 19 -> {
                    grid.toggleCollapsedRecipe(chainGroup, RecipeKey.of(recipe("minecraft:stick")));
                    moveMouse(5, 5);
                }
                case 20 -> {
                    screenshot("08-collapsed-recipe");
                    grid.toggleCollapsedRecipe(chainGroup, RecipeKey.of(recipe("minecraft:stick")));
                    grid.toggleCollapsedState(chainGroup);
                }
                case 21 -> {
                    screenshot("09-collapsed-group");
                    grid.toggleCollapsedState(chainGroup);
                }
                case 22 -> {
                    // S over a bookmarked recipe shows NEI's recipe overlay in the crafting grid
                    moveToTorch();
                    check(EmiScreenManager.keyPressed(GLFW.GLFW_KEY_S, 31, 0), "S over the torch consumed");
                    check(RecipeOverlay.isActive(), "S showed the recipe overlay");
                }
                case 23 -> {
                    moveMouse(5, 5);
                    screenshot("10-recipe-overlay");
                    check(EmiScreenManager.keyPressed(GLFW.GLFW_KEY_S, 31, 0), "S over nothing consumed");
                    check(!RecipeOverlay.isActive(), "S over nothing cleared the overlay");
                }
                case 24 -> {
                    // LMB on a bookmark opens its recipes (NEI), it does not craft (EMI)
                    final double[] torch = moveToTorch();
                    EmiScreenManager.mouseClicked(torch[0], torch[1], 0);
                    EmiScreenManager.mouseReleased(torch[0], torch[1], 0);
                    check(minecraft.screen instanceof RecipeScreen, "LMB on a bookmark opened its recipes");
                }
                case 25 -> {
                    screenshot("11-lmb-recipes");
                    minecraft.setScreen(new InventoryScreen(minecraft.player));
                }
                case 26 -> {
                    // MMB picks the bookmark up; a click outside the GUI lets it go
                    final double[] torch = moveToTorch();
                    check(EmiScreenManager.mouseClicked(torch[0], torch[1], 2), "MMB on a bookmark consumed");
                    EmiScreenManager.mouseReleased(torch[0], torch[1], 2);
                    check(HeldStack.isHeld(), "MMB picked the bookmark up");
                    moveMouse(torch[0] + 60, torch[1] + 20);
                }
                case 27 -> {
                    screenshot("12-held-stack");
                    final double x = minecraft.screen.width / 2.0;
                    EmiScreenManager.mouseClicked(x, 4, 0);
                    EmiScreenManager.mouseReleased(x, 4, 0);
                    check(!HeldStack.isHeld(), "clicking outside the GUI dropped the held bookmark");
                }
                case 28 -> {
                    // the footer arrows switch bookmark namespaces
                    final NamespaceFooter.Layout footer = NamespaceFooter.layout(panel().geometry());
                    check(footer != null && footer.visible(), "namespace footer visible");
                    EmiScreenManager.mouseClicked(footer.nextX() + 8, footer.y() + 8, 0);
                    EmiScreenManager.mouseReleased(footer.nextX() + 8, footer.y() + 8, 0);
                    check(BookmarkStorage.getActiveIndex() == 1 && BookmarkStorage.grid().isEmpty(),
                            "next arrow opened a new empty namespace");
                    moveMouse(5, 5);
                }
                case 29 -> {
                    screenshot("13-second-namespace");
                    final NamespaceFooter.Layout footer = NamespaceFooter.layout(panel().geometry());
                    EmiScreenManager.mouseClicked(footer.prevX() + 8, footer.y() + 8, 0);
                    EmiScreenManager.mouseReleased(footer.prevX() + 8, footer.y() + 8, 0);
                    check(BookmarkStorage.getActiveIndex() == 0 && !BookmarkStorage.grid().isEmpty(),
                            "previous arrow went back to the first namespace");
                }
                case 30 -> {
                    // RMB on the page label toggles the default group's crafting chain (green brackets)
                    final SidebarPanel side = panel().panel();
                    final double x = (side.cycle.getX() + side.cycle.getWidth() + side.pageRight.getX()) / 2.0;
                    final double y = side.pageLeft.getY() + 8;
                    EmiScreenManager.mouseClicked(x, y, 1);
                    EmiScreenManager.mouseReleased(x, y, 1);
                    check(BookmarkStorage.grid().isCraftingMode(0), "RMB on the page label made a crafting chain");
                    moveMouse(x, y);
                }
                case 31 -> {
                    screenshot("14-label-tooltip");
                    final SidebarPanel side = panel().panel();
                    final double x = (side.cycle.getX() + side.cycle.getWidth() + side.pageRight.getX()) / 2.0;
                    final double y = side.pageLeft.getY() + 8;
                    EmiScreenManager.mouseClicked(x, y, 1);
                    EmiScreenManager.mouseReleased(x, y, 1);
                    check(!BookmarkStorage.grid().isCraftingMode(0), "RMB again turned it off");
                    // B hides the panel
                    check(EmiScreenManager.keyPressed(GLFW.GLFW_KEY_B, 48, 0), "B consumed");
                    check(BookmarkPanel.sidebarStacks().isEmpty(), "B hid the bookmark panel");
                    moveMouse(5, 5);
                }
                case 32 -> {
                    screenshot("15-hidden");
                    EmiScreenManager.keyPressed(GLFW.GLFW_KEY_B, 48, 0);
                    check(!BookmarkPanel.sidebarStacks().isEmpty(), "B showed it again");
                }
                case 33 -> {
                    // make the bamboo stick recipe the stick's favorite (EMI's default recipe) and find the stick
                    BoM.addedRecipes.put(EmiStack.of(Items.STICK), recipe(BAMBOO_STICK));
                    check(FavoriteRecipes.getFavorite(EmiStack.of(Items.STICK)) == recipe(BAMBOO_STICK),
                            "the default recipe is the stick's favorite");
                    EmiScreenManager.search.setValue("stick");
                }
                case 34 -> {
                    // Shift + A on the stick in EMI's item list bookmarks its favorite recipe at x0 (NEI)
                    final double[] stick = moveToIndexStack(EmiStack.of(Items.STICK));
                    check(stick != null, "the stick is in the item list");
                    TestInput.hold(GLFW.GLFW_KEY_LEFT_SHIFT);
                    check(key(GLFW.GLFW_KEY_A, 30), "Shift + A over the item list consumed");
                    TestInput.release();

                    final ChainItem result = recipeItem(grid, BAMBOO_STICK, ChainItem.Type.RESULT);
                    final ChainItem bamboo = recipeItem(grid, BAMBOO_STICK, ChainItem.Type.INGREDIENT);
                    check(result != null && bamboo != null && bamboo.stack.key.equals("item:minecraft:bamboo"),
                            "Shift + A added the stick with its favorite recipe and ingredients");
                    check(result != null && result.multiplier == 0 && result.groupId == 0,
                            "the recipe went to the default group at x0");

                    if (result != null && bamboo != null) {
                        final GridLayout layout = panel().layout();
                        final int columns = layout.getColumns();
                        final int resultSlot = layout.getAbsoluteSlotIndex(grid.getBookmarkItems().indexOf(result));
                        final int bambooSlot = layout.getAbsoluteSlotIndex(grid.getBookmarkItems().indexOf(bamboo));
                        final int diamondSlot = layout.getAbsoluteSlotIndex(grid.getBookmarkItems().indexOf(diamond(grid)));
                        check(resultSlot % columns == 0 && bambooSlot == resultSlot + 1,
                                "the recipe sits on its own row, result first");
                        check(diamondSlot % columns == 0 && diamondSlot / columns != resultSlot / columns,
                                "a plain item gets its own row too");
                    }

                    moveMouse(5, 5);
                }
                case 35 -> {
                    screenshot("16-shift-a-favorite");
                    // Shift + A over the bookmarked result removes the whole recipe
                    moveTo(recipeItem(grid, BAMBOO_STICK, ChainItem.Type.RESULT));
                    TestInput.hold(GLFW.GLFW_KEY_LEFT_SHIFT);
                    key(GLFW.GLFW_KEY_A, 30);
                    TestInput.release();
                    check(recipeItem(grid, BAMBOO_STICK, ChainItem.Type.RESULT) == null
                            && recipeItem(grid, BAMBOO_STICK, ChainItem.Type.INGREDIENT) == null,
                            "Shift + A over the bookmark removed the recipe");
                }
                case 36 -> {
                    // Ctrl + Shift + A keeps the count: x1
                    moveToIndexStack(EmiStack.of(Items.STICK));
                    TestInput.hold(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_LEFT_CONTROL);
                    key(GLFW.GLFW_KEY_A, 30);
                    TestInput.release();
                    final ChainItem result = recipeItem(grid, BAMBOO_STICK, ChainItem.Type.RESULT);
                    check(result != null && result.multiplier == 1, "Ctrl + Shift + A added the recipe at x1");

                    // without a default recipe the stick (two recipes) falls back to the one EMI prefers
                    BoM.addedRecipes.remove(EmiStack.of(Items.STICK));
                    check(FavoriteRecipes.getFavorite(EmiStack.of(Items.STICK)) == null,
                            "no favorite without a default recipe");
                    check(FavoriteRecipes.getPreferred(EmiStack.of(Items.STICK)) != null,
                            "Shift + A still finds a recipe without a favorite");
                    EmiScreenManager.search.setValue("");
                }
                case 37 -> {
                    // Alt shows NEI's hotkey list on a bookmark
                    final double[] torch = moveToTorch();
                    TestInput.hold(GLFW.GLFW_KEY_LEFT_ALT);
                    final BookmarksGridSlot slot = panel().layout().getSlotMouseOver(torch[0], torch[1], panel().page());
                    check(slot != null && Hotkeys.slot(slot).size() > 5, "Alt lists the bookmark hotkeys");
                }
                case 38 -> {
                    screenshot("17-alt-hotkeys");
                    TestInput.release();

                    // Ctrl + C copies the name
                    final String clipboard = minecraft.keyboardHandler.getClipboard();
                    moveToTorch();
                    TestInput.hold(GLFW.GLFW_KEY_LEFT_CONTROL);
                    key(GLFW.GLFW_KEY_C, 46);
                    TestInput.release();
                    final String copied = minecraft.keyboardHandler.getClipboard();
                    minecraft.keyboardHandler.setClipboard(clipboard);
                    check("Torch".equals(copied), "Ctrl + C copied the name (" + copied + ")");
                }
                case 39 -> {
                    // Ctrl + scroll changes the quantity
                    final double[] torch = moveToTorch();
                    final long before = torch(grid).multiplier;
                    TestInput.hold(GLFW.GLFW_KEY_LEFT_CONTROL);
                    EmiScreenManager.mouseScrolled(torch[0], torch[1], 1);
                    final long up = torch(grid).multiplier;
                    EmiScreenManager.mouseScrolled(torch[0], torch[1], -1);
                    TestInput.release();
                    check(up > before, "Ctrl + scroll up raised the quantity (" + before + " -> " + up + ")");
                    check(torch(grid).multiplier == before, "Ctrl + scroll down lowered it again");
                }
                case 40 -> {
                    // Alt + LMB on the bracket collapses the group, and again expands it
                    final BookmarkPanel.PanelSpace space = panel();
                    final double x = gutterX(space);
                    final double y = rowY(space, firstRowOf(space, chainGroup));
                    TestInput.hold(GLFW.GLFW_KEY_LEFT_ALT);
                    EmiScreenManager.mouseClicked(x, y, 0);
                    EmiScreenManager.mouseReleased(x, y, 0);
                    final boolean collapsed = grid.getGroup(chainGroup).collapsed;
                    EmiScreenManager.mouseClicked(x, rowY(panel(), firstRowOf(panel(), chainGroup)), 0);
                    EmiScreenManager.mouseReleased(x, rowY(panel(), firstRowOf(panel(), chainGroup)), 0);
                    TestInput.release();
                    check(collapsed, "Alt + LMB collapsed the group");
                    check(!grid.getGroup(chainGroup).collapsed, "Alt + LMB expanded it again");
                }
                case 41 -> EmiApi.displayRecipes(EmiStack.of(Items.TORCH));
                case 42 -> {
                    // in EMI's recipe view A adds only the item, Shift + A the recipe
                    check(minecraft.screen instanceof RecipeScreen, "the torch recipes are open");
                    final double[] output = moveToRecipeOutput("minecraft:torch");
                    check(output != null, "the torch recipe's output slot is on the page");
                    final int size = grid.size();
                    final long ingredients = countType(grid, ChainItem.Type.INGREDIENT);

                    check(minecraft.screen.keyPressed(GLFW.GLFW_KEY_A, 30, 0), "A in the recipe view consumed");
                    check(grid.size() == size + 1 && countType(grid, ChainItem.Type.INGREDIENT) == ingredients,
                            "A in the recipe view added only the item");
                    minecraft.screen.keyPressed(GLFW.GLFW_KEY_A, 30, 0);
                    check(grid.size() == size, "A again removed it");

                    TestInput.hold(GLFW.GLFW_KEY_LEFT_SHIFT);
                    check(minecraft.screen.keyPressed(GLFW.GLFW_KEY_A, 30, GLFW.GLFW_MOD_SHIFT),
                            "Shift + A in the recipe view consumed");
                    TestInput.release();
                    check(grid.getBookmarkItems().stream().anyMatch(item -> item.groupId == 0
                            && item.type == ChainItem.Type.INGREDIENT && item.recipe != null
                            && item.recipe.id.equals("minecraft:torch")),
                            "Shift + A in the recipe view added the recipe with its ingredients");
                }
                case 43 -> {
                    screenshot("19-recipe-view-shift-a");
                    TestInput.hold(GLFW.GLFW_KEY_LEFT_SHIFT);
                    minecraft.screen.keyPressed(GLFW.GLFW_KEY_A, 30, GLFW.GLFW_MOD_SHIFT);
                    TestInput.release();
                    check(grid.getBookmarkItems().stream().noneMatch(item -> item.groupId == 0
                            && item.recipe != null && item.recipe.id.equals("minecraft:torch")),
                            "Shift + A again removed the recipe");
                    check(torch(grid).groupId == chainGroup, "the chain's torch recipe was left alone");
                    minecraft.setScreen(new InventoryScreen(minecraft.player));
                }
                case 44 -> {
                    // a recipe taking any axe: the inventory only has worn axes with extra NBT, none of them the
                    // axe the bookmark shows
                    minecraft.getSingleplayerServer().execute(() -> {
                        final ServerPlayer player = minecraft.getSingleplayerServer().getPlayerList().getPlayers()
                                .get(0);
                        final ItemStack stoneAxe = new ItemStack(Items.STONE_AXE);
                        stoneAxe.setDamageValue(50);
                        stoneAxe.setHoverName(Component.literal("Old Axe"));
                        final ItemStack goldenAxe = new ItemStack(Items.GOLDEN_AXE);
                        goldenAxe.setDamageValue(10);
                        player.getInventory().add(stoneAxe);
                        player.getInventory().add(goldenAxe);
                        player.getInventory().add(new ItemStack(Items.BIRCH_LOG, 2));
                        player.inventoryMenu.broadcastChanges();
                    });

                    axeGroup = grid.addGroup(new BookmarkGroup(BookmarkViewMode.TODO_LIST, true));
                    addRecipe(grid, AXE_PLANKS, axeGroup, 2);

                    // a crafting chain at x0 has nothing to craft and says so
                    final int zeroGroup = grid.addGroup(new BookmarkGroup(BookmarkViewMode.TODO_LIST, true));
                    addRecipe(grid, "minecraft:stick", zeroGroup, 0);
                    final BookmarkPanel.PanelSpace space = panel();
                    moveMouse(gutterX(space), rowY(space, firstRowOf(space, zeroGroup)));
                    TestInput.hold(GLFW.GLFW_KEY_LEFT_SHIFT);
                    check(key(GLFW.GLFW_KEY_C, 46), "Shift + C over a x0 chain consumed");
                    TestInput.release();
                    check(!AutoCrafting.processing() && StatusMessage.current() != null
                            && StatusMessage.current().getString().contains("quantity"),
                            "a x0 chain explains that it needs a quantity");
                    grid.removeGroup(zeroGroup);
                }
                case 45 -> {
                    // the worn, renamed axes count as the axe the recipe shows (no "missing" tool)
                    final var math = grid.createRecipeChainMath(axeGroup);
                    final var carried = InventoryStacks.forChain(InventoryStacks.of(minecraft.screen),
                            math.recipeIngredients);
                    check(InventoryStacks.count(carried, "item:minecraft:stone_axe") == 1
                            && InventoryStacks.count(carried, "item:minecraft:golden_axe") == 1,
                            "worn and renamed axes count as the recipe's axes " + carried.keySet());

                    // Shift + click on the bookmark fills the crafting grid
                    final ChainItem result = recipeItem(grid, AXE_PLANKS, ChainItem.Type.RESULT);
                    check(result != null, "the axe recipe is bookmarked");
                    final double[] pos = moveTo(result);
                    TestInput.hold(GLFW.GLFW_KEY_LEFT_SHIFT);
                    EmiScreenManager.mouseClicked(pos[0], pos[1], 0);
                    EmiScreenManager.mouseReleased(pos[0], pos[1], 0);
                    TestInput.release();
                }
                case 46 -> {
                    final var slots = minecraft.player.inventoryMenu.slots;
                    final boolean log = slots.subList(1, 5).stream().anyMatch(slot -> slot.getItem().is(Items.BIRCH_LOG));
                    final boolean axe = slots.subList(1, 5).stream()
                            .anyMatch(slot -> slot.getItem().is(Items.STONE_AXE) || slot.getItem().is(Items.GOLDEN_AXE));
                    check(log && axe, "Shift + click filled the crafting grid with a log and a worn axe");
                    moveMouse(5, 5);
                }
                case 47 -> {
                    screenshot("20-shift-click-fill");
                    final ChainItem result = recipeItem(grid, AXE_PLANKS, ChainItem.Type.RESULT);
                    moveTo(result);
                    TestInput.hold(GLFW.GLFW_KEY_LEFT_SHIFT);
                    check(key(GLFW.GLFW_KEY_C, 46), "Shift + C over the axe recipe consumed");
                    TestInput.release();
                    check(AutoCrafting.processing(), "auto-crafting the axe recipe started");
                }
                case 48, 49, 50, 51, 52 -> {
                    if (AutoCrafting.processing()) {
                        return;
                    }
                    step = 53;
                    final long planks = countInInventory(Items.BIRCH_PLANKS);
                    check(planks == 16, "two crafts with worn, renamed and substitute axes made 16 planks (got "
                            + planks + ", message: " + StatusMessage.current() + ")");
                    check(countInInventory(Items.BIRCH_LOG) == 0, "both birch logs were used");
                    check(countInInventory(Items.STONE_AXE) == 0 && countInInventory(Items.GOLDEN_AXE) == 0,
                            "both axes were used");
                    moveMouse(5, 5);
                }
                case 53 -> screenshot("21-axe-autocraft");
                case 54 -> {
                    // the same in a crafting table, opened like a player does (FastWorkbench replaces it when loaded)
                    minecraft.getSingleplayerServer().execute(() -> {
                        final ServerPlayer player = minecraft.getSingleplayerServer().getPlayerList().getPlayers()
                                .get(0);
                        final ItemStack stoneAxe = new ItemStack(Items.STONE_AXE);
                        stoneAxe.setDamageValue(70);
                        final ItemStack goldenAxe = new ItemStack(Items.GOLDEN_AXE);
                        goldenAxe.setDamageValue(5);
                        player.getInventory().add(stoneAxe);
                        player.getInventory().add(goldenAxe);
                        player.getInventory().add(new ItemStack(Items.BIRCH_LOG, 2));
                        player.inventoryMenu.broadcastChanges();

                        final BlockPos pos = player.blockPosition().east(2);
                        player.level().setBlockAndUpdate(pos, Blocks.CRAFTING_TABLE.defaultBlockState());
                        player.level().getBlockState(pos).use(player.level(), player, InteractionHand.MAIN_HAND,
                                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                    });
                }
                case 55 -> {
                    EmiCraftingTree.LOGGER.info("[selftest] crafting table screen: {}", minecraft.screen);
                    check(minecraft.screen instanceof AbstractContainerScreen<?> container
                            && container.getMenu().slots.size() == 46, "a crafting table is open");
                    planksBefore = countInInventory(Items.BIRCH_PLANKS);
                    moveTo(recipeItem(grid, AXE_PLANKS, ChainItem.Type.RESULT));
                    TestInput.hold(GLFW.GLFW_KEY_LEFT_SHIFT);
                    check(key(GLFW.GLFW_KEY_C, 46), "Shift + C in the crafting table consumed");
                    TestInput.release();
                    check(AutoCrafting.processing(), "auto-crafting in the crafting table started");
                }
                case 56, 57, 58, 59, 60 -> {
                    if (AutoCrafting.processing()) {
                        return;
                    }
                    step = 61;
                    final long planks = countInInventory(Items.BIRCH_PLANKS) - planksBefore;
                    check(planks == 16, "the crafting table made 16 planks with worn axes (got " + planks
                            + ", message: " + StatusMessage.current() + ")");
                    final var grid3x3 = ((AbstractContainerScreen<?>) minecraft.screen).getMenu().slots.subList(1, 10);
                    check(grid3x3.stream().allMatch(slot -> slot.getItem().isEmpty()),
                            "nothing was left in the crafting grid");
                    moveMouse(5, 5);
                }
                case 61 -> {
                    screenshot("22-crafting-table");
                    minecraft.setScreen(new InventoryScreen(minecraft.player));
                }
                case 62 -> {
                    // RMB + drag over all rows of a group ungroups them and keeps the bookmarks (NEI)
                    final BookmarkPanel.PanelSpace space = panel();
                    final double x = gutterX(space);
                    final int first = firstRowOf(space, chainGroup);
                    final int last = lastRowOf(space, chainGroup);
                    final int size = grid.size();
                    final long chainItems = grid.getBookmarkItems().stream()
                            .filter(item -> item.groupId == chainGroup).count();
                    check(first != -1 && last > first, "the chain group spans several rows");
                    EmiScreenManager.mouseClicked(x, rowY(space, first), 1);
                    EmiScreenManager.mouseDragged(x, rowY(space, last), 1, 0, 0);
                    EmiScreenManager.mouseReleased(x, rowY(space, last), 1);
                    check(grid.getBookmarkItems().stream().noneMatch(item -> item.groupId == chainGroup),
                            "RMB + drag took every row out of the group");
                    check(grid.size() == size && chainItems > 0, "ungrouping kept all bookmarks");
                    moveMouse(5, 5);
                }
                case 63 -> {
                    screenshot("18-ungrouped");
                    EmiCraftingTree.LOGGER.info("[selftest] done, {} failure(s): {}", failures.size(), failures);
                    minecraft.stop();
                }
                default -> {}
            }
        } catch (Throwable th) {
            EmiCraftingTree.LOGGER.error("[selftest] failed at step {}", step - 1, th);
            minecraft.stop();
        }
    }

    /** Puts the mouse over the torch bookmark and returns its position. */
    private static double[] moveToTorch() throws ReflectiveOperationException {
        return moveTo(torch(BookmarkStorage.grid()));
    }

    /** Puts the mouse over a bookmark and returns its position. */
    private static double[] moveTo(ChainItem item) throws ReflectiveOperationException {
        final BookmarkPanel.PanelSpace space = panel();
        final GridLayout layout = space.layout();
        final int itemIndex = BookmarkStorage.grid().getBookmarkItems().indexOf(item);
        final int slot = layout.getAbsoluteSlotIndex(itemIndex) % space.geometry().perPage();
        final double x = space.geometry().slotX(slot) + 9;
        final double y = space.geometry().slotY(slot) + 9;
        moveMouse(x, y);
        return new double[] { x, y };
    }

    /** Puts the mouse over a stack on the item list's current page, or returns null when it is not shown. */
    private static double[] moveToIndexStack(EmiStack stack) throws ReflectiveOperationException {
        final SidebarPanel right = EmiScreenManager.getPanelFor(SidebarSide.RIGHT);
        final List<? extends EmiIngredient> page = right.space.getPage(right.page);

        for (int index = 0; index < page.size(); index++) {
            if (!page.get(index).isEmpty() && page.get(index).getEmiStacks().get(0).isEqual(stack)) {
                final double x = right.space.getRawX(index) + 9;
                final double y = right.space.getRawY(index) + 9;
                moveMouse(x, y);
                return new double[] { x, y };
            }
        }

        return null;
    }

    /** Puts the mouse over the output slot of a recipe shown in EMI's recipe view. */
    private static double[] moveToRecipeOutput(String recipeId) throws ReflectiveOperationException {
        for (WidgetGroup group : ((RecipeScreenAccessor) Minecraft.getInstance().screen)
                .emicraftingtree$getCurrentPage()) {
            if (group.recipe == null || !recipeId.equals(String.valueOf(group.recipe.getId()))) {
                continue;
            }

            for (Widget widget : group.widgets) {
                if (widget instanceof SlotWidget slot && slot.getRecipe() != null) {
                    final double x = group.x + widget.getBounds().x() + widget.getBounds().width() / 2.0;
                    final double y = group.y + widget.getBounds().y() + widget.getBounds().height() / 2.0;
                    moveMouse(x, y);
                    return new double[] { x, y };
                }
            }
        }

        return null;
    }

    private static long countType(BookmarkGrid grid, ChainItem.Type type) {
        return grid.getBookmarkItems().stream().filter(item -> item.type == type).count();
    }

    /** A key press with the modifiers the test holds. */
    private static boolean key(int keyCode, int scanCode) {
        return EmiScreenManager.keyPressed(keyCode, scanCode, TestInput.modifiers());
    }

    private static int lastRowOf(BookmarkPanel.PanelSpace space, int groupId) {
        final GridLayout layout = space.layout();
        int last = -1;
        for (int row = 0; row < space.geometry().rows; row++) {
            if (layout.getRowGroupId(row, space.page()) == groupId) {
                last = row;
            }
        }
        return last;
    }

    private static ChainItem recipeItem(BookmarkGrid grid, String recipeId, ChainItem.Type type) {
        return grid.getBookmarkItems().stream().filter(item -> item.type == type && item.recipe != null
                && item.recipe.id.equals(recipeId)).findFirst().orElse(null);
    }

    /** Gutter drags only start after the button was held for a moment (NEI's 250 ms). */
    private static void pressHeld() throws InterruptedException {
        Thread.sleep(300);
    }

    private static ChainItem diamond(BookmarkGrid grid) {
        return grid.getBookmarkItems().stream().filter(item -> item.stack.key.equals("item:minecraft:diamond"))
                .findFirst().get();
    }

    private static ChainItem torch(BookmarkGrid grid) {
        return grid.getBookmarkItems().stream().filter(item -> item.type == ChainItem.Type.RESULT
                && item.recipe != null && item.recipe.id.equals("minecraft:torch")).findFirst().get();
    }

}
