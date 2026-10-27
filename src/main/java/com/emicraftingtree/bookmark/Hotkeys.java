package com.emicraftingtree.bookmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import com.emicraftingtree.Config;
import com.emicraftingtree.Keys;
import com.emicraftingtree.chain.ChainItem;
import com.mojang.blaze3d.platform.InputConstants;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.render.EmiTooltipComponents;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.input.EmiBind;
import dev.emi.emi.input.EmiInput;
import dev.emi.emi.screen.RecipeScreen;

/**
 * The hotkey lists NEI adds to tooltips while Alt is held (its handleHotkeys methods), and the "Hold ALT for hotkeys"
 * hint otherwise. Keys are written like NEI writes them ("SHIFT + A", "CTRL + Scroll").
 */
public final class Hotkeys {

    /**
     * The last hint list built for one kind of tooltip. EMI rebuilds tooltips every frame, while the hints only change
     * with what is hovered, Alt, the screen and cheat mode; anything else (options, key binds) is picked up within a
     * second.
     */
    static final class Memo {

        private static final long MAX_AGE_MS = 1000;

        private @Nullable List<Object> key;
        private List<ClientTooltipComponent> value = List.of();
        private long builtAt;

        List<ClientTooltipComponent> get(Supplier<List<ClientTooltipComponent>> build, Object... inputs) {
            final List<Object> key = Arrays.asList(Arrays.copyOf(inputs, inputs.length + 3));
            key.set(inputs.length, Screen.hasAltDown());
            key.set(inputs.length + 1, Minecraft.getInstance().screen);
            key.set(inputs.length + 2, EmiApi.isCheatMode());
            final long now = System.currentTimeMillis();

            if (!Objects.equals(key, this.key) || now - this.builtAt > MAX_AGE_MS) {
                this.value = List.copyOf(build.get());
                this.key = key;
                this.builtAt = now;
            }

            return this.value;
        }
    }

    private static final Memo SLOT = new Memo();
    private static final Memo ITEM = new Memo();
    private static final Memo GROUP = new Memo();

    private Hotkeys() {}

    private static String word(String key) {
        return Component.translatable("emicraftingtree.key." + key).getString();
    }

    private static String combo(String... parts) {
        return String.join(" + ", parts);
    }

    private static String ctrl() {
        return word("ctrl");
    }

    private static String alt() {
        return word("alt");
    }

    private static String shift() {
        return word("shift");
    }

    private static String key(KeyMapping mapping) {
        return Keys.name(mapping);
    }

    /** The first key of an EMI bind (EMI's favorite key stands in for NEI's bookmark key). */
    private static String bindName(EmiBind bind) {
        final List<String> names = bindNames(bind, false);
        return names.isEmpty() ? "?" : names.get(0);
    }

    private static List<String> bindNames(EmiBind bind, boolean includeMouse) {
        final List<String> names = new ArrayList<>();

        for (EmiBind.ModifiedKey key : bind.boundKeys) {
            if (key.isUnbound()) {
                continue;
            }

            final InputConstants.Key inputKey = key.key();

            if (inputKey.getType() == InputConstants.Type.MOUSE && !includeMouse) {
                continue;
            }

            final List<String> parts = new ArrayList<>();

            if ((key.modifiers() & EmiInput.CONTROL_MASK) != 0) {
                parts.add(ctrl());
            }

            if ((key.modifiers() & EmiInput.ALT_MASK) != 0) {
                parts.add(alt());
            }

            if ((key.modifiers() & EmiInput.SHIFT_MASK) != 0) {
                parts.add(shift());
            }

            if (inputKey.getType() == InputConstants.Type.MOUSE) {
                parts.add(switch (inputKey.getValue()) {
                    case 0 -> word("lmb");
                    case 1 -> word("rmb");
                    case 2 -> word("mmb");
                    default -> inputKey.getDisplayName().getString();
                });
            } else {
                parts.add(inputKey.getDisplayName().getString().toUpperCase());
            }

            names.add(String.join(" + ", parts));
        }

        return names;
    }

    private static void put(Map<String, String> hotkeys, KeyMapping mapping, String key, String message) {
        if (Keys.isBound(mapping)) {
            hotkeys.put(key, message);
        }
    }

    /** NEI's collectHotkeyTips: the sorted list while Alt is held, otherwise the help line. */
    public static List<ClientTooltipComponent> format(Map<String, String> hotkeys) {

        if (hotkeys.isEmpty()) {
            return List.of();
        }

        if (!Screen.hasAltDown()) {
            if (!Config.hotkeysHelpText()) {
                return List.of();
            }

            return List.of(EmiTooltipComponents.of(Component.translatable("emicraftingtree.hotkeys.hint",
                    Component.literal(alt()).withStyle(ChatFormatting.GOLD)).withStyle(ChatFormatting.GRAY)));
        }

        if (!Config.showHotkeys()) {
            return List.of();
        }

        final Map<String, List<String>> messages = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : hotkeys.entrySet()) {
            messages.computeIfAbsent(entry.getValue(), m -> new ArrayList<>()).add(entry.getKey());
        }

        for (List<String> keys : messages.values()) {
            keys.sort((a, b) -> a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b));
        }

        final List<Map.Entry<String, List<String>>> lines = new ArrayList<>(messages.entrySet());

        lines.sort((a, b) -> {
            final String sa = String.join("/", a.getValue());
            final String sb = String.join("/", b.getValue());
            return sa.length() != sb.length() ? Integer.compare(sa.length(), sb.length()) : sa.compareTo(sb);
        });

        final List<ClientTooltipComponent> components = new ArrayList<>();

        for (Map.Entry<String, List<String>> line : lines) {
            final MutableComponent text = Component.empty();

            for (int index = 0; index < line.getValue().size(); index++) {
                if (index > 0) {
                    text.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY));
                }
                text.append(Component.literal(line.getValue().get(index)).withStyle(ChatFormatting.GOLD));
            }

            text.append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY));
            text.append(Component.translatable(line.getKey()).withStyle(ChatFormatting.GRAY));
            components.add(EmiTooltipComponents.of(text));
        }

        return components;
    }

    /** Hotkeys every hovered item has (NEI's ShortcutInputHandler.handleHotkeys, after the bookmark part). */
    private static void itemHotkeys(Map<String, String> hotkeys, @Nullable EmiRecipe recipe, boolean bookmark) {
        final boolean recipeScreen = Minecraft.getInstance().screen instanceof RecipeScreen;

        for (String name : bindNames(EmiConfig.viewRecipes, !bookmark)) {
            hotkeys.put(name, "emicraftingtree.open_crafting");
        }

        for (String name : bindNames(EmiConfig.viewUses, !bookmark)) {
            hotkeys.put(name, "emicraftingtree.open_usage");
        }

        put(hotkeys, Keys.COPY_NAME, combo(ctrl(), key(Keys.COPY_NAME)), "emicraftingtree.copy_name");
        put(hotkeys, Keys.COPY_OREDICT, combo(ctrl(), key(Keys.COPY_OREDICT)), "emicraftingtree.copy_oredict");
        put(hotkeys, Keys.COPY_IDENTIFIER, combo(ctrl(), key(Keys.COPY_IDENTIFIER)), "emicraftingtree.copy_id");
        put(hotkeys, Keys.CHAT_LINK, combo(ctrl(), key(Keys.CHAT_LINK)), "emicraftingtree.chat_link_item");

        if (bookmark) {
            if (!recipeScreen && EmiApi.isCheatMode()) {
                hotkeys.put(combo(shift(), word("lmb")), "emicraftingtree.open_crafting");
                hotkeys.put(combo(shift(), word("rmb")), "emicraftingtree.open_usage");
            } else {
                hotkeys.put(word("lmb"), "emicraftingtree.open_crafting");
                hotkeys.put(word("rmb"), "emicraftingtree.open_usage");
            }
        }

        if (recipe != null) {

            if (RecipeOverlay.canUseOverlay(recipe)) {
                put(hotkeys, Keys.OVERLAY, key(Keys.OVERLAY), "emicraftingtree.overlay_recipe");
            }

            if (bookmark && !recipeScreen && RecipeOverlay.canFill(recipe)) {
                hotkeys.put(combo(shift(), word("lmb")), "emicraftingtree.fill_crafting_grid");
                hotkeys.put(combo(ctrl(), shift(), word("lmb")), "emicraftingtree.fill_crafting_grid_quantity");
            }

            if (Config.autocrafting()) {
                put(hotkeys, Keys.CRAFT_ITEMS, combo(ctrl(), shift(), key(Keys.CRAFT_ITEMS)),
                        "emicraftingtree.craft_missing");
                put(hotkeys, Keys.CRAFT_ITEMS, combo(shift(), key(Keys.CRAFT_ITEMS)), "emicraftingtree.craft_all");
            }
        }
    }

    /** A bookmark slot (NEI's BookmarkPanel.handleHotkeys slot part plus ShortcutInputHandler). */
    public static List<ClientTooltipComponent> slot(BookmarksGridSlot slot) {
        return SLOT.get(() -> buildSlot(slot), slot);
    }

    private static List<ClientTooltipComponent> buildSlot(BookmarksGridSlot slot) {
        final Map<String, String> hotkeys = new LinkedHashMap<>();
        final BookmarkGroup group = slot.getGroup();
        final String favorite = bindName(EmiConfig.favorite);

        hotkeys.put(combo(ctrl(), word("scroll")), "emicraftingtree.change_quantity");
        hotkeys.put(combo(ctrl(), alt(), word("scroll")), "emicraftingtree.change_quantity_step");
        hotkeys.put(combo(shift(), word("lmb"), word("drag")), "emicraftingtree.item.sorting");

        if (group.collapsed) {
            hotkeys.put(combo(alt(), word("lmb")), "emicraftingtree.group.toggle_collapse");
        } else if (group.crafting != null) {
            hotkeys.put(combo(alt(), word("lmb")), "emicraftingtree.recipe.toggle_collapse");
        }

        if (slot.getType() == ChainItem.Type.INGREDIENT && slot.getRecipeId() != null
                && slot.getBookmarkItem().permutations.size() > 1) {
            hotkeys.put(combo(shift(), word("scroll")), "emicraftingtree.accepts.scroll");
        }

        if (group.crafting != null && group.collapsed) {
            hotkeys.put(combo(shift(), favorite), "emicraftingtree.group.remove_recipe");
        } else if (slot.getRecipeId() == null || slot.getType() == ChainItem.Type.ITEM) {
            hotkeys.put(favorite, "emicraftingtree.remove_item");
        } else if (group.crafting != null && group.crafting.recipeRelations.entrySet().stream()
                .anyMatch(entry -> entry.getKey().equals(slot.getRecipeId())
                        || entry.getValue().contains(slot.getRecipeId()))) {
            hotkeys.put(combo(shift(), favorite), "emicraftingtree.remove_recipe");
        } else {
            final ChainItem item = slot.getBookmarkItem();

            hotkeys.put(combo(shift(), favorite), "emicraftingtree.remove_recipe");

            if (slot.getType() == ChainItem.Type.INGREDIENT) {
                hotkeys.put(favorite, "emicraftingtree.remove_item");
            } else if (BookmarkPanel.grid().getBookmarkItems().stream().noneMatch(
                    m -> m.type == ChainItem.Type.RESULT && !m.equals(item) && item.equalsRecipe(m))) {
                hotkeys.put(favorite, "emicraftingtree.remove_recipe");
            } else {
                hotkeys.put(favorite, "emicraftingtree.remove_item");
            }
        }

        if (ItemPuller.isStorage(Minecraft.getInstance().screen)) {
            put(hotkeys, Keys.PULL_ITEMS, key(Keys.PULL_ITEMS), "emicraftingtree.group.pull_items");
            put(hotkeys, Keys.PULL_ITEMS, combo(shift(), key(Keys.PULL_ITEMS)),
                    "emicraftingtree.group.pull_items_shift");
        }

        itemHotkeys(hotkeys, slot.getEmiRecipeContext(), true);
        return format(hotkeys);
    }

    /** Any other hovered item: EMI's item list, recipe view and inventory (NEI's item panel hotkeys). */
    public static List<ClientTooltipComponent> item(long amount, @Nullable EmiRecipe recipe) {
        return ITEM.get(() -> buildItem(amount, recipe), amount > 0, recipe);
    }

    private static List<ClientTooltipComponent> buildItem(long amount, @Nullable EmiRecipe recipe) {
        final Map<String, String> hotkeys = new LinkedHashMap<>();
        final String favorite = bindName(EmiConfig.favorite);

        hotkeys.put(favorite, "emicraftingtree.add_item");

        if (amount > 0) {
            hotkeys.put(combo(ctrl(), favorite), "emicraftingtree.add_item_with_count");
        }

        if (recipe != null) {
            hotkeys.put(combo(ctrl(), shift(), favorite), "emicraftingtree.add_item_with_recipe_and_count");
            hotkeys.put(combo(shift(), favorite), "emicraftingtree.add_item_with_recipe");
        }

        itemHotkeys(hotkeys, recipe, false);
        return format(hotkeys);
    }

    /** A group bracket or, with {@code label}, the page label standing for the default group. */
    public static List<ClientTooltipComponent> group(int groupId, boolean label) {
        return GROUP.get(() -> buildGroup(groupId, label), groupId, label, BookmarkPanel.grid(),
                BookmarkPanel.grid().revision());
    }

    private static List<ClientTooltipComponent> buildGroup(int groupId, boolean label) {
        final Map<String, String> hotkeys = new LinkedHashMap<>();
        final boolean defaultRow = !label && groupId == BookmarkGrid.DEFAULT_GROUP_ID;

        if (label) {
            hotkeys.put(combo(ctrl(), word("scroll")), "emicraftingtree.change_quantity");
            hotkeys.put(combo(ctrl(), alt(), word("scroll")), "emicraftingtree.change_quantity_step");
            hotkeys.put(word("lmb"), "emicraftingtree.group.toggle_mode");
            hotkeys.put(word("rmb"), "emicraftingtree.group.toggle_crafting_chain");
            hotkeys.put(combo(alt(), word("lmb")), "emicraftingtree.group.toggle_collapse");
        } else {
            hotkeys.put(combo(word("lmb"), word("drag")), "emicraftingtree.group.include_group");

            if (!defaultRow) {
                hotkeys.put(combo(ctrl(), word("scroll")), "emicraftingtree.change_quantity");
                hotkeys.put(combo(ctrl(), alt(), word("scroll")), "emicraftingtree.change_quantity_step");
                hotkeys.put(combo(word("rmb"), word("drag")), "emicraftingtree.group.exclude_group");
                hotkeys.put(word("lmb"), "emicraftingtree.group.toggle_mode");
                hotkeys.put(word("rmb"), "emicraftingtree.group.toggle_crafting_chain");
                hotkeys.put(combo(shift(), word("lmb"), word("drag")), "emicraftingtree.group.sorting");
                hotkeys.put(combo(alt(), word("lmb")), "emicraftingtree.group.toggle_collapse");
            }
        }

        if (!defaultRow) {
            hotkeys.put(combo(shift(), bindName(EmiConfig.favorite)), "emicraftingtree.group.remove_recipe");

            if (ItemPuller.isStorage(Minecraft.getInstance().screen)) {
                put(hotkeys, Keys.PULL_ITEMS, key(Keys.PULL_ITEMS), "emicraftingtree.group.pull_items");
                put(hotkeys, Keys.PULL_ITEMS, combo(shift(), key(Keys.PULL_ITEMS)),
                        "emicraftingtree.group.pull_items_shift");
            }

            if (Config.autocrafting() && BookmarkPanel.grid().isCraftingMode(groupId)) {
                put(hotkeys, Keys.CRAFT_ITEMS, combo(ctrl(), shift(), key(Keys.CRAFT_ITEMS)),
                        "emicraftingtree.group.craft_missing");
                put(hotkeys, Keys.CRAFT_ITEMS, combo(shift(), key(Keys.CRAFT_ITEMS)),
                        "emicraftingtree.group.craft_all");
            }
        }

        return format(hotkeys);
    }

}
