package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Plain JVM tests for the channel menu's slot arithmetic. */
public class WiiGridTest {

    @Test
    public void arrowsMoveWithinAPage() {
        assertEquals(6, WiiGrid.neighbor(5, 1, 0, 4));
        assertEquals(4, WiiGrid.neighbor(5, -1, 0, 4));
        assertEquals(1, WiiGrid.neighbor(5, 0, -1, 4));
        assertEquals(9, WiiGrid.neighbor(5, 0, 1, 4));
    }

    @Test
    public void sidesTurnThePageOnTheSameRow() {
        assertEquals(16, WiiGrid.neighbor(7, 1, 0, 4));     // second row, last column -> next page, second row
        assertEquals(7, WiiGrid.neighbor(16, -1, 0, 4));
        assertEquals(44, WiiGrid.neighbor(35, 1, 0, 4));    // third page, bottom row -> fourth page, bottom row
    }

    @Test
    public void edgesStop() {
        assertEquals(-1, WiiGrid.neighbor(0, -1, 0, 4));    // first page, first column
        assertEquals(-1, WiiGrid.neighbor(47, 1, 0, 4));    // last page, last column
        assertEquals(-1, WiiGrid.neighbor(14, 0, -1, 4));   // top row
        assertEquals(-1, WiiGrid.neighbor(22, 0, 1, 4));    // bottom row: the menu goes to the buttons instead
        assertEquals(-1, WiiGrid.neighbor(3, 1, 0, 1));     // a single page has no next one
    }

    @Test
    public void noArrowGoesNowhere() {
        assertEquals(-1, WiiGrid.neighbor(5, 0, 0, 4));
    }
}
