package dev.railrestock.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenHiderTest {
    @Test
    void hidesOnlyOurOwnAutomaticContainerScreen() {
        assertTrue(ScreenHider.hide(new ScreenHider.Facts(true, true, true, true, true)));
    }

    @Test
    void neverHidesAUserScreen() {
        assertFalse(ScreenHider.hide(new ScreenHider.Facts(true, false, true, true, true)));
        assertFalse(ScreenHider.hide(new ScreenHider.Facts(true, true, false, true, true)));
        assertFalse(ScreenHider.hide(new ScreenHider.Facts(true, true, true, false, true)));
        assertFalse(ScreenHider.hide(new ScreenHider.Facts(true, true, true, true, false)));
    }

    @Test
    void unobtrusiveModeCanBeDisabledEntirely() {
        assertFalse(ScreenHider.hide(new ScreenHider.Facts(false, true, true, true, true)));
    }

    @Test
    void everyCombinationHidesOnlyWhenAllFiveConditionsHold() {
        for (int mask = 0; mask < 32; mask++) {
            boolean unobtrusive = (mask & 1) != 0;
            boolean ownOpen = (mask & 2) != 0;
            boolean handled = (mask & 4) != 0;
            boolean supported = (mask & 8) != 0;
            boolean noScreen = (mask & 16) != 0;
            boolean expected = unobtrusive && ownOpen && handled && supported && noScreen;
            assertTrue(expected == ScreenHider.hide(
                    new ScreenHider.Facts(unobtrusive, ownOpen, handled, supported, noScreen)));
        }
    }

    @Test
    void factsAreRequired() {
        assertThrows(NullPointerException.class, () -> ScreenHider.hide(null));
    }
}
