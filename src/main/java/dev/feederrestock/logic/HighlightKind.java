package dev.feederrestock.logic;

/**
 * What the cache knows about one registered container, which decides how the
 * highlight is drawn. Pure logic: the caller supplies the cache facts, so the
 * colour/state mapping stays unit-testable without a world.
 */
public enum HighlightKind {
    /** Cached, and still holding something the current deficit needs. */
    USEFUL(0.20f, 0.95f, 0.35f),
    /** Registered but never opened (or the cache expired): contents unknown. */
    UNKNOWN(1.00f, 0.80f, 0.15f),
    /** Cached and known to hold nothing the deficit needs. */
    EMPTY(0.62f, 0.62f, 0.62f);

    private final float red;
    private final float green;
    private final float blue;

    HighlightKind(float red, float green, float blue) {
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    public float red() {
        return red;
    }

    public float green() {
        return green;
    }

    public float blue() {
        return blue;
    }

    public static HighlightKind of(boolean known, boolean useful) {
        if (!known) return UNKNOWN;
        return useful ? USEFUL : EMPTY;
    }
}
