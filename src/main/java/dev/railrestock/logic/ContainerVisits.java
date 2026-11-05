package dev.railrestock.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.LongPredicate;

/** Inspects once per encounter, unless inventory space actually improves after a blocked visit. */
public final class ContainerVisits {
    private final Set<Long> visited = new HashSet<>();
    private final Map<Long, List<CapacityCheck>> capacityBlocked = new HashMap<>();

    private record CapacityCheck(int baseline, int minimum, IntSupplier current) {
        boolean improved() {
            int capacity = current.getAsInt();
            return capacity > baseline && capacity >= minimum;
        }
    }

    public void capacityBlocked(long key, int baseline, int minimum, IntSupplier current) {
        capacityBlocked.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new CapacityCheck(baseline, minimum, current));
    }

    public boolean contains(long key) {
        return visited.contains(key);
    }

    public void mark(long key) {
        visited.add(key);
    }

    /** Uses physical reach, not line of sight, screen state or material/inventory changes. */
    public void forgetDeparted(LongPredicate stillNearby) {
        visited.removeIf(key -> !stillNearby.test(key)
                || capacityBlocked.getOrDefault(key, List.of()).stream().anyMatch(CapacityCheck::improved));
        capacityBlocked.keySet().retainAll(visited);
    }

    public void clear() {
        visited.clear();
        capacityBlocked.clear();
    }
}
