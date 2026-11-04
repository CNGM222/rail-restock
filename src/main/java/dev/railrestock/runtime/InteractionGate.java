package dev.railrestock.runtime;

/** Main-client-thread arbitration only; never changes Litematica's configuration. */
public final class InteractionGate {
    private static boolean busy;

    private InteractionGate() { }

    public static boolean isBusy() {
        return busy;
    }

    public static void setBusy(boolean value) {
        busy = value;
    }
}
