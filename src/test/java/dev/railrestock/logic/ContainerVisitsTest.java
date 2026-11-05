package dev.railrestock.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContainerVisitsTest {
    @Test
    void all64NearbyContainersStayVisitedAcrossManyScanCycles() {
        ContainerVisits visits = new ContainerVisits();
        for (long key = 0; key < 64; key++) visits.mark(key);
        for (int scan = 0; scan < 20_000; scan++) visits.forgetDeparted(key -> true);
        for (long key = 0; key < 64; key++) assertTrue(visits.contains(key));
    }

    @Test
    void onlyDepartedContainersAreRearmed() {
        ContainerVisits visits = new ContainerVisits();
        visits.mark(1);
        visits.mark(2);
        visits.forgetDeparted(key -> key == 2);
        assertFalse(visits.contains(1));
        assertTrue(visits.contains(2));
        visits.mark(1);
        assertTrue(visits.contains(1));
    }

    @Test
    void fullInventoryDoesNotCauseRetriesUntilSpaceActuallyIncreases() {
        ContainerVisits visits = new ContainerVisits();
        int[] capacity = {0};
        visits.mark(1);
        visits.mark(2); // An exhausted container must stay remembered.
        visits.capacityBlocked(1, 0, 1, () -> capacity[0]);
        for (int scan = 0; scan < 2000; scan++) visits.forgetDeparted(key -> true);
        assertTrue(visits.contains(1));
        capacity[0] = 64; // Printing used a stack while standing still.
        visits.forgetDeparted(key -> true);
        assertFalse(visits.contains(1));
        assertTrue(visits.contains(2));
    }

    @Test
    void tinyCapacityIncreasesWaitUntilAnExactPickupCanFit() {
        ContainerVisits visits = new ContainerVisits();
        int[] capacity = {10};
        visits.mark(1);
        visits.capacityBlocked(1, 10, 32, () -> capacity[0]);
        capacity[0] = 11;
        visits.forgetDeparted(key -> true);
        assertTrue(visits.contains(1));
        capacity[0] = 32;
        visits.forgetDeparted(key -> true);
        assertFalse(visits.contains(1));
    }

    @Test
    void explicitRestartOrNewWorldCanRecheckAllContainers() {
        ContainerVisits visits = new ContainerVisits();
        visits.mark(42);
        visits.clear();
        assertFalse(visits.contains(42));
    }
}
