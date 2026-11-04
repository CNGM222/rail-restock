package dev.railrestock.logic;

/**
 * Decides whether a container screen is one we opened ourselves and may keep
 * hidden. Pure logic: the caller must supply accurate ownership facts and must
 * close its hidden session before letting a real user screen appear.
 */
public final class ScreenHider {
    private ScreenHider() { }

    /**
     * @param unobtrusive        the user enabled invisible restocking
     * @param ownOpenRequested   we just sent the block interaction and await its handler
     * @param handledScreen      the requested screen is a container screen at all
     * @param supportedHandler   the newly bound handler is a supported container handler
     * @param noCurrentScreen    no screen is currently displayed (so the mouse is still captured)
     */
    public record Facts(boolean unobtrusive, boolean ownOpenRequested, boolean handledScreen,
                        boolean supportedHandler, boolean noCurrentScreen) { }

    /** Hides only our own automatic container screen; every other screen passes through. */
    public static boolean hide(Facts facts) {
        return facts.unobtrusive()
                && facts.ownOpenRequested()
                && facts.handledScreen()
                && facts.supportedHandler()
                && facts.noCurrentScreen();
    }
}
