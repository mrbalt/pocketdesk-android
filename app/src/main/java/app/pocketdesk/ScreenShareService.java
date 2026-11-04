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
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.view.Surface;
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
    private static final int LONG_EDGE = 2560;      // never capture bigger than this
    private static volatile ScreenShareService instance;

    /** Capture right at the wanted size: no big picture to shrink for every frame. */
    private static int captureEdge() { return Math.max(320, Math.min(LONG_EDGE, ShareSettings.maxEdge)); }

    /** Called when the PC changes the picture size or the video type, so the capture follows. */
    public static void sizeChanged() {
        ScreenShareService s = instance;
        if (s != null && s.handler != null && s.display != null) s.handler.post(s::applyPipeline);
    }

    private VideoStreamer video;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private String base;
    private FrameUploader uploader;
    private AudioUploader audioUp;
    private AudioRecord audioRec;
    private volatile boolean audioLoop;
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
            wifi = wm.createWifiLock((Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF), "pocketdesk:share");
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
            applyPipeline();
            startAudio();
            running = true;
            instance = this;
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

    /**
     * (Re)builds how the screen reaches the PC, at the size the PC asked for: either a hardware H.264 encoder fed straight from
     * the capture, or the picture-by-picture JPEG path. The capture itself keeps running; only where it draws is switched.
     */
    private void applyPipeline() {
        try {
            DisplayMetrics m = RealScreen.get(this);
            float scale = Math.min(1f, (float) captureEdge() / Math.max(m.widthPixels, m.heightPixels));
            final int w = Math.max(2, Math.round(m.widthPixels * scale) & ~1);
            final int h = Math.max(2, Math.round(m.heightPixels * scale) & ~1);
            ImageReader oldReader = reader;
            VideoStreamer oldVideo = video;
            ImageReader newReader = null;
            VideoStreamer newVideo = null;
            Surface target = null;
            if ("h264".equals(ShareSettings.codec)) {
                try {
                    int fps = Math.max(5, Math.min(60, ShareSettings.maxFps));
                    int kbps = (int) Math.max(2500, Math.min(25000, w * (long) h * fps * 0.14 / 1000));
                    newVideo = new VideoStreamer(base, w, h, fps, kbps);
                    target = newVideo.surface();
                } catch (Exception e) {
                    PcLog.send(base, "hardware video unavailable (" + e + "), using pictures");
                    ShareSettings.codec = "jpeg";
                    newVideo = null;
                }
            }
            if (newVideo == null) {
                newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
                target = newReader.getSurface();
            }
            if (display == null) {
                display = projection.createVirtualDisplay("pocketdesk-share", w, h, m.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, target, null, handler);
            } else {
                display.resize(w, h, m.densityDpi);
                display.setSurface(target);
            }
            if (newReader != null) newReader.setOnImageAvailableListener(r -> onFrame(r, w, h), handler);
            reader = newReader;
            video = newVideo;
            if (oldReader != null) { oldReader.setOnImageAvailableListener(null, null); oldReader.close(); }
            if (oldVideo != null) oldVideo.release();
        } catch (Exception e) {
            PcLog.send(base, "could not set up the capture: " + e);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        if (handler != null && display != null) handler.postDelayed(this::applyPipeline, 300);   // the phone turned: follow it
    }

    /** Captures what other apps play (Android 10+, needs the microphone permission) and streams it to the PC. */
    private void startAudio() {
        if (Build.VERSION.SDK_INT < 29 || checkSelfPermission("android.permission.RECORD_AUDIO") != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            final int rate = 32000;
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            audioRec = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(min, 16384))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            audioUp = new AudioUploader(base, rate, 2);
            audioRec.startRecording();
            audioLoop = true;
            Thread t = new Thread(() -> {
                byte[] buf = new byte[rate * 2 * 2 / 40];     // 25 ms of 16-bit stereo
                long lastRead = SystemClock.uptimeMillis(), lastGapLog = 0;
                while (audioLoop) {
                    int n = audioRec.read(buf, 0, buf.length);
                    long nowRead = SystemClock.uptimeMillis();
                    if (nowRead - lastRead > 400 && nowRead - lastGapLog > 3000) {     // the capture itself went quiet (not the network)
                        lastGapLog = nowRead;
                        PcLog.send(base, "sound capture gave nothing for " + (nowRead - lastRead) + " ms");
                    }
                    lastRead = nowRead;
                    if (n > 0) {
                        byte[] out = new byte[n];
                        System.arraycopy(buf, 0, out, 0, n);
                        audioUp.offer(out);
                    } else if (n < 0) {
                        break;
                    }
                }
            }, "pd-audio-read");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            audioLoop = false;     // sound is a bonus: carry on with the picture only
        }
    }

    // Two JPEG encoders work side by side, so the phone can keep up with 30-60 frames a second instead of one at a time.
    private final java.util.concurrent.ExecutorService encoders = java.util.concurrent.Executors.newFixedThreadPool(2);
    private final java.util.concurrent.atomic.AtomicInteger inflight = new java.util.concurrent.atomic.AtomicInteger();
    private long frameSeq;
    private volatile long offeredSeq;

    private void onFrame(ImageReader r, int w, int h) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastSent < ShareSettings.minGapMs() || inflight.get() >= 2) return;   // frame-rate limit / both encoders busy
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int rowPad = p.getRowStride() - p.getPixelStride() * w;
            Bitmap bmp = Bitmap.createBitmap(w + rowPad / p.getPixelStride(), h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            Bitmap crop = rowPad == 0 ? bmp : Bitmap.createBitmap(bmp, 0, 0, w, h);
            float k = Math.min(1f, (float) ShareSettings.maxEdge / Math.max(w, h)) * (uploader == null ? 1f : uploader.scale());
            if (k < 0.98f) {                       // slow link: send a smaller picture
                Bitmap small = Bitmap.createScaledBitmap(crop, Math.max(2, Math.round(w * k)), Math.max(2, Math.round(h * k)), true);
                if (crop != bmp) crop.recycle();
                crop = small;
            }
            if (crop != bmp) bmp.recycle();
            lastSent = now;
            final Bitmap toEncode = crop;
            final long my = ++frameSeq;
            final int q = uploader == null ? 65 : uploader.quality();
            inflight.incrementAndGet();
            try {
                encoders.execute(() -> {
                    try {
                        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                        toEncode.compress(Bitmap.CompressFormat.JPEG, q, out);
                        synchronized (encoders) {
                            if (my > offeredSeq && uploader != null) { offeredSeq = my; uploader.offer(out.toByteArray()); }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        toEncode.recycle();
                        inflight.decrementAndGet();
                    }
                });
            } catch (Exception e) {
                toEncode.recycle();
                inflight.decrementAndGet();
            }
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (instance == this) instance = null;
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) {}
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.setOnImageAvailableListener(null, null); } catch (Exception ignored) {}
        encoders.shutdownNow();
        audioLoop = false;
        try { if (audioRec != null) { audioRec.stop(); audioRec.release(); } } catch (Exception ignored) {}
        if (audioUp != null) audioUp.stop();
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (video != null) video.release(); } catch (Exception ignored) {}
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
