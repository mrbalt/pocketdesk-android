package app.pocketdesk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.net.wifi.WifiManager;
import android.util.DisplayMetrics;
import android.webkit.CookieManager;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;

/**
 * Shares this phone's screen with the PC. Captures with MediaProjection, turns each frame into a JPEG and
 * POSTs it to PocketDesk's /phone/frame (using the same login cookie as the WebView).
 * The PC shows it in PocketDesk's "Phone screen" view.
 */
public class ScreenShareService extends Service {
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_URL = "url";
    public static final String ACTION_STOP = "app.pocketdesk.STOP_SHARE";
    public static volatile boolean running = false;

    private static final String CHANNEL = "share";
    private static final int NOTE_ID = 7;
    private static final int LONG_EDGE = 1280;      // frames are scaled so the longer side is at most this
    private static final long MIN_GAP_MS = 66;      // ~15 fps ceiling; the network decides the real rate

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private String base;
    private FrameUploader uploader;
    private long lastSent;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        base = intent.getStringExtra(EXTRA_URL);
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (base == null || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        try {   // keep the CPU and Wi-Fi alive while the screen is locked
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketdesk:share");
            wake.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pocketdesk:share");
            wifi.acquire();
        } catch (Exception ignored) {}
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) throw new IllegalStateException("no projection");
            thread = new HandlerThread("pd-share");
            thread.start();
            handler = new Handler(thread.getLooper());
            // Android 14 requires a callback to be registered before creating the display
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopSelf();
                }
            }, handler);
            uploader = new FrameUploader(base);
            startCapture();
            running = true;
        } catch (Exception e) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW));
        }
        Intent stop = new Intent(this, ScreenShareService.class).setAction(ACTION_STOP);
        int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stop, pf);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("Sharing your phone screen to PocketDesk")
                .setContentText("Tap Stop to end")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build());
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTE_ID, n);
        }
    }

    private void startCapture() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        float scale = Math.min(1f, (float) LONG_EDGE / Math.max(m.widthPixels, m.heightPixels));
        final int w = Math.max(2, Math.round(m.widthPixels * scale) & ~1);
        final int h = Math.max(2, Math.round(m.heightPixels * scale) & ~1);
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("pocketdesk-share", w, h, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        reader.setOnImageAvailableListener(r -> onFrame(r, w, h), handler);
    }

    private void onFrame(ImageReader r, int w, int h) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastSent < MIN_GAP_MS) return;                 // ~15 fps ceiling
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int rowPad = p.getRowStride() - p.getPixelStride() * w;
            Bitmap bmp = Bitmap.createBitmap(w + rowPad / p.getPixelStride(), h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            Bitmap crop = rowPad == 0 ? bmp : Bitmap.createBitmap(bmp, 0, 0, w, h);
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            crop.compress(Bitmap.CompressFormat.JPEG, uploader == null ? 65 : uploader.quality(), out);
            if (crop != bmp) crop.recycle();
            bmp.recycle();
            lastSent = now;
            if (uploader != null) uploader.offer(out.toByteArray());
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.setOnImageAvailableListener(null, null); } catch (Exception ignored) {}
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        if (thread != null) thread.quitSafely();
        if (uploader != null) uploader.stop(true);   // also tells the PC viewer that sharing ended
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
