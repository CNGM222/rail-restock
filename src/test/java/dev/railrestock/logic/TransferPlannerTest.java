package dev.railrestock.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferPlannerTest {
    @Test
    void wholeStackIsTakenWhenTheWholeCursorFits() {
        TransferPlanner.Plan plan = TransferPlanner.planWithoutQuickMove(64, 100, 64);
        assertEquals(TransferPlanner.Kind.PICKUP_ALL, plan.kind());
        assertEquals(64, plan.pickupCount());
        assertEquals(64, plan.transferCount());
    }

    @Test
    void halfStackFitsWhenTheWholeStackWouldNot() {
        TransferPlanner.Plan plan = TransferPlanner.planWithoutQuickMove(64, 100, 32);
        assertEquals(TransferPlanner.Kind.PICKUP_HALF, plan.kind());
        assertEquals(32, plan.pickupCount());
        assertEquals(32, plan.transferCount());
    }

    @Test
    void transferNeverExceedsTheDeficit() {
        TransferPlanner.Plan plan = TransferPlanner.planWithoutQuickMove(64, 5, 64);
        assertEquals(64, plan.pickupCount());
        assertEquals(5, plan.transferCount());
    }

    @Test
    void oddStackRoundsTheHalfPickupUp() {
        assertEquals(33, TransferPlanner.planWithoutQuickMove(65, 100, 33).pickupCount());
    }

    @Test
    void noPlanWhenNothingFitsOrNothingIsNeeded() {
        assertEquals(TransferPlanner.Kind.NONE, TransferPlanner.planWithoutQuickMove(64, 100, 0).kind());
        assertEquals(TransferPlanner.Kind.NONE, TransferPlanner.planWithoutQuickMove(64, 0, 64).kind());
        assertEquals(TransferPlanner.Kind.NONE, TransferPlanner.planWithoutQuickMove(0, 10, 10).kind());
        assertThrows(IllegalArgumentException.class,
                () -> TransferPlanner.planWithoutQuickMove(-1, 10, 10));
    }

    @Test
    void extremeValuesStayBounded() {
        TransferPlanner.Plan plan = TransferPlanner.planWithoutQuickMove(Integer.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, plan.pickupCount());
        assertEquals(Integer.MAX_VALUE, plan.transferCount());
    }

    @Test
    void everySmallCombinationRespectsEveryLimit() {
        for (int source = 0; source <= 64; source++) {
            for (int capacity = 0; capacity <= 128; capacity++) {
                for (int deficit = 0; deficit <= 128; deficit++) {
                    TransferPlanner.Plan plan =
                            TransferPlanner.planWithoutQuickMove(source, deficit, capacity);
                    assertTrue(plan.transferCount() <= deficit);
                    assertTrue(plan.transferCount() <= source);
                    assertTrue(plan.pickupCount() <= capacity);
                    assertTrue(plan.transferCount() <= plan.pickupCount());
                    if (plan.kind() == TransferPlanner.Kind.NONE) {
                        assertEquals(0, plan.pickupCount());
                        assertEquals(0, plan.transferCount());
                    }
                    // The whole cursor stack must fit, so a sudden close can always
                    // put it back: pickupCount <= capacity is the safety invariant.
                    assertTrue(plan.pickupCount() <= capacity || plan.pickupCount() == 0);
                }
            }
        }
    }
}
