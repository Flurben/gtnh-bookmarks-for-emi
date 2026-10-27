package com.emicraftingtree.bookmark;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A short note at the top of the screen, for actions that would otherwise fail silently (auto-crafting). */
public final class StatusMessage {

    private static final long DURATION_MS = 4000;

    private static @Nullable Component message;
    private static long shownAt;

    private StatusMessage() {}

    public static void show(Component text) {
        message = text;
        shownAt = System.currentTimeMillis();
    }

    public static @Nullable Component current() {
        return message != null && System.currentTimeMillis() - shownAt < DURATION_MS ? message : null;
    }

    static void render(Screen screen, GuiGraphics graphics) {
        final Component text = current();

        if (text == null) {
            return;
        }

        final Font font = Minecraft.getInstance().font;
        final int width = font.width(text);
        final int x = (screen.width - width) / 2;
        final int y = 4;

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 450);
        graphics.fill(x - 4, y - 3, x + width + 4, y + font.lineHeight + 2, 0xC0101010);
        graphics.drawString(font, text, x, y, 0xFFFFFF);
        graphics.pose().popPose();
    }

}
