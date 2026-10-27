package com.emicraftingtree;

import java.util.function.Supplier;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client options, mirroring NEI's bookmark settings. Stored in {@code config/emicraftingtree-client.toml}.
 */
public final class Config {

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue WORLD_SPECIFIC;
    private static final ForgeConfigSpec.BooleanValue ITEMS_WITH_RECIPE;
    private static final ForgeConfigSpec.BooleanValue RECIPE_WITH_COUNT;
    private static final ForgeConfigSpec.IntValue RECIPE_MARKER_MODE;
    private static final ForgeConfigSpec.IntValue RECIPE_MARKER_COLOR;
    private static final ForgeConfigSpec.BooleanValue RECIPE_HANDLER_ICON;
    private static final ForgeConfigSpec.BooleanValue ANIMATION;
    private static final ForgeConfigSpec.BooleanValue AUTOCRAFTING;
    private static final ForgeConfigSpec.BooleanValue SHOW_HOTKEYS;
    private static final ForgeConfigSpec.BooleanValue HOTKEYS_HELP_TEXT;
    private static final ForgeConfigSpec.IntValue RECIPE_TOOLTIPS_MODE;
    private static final ForgeConfigSpec.BooleanValue IMPORT_EMI_FAVORITES;
    private static final ForgeConfigSpec.BooleanValue HIDDEN;

    static {
        final ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("bookmarks");
        WORLD_SPECIFIC = builder.comment("Keep a separate set of bookmarks for every world/server instead of one "
                + "global set.").define("worldSpecific", false);
        ITEMS_WITH_RECIPE = builder.comment("Bookmarking an item that has a recipe context (for example a recipe "
                + "output in EMI's recipe view) remembers that recipe with the item.").define("itemsWithRecipe", true);
        RECIPE_WITH_COUNT = builder.comment("Recipes bookmarked with Shift + A start with a quantity of 1 instead of 0 "
                + "(Ctrl + Shift + A always starts at 1).").define("recipeWithCount", false);
        RECIPE_TOOLTIPS_MODE = builder.comment("Show recipe tooltips in bookmarks: 0 = none, 1 = in horizontal "
                + "(default) view, 2 = in vertical (to-do list) view, 3 = always.")
                .defineInRange("recipeTooltipsMode", 1, 0, 3);
        RECIPE_MARKER_MODE = builder.comment("Show recipe marker: 0 = none, 1 = text ('R' on every result), "
                + "2 = background behind results.").defineInRange("recipeMarkerMode", 0, 0, 2);
        RECIPE_MARKER_COLOR = builder.comment("Color of the 'xN' quantity marker (RGB).")
                .defineInRange("recipeMarkerColor", 0xADADAD, 0, 0xFFFFFF);
        RECIPE_HANDLER_ICON = builder.comment("Show the recipe category icon on bookmarked recipes.")
                .define("recipeHandlerIcon", false);
        ANIMATION = builder.comment("Pop-in animation for new bookmarks.").define("animation", true);
        HIDDEN = builder.comment("The bookmark panel is hidden (toggled with the 'Toggle Bookmark Panel' key, B).")
                .define("hidden", false);
        IMPORT_EMI_FAVORITES = builder.comment("On first start, copy EMI's existing favorites into the bookmarks.")
                .define("importEmiFavorites", true);
        builder.pop();

        AUTOCRAFTING = builder.comment("Allow Shift + C to craft bookmarked recipes and crafting chains.")
                .define("autocrafting", true);
        SHOW_HOTKEYS = builder.comment("List the available hotkeys in tooltips while Alt is held.")
                .define("showHotkeys", true);
        HOTKEYS_HELP_TEXT = builder.comment("Show 'Hold ALT for hotkeys' in tooltips.")
                .define("hotkeysHelpText", true);

        SPEC = builder.build();
    }

    private Config() {}

    private static <T> T get(Supplier<T> value, T fallback) {
        try {
            return SPEC.isLoaded() ? value.get() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static boolean worldSpecific() {
        return get(WORLD_SPECIFIC::get, false);
    }

    public static boolean bookmarkItemsWithRecipe() {
        return get(ITEMS_WITH_RECIPE::get, true);
    }

    public static boolean bookmarkRecipeWithCount() {
        return get(RECIPE_WITH_COUNT::get, false);
    }

    public static int recipeTooltipsMode() {
        return get(RECIPE_TOOLTIPS_MODE::get, 1);
    }

    public static boolean hotkeysHelpText() {
        return get(HOTKEYS_HELP_TEXT::get, true);
    }

    public static int showRecipeMarkerMode() {
        return get(RECIPE_MARKER_MODE::get, 0);
    }

    public static int recipeMarkerColor() {
        return get(RECIPE_MARKER_COLOR::get, 0xADADAD);
    }

    public static boolean showRecipeHandlerIcon() {
        return get(RECIPE_HANDLER_ICON::get, false);
    }

    public static boolean animation() {
        return get(ANIMATION::get, true);
    }

    public static boolean autocrafting() {
        return get(AUTOCRAFTING::get, true);
    }

    public static boolean showHotkeys() {
        return get(SHOW_HOTKEYS::get, true);
    }

    public static boolean bookmarksHidden() {
        return get(HIDDEN::get, false);
    }

    public static void setBookmarksHidden(boolean hidden) {
        if (SPEC.isLoaded()) {
            HIDDEN.set(hidden);
            SPEC.save();
        }
    }

    public static boolean importEmiFavorites() {
        return get(IMPORT_EMI_FAVORITES::get, true);
    }

}
