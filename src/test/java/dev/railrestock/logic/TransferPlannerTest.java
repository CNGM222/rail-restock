package dev.railrestock.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransferPlannerTest {
    @Test
    void wholeStackUsesShiftWithoutCursor() {
        assertEquals(new TransferPlanner.Plan(TransferPlanner.Kind.QUICK_MOVE, 0, 64),
                TransferPlanner.plan(64, 100, 64));
    }

    @Test
    void shiftCanFillOnlyTheRemainingInventorySpace() {
        assertEquals(new TransferPlanner.Plan(TransferPlanner.Kind.QUICK_MOVE, 0, 5),
                TransferPlanner.plan(64, 100, 5));
        assertEquals(TransferPlanner.Kind.QUICK_MOVE, TransferPlanner.plan(64, 5, 5).kind());
    }

    @Test
    void finalPartialStackDoesNotOvertake() {
        TransferPlanner.Plan plan = TransferPlanner.plan(64, 5, 64);
        assertEquals(TransferPlanner.Kind.PICKUP_HALF, plan.kind());
        assertEquals(32, plan.pickupCount());
        assertEquals(5, plan.transferCount());
        assertEquals(TransferPlanner.Kind.PICKUP_ALL, TransferPlanner.plan(64, 60, 64).kind());
    }

    @Test
    void keepsCapacityForTheWholeCursor() {
        assertEquals(TransferPlanner.Kind.NONE, TransferPlanner.plan(64, 5, 10).kind());
        assertEquals(new TransferPlanner.Plan(TransferPlanner.Kind.PICKUP_HALF, 32, 32),
                TransferPlanner.plan(64, 39, 40));
        assertEquals(33, TransferPlanner.plan(65, 32, 33).pickupCount());
    }

    @Test
    void noPlanWhenNothingFitsOrNothingIsNeeded() {
        assertEquals(TransferPlanner.Kind.NONE, TransferPlanner.plan(64, 100, 0).kind());
        assertEquals(TransferPlanner.Kind.NONE, TransferPlanner.plan(64, 0, 64).kind());
        assertEquals(TransferPlanner.Kind.NONE, TransferPlanner.plan(0, 10, 10).kind());
        assertThrows(IllegalArgumentException.class, () -> TransferPlanner.plan(-1, 10, 10));
    }

    @Test
    void extremeValuesStayBounded() {
        assertEquals(new TransferPlanner.Plan(TransferPlanner.Kind.QUICK_MOVE, 0, Integer.MAX_VALUE),
                TransferPlanner.plan(Integer.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void everySmallCombinationRespectsDeficitAndCapacity() {
        for (int source = 0; source <= 64; source++) {
            for (int capacity = 0; capacity <= 128; capacity++) {
                for (int deficit = 0; deficit <= 128; deficit++) {
                    TransferPlanner.Plan plan = TransferPlanner.plan(source, deficit, capacity);
                    assertTrue(plan.transferCount() <= deficit);
                    assertTrue(plan.transferCount() <= source);
                    assertTrue(plan.transferCount() <= capacity);
                    assertTrue(plan.pickupCount() <= capacity);
                    if (plan.kind() == TransferPlanner.Kind.QUICK_MOVE) {
                        assertEquals(Math.min(source, capacity), plan.transferCount());
                        assertEquals(0, plan.pickupCount());
                    } else {
                        assertTrue(plan.transferCount() <= plan.pickupCount());
                    }
                }
            }
        }
    }
}
