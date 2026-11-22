package dev.railrestock.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrintPauseTest {
    @Test
    void pauseEndsOnItsOwnAfterTheDeadline() {
        PrintPause pause = new PrintPause();
        pause.hold(100, 6);
        for (long now = 100; now < 106; now++) assertTrue(pause.active(now), "tick " + now);
        assertFalse(pause.active(106));
        assertFalse(pause.active(400));
    }

    @Test
    void aLongWaitCannotStallPrinting() {
        // Opening or verifying may take dozens of ticks; only the action holds.
        PrintPause pause = new PrintPause();
        pause.hold(10, 6);
        assertFalse(pause.active(70), "a stalled synchronization must not keep printing paused");
    }

    @Test
    void overlappingActionsExtendButNeverShortenTheWindow() {
        PrintPause pause = new PrintPause();
        pause.hold(10, 6);
        pause.hold(12, 6);
        assertEquals(6, pause.remaining(12));
        pause.hold(13, 2); // shorter hold must not pull the deadline back
        assertEquals(5, pause.remaining(13));
    }

    @Test
    void theVisitWindowNeverExceedsItsCap() {
        // A stalled sync must not freeze printing: the visit holds until a cap,
        // and the tick loop drops the gate once that absolute deadline passed.
        PrintPause pause = new PrintPause();
        pause.holdUntil(40);
        assertTrue(pause.active(39));
        assertFalse(pause.active(40));
        assertFalse(pause.active(200));
    }

    @Test
    void aLaterActionCanExtendTheVisitWindowButNotShortenIt() {
        PrintPause pause = new PrintPause();
        pause.holdUntil(40);
        assertEquals(10, pause.remaining(30));
        pause.hold(30, 6); // shorter than the cap: the window is unchanged
        assertEquals(10, pause.remaining(30));
        pause.hold(38, 6); // an action near the end extends past the cap
        assertEquals(6, pause.remaining(38));
    }

    @Test
    void releaseRestoresPrintingImmediately() {
        PrintPause pause = new PrintPause();
        pause.hold(50, 6);
        pause.release();
        assertFalse(pause.active(50));
        assertEquals(0, pause.remaining(50));
    }

    @Test
    void rejectsNonPositiveDurations() {
        PrintPause pause = new PrintPause();
        assertThrows(IllegalArgumentException.class, () -> pause.hold(0, 0));
        assertFalse(pause.active(0));
    }
}
