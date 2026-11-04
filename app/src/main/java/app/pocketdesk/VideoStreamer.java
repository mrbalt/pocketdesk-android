package app.pocketdesk;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * Hardware H.264 encoder fed straight from the screen capture (no copying, no JPEG). The encoded frames go to a VideoUploader.
 * The bitrate follows the link: it drops when the uploader has to throw frames away and creeps back up while all is well.
 */
final class VideoStreamer {
    private final MediaCodec enc;
    private final Surface surface;
    private final VideoUploader up;
    private final Thread drain;
    private volatile boolean alive = true;
    private byte[] config;
    private int kbps;
    private final int maxKbps;
    private long lastDrop, lastRaise, lastKeyOut, lastKeyReq, lastLog;
    private final String base;

    VideoStreamer(String base, int w, int h, int fps, int kbps) throws Exception {
        this.base = base;
        this.kbps = kbps;
        this.maxKbps = kbps;
        MediaCodec c = MediaCodec.createEncoderByType("video/avc");
        try {
            c.configure(format(w, h, fps, kbps, true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {                       // some encoders dislike the optional settings: try again with only the basics
            c.release();
            c = MediaCodec.createEncoderByType("video/avc");
            c.configure(format(w, h, fps, kbps, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }
        enc = c;
        surface = enc.createInputSurface();
        enc.start();
        up = new VideoUploader(base, w, h, new VideoUploader.Events() {
            @Override public void keyframeWanted() { requestKey(); }
            @Override public void overflowed() { VideoStreamer.this.overflowed(); }
        });
        drain = new Thread(this::drainLoop, "pd-video-enc");
        drain.setDaemon(true);
        drain.start();
    }

    private static MediaFormat format(int w, int h, int fps, int kbps, boolean extras) {
        MediaFormat f = MediaFormat.createVideoFormat("video/avc", w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (extras) {
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            f.setInteger("repeat-previous-frame-after", 500000);     // a still screen still sends a frame twice a second
            f.setInteger("low-latency", 1);
            f.setInteger("priority", 0);                              // real time
            f.setInteger("max-bframes", 0);
            f.setInteger("max-fps-to-encoder", fps);
        }
        return f;
    }

    Surface surface() {
        return surface;
    }

    private void overflowed() {
        requestKey();
        long now = SystemClock.uptimeMillis();
        if (now - lastDrop > 800) {                    // the link cannot take this much: ask for less
            lastDrop = now;
            kbps = Math.max(1500, kbps * 4 / 5);
            setBitrate();
            if (now - lastLog > 2000) {
                lastLog = now;
                PcLog.send(base, "video: the link could not keep up, dropped the backlog; bitrate now " + kbps + " kbps");
            }
        }
    }

    private void requestKey() {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            enc.setParameters(b);
        } catch (Exception ignored) { }
    }

    private void setBitrate() {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000);
            enc.setParameters(b);
        } catch (Exception ignored) { }
    }

    private static boolean startsWithSps(byte[] d) {
        int i = d.length > 4 && d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 1 ? 4 : (d.length > 3 && d[0] == 0 && d[1] == 0 && d[2] == 1 ? 3 : -1);
        return i >= 0 && i < d.length && (d[i] & 0x1f) == 7;
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        lastRaise = SystemClock.uptimeMillis();
        lastKeyOut = lastKeyReq = lastRaise;
        try {
            while (alive) {
                long t = SystemClock.uptimeMillis();
                // A full picture at least once a second, however quiet the screen: whoever missed frames (a stalled link, a
                // window that fell behind) can rejoin within a second instead of waiting for the encoder's own, much slower, rhythm.
                if (t - lastKeyOut > 1000 && t - lastKeyReq > 700) {
                    lastKeyReq = t;
                    requestKey();
                }
                int i = enc.dequeueOutputBuffer(info, 50000);
                if (i < 0) continue;
                try {
                    ByteBuffer b = enc.getOutputBuffer(i);
                    if (b != null && info.size > 0) {
                        byte[] data = new byte[info.size];
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        b.get(data);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            config = data;
                        } else {
                            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            if (key) lastKeyOut = SystemClock.uptimeMillis();
                            if (key && config != null && !startsWithSps(data)) {
                                byte[] withCfg = new byte[config.length + data.length];
                                System.arraycopy(config, 0, withCfg, 0, config.length);
                                System.arraycopy(data, 0, withCfg, config.length, data.length);
                                data = withCfg;
                            }
                            up.offer(data, key);
                        }
                    }
                } finally {
                    enc.releaseOutputBuffer(i, false);
                }
                long now = SystemClock.uptimeMillis();
                if (kbps < maxKbps && now - lastDrop > 6000 && now - lastRaise > 3000) {     // calm for a while: win some quality back
                    lastRaise = now;
                    kbps = Math.min(maxKbps, kbps * 11 / 10);
                    setBitrate();
                }
            }
        } catch (Exception ignored) {
            // encoder stopped
        }
    }

    void release() {
        alive = false;
        up.stop();
        try { drain.join(300); } catch (InterruptedException ignored) { }
        try { enc.stop(); } catch (Exception ignored) { }
        try { enc.release(); } catch (Exception ignored) { }
        try { surface.release(); } catch (Exception ignored) { }
    }
}
