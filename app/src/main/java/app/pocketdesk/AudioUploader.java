package app.pocketdesk;

import android.os.SystemClock;
import android.webkit.CookieManager;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Streams raw PCM sound to PocketDesk over one long chunked POST (/phone/audio). If the link cannot keep up,
 * the oldest sound is dropped (about one second is buffered at most), so the sound never falls far behind.
 */
public class AudioUploader {
    private final String base;
    private final int rate, channels;
    private final LinkedBlockingDeque<byte[]> q = new LinkedBlockingDeque<>(40);
    private volatile boolean alive = true;
    private final Thread thread;
    private long lastSlowLog;

    public AudioUploader(String base, int rate, int channels) {
        this.base = base;
        this.rate = rate;
        this.channels = channels;
        thread = new Thread(this::run, "pd-audio");
        thread.setDaemon(true);
        thread.start();
    }

    public void offer(byte[] pcm) {
        while (!q.offerLast(pcm)) q.pollFirst();
    }

    private void run() {
        int backoff = 1000;
        while (alive) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + "/phone/audio?rate=" + rate + "&ch=" + channels).openConnection();
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
                    byte[] b = q.poll(2, TimeUnit.SECONDS);
                    if (b == null) continue;
                    long t = SystemClock.uptimeMillis();
                    os.write(b);
                    os.flush();
                    long took = SystemClock.uptimeMillis() - t;
                    if (took > 400 && t - lastSlowLog > 3000) {
                        lastSlowLog = t;
                        PcLog.send(base, "sound upload slow: one piece took " + took + " ms to send");
                    }
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

    public void stop() {
        alive = false;
        thread.interrupt();
    }
}
