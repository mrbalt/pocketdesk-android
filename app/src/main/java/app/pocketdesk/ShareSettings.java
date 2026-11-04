package app.pocketdesk;

/** Sharing limits chosen on the PC (max frame rate and the longest side of the picture). Applied live. */
public final class ShareSettings {
    public static volatile int maxFps = 60;
    public static volatile int maxEdge = 1280;

    private ShareSettings() { }

    public static long minGapMs() {
        return Math.max(1, 1000 / Math.max(1, maxFps));
    }
}
