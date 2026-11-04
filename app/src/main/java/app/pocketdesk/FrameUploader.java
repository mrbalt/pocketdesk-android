package app.pocketdesk;

import android.webkit.CookieManager;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
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
            try {
                URL u = new URL(base + "/phone/stream");
                if (u.getProtocol().equals("http")) runSocket(u); else runHttps();
                backoff = 1000;
            } catch (Exception e) {
                if (!alive) break;
                try { Thread.sleep(backoff); } catch (InterruptedException ignored) { }
                backoff = Math.min(backoff * 2, 5000);
            }
        }
    }

    private byte[] nextFrame() throws InterruptedException {
        synchronized (lock) {
            while (pending == null && alive) lock.wait(3000);
            byte[] f = pending;
            pending = null;
            return f;
        }
    }

    /**
     * Plain-http upload over our own socket with a SMALL send buffer. With the system's big default buffer, hundreds of
     * milliseconds of old frames pile up in it and the picture arrives late; a small buffer makes the writer wait instead,
     * so stale frames are dropped here and the newest one is always next.
     */
    private void runSocket(URL u) throws Exception {
        int port = u.getPort() > 0 ? u.getPort() : 80;
        final Socket sk = new Socket();
        try {
            sk.setTcpNoDelay(true);
            sk.setSendBufferSize(32 * 1024);
            sk.connect(new InetSocketAddress(u.getHost(), port), 6000);
            OutputStream os = sk.getOutputStream();
            String ck = CookieManager.getInstance().getCookie(base);
            String req = "POST /phone/stream HTTP/1.1\r\nHost: " + u.getHost() + ":" + port + "\r\nContent-Type: application/octet-stream\r\n"
                    + "Transfer-Encoding: chunked\r\n" + (ck != null ? "Cookie: " + ck + "\r\n" : "") + "\r\n";
            os.write(req.getBytes("US-ASCII"));
            os.flush();
            Thread drain = new Thread(() -> {          // read (and ignore) the reply; if the server hangs up, wake the writer
                try { byte[] b = new byte[512]; while (sk.getInputStream().read(b) >= 0) { } } catch (Exception ignored) { }
                try { sk.close(); } catch (Exception ignored) { }
            }, "pd-upload-in");
            drain.setDaemon(true);
            drain.start();
            while (alive) {
                byte[] f = nextFrame();
                if (f == null) continue;
                byte[] pre = (Integer.toHexString(f.length + 4) + "\r\n").getBytes("US-ASCII");
                byte[] head = {(byte) (f.length >>> 24), (byte) (f.length >>> 16), (byte) (f.length >>> 8), (byte) f.length};
                byte[] chunk = new byte[pre.length + 4 + f.length + 2];
                System.arraycopy(pre, 0, chunk, 0, pre.length);
                System.arraycopy(head, 0, chunk, pre.length, 4);
                System.arraycopy(f, 0, chunk, pre.length + 4, f.length);
                chunk[chunk.length - 2] = '\r';
                chunk[chunk.length - 1] = '\n';
                os.write(chunk);
                os.flush();
            }
        } finally {
            try { sk.close(); } catch (Exception ignored) { }
        }
    }

    private void runHttps() throws Exception {
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
            while (alive) {
                byte[] f = nextFrame();
                if (f == null) continue;
                byte[] head = {(byte) (f.length >>> 24), (byte) (f.length >>> 16), (byte) (f.length >>> 8), (byte) f.length};
                os.write(head);
                os.write(f);
                os.flush();
            }
        } finally {
            if (c != null) c.disconnect();
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
