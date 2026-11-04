package app.pocketdesk;

import android.webkit.CookieManager;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayDeque;

/**
 * Sends H.264 frames to PocketDesk over one long chunked POST (/phone/video), each as [4-byte length][1 flag byte][Annex-B data]
 * (flag 1 = keyframe). Frames depend on each other, so they cannot be dropped one at a time: when the link falls behind, the whole
 * backlog is thrown away, the encoder is asked for a fresh keyframe, and nothing but a keyframe is sent until it arrives.
 * Plain http goes over our own socket with a small send buffer, so old frames cannot pile up inside the system.
 */
final class VideoUploader {
    private static final int MAX_QUEUE = 10;

    private final String base;
    private final int w, h;
    interface Events { void keyframeWanted(); void overflowed(); }

    private final Events events;
    private final Object lock = new Object();
    private final ArrayDeque<byte[]> q = new ArrayDeque<>();
    private boolean dropping;
    private volatile boolean alive = true;
    private final Thread thread;

    VideoUploader(String base, int w, int h, Events events) {
        this.base = base;
        this.w = w;
        this.h = h;
        this.events = events;
        thread = new Thread(this::run, "pd-video-up");
        thread.setDaemon(true);
        thread.start();
    }

    void offer(byte[] data, boolean key) {
        boolean overflowed = false;
        synchronized (lock) {
            if (dropping && !key) return;
            if (q.size() >= MAX_QUEUE) {
                q.clear();
                overflowed = true;
                dropping = !key;
            }
            if (key) dropping = false;
            if (!dropping) {
                byte[] f = new byte[5 + data.length];
                int len = data.length + 1;
                f[0] = (byte) (len >>> 24); f[1] = (byte) (len >>> 16); f[2] = (byte) (len >>> 8); f[3] = (byte) len;
                f[4] = (byte) (key ? 1 : 0);
                System.arraycopy(data, 0, f, 5, data.length);
                q.addLast(f);
                lock.notifyAll();
            }
        }
        if (overflowed && events != null) events.overflowed();
    }

    private byte[] next() throws InterruptedException {
        synchronized (lock) {
            while (q.isEmpty() && alive) lock.wait(3000);
            return q.pollFirst();
        }
    }

    private void run() {
        int backoff = 1000;
        while (alive) {
            try {
                URL u = new URL(base + "/phone/video?w=" + w + "&h=" + h);
                synchronized (lock) { q.clear(); dropping = true; }       // a new connection starts at the next keyframe
                if (events != null) events.keyframeWanted();
                if (u.getProtocol().equals("http")) runSocket(u); else runHttps(u);
                backoff = 1000;
            } catch (Exception e) {
                if (!alive) break;
                try { Thread.sleep(backoff); } catch (InterruptedException ignored) { }
                backoff = Math.min(backoff * 2, 5000);
            }
        }
    }

    private void runSocket(URL u) throws Exception {
        int port = u.getPort() > 0 ? u.getPort() : 80;
        final Socket sk = new Socket();
        try {
            sk.setTcpNoDelay(true);
            sk.setSendBufferSize(64 * 1024);
            sk.connect(new InetSocketAddress(u.getHost(), port), 6000);
            OutputStream os = sk.getOutputStream();
            String ck = CookieManager.getInstance().getCookie(base);
            String req = "POST " + u.getFile() + " HTTP/1.1\r\nHost: " + u.getHost() + ":" + port + "\r\nContent-Type: application/octet-stream\r\n"
                    + "Transfer-Encoding: chunked\r\n" + (ck != null ? "Cookie: " + ck + "\r\n" : "") + "\r\n";
            os.write(req.getBytes("US-ASCII"));
            os.flush();
            Thread in = new Thread(() -> {
                try { byte[] b = new byte[512]; while (sk.getInputStream().read(b) >= 0) { } } catch (Exception ignored) { }
                try { sk.close(); } catch (Exception ignored) { }
            }, "pd-video-in");
            in.setDaemon(true);
            in.start();
            while (alive) {
                byte[] f = next();
                if (f == null) continue;
                byte[] pre = (Integer.toHexString(f.length) + "\r\n").getBytes("US-ASCII");
                byte[] chunk = new byte[pre.length + f.length + 2];
                System.arraycopy(pre, 0, chunk, 0, pre.length);
                System.arraycopy(f, 0, chunk, pre.length, f.length);
                chunk[chunk.length - 2] = '\r';
                chunk[chunk.length - 1] = '\n';
                os.write(chunk);
                os.flush();
            }
        } finally {
            try { sk.close(); } catch (Exception ignored) { }
        }
    }

    private void runHttps(URL u) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) u.openConnection();
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
                byte[] f = next();
                if (f == null) continue;
                os.write(f);
                os.flush();
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    void stop() {
        alive = false;
        synchronized (lock) { lock.notifyAll(); }
        thread.interrupt();
    }
}
