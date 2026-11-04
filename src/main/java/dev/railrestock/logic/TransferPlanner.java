package dev.railrestock.logic;

/**
 * Plans a single safe transfer from an interactable container slot into the
 * player's inventory. No Minecraft dependency and no side effects.
 *
 * <p>{@code capacity} is the insertable capacity available for this item. A
 * pickup is only permitted when the whole cursor stack fits, including the part
 * not needed for the deficit, so an unexpected container close cannot strand an
 * oversized cursor stack. Client predictions are not server confirmations: the
 * caller must cross an authoritative inventory synchronization barrier before
 * treating a transfer as complete or planning another one.
 */
public final class TransferPlanner {
    private static final Plan NONE = new Plan(Kind.NONE, 0, 0);

    private TransferPlanner() { }

    public enum Kind { NONE, QUICK_MOVE, PICKUP_ALL, PICKUP_HALF }

    /**
     * @param kind the operation to perform
     * @param pickupCount items taken onto the cursor (0 for NONE/QUICK_MOVE)
     * @param transferCount items that should stay in the inventory; any surplus
     *                      cursor remainder is returned to the source slot
     */
    public record Plan(Kind kind, int pickupCount, int transferCount) { }

    /**
     * Cursor-based plan. Quick move is intentionally unsupported: it cannot
     * exclude a reserved hotbar slot and cannot express a partial count.
     */
    public static Plan planWithoutQuickMove(int sourceCount, long deficit, int capacity) {
        if (sourceCount < 0) throw new IllegalArgumentException("sourceCount must not be negative");
        if (sourceCount == 0 || deficit <= 0 || capacity <= 0) return NONE;
        if (capacity >= sourceCount) {
            return new Plan(Kind.PICKUP_ALL, sourceCount, (int) Math.min((long) sourceCount, deficit));
        }
        int half = sourceCount / 2 + sourceCount % 2;
        if (capacity >= half) {
            return new Plan(Kind.PICKUP_HALF, half, (int) Math.min((long) half, deficit));
        }
        return NONE;
    }
}
