package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WiiMenuTimingTest {
    @Test public void pausedMenuNeverSchedulesAnotherFrame() {
        for (int flags = 0; flags < 8; flags++) {
            assertEquals(-1, WiiMenuView.frameDelay(false, (flags & 1) != 0,
                    (flags & 2) != 0, (flags & 4) != 0));
        }
    }

    @Test public void transitionsAndIntroKeepTheDisplayCadence() {
        assertEquals(0, WiiMenuView.frameDelay(true, true, false, true));
        assertEquals(0, WiiMenuView.frameDelay(true, false, true, false));
    }

    @Test public void liveBannerKeepsItsExistingCadence() {
        assertEquals(33, WiiMenuView.frameDelay(true, false, false, true));
    }

    @Test public void idleMenuWaitsForAnEvent() {
        assertEquals(-1, WiiMenuView.frameDelay(true, false, false, false));
    }

    @Test public void resumingAllowsAnimationAgain() {
        assertEquals(-1, WiiMenuView.frameDelay(false, true, true, true));
        assertEquals(0, WiiMenuView.frameDelay(true, true, true, true));
    }

    @Test public void clockReadsOncePerSecondDuringContinuousFrames() {
        long previous = 0;
        int reads = 0;
        for (long now = 0; now < 10_000; now += 16) {
            if (WiiMenuView.clockDue(reads == 0, now - previous)) {
                previous = now;
                reads++;
            }
        }
        assertEquals(10, reads);
    }

    @Test public void staleClockRefreshesImmediatelyAfterResume() {
        assertTrue(WiiMenuView.clockDue(true, 0));
    }
}
