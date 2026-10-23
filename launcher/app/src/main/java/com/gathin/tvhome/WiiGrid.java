package com.gathin.tvhome;

/** Slot arithmetic of the channel menu: twelve slots to a page, in four columns and three rows. */
final class WiiGrid {
    static final int COLS = 4;
    static final int ROWS = 3;
    static final int PER_PAGE = COLS * ROWS;

    private WiiGrid() { }

    /**
     * The slot an arrow leads to from this one, or -1 past the grid's edge. Right from the last column goes on
     * to the same row of the next page, left from the first column back to the previous page's last column.
     */
    static int neighbor(int slot, int dx, int dy, int pages) {
        int pg = slot / PER_PAGE, i = slot % PER_PAGE, row = i / COLS, col = i % COLS;
        if (dx < 0) {
            if (col > 0) return slot - 1;
            return pg > 0 ? (pg - 1) * PER_PAGE + row * COLS + COLS - 1 : -1;
        }
        if (dx > 0) {
            if (col < COLS - 1) return slot + 1;
            return pg < pages - 1 ? (pg + 1) * PER_PAGE + row * COLS : -1;
        }
        if (dy < 0) return row > 0 ? slot - COLS : -1;
        if (dy > 0) return row < ROWS - 1 ? slot + COLS : -1;
        return -1;
    }
}
