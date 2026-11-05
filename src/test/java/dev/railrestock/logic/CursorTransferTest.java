package dev.railrestock.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CursorTransferTest {
    @Test
    void mergesPartialSlotInOneClickInsteadOfOneItemAtATime() {
        Inventory inventory = new Inventory(64, 32, 64);
        CursorTransfer.execute(TransferPlanner.plan(64, 60, 96), inventory);
        assertEquals(60, inventory.deposited);
        assertEquals(4, inventory.source);
        assertEquals(0, inventory.cursor);
        // pickup + 4 surplus returns + 2 left-click deposits
        assertEquals(7, inventory.clicks);
        assertEquals(2, inventory.wholeDeposits);
    }

    @Test
    void smallFinalDeficitCompletesImmediatelyAndReturnsTheRest() {
        Inventory inventory = new Inventory(64, 64);
        CursorTransfer.execute(TransferPlanner.plan(64, 5, 64), inventory);
        assertEquals(5, inventory.deposited);
        assertEquals(59, inventory.source);
        assertEquals(0, inventory.cursor);
        assertEquals(7, inventory.clicks);
    }

    @Test
    void exactHalfRequiresJustPickupAndDeposit() {
        Inventory inventory = new Inventory(64, 64);
        CursorTransfer.execute(TransferPlanner.plan(64, 32, 64), inventory);
        assertEquals(32, inventory.source);
        assertEquals(32, inventory.deposited);
        assertEquals(0, inventory.cursor);
        assertEquals(2, inventory.clicks);
    }

    @Test
    void rejectsUnexpectedCursorPrediction() {
        Inventory inventory = new Inventory(32, 64);
        assertThrows(IllegalStateException.class,
                () -> CursorTransfer.execute(TransferPlanner.plan(64, 5, 64), inventory));
        assertEquals(0, inventory.deposited);
    }

    @Test
    void partialTransfersPreserveItemsAcrossSmallInventories() {
        for (int source = 1; source <= 64; source++) {
            for (int wanted = 1; wanted < source; wanted++) {
                for (int firstSpace : new int[]{1, 7, 31, 63}) {
                    Inventory inventory = new Inventory(source, firstSpace, 64);
                    TransferPlanner.Plan plan = TransferPlanner.plan(source, wanted, firstSpace + 64);
                    CursorTransfer.execute(plan, inventory);
                    assertEquals(wanted, inventory.deposited);
                    assertEquals(source - wanted, inventory.source);
                    assertEquals(0, inventory.cursor);
                    assertTrue(inventory.clicks <= 36, "Final remainder must not need a whole stack of single clicks");
                }
            }
        }
    }

    /** Simulates the vanilla left/right pickup rules, not the transfer algorithm. */
    private static final class Inventory implements CursorTransfer.Access {
        int source;
        int cursor;
        int deposited;
        int clicks;
        int wholeDeposits;
        final int[] spaces;

        Inventory(int source, int... spaces) {
            this.source = source;
            this.spaces = spaces;
        }

        @Override public void pickup(boolean half) {
            clicks++;
            cursor = half ? (source + 1) / 2 : source;
            source -= cursor;
        }
        @Override public int cursorCount() { return cursor; }
        @Override public boolean canReturn(int count) { return source + count <= 64; }
        @Override public void returnToSource(boolean single) {
            clicks++;
            int moved = single ? 1 : cursor;
            source += moved;
            cursor -= moved;
        }
        @Override public int destination() {
            for (int i = 0; i < spaces.length; i++) if (spaces[i] > 0) return i;
            return -1;
        }
        @Override public int space(int slot) { return spaces[slot]; }
        @Override public void deposit(int slot, boolean single) {
            clicks++;
            if (!single) wholeDeposits++;
            int moved = Math.min(spaces[slot], single ? 1 : cursor);
            cursor -= moved;
            spaces[slot] -= moved;
            deposited += moved;
        }
    }
}
