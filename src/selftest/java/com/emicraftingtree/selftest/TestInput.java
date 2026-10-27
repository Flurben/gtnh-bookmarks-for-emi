package com.emicraftingtree.selftest;

import java.util.HashSet;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

/**
 * Keys the self-test holds down. Minecraft (and with it {@code Screen.hasShiftDown()} and friends) asks GLFW whether a
 * key is down; the self-test mixin answers "yes" for these keys, so modifier shortcuts can be scripted.
 */
public final class TestInput {

    private static final Set<Integer> HELD = new HashSet<>();

    private TestInput() {}

    public static boolean isHeld(int key) {
        return HELD.contains(key);
    }

    /** Holds the given keys (GLFW key codes) until {@link #release()}. */
    public static void hold(int... keys) {
        for (int key : keys) {
            HELD.add(key);
        }
    }

    public static void release() {
        HELD.clear();
    }

    /** The GLFW modifier bits for the held keys, as passed to keyPressed/mouseClicked. */
    public static int modifiers() {
        int modifiers = 0;

        if (isHeld(GLFW.GLFW_KEY_LEFT_SHIFT) || isHeld(GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            modifiers |= GLFW.GLFW_MOD_SHIFT;
        }

        if (isHeld(GLFW.GLFW_KEY_LEFT_CONTROL) || isHeld(GLFW.GLFW_KEY_RIGHT_CONTROL)) {
            modifiers |= GLFW.GLFW_MOD_CONTROL;
        }

        if (isHeld(GLFW.GLFW_KEY_LEFT_ALT) || isHeld(GLFW.GLFW_KEY_RIGHT_ALT)) {
            modifiers |= GLFW.GLFW_MOD_ALT;
        }

        return modifiers;
    }

}
