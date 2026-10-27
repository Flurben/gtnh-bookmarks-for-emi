package com.emicraftingtree.bookmark;

import java.util.Arrays;

/**
 * Where the bookmark grid sits on screen: EMI's favorites space (columns x rows of 18px slots starting at
 * {@code x, y}) plus NEI's group gutter to its left. Rows can be shorter than {@code columns} where EMI's exclusion
 * areas cut into the sidebar; those slots are "invalid" like in NEI.
 */
public final class GridGeometry {

    public static final int SLOT_SIZE = 18;
    public static final int GROUP_PANEL_WIDTH = 7;

    public final int x;
    public final int y;
    public final int columns;
    public final int rows;
    public final boolean rtl;
    private final int[] widths;
    private final boolean[] invalidSlots;

    public GridGeometry(int x, int y, int columns, int rows, boolean rtl, int[] widths) {
        this.x = x;
        this.y = y;
        this.columns = Math.max(0, columns);
        this.rows = Math.max(0, rows);
        this.rtl = rtl;
        this.widths = Arrays.copyOf(widths, this.rows);
        this.invalidSlots = new boolean[this.rows * this.columns];

        for (int row = 0; row < this.rows; row++) {
            for (int column = 0; column < this.columns; column++) {
                this.invalidSlots[row * this.columns + column] = rtl ? column < this.columns - this.widths[row]
                        : column >= this.widths[row];
            }
        }
    }

    public int perPage() {
        return this.rows * this.columns;
    }

    /** Number of usable slots on a page, which is EMI's page size. */
    public int pageSize() {
        int size = 0;
        for (int width : this.widths) {
            size += width;
        }
        return size;
    }

    public boolean isInvalidSlot(int slotIndex) {
        return slotIndex < 0 || slotIndex >= this.invalidSlots.length || this.invalidSlots[slotIndex];
    }

    public int slotX(int slotIndex) {
        return this.x + (slotIndex % this.columns) * SLOT_SIZE;
    }

    public int slotY(int slotIndex) {
        return this.y + (slotIndex / this.columns) * SLOT_SIZE;
    }

    public int rowY(int rowIndex) {
        return this.y + rowIndex * SLOT_SIZE;
    }

    /** Row under the mouse, may be out of range. */
    public int getRowIndex(double mouseY) {
        return (int) Math.floor((mouseY - this.y) / SLOT_SIZE);
    }

    public int getColumnIndex(double mouseX) {
        return (int) Math.floor((mouseX - this.x) / SLOT_SIZE);
    }

    /** Page-local slot under the mouse, or -1. */
    public int getSlotIndex(double mouseX, double mouseY) {
        final int row = getRowIndex(mouseY);
        final int column = getColumnIndex(mouseX);

        if (row < 0 || row >= this.rows || column < 0 || column >= this.columns) {
            return -1;
        }

        final int slotIndex = row * this.columns + column;
        return isInvalidSlot(slotIndex) ? -1 : slotIndex;
    }

    public boolean containsGrid(double mouseX, double mouseY) {
        return getSlotIndex(mouseX, mouseY) != -1;
    }

    public boolean containsGutter(double mouseX, double mouseY) {
        return mouseX >= this.x - GROUP_PANEL_WIDTH && mouseX < this.x && mouseY >= this.y
                && mouseY < this.y + this.rows * SLOT_SIZE;
    }

    /** EMI's index of a page-local slot inside its page, counting only usable slots. */
    public int toEmiOffset(int slotIndex) {
        final int row = slotIndex / this.columns;
        final int column = slotIndex % this.columns;
        int offset = 0;

        for (int r = 0; r < row; r++) {
            offset += this.widths[r];
        }

        return offset + (this.rtl ? column - (this.columns - this.widths[row]) : column);
    }

    /** Inverse of {@link #toEmiOffset}, or -1. */
    public int fromEmiOffset(int offset) {
        for (int row = 0; row < this.rows; row++) {
            if (offset < this.widths[row]) {
                return row * this.columns + (this.rtl ? this.columns - this.widths[row] + offset : offset);
            }
            offset -= this.widths[row];
        }
        return -1;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof GridGeometry other && this.x == other.x && this.y == other.y
                && this.columns == other.columns && this.rows == other.rows && this.rtl == other.rtl
                && Arrays.equals(this.widths, other.widths);
    }

    @Override
    public int hashCode() {
        return ((this.x * 31 + this.y) * 31 + this.columns) * 31 + this.rows + Arrays.hashCode(this.widths);
    }

}
