package app.pocketdesk;

import android.webkit.CookieManager;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Sends JPEG frames to PocketDesk over ONE long-lived chunked POST (/phone/stream), each as [4-byte length][JPEG].
 * There is no per-frame round trip, so a slow link only adds delay. Only the newest frame is kept while the link is busy;
 * quality adapts to how often frames have to be dropped. It reconnects by itself and only stops when told to.
 */
public class FrameUploader {
    private final String base;
    private final Object lock = new Object();
    private byte[] pending;
    private volatile boolean alive = true;
    private volatile int quality = 65;
    private volatile float scale = 1f;
    private int goodRun;
    private final Thread thread;

    public FrameUploader(String base) {
        this.base = base;
        thread = new Thread(this::run, "pd-upload");
        thread.setDaemon(true);
        thread.start();
    }

    public int quality() {
        return quality;
    }

    /** Picture size factor (1 = full, down to 0.4): shrinks when frames keep being dropped, grows back when the link keeps up. */
    public float scale() {
        return scale;
    }

    /** Hand over the newest frame. If the previous one is still waiting, it is dropped and quality goes down a notch. */
    public void offer(byte[] jpeg) {
        synchronized (lock) {
            if (pending != null) {                      // the link could not take the last frame: send less data
                if (quality > 40) quality -= 4;
                else scale = Math.max(0.4f, scale * 0.88f);
                goodRun = 0;
            } else if (++goodRun >= 15) {              // a run of frames went out in time: win some back
                if (scale < 1f) scale = Math.min(1f, scale * 1.08f);
                else if (quality < 80) quality += 2;
                goodRun = 0;
            }
            pending = jpeg;
            lock.notifyAll();
        }
    }

    private void run() {
        int backoff = 1000;
        while (alive) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + "/phone/stream").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(6000);
                c.setReadTimeout(0);
                c.setChunkedStreamingMode(0);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                String ck = CookieManager.getInstance().getCookie(base);
                if (ck != null) c.setRequestProperty("Cookie", ck);
                OutputStream os = c.getOutputStream();
                backoff = 1000;
                while (alive) {
                    byte[] f;
                    synchronized (lock) {
                        while (pending == null && alive) lock.wait(3000);
                        f = pending;
                        pending = null;
                    }
                    if (f == null) continue;
                    byte[] head = {(byte) (f.length >>> 24), (byte) (f.length >>> 16), (byte) (f.length >>> 8), (byte) f.length};
                    os.write(head);
                    os.write(f);
                    os.flush();
                }
            } catch (Exception e) {
                if (!alive) break;
                try { Thread.sleep(backoff); } catch (InterruptedException ignored) { }
                backoff = Math.min(backoff * 2, 5000);
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    /** Stop sending. With tellPc, also tells the PocketDesk server that sharing ended. */
    public void stop(boolean tellPc) {
        alive = false;
        synchronized (lock) { lock.notifyAll(); }
        thread.interrupt();
        if (!tellPc) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base + "/phone/stop").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                c.setConnectTimeout(2000);
                c.setReadTimeout(2000);
                String ck = CookieManager.getInstance().getCookie(base);
                if (ck != null) c.setRequestProperty("Cookie", ck);
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ignored) { }
        }).start();
    }
}
