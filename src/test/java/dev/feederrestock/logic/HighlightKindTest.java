package dev.feederrestock.logic;

import org.junit.jupiter.api.Test;

import java.util.Set;

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
        int useful = packed(HighlightKind.USEFUL);
        int unknown = packed(HighlightKind.UNKNOWN);
        int empty = packed(HighlightKind.EMPTY);
        assertEquals(3, Set.of(useful, unknown, empty).size(), "三种状态必须用三种不同的颜色");
        // Green for useful, yellow for unknown: the red channel stays high for yellow.
        assertTrue(HighlightKind.USEFUL.green() > HighlightKind.USEFUL.red());
        assertTrue(HighlightKind.UNKNOWN.red() > 0.5f && HighlightKind.UNKNOWN.blue() < 0.5f);
        assertNotEquals(HighlightKind.USEFUL.green(), HighlightKind.UNKNOWN.green());
    }

    /** Same packing the renderer hands to MaLiLib's {@code Color4f}. */
    private static int packed(HighlightKind kind) {
        return (Math.round(kind.red() * 255) << 16)
                | (Math.round(kind.green() * 255) << 8)
                | Math.round(kind.blue() * 255);
    }
}
