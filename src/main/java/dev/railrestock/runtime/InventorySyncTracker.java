package dev.railrestock.runtime;

import java.util.HashMap;
import java.util.Map;

/** Only advanced after a full server inventory packet was applied client-side. */
public final class InventorySyncTracker {
    private static long sequence;
    private static final Map<Integer, Long> fullUpdates = new HashMap<>();

    private InventorySyncTracker() { }

    public static void fullUpdate(int syncId) {
        if (fullUpdates.size() > 64) fullUpdates.clear();
        fullUpdates.put(syncId, ++sequence);
    }

    public static long sequence() {
        return sequence;
    }

    public static boolean hasFullUpdateAfter(int syncId, long baseline) {
        return fullUpdates.getOrDefault(syncId, 0L) > baseline;
    }

    public static void reset() {
        fullUpdates.clear();
        sequence = 0L;
    }
}
