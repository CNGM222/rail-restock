package dev.railrestock.logic;

/** Plans exact transfers without exceeding either the deficit or inventory capacity. */
public final class TransferPlanner {
    private static final Plan NONE = new Plan(Kind.NONE, 0, 0);

    private TransferPlanner() { }

    public enum Kind { NONE, QUICK_MOVE, PICKUP_ALL, PICKUP_HALF }

    /** Cursor pickups require capacity for the entire pickup in case the screen closes. */
    public record Plan(Kind kind, int pickupCount, int transferCount) { }

    public static Plan plan(int sourceCount, long deficit, int capacity) {
        if (sourceCount < 0) throw new IllegalArgumentException("sourceCount must not be negative");
        if (sourceCount == 0 || deficit <= 0 || capacity <= 0) return NONE;
        int quickCount = Math.min(sourceCount, capacity);
        // Vanilla QUICK_MOVE also handles a stack that only partly fits. It never
        // puts anything on the cursor and does not change the selected hotbar slot.
        if (quickCount <= deficit) return new Plan(Kind.QUICK_MOVE, 0, quickCount);

        int half = sourceCount / 2 + sourceCount % 2;
        if (capacity >= half && deficit <= half) {
            return new Plan(Kind.PICKUP_HALF, half, (int) deficit);
        }
        if (capacity >= sourceCount) {
            return new Plan(Kind.PICKUP_ALL, sourceCount, (int) deficit);
        }
        if (capacity >= half) {
            return new Plan(Kind.PICKUP_HALF, half, (int) Math.min((long) half, deficit));
        }
        return NONE;
    }
}
