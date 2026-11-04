package app.pocketdesk;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.SystemClock;
import android.view.Display;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    private volatile boolean shotLoop;
    private FrameUploader shotUp;
    private PowerManager.WakeLock shotWake;
    public static volatile boolean sharing = false;

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
        stopScreenshotShare();
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
            boolean failed = false;
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
                failed = true;
                sleep(backoff);
                backoff = Math.min(backoff * 2, 8000);
            } finally {
                if (c != null && failed) c.disconnect();     // otherwise keep the connection for the next poll: much quicker for games
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
            case "touch":
                touch(c.optString("p", "u"), c.optDouble("x", 0), c.optDouble("y", 0));
                break;
            case "rot":
                orient(c.optString("o", "auto"));
                break;
            case "cfg":
                ShareSettings.maxFps = Math.max(1, Math.min(60, c.optInt("fps", 30)));
                ShareSettings.maxEdge = Math.max(320, Math.min(3840, c.optInt("edge", 1280)));
                break;
            case "share":
                if (!c.optBoolean("on", true)) { stopScreenshotShare(); stopService(new Intent(this, ScreenShareService.class)); }
                else if ("projection".equals(c.optString("mode", "screenshot"))) startSharing();   // smooth, but Android shows its prompt
                else startScreenshotShare();                                                      // no prompt, about 3 frames a second
                break;
            default:
                break;
        }
    }

    // ---- live touch: a finger that stays down while the PC mouse is held, so games can hold and drag
    private final Object touchLock = new Object();
    private GestureDescription.StrokeDescription touchStroke;
    private boolean touchBusy, wantMove, wantUp;
    private float lastX, lastY, wantX, wantY;

    private final AccessibilityService.GestureResultCallback touchCb = new AccessibilityService.GestureResultCallback() {
        @Override public void onCompleted(GestureDescription g) { synchronized (touchLock) { touchBusy = false; touchPump(); } }
        @Override public void onCancelled(GestureDescription g) { synchronized (touchLock) { touchBusy = false; touchStroke = null; wantMove = false; wantUp = false; } }
    };

    private void touch(String phase, double fx, double fy) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        float x = (float) (fx * (m.widthPixels - 1)), y = (float) (fy * (m.heightPixels - 1));
        synchronized (touchLock) {
            if (phase.equals("d") && touchStroke == null && !touchBusy) {
                lastX = x; lastY = y; wantMove = false; wantUp = false;
                Path p = new Path();
                p.moveTo(x, y);
                touchStroke = new GestureDescription.StrokeDescription(p, 0, 1, true);
                touchBusy = true;
                if (!dispatchGesture(new GestureDescription.Builder().addStroke(touchStroke).build(), touchCb, null)) { touchBusy = false; touchStroke = null; }
            } else if (phase.equals("u")) {
                wantX = x; wantY = y; wantMove = true; wantUp = true;
                touchPump();
            } else if (touchStroke != null || touchBusy) {      // a move (or a stray second "down")
                wantX = x; wantY = y; wantMove = true;
                touchPump();
            }
        }
    }

    /** Sends the newest wanted finger position as the next piece of the held stroke. Call with touchLock held. */
    private void touchPump() {
        if (touchBusy || touchStroke == null || !(wantMove || wantUp)) return;
        float tx = wantMove ? wantX : lastX, ty = wantMove ? wantY : lastY;
        boolean end = wantUp;
        Path p = new Path();
        p.moveTo(lastX, lastY);
        p.lineTo(tx, ty);
        GestureDescription.StrokeDescription next = touchStroke.continueStroke(p, 0, end ? 1 : 12, !end);
        lastX = tx; lastY = ty; wantMove = false; wantUp = false;
        touchStroke = end ? null : next;
        touchBusy = true;
        if (!dispatchGesture(new GestureDescription.Builder().addStroke(next).build(), touchCb, null)) { touchBusy = false; touchStroke = null; }
    }

    // ---- really rotating the phone: a see-through window that asks Android for an orientation
    private android.view.View rotView;

    private void orient(final String o) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
                if (o.equals("auto")) {
                    if (rotView != null) { wm.removeView(rotView); rotView = null; }
                    return;
                }
                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                        android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT);
                lp.screenOrientation = o.equals("landscape") ? 6 : 7;     // SENSOR_LANDSCAPE / SENSOR_PORTRAIT
                if (rotView == null) {
                    rotView = new android.view.View(this);
                    wm.addView(rotView, lp);
                } else {
                    wm.updateViewLayout(rotView, lp);
                }
            } catch (Exception ignored) { }
        });
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

    // ------------------------------------------------------------------ prompt-free sharing (screenshots)

    private void startScreenshotShare() {
        String base = baseUrl();
        if (shotLoop || base == null || Build.VERSION.SDK_INT < 30) return;
        shotLoop = true;
        sharing = true;
        shotUp = new FrameUploader(base);
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            shotWake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketdesk:shots");
            shotWake.acquire();
        } catch (Exception ignored) { }
        Thread t = new Thread(this::shotRun, "pd-shot");
        t.setDaemon(true);
        t.start();
    }

    private void stopScreenshotShare() {
        shotLoop = false;
        sharing = false;
        FrameUploader u = shotUp;
        shotUp = null;
        if (u != null) u.stop(true);
        try { if (shotWake != null && shotWake.isHeld()) shotWake.release(); } catch (Exception ignored) { }
    }

    private void shotRun() {
        final Executor ex = Executors.newSingleThreadExecutor();
        while (shotLoop && alive) {
            long t0 = SystemClock.uptimeMillis();
            final CountDownLatch done = new CountDownLatch(1);
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, ex, new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult r) {
                        try {
                            HardwareBuffer hb = r.getHardwareBuffer();
                            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hb.close();
                            int longEdge = Math.max(sw.getWidth(), sw.getHeight());
                            FrameUploader up = shotUp;
                            float k = Math.min(1f, (float) ShareSettings.maxEdge / longEdge) * (up == null ? 1f : up.scale());
                            Bitmap sc = k < 1f ? Bitmap.createScaledBitmap(sw, Math.round(sw.getWidth() * k), Math.round(sw.getHeight() * k), true) : sw;
                            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                            FrameUploader u = shotUp;
                            sc.compress(Bitmap.CompressFormat.JPEG, u == null ? 65 : u.quality(), out);
                            if (u != null) u.offer(out.toByteArray());
                            if (sc != sw) sc.recycle();
                            sw.recycle();
                        } catch (Exception ignored) {
                        } finally {
                            done.countDown();
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        done.countDown();
                    }
                });
                done.await(3, TimeUnit.SECONDS);
            } catch (Exception ignored) { }
            long wait = Math.max(350, ShareSettings.minGapMs()) - (SystemClock.uptimeMillis() - t0);   // Android allows a screenshot only every ~1/3 s
            if (wait > 0) sleep(wait);
        }
    }

    /** Opens PocketDesk, which shows Android's screen-capture prompt, then taps through that prompt. */
    private void startSharing() {
        if (ScreenShareService.running) return;
        stopScreenshotShare();
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_AUTOSHARE, true);
        try {
            startActivity(i);
        } catch (Exception e) {
            // Android refused to open it from the background: leave a notification the person can tap instead
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("share-ask", "Share requests", NotificationManager.IMPORTANCE_HIGH));
            int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "share-ask") : new Notification.Builder(this);
            b.setContentTitle("Your PC asked to share this screen").setContentText("Tap to start sharing")
                    .setSmallIcon(android.R.drawable.ic_menu_share).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(this, 1, i, pf));
            nm.notify(9, b.build());
            return;
        }
        // Walk through Android's capture prompt: open the "Share one app" dropdown, pick "Share entire screen",
        // then press the start button. Never press start before the entire-screen choice, or it would share one app.
        String[] buttons = {"Start now", "Share screen", "Start", "Next", "While using the app", "Allow"};
        long end = System.currentTimeMillis() + 20000;
        boolean entireChosen = false;
        int dropdownTries = 0;
        while (System.currentTimeMillis() < end && alive && !ScreenShareService.running) {
            sleep(600);
            if (!entireChosen) {
                if (pressContains("entire screen")) { entireChosen = true; sleep(500); continue; }
                if (dropdownTries < 6 && (pressContains("one app") || pressContains("single app"))) { dropdownTries++; sleep(500); continue; }
            }
            for (String g : buttons) {
                if (pressLabel(g)) { sleep(800); break; }
            }
        }
    }

    /** Like pressLabel, but matches any visible text or description that contains the given words (case-insensitive). */
    private boolean pressContains(String needle) {
        List<AccessibilityWindowInfo> wins = getWindows();
        if (wins == null) return false;
        for (int w = wins.size() - 1; w >= 0; w--) {
            AccessibilityNodeInfo root = wins.get(w).getRoot();
            if (root == null) continue;
            AccessibilityNodeInfo hit = findContains(root, needle.toLowerCase(), 0);
            if (hit != null) {
                AccessibilityNodeInfo n = hit;
                while (n != null && !n.isClickable()) n = n.getParent();
                if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findContains(AccessibilityNodeInfo n, String needle, int depth) {
        if (n == null || depth > 25) return null;
        CharSequence t = n.getText(), d = n.getContentDescription();
        if ((t != null && t.toString().toLowerCase().contains(needle)) || (d != null && d.toString().toLowerCase().contains(needle))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findContains(n.getChild(i), needle, depth + 1);
            if (r != null) return r;
        }
        return null;
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
