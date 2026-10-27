package com.emicraftingtree;

import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * NEI's key bindings that act on bookmarks and hovered items, with NEI's default keys (Controls > "EMI Bookmarks").
 * Like NEI, modifiers select variants of an action (Shift + C crafts, Ctrl + C copies the name), so bindings are
 * matched on the key alone. Adding and removing bookmarks uses EMI's "favorite" key, and recipes/uses use EMI's keys.
 */
public final class Keys {

    private static final String CATEGORY = "key.categories.emicraftingtree";

    public static final KeyMapping OVERLAY = key("overlay", GLFW.GLFW_KEY_S);
    public static final KeyMapping CRAFT_ITEMS = key("craft_items", GLFW.GLFW_KEY_C);
    public static final KeyMapping PULL_ITEMS = key("pull_items", GLFW.GLFW_KEY_V);
    public static final KeyMapping CHAT_LINK = key("chat_link", GLFW.GLFW_KEY_L);
    public static final KeyMapping COPY_NAME = key("copy_name", GLFW.GLFW_KEY_C);
    public static final KeyMapping COPY_OREDICT = key("copy_oredict", GLFW.GLFW_KEY_D);
    public static final KeyMapping COPY_IDENTIFIER = key("copy_identifier", GLFW.GLFW_KEY_X);
    public static final KeyMapping PREV_PAGE = key("prev_page", GLFW.GLFW_KEY_PAGE_UP);
    public static final KeyMapping NEXT_PAGE = key("next_page", GLFW.GLFW_KEY_PAGE_DOWN);
    public static final KeyMapping HIDE = key("hide", GLFW.GLFW_KEY_B);

    public static final List<KeyMapping> ALL = List.of(OVERLAY, CRAFT_ITEMS, PULL_ITEMS, CHAT_LINK, COPY_NAME,
            COPY_OREDICT, COPY_IDENTIFIER, PREV_PAGE, NEXT_PAGE, HIDE);

    private Keys() {}

    private static KeyMapping key(String name, int key) {
        return new KeyMapping("key.emicraftingtree." + name, KeyConflictContext.GUI, InputConstants.Type.KEYSYM, key,
                CATEGORY);
    }

    /** Whether a key event is this binding, ignoring held modifiers (they select variants of the action). */
    public static boolean matches(KeyMapping mapping, int keyCode, int scanCode) {
        final InputConstants.Key key = mapping.getKey();

        if (key.equals(InputConstants.UNKNOWN)) {
            return false;
        }

        return key.getType() == InputConstants.Type.KEYSYM ? key.getValue() == keyCode
                : key.getType() == InputConstants.Type.SCANCODE && key.getValue() == scanCode;
    }

    public static boolean isBound(KeyMapping mapping) {
        return !mapping.getKey().equals(InputConstants.UNKNOWN);
    }

    /** The key's name as NEI writes it in hotkey lists ("C", "PAGE UP"). */
    public static String name(KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString().toUpperCase();
    }

}
