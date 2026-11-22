package me.aleksilassila.litematica.printer.config;

/**
 * Test-only stand-in for the printer mod's config class, so {@code PrinterBridge}
 * can be exercised through its real reflection path without the printer installed.
 * This lives in the test source set only and never reaches the shipped jar.
 */
public final class Configs {
    public static final FakeBoolean PRINT_MODE = new FakeBoolean();

    private Configs() { }

    /** Mirrors MaLiLib's ConfigBoolean surface used by the bridge. */
    public static final class FakeBoolean {
        private boolean value;

        public boolean getBooleanValue() {
            return value;
        }

        public void setBooleanValue(boolean value) {
            this.value = value;
        }
    }
}
