package app.pocketdesk;

import android.webkit.CookieManager;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;

/** Sends one short diagnostic line to the PC (it appears in `journalctl --user -u pocketdesk`). */
final class PcLog {
    private PcLog() { }

    static void send(final String base, final String msg) {
        if (base == null) return;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                JSONObject o = new JSONObject();
                o.put("m", msg);
                c = (HttpURLConnection) new URL(base + "/phone/log").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(3000);
                c.setReadTimeout(3000);
                c.setRequestProperty("Content-Type", "application/json");
                String ck = CookieManager.getInstance().getCookie(base);
                if (ck != null) c.setRequestProperty("Cookie", ck);
                c.getOutputStream().write(o.toString().getBytes("UTF-8"));
                c.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }, "pd-log").start();
    }
}
