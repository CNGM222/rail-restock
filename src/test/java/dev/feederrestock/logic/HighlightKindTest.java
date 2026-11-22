package dev.feederrestock.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The highlight colour is the only thing telling "this chest has the material" from
 * "this chest was never surveyed", so the mapping is pinned here.
 */
class HighlightKindTest {
    @Test
    void unknownCacheIsYellowNoMatterWhatTheUsefulCountSays() {
        assertEquals(HighlightKind.UNKNOWN, HighlightKind.of(false, false));
        assertEquals(HighlightKind.UNKNOWN, HighlightKind.of(false, true));
    }

    @Test
    void cachedContainersSplitIntoUsefulAndEmpty() {
        assertEquals(HighlightKind.USEFUL, HighlightKind.of(true, true));
        assertEquals(HighlightKind.EMPTY, HighlightKind.of(true, false));
    }

    @Test
    void eachStateHasADistinctOpaqueColour() {
        int useful = HighlightKind.USEFUL.argb();
        int unknown = HighlightKind.UNKNOWN.argb();
        int empty = HighlightKind.EMPTY.argb();
        assertEquals(3, java.util.Set.of(useful, unknown, empty).size());
        for (int colour : new int[]{useful, unknown, empty}) {
            assertEquals(0xFF, (colour >>> 24) & 0xFF, "颜色必须不透明");
        }
        // Green for useful, yellow for unknown: the red channel stays high for yellow.
        assertTrue(HighlightKind.USEFUL.green() > HighlightKind.USEFUL.red());
        assertTrue(HighlightKind.UNKNOWN.red() > 0.5f && HighlightKind.UNKNOWN.blue() < 0.5f);
        assertNotEquals(HighlightKind.USEFUL.green(), HighlightKind.UNKNOWN.green());
    }
}
