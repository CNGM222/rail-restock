package dev.railrestock.logic;

/**
 * A deadline, not a visit lock: printing resumes on its own a few ticks after the
 * last slot action, so a slow open or inventory synchronization cannot stall it.
 */
public final class PrintPause {
    private long until;

    public void hold(long now, int ticks) {
        if (ticks < 1) throw new IllegalArgumentException("ticks must be positive");
        until = Math.max(until, now + ticks);
    }

    /** Keeps printing paused until an absolute tick, used by the capped visit window. */
    public void holdUntil(long deadline) {
        until = Math.max(until, deadline);
    }

    public boolean active(long now) {
        return until > now;
    }

    /** Remaining ticks, for diagnostics and tests. */
    public long remaining(long now) {
        return Math.max(0, until - now);
    }

    public void release() {
        until = 0;
    }
}
