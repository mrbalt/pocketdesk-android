package app.pocketdesk;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Lets the PocketDesk desktop app operate this phone. While this accessibility service is switched on, it long-polls the
 * PocketDesk server for commands (tap, swipe, key, text, wake, unlock) and carries them out.
 */
public class PocketAccessibilityService extends AccessibilityService {
    public static volatile boolean connected = false;
    private volatile boolean alive;
    private Thread poller;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        alive = true;
        poller = new Thread(this::pollLoop, "pd-cmd");
        poller.setDaemon(true);
        poller.start();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        alive = false;
        connected = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        alive = false;
        connected = false;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ talking to the PC

    private String baseUrl() {
        SharedPreferences p = getSharedPreferences("pocketdesk", Context.MODE_PRIVATE);
        String u = p.getString("url", null);
        return u == null ? null : u.replaceAll("/+$", "");
    }

    private void pollLoop() {
        int backoff = 1000;
        while (alive) {
            String base = baseUrl();
            if (base == null) { sleep(3000); continue; }
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + "/phone/cmd/poll?timeout=20").openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(30000);
                String ck = CookieManager.getInstance().getCookie(base);
                if (ck != null) c.setRequestProperty("Cookie", ck);
                int code = c.getResponseCode();
                if (code != 200) { sleep(code == 401 ? 5000 : backoff); backoff = Math.min(backoff * 2, 8000); continue; }
                backoff = 1000;
                JSONArray cmds = new JSONObject(readAll(c.getInputStream())).getJSONArray("cmds");
                for (int i = 0; i < cmds.length() && alive; i++) {
                    try { run(cmds.getJSONObject(i)); } catch (Exception ignored) { }
                }
            } catch (Exception e) {
                sleep(backoff);
                backoff = Math.min(backoff * 2, 8000);
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    // ------------------------------------------------------------------ doing things

    private void run(JSONObject c) throws Exception {
        String t = c.getString("t");
        switch (t) {
            case "tap":
                gesture(c.getDouble("x"), c.getDouble("y"), c.getDouble("x"), c.getDouble("y"), 50);
                break;
            case "swipe":
                gesture(c.getDouble("x1"), c.getDouble("y1"), c.getDouble("x2"), c.getDouble("y2"), c.optInt("ms", 250));
                break;
            case "key":
                key(c.getString("k"));
                break;
            case "text":
                typeText(c.getString("s"));
                break;
            case "wake":
                wake();
                break;
            case "unlock":
                unlock(c.optString("pin", ""));
                break;
            default:
                break;
        }
    }

    private void gesture(double x1, double y1, double x2, double y2, int ms) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        Path p = new Path();
        p.moveTo((float) (x1 * (m.widthPixels - 1)), (float) (y1 * (m.heightPixels - 1)));
        if (x1 != x2 || y1 != y2) p.lineTo((float) (x2 * (m.widthPixels - 1)), (float) (y2 * (m.heightPixels - 1)));
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(ms, 1)))
                .build();
        dispatchGesture(g, null, null);
        sleep(Math.min(ms, 400) + 60);   // keep gestures from overlapping and cancelling each other
    }

    private void key(String k) {
        switch (k) {
            case "back": performGlobalAction(GLOBAL_ACTION_BACK); break;
            case "home": performGlobalAction(GLOBAL_ACTION_HOME); break;
            case "recents": performGlobalAction(GLOBAL_ACTION_RECENTS); break;
            case "del": editFocused(null, true); break;
            case "enter": {
                AccessibilityNodeInfo n = getRootInActiveWindow() == null ? null : getRootInActiveWindow().findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (n != null && Build.VERSION.SDK_INT >= 30) n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                break;
            }
            default: break;   // arrows and Tab cannot be sent through accessibility
        }
    }

    private void typeText(String s) {
        editFocused(s, false);
    }

    /** Appends text to (or deletes the last character of) the focused text field. */
    private void editFocused(String add, boolean backspace) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (n == null) return;
        CharSequence cur = (n.getText() == null || n.isShowingHintText()) ? "" : n.getText();
        String next = backspace ? (cur.length() > 0 ? cur.subSequence(0, cur.length() - 1).toString() : "") : cur.toString() + add;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next);
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    @SuppressWarnings("deprecation")
    private void wake() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "pocketdesk:wake");
        wl.acquire(4000);
    }

    /** Best effort: wake, swipe up, then press the PIN keys on the lock screen by their labels, then Enter/OK. */
    private void unlock(String pin) {
        wake();
        sleep(900);
        gesture(0.5, 0.85, 0.5, 0.25, 300);
        sleep(900);
        if (pin.isEmpty()) return;
        for (int i = 0; i < pin.length(); i++) {
            if (!pressLabel(String.valueOf(pin.charAt(i)))) return;   // keypad not found: stop rather than tap blindly
            sleep(180);
        }
        sleep(200);
        pressLabel("Enter");
    }

    private boolean pressLabel(String label) {
        List<AccessibilityWindowInfo> wins = getWindows();
        for (int w = wins.size() - 1; w >= 0; w--) {          // topmost windows first
            AccessibilityNodeInfo root = wins.get(w).getRoot();
            if (root == null) continue;
            AccessibilityNodeInfo hit = find(root, label, 0);
            if (hit != null) {
                AccessibilityNodeInfo n = hit;
                while (n != null && !n.isClickable()) n = n.getParent();
                if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo n, String label, int depth) {
        if (n == null || depth > 25) return null;
        CharSequence t = n.getText(), d = n.getContentDescription();
        if ((t != null && label.equalsIgnoreCase(t.toString().trim())) || (d != null && label.equalsIgnoreCase(d.toString().trim()))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = find(n.getChild(i), label, depth + 1);
            if (r != null) return r;
        }
        return null;
    }
}
