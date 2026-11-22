package dev.feederrestock.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Any open container handler, and any item on the cursor, makes a print click land
 * in the wrong place. Both must always block, even when no restock session owns the
 * container — this is the guard against painting a whole area with the wrong block.
 */
class PrintSafetyTest {
    @Test
    void playerInventoryWithEmptyCursorIsSafe() {
        assertFalse(PrintSafety.blocks(false, false));
    }

    @Test
    void foreignContainerHandlerAlwaysBlocks() {
        assertTrue(PrintSafety.blocks(true, false));
    }

    @Test
    void occupiedCursorAlwaysBlocks() {
        assertTrue(PrintSafety.blocks(false, true));
    }

    @Test
    void bothUnsafeStatesBlock() {
        assertTrue(PrintSafety.blocks(true, true));
    }
}
