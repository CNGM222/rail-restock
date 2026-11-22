package dev.feederrestock.runtime;

/**
 * Main-client-thread arbitration only; never changes Litematica's configuration.
 *
 * <p>{@link InventoryGuard} is part of {@link #isBusy()} on purpose: a container
 * handler that is still open, or an item stuck on the cursor, makes every slot
 * click land in the wrong place, so no call site may forget that check.</p>
 */
public final class InteractionGate {
    private static boolean busy;

    private InteractionGate() { }

    public static boolean isBusy() {
        return busy || InventoryGuard.blocksPrinting();
    }

    public static void setBusy(boolean value) {
        busy = value;
    }
}
