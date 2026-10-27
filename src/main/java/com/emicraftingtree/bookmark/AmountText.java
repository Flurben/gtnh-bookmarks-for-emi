package com.emicraftingtree.bookmark;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Small overlay labels drawn in the corners of a slot, like NEI's drawNEIOverlayText.
 */
public final class AmountText {

    public enum Alignment {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    private static final String[] UNITS = { "K", "M", "G", "T", "P", "E" };

    private AmountText() {}

    /** Short form: plain below 10,000, then 12K, 1.5M and so on (NEI's ReadableNumberConverter wide form). */
    public static String compact(long amount) {

        if (amount < 10_000) {
            return String.valueOf(amount);
        }

        double value = amount;
        int unit = -1;

        while (value >= 1000 && unit < UNITS.length - 1) {
            value /= 1000;
            unit++;
        }

        if (value < 10) {
            final long tenths = (long) Math.floor(value * 10);
            return tenths % 10 == 0 ? (tenths / 10) + UNITS[unit] : (tenths / 10) + "." + (tenths % 10) + UNITS[unit];
        }

        return (long) Math.floor(value) + UNITS[unit];
    }

    public static float smallTextScale() {
        return Math.max(1f / (float) Minecraft.getInstance().getWindow().getGuiScale(), 0.5f);
    }

    /** Draws half-size text in a corner of the 16x16 box at (x, y). */
    public static void draw(GuiGraphics graphics, String text, int x, int y, int color, Alignment alignment) {
        final Font font = Minecraft.getInstance().font;
        final float scale = smallTextScale();
        final float width = font.width(text) * scale;
        final float height = (font.lineHeight - 1) * scale;
        final boolean right = alignment == Alignment.TOP_RIGHT || alignment == Alignment.BOTTOM_RIGHT;
        final boolean bottom = alignment == Alignment.BOTTOM_LEFT || alignment == Alignment.BOTTOM_RIGHT;
        final float textX = right ? x + 16 - width : x;
        final float textY = bottom ? y + 16 - height : y;
        final PoseStack pose = graphics.pose();

        pose.pushPose();
        pose.translate(textX, textY, 200);
        pose.scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, color, true);
        pose.popPose();
    }

}
