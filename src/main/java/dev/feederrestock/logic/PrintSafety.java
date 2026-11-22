package dev.feederrestock.logic;

/**
 * Pure decision for whether printing must stay paused.
 *
 * <p>Printing, Easy Place and the printer all act on the slots of the <em>active</em>
 * screen handler and on the item the player currently holds. With a container
 * handler open those slot clicks land inside the chest, so the item picked for the
 * hotbar never reaches it, stays on the cursor, and the print then places whatever
 * is still in hand — which can paint a whole area with the wrong block. An occupied
 * cursor is just as unsafe, because the next pickup merges into it instead of
 * replacing it.</p>
 *
 * <p>Both conditions are therefore an unconditional stop, independent of whether a
 * restock session thinks it owns the container.</p>
 */
public final class PrintSafety {
    private PrintSafety() { }

    /** @param foreignScreenHandler the active handler is not the player inventory
     *  @param cursorOccupied the mouse cursor carries an item stack */
    public static boolean blocks(boolean foreignScreenHandler, boolean cursorOccupied) {
        return foreignScreenHandler || cursorOccupied;
    }
}
