package com.emicraftingtree.bookmark;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.emicraftingtree.EmiCraftingTree;
import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.Stacks;
import com.emicraftingtree.selftest.FrameTimer;
import com.emicraftingtree.selftest.TestInput;
import com.google.gson.GsonBuilder;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.config.SidebarSide;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.runtime.EmiPersistentData;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.screen.EmiScreenManager.ScreenSpace;
import dev.emi.emi.screen.RecipeScreen;
import dev.emi.emi.screen.WidgetGroup;

/**
 * Dev-only benchmark (./gradlew runClient -Pbenchmark, add -Pbaseline for EMI without this mod): the same bookmarks
 * (EMI favorites in the baseline), screens and mouse positions in both runs, timing every frame and the actions that
 * matter (opening recipes and uses, key presses, clicks). Results go to run/benchmark/&lt;mod|baseline&gt;.json.
 */
@Mod.EventBusSubscriber(modid = EmiCraftingTree.MOD_ID, value = Dist.CLIENT)
public final class Benchmark {

    private static final boolean ENABLED = Boolean.getBoolean("emicraftingtree.benchmark");
    private static final boolean BASELINE = Boolean.getBoolean("emicraftingtree.baseline");
    private static final String WORLD = "emicraftingtree-benchmark";
    private static final String MODE = (BASELINE ? "baseline" : "mod")
            + (Integer.getInteger("emicraftingtree.benchmark.recipes", 60) != 60
                    ? "-" + Integer.getInteger("emicraftingtree.benchmark.recipes") : "");

    private static final int WARMUP_TICKS = 30;
    private static final int MEASURE_TICKS = 100;
    private static final int REPEATS = 2000;
    private static final Item[] ITEMS = { Items.DIAMOND, Items.IRON_INGOT, Items.GOLD_INGOT };
    private static final Item[] HEAVY_USES = { Items.STICK, Items.OAK_PLANKS, Items.IRON_INGOT, Items.COBBLESTONE,
            Items.STRING, Items.REDSTONE, Items.COAL, Items.DIAMOND };
    /** -Pbenchsize=N bookmarks N crafting recipes instead of 60 (a GTNH-sized panel has hundreds). */
    private static final int RECIPES = Integer.getInteger("emicraftingtree.benchmark.recipes", 60);
    private static final int RECIPES_PER_GROUP = 5;

    private record Phase(String name, Runnable action, int ticks) {}

    private static boolean worldRequested;
    private static int ticks = -1;
    private static int wait;
    private static final Deque<Phase> phases = new ArrayDeque<>();
    private static final Map<String, Object> results = new LinkedHashMap<>();
    private static final Map<String, List<Double>> samples = new LinkedHashMap<>();
    private static long openCallNanos;

    private Benchmark() {}

    // ---------------------------------------------------------------------------------------------------------------
    // Setup

    private static void moveMouse(double guiX, double guiY) {
        final Minecraft minecraft = Minecraft.getInstance();
        final double scale = minecraft.getWindow().getGuiScale();

        try {
            final Field xpos = MouseHandler.class.getDeclaredField("xpos");
            final Field ypos = MouseHandler.class.getDeclaredField("ypos");
            xpos.setAccessible(true);
            ypos.setAccessible(true);
            xpos.setDouble(minecraft.mouseHandler, guiX * scale);
            ypos.setDouble(minecraft.mouseHandler, guiY * scale);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }

        EmiScreenManager.lastMouseX = (int) guiX;
        EmiScreenManager.lastMouseY = (int) guiY;
    }

    private static List<EmiRecipe> recipes() {
        return EmiApi.getRecipeManager().getRecipes(VanillaEmiRecipeCategories.CRAFTING).stream()
                .filter(recipe -> recipe.getId() != null && recipe.supportsRecipeTree())
                .sorted(Comparator.comparing(recipe -> recipe.getId().toString())).limit(RECIPES).toList();
    }

    /** Three loose items and {@link #RECIPES} crafting recipes, five per group, every other group a crafting chain. */
    private static void populate() {
        final List<EmiRecipe> recipes = recipes();

        if (BASELINE) {
            EmiFavorites.favorites.clear();

            for (Item item : ITEMS) {
                EmiFavorites.addFavorite(EmiStack.of(item));
            }

            for (EmiRecipe recipe : recipes) {
                EmiFavorites.addFavorite(recipe.getOutputs().get(0), recipe);
            }

            log("EMI favorites: {}", EmiFavorites.favorites.size());
            return;
        }

        BookmarkStorage.ensureLoaded();
        final BookmarkGrid grid = BookmarkStorage.grid();
        grid.clear();

        for (Item item : ITEMS) {
            grid.addItem(ChainItem.ofItem(0, Stacks.of(EmiStack.of(item), 1), 0), false);
        }

        int groupId = 0;

        for (int index = 0; index < recipes.size(); index++) {
            if (index % RECIPES_PER_GROUP == 0) {
                final boolean chain = (index / RECIPES_PER_GROUP) % 2 == 0;
                groupId = grid.addGroup(new BookmarkGroup(BookmarkViewMode.TODO_LIST, chain));
            }

            grid.addRecipe(RecipeBookmarks.build(recipes.get(index), groupId, 1, null), groupId);
        }

        grid.animation.clear();
        log("bookmarks: {} items in {} groups", grid.size(), grid.getGroups().size());
    }

    private static ScreenSpace favorites() {
        return EmiScreenManager.getPanelFor(SidebarSide.LEFT).space;
    }

    private static ScreenSpace index() {
        return EmiScreenManager.getPanelFor(SidebarSide.RIGHT).space;
    }

    private static double[] idle() {
        return new double[] { Minecraft.getInstance().screen.width / 2.0, 3 };
    }

    private static double[] favoriteSlot(int index) {
        final ScreenSpace space = favorites();
        return new double[] { space.tx + (index % space.tw) * 18 + 9, space.ty + (index / space.tw) * 18 + 9 };
    }

    /** A bookmarked recipe result (baseline: the first favorite with a recipe). */
    private static double[] recipeBookmark() {
        if (BASELINE) {
            return favoriteSlot(ITEMS.length);
        }

        final BookmarkPanel.PanelSpace space = BookmarkPanel.mainSpace();

        for (BookmarksGridSlot slot : space.layout().getSlots(space.page())) {
            if (slot.getType() == ChainItem.Type.RESULT && slot.getEmiRecipeContext() != null) {
                return new double[] { space.geometry().slotX(slot.slotIndex) + 9,
                        space.geometry().slotY(slot.slotIndex) + 9 };
            }
        }

        throw new IllegalStateException("No recipe bookmark on the first page");
    }

    /** The bracket of the first crafting chain (baseline: the same spot left of the favorites). */
    private static double[] chainGutter() {
        if (BASELINE) {
            final ScreenSpace space = favorites();
            return new double[] { space.tx - 3, space.ty + 12 * 18 + 9 };
        }

        final BookmarkPanel.PanelSpace space = BookmarkPanel.mainSpace();
        final GridLayout layout = space.layout();

        for (int row = 0; row < space.geometry().rows; row++) {
            final int groupId = layout.getRowGroupId(row, space.page());

            if (groupId != BookmarkGrid.DEFAULT_GROUP_ID && BookmarkStorage.grid().isCraftingMode(groupId)) {
                return new double[] { space.geometry().x - 3, space.geometry().rowY(row) + 9 };
            }
        }

        throw new IllegalStateException("No crafting chain on the first page");
    }

    private static double[] indexSlot() {
        final ScreenSpace space = index();
        return new double[] { space.tx + 9, space.ty + 9 };
    }

    @SuppressWarnings("unchecked")
    private static double[] recipeSlot() {
        try {
            final Field field = RecipeScreen.class.getDeclaredField("currentPage");
            field.setAccessible(true);

            for (WidgetGroup group : (List<WidgetGroup>) field.get(Minecraft.getInstance().screen)) {
                for (Widget widget : group.widgets) {
                    if (widget instanceof SlotWidget) {
                        return new double[] { group.x + widget.getBounds().x() + 9,
                                group.y + widget.getBounds().y() + 9 };
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }

        throw new IllegalStateException("No slot in the recipe view");
    }

    private static void inventory() {
        final Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new InventoryScreen(minecraft.player));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Scenarios

    private interface Position {
        double[] get();
    }

    /** Frames with the mouse at a spot, optionally with keys held. */
    private static void frames(String name, Runnable screen, Position position, int... keys) {
        final double[][] at = new double[1][];

        phases.add(new Phase(name + " (setup)", () -> {
            TestInput.release();
            screen.run();
        }, 2));
        phases.add(new Phase(name + " (warmup)", () -> {
            at[0] = position.get();
            moveMouse(at[0][0], at[0][1]);
            TestInput.hold(keys);
        }, WARMUP_TICKS));
        phases.add(new Phase(name, FrameTimer::start, MEASURE_TICKS));
        phases.add(new Phase(name + " (done)", () -> {
            FrameTimer.stop();
            TestInput.release();
            final Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("frames", FrameTimer.FRAME_NANOS.size());
            stats.put("frame_ms", percentiles(FrameTimer.FRAME_NANOS.toLongArray()));
            stats.put("screen_ms", percentiles(FrameTimer.SCREEN_NANOS.toLongArray()));
            stats.put("screen_alloc_kb_mean", Arrays.stream(FrameTimer.SCREEN_BYTES.toLongArray()).average()
                    .orElse(0) / 1024);
            results.put("frames: " + name, stats);
            log("{} {}", name, stats);
        }, 0));
    }

    /** Opening the recipe view: EMI's call itself, then the first frame of the new screen. */
    private static void open(String name, boolean uses) {
        for (int repeat = 0; repeat < 3; repeat++) {
            for (Item item : HEAVY_USES) {
                phases.add(new Phase(name + " (reset)", () -> {
                    inventory();
                    moveMouse(idle()[0], idle()[1]);
                }, 3));
                phases.add(new Phase(name + " " + item, () -> {
                    final EmiStack stack = EmiStack.of(item);
                    FrameTimer.markNext();
                    final long start = System.nanoTime();

                    if (uses) {
                        EmiApi.displayUses(stack);
                    } else {
                        EmiApi.displayRecipes(stack);
                    }

                    openCallNanos = System.nanoTime() - start;
                }, 3));
                phases.add(new Phase(name + " (collect)", () -> {
                    sample(name + ": call", openCallNanos);
                    sample(name + ": first frame", FrameTimer.markedFrameNanos);
                    sample(name + ": call + first frame", openCallNanos + FrameTimer.markedFrameNanos);
                }, 0));
            }
        }
    }

    /** Calls an input handler many times at a spot and keeps the time per call. */
    private static void repeated(String name, Runnable screen, Position position, Runnable call, int... keys) {
        phases.add(new Phase(name + " (setup)", () -> {
            TestInput.release();
            screen.run();
        }, 2));
        phases.add(new Phase(name + " (position)", () -> {
            final double[] at = position.get();
            moveMouse(at[0], at[1]);
            TestInput.hold(keys);
        }, 2));
        phases.add(new Phase(name, () -> {
            for (int warmup = 0; warmup < REPEATS / 4; warmup++) {
                call.run();
            }

            final long start = System.nanoTime();

            for (int repeat = 0; repeat < REPEATS; repeat++) {
                call.run();
            }

            final double micros = (System.nanoTime() - start) / 1000.0 / REPEATS;
            TestInput.release();
            results.put("call: " + name + " (us)", micros);
            log("{}: {} us per call", name, micros);
        }, 2));
    }

    private static void plan() {
        final Runnable inv = Benchmark::inventory;
        final Runnable usesOfStick = () -> EmiApi.displayUses(EmiStack.of(Items.STICK));

        // one quick pass first so the JIT has compiled the paths being measured
        for (int pass = 0; pass < 2; pass++) {
            final String prefix = pass == 0 ? "warmup " : "";

            frames(prefix + "inventory, mouse idle", inv, Benchmark::idle);
            frames(prefix + "inventory, hover bookmark item", inv, () -> favoriteSlot(0));
            frames(prefix + "inventory, hover bookmark recipe", inv, Benchmark::recipeBookmark);
            frames(prefix + "inventory, hover bookmark recipe + Alt", inv, Benchmark::recipeBookmark,
                    GLFW.GLFW_KEY_LEFT_ALT);
            frames(prefix + "inventory, hover index item", inv, Benchmark::indexSlot);
            frames(prefix + "inventory, hover index item + Alt", inv, Benchmark::indexSlot, GLFW.GLFW_KEY_LEFT_ALT);
            frames(prefix + "inventory, hover chain bracket", inv, Benchmark::chainGutter);
            frames(prefix + "inventory, hover chain bracket + Shift", inv, Benchmark::chainGutter,
                    GLFW.GLFW_KEY_LEFT_SHIFT);
            frames(prefix + "uses of stick, mouse idle", usesOfStick, Benchmark::idle);
            frames(prefix + "uses of stick, hover recipe slot", usesOfStick, Benchmark::recipeSlot);
            frames(prefix + "uses of stick, hover recipe slot + Alt", usesOfStick, Benchmark::recipeSlot,
                    GLFW.GLFW_KEY_LEFT_ALT);

            if (pass == 0) {
                // drop the warmup numbers
                phases.add(new Phase("warmup done", results::clear, 0));
            }
        }

        open("open uses", true);
        open("open recipes", false);

        repeated("key press (unbound) over index item", inv, Benchmark::indexSlot,
                () -> EmiScreenManager.keyPressed(GLFW.GLFW_KEY_F24, 0, 0));
        repeated("key press (unbound) over bookmark", inv, Benchmark::recipeBookmark,
                () -> EmiScreenManager.keyPressed(GLFW.GLFW_KEY_F24, 0, 0));
        repeated("click + release on empty space", inv, Benchmark::idle, () -> {
            final double[] at = idle();
            EmiScreenManager.mouseClicked(at[0], at[1], 0);
            EmiScreenManager.mouseReleased(at[0], at[1], 0);
        });
        repeated("scroll over index", inv, Benchmark::indexSlot, () -> {
            final double[] at = indexSlot();
            EmiScreenManager.mouseScrolled(at[0], at[1], 1);
            EmiScreenManager.mouseScrolled(at[0], at[1], -1);
        });

        if (!BASELINE) {
            repeated("Ctrl + scroll on a chain recipe, then re-layout", inv, Benchmark::recipeBookmark, () -> {
                final double[] at = recipeBookmark();
                EmiScreenManager.mouseScrolled(at[0], at[1], 1);
                BookmarkPanel.sidebarStacks();
                EmiScreenManager.mouseScrolled(at[0], at[1], -1);
                BookmarkPanel.sidebarStacks();
            }, GLFW.GLFW_KEY_LEFT_CONTROL);
            repeated("crafting chain tooltip (Shift)", inv, Benchmark::chainGutter, () -> {
                final double[] at = chainGutter();
                final int groupId = BookmarkPanel.hoveredGroupId(at[0], at[1]);
                ChainTooltip.create(BookmarkStorage.grid(), groupId, true, false,
                        InventoryStacks.of(Minecraft.getInstance().screen));
            });
        }

        phases.add(new Phase("finish", Benchmark::finish, 0));
    }

    private static void finish() {
        for (Map.Entry<String, List<Double>> entry : samples.entrySet()) {
            final long[] nanos = entry.getValue().stream().mapToLong(Double::longValue).toArray();
            results.put("action: " + entry.getKey(), percentiles(nanos));
        }

        final Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("benchmark").resolve(MODE + ".json");

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(results),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            EmiCraftingTree.LOGGER.error("[bench] could not write {}", file, e);
        }

        if (BASELINE) {
            EmiFavorites.favorites.clear();
            EmiPersistentData.save();
        } else {
            BookmarkStorage.grid().clear();
        }

        log("done, results in {}", file);
        Minecraft.getInstance().stop();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Numbers

    private static void sample(String name, long nanos) {
        samples.computeIfAbsent(name, k -> new ArrayList<>()).add((double) nanos);
    }

    private static Map<String, Double> percentiles(long[] nanos) {
        final Map<String, Double> result = new LinkedHashMap<>();

        if (nanos.length == 0) {
            return result;
        }

        final long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        result.put("mean", round(Arrays.stream(sorted).average().orElse(0) / 1e6));
        result.put("p50", round(sorted[sorted.length / 2] / 1e6));
        result.put("p95", round(sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95))] / 1e6));
        result.put("p99", round(sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.99))] / 1e6));
        result.put("max", round(sorted[sorted.length - 1] / 1e6));
        return result;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static void log(String format, Object... args) {
        EmiCraftingTree.LOGGER.info("[bench:" + MODE + "] " + format, args);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Driver

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
                    minecraft.options.renderDistance().set(2);
                    minecraft.options.framerateLimit().set(260);
                    minecraft.options.enableVsync().set(false);
                    minecraft.options.pauseOnLostFocus = false;
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

            if (ticks == 40) {
                minecraft.getSingleplayerServer().execute(() -> {
                    final ServerPlayer player = minecraft.getSingleplayerServer().getPlayerList().getPlayers().get(0);
                    player.getInventory().clearContent();
                    player.getInventory().add(new ItemStack(Items.OAK_LOG, 16));
                    player.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
                    player.getInventory().add(new ItemStack(Items.IRON_INGOT, 16));
                    player.getInventory().add(new ItemStack(Items.COAL, 16));
                    player.getInventory().add(new ItemStack(Items.STRING, 8));
                    player.inventoryMenu.broadcastChanges();
                });
                inventory();
                return;
            }

            if (ticks == 60) {
                populate();
                plan();
                return;
            }

            if (ticks < 60 || phases.isEmpty()) {
                return;
            }

            if (wait > 0) {
                wait--;
                return;
            }

            final Phase phase = phases.poll();
            phase.action().run();
            wait = phase.ticks();
        } catch (Throwable th) {
            EmiCraftingTree.LOGGER.error("[bench] failed", th);
            minecraft.stop();
        }
    }

}
