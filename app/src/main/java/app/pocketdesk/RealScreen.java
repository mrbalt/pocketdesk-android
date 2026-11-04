package app.pocketdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;

/**
 * The phone's true screen size in pixels, including the status and navigation bars. getResources().getDisplayMetrics()
 * can leave the navigation bar out, which made taps from the PC land a little off (more so near the bottom).
 */
final class RealScreen {
    private RealScreen() { }

    static DisplayMetrics get(Context c) {
        DisplayMetrics m = new DisplayMetrics();
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            d.getRealMetrics(m);
            if (m.widthPixels > 0 && m.heightPixels > 0) return m;
        } catch (Exception ignored) { }
        return c.getResources().getDisplayMetrics();
    }
}
