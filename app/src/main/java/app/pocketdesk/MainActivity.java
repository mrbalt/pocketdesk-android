package app.pocketdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * PocketDesk for Android: a full-screen WebView around the PocketDesk page served by your PC.
 * It remembers the PC's address, keeps the screen on, hides the system bars and keeps your login cookie.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "pocketdesk";
    private static final String KEY_URL = "url";
    private static final int BG = Color.parseColor("#0E1116");
    private static final int DIM = Color.parseColor("#8B94A3");
    private static final int ACCENT = Color.parseColor("#5B9DFF");

    private FrameLayout root;
    private WebView web;
    private LinearLayout errorView;
    private TextView errorText;
    private String url;
    private static final int REQ_SHARE = 41, REQ_NOTIF = 42;
    public static final String EXTRA_AUTOSHARE = "autoshare";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        url = prefs().getString(KEY_URL, null);
        if (url == null) {
            showSetup(null);
        } else {
            showWeb();
            if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTOSHARE, false) && !ScreenShareService.running) {
                getIntent().removeExtra(EXTRA_AUTOSHARE);
                root.post(this::startShare);      // opened by the PC's "Start sharing": go straight to the capture prompt
            }
        }
    }

    // ------------------------------------------------------------------ setup screen

    private void showSetup(String error) {
        destroyWeb();
        root.removeAllViews();
        immersive(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setBackgroundColor(BG);
        int pad = dp(24);
        col.setPadding(pad, pad, pad, pad);

        TextView title = label("PocketDesk", 28, Color.WHITE, true);
        TextView hint = label("Enter your PC's address: its Tailscale IP (100.x.x.x) or its home-network IP. "
                + "The port 8765 is added for you.\n\nFor a 100.x address, the Tailscale app must be switched on.", 14, DIM, false);
        hint.setPadding(0, dp(8), 0, dp(20));

        final EditText input = new EditText(this);
        input.setHint("100.64.0.5");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#5A6372"));
        if (url != null) {
            input.setText(url);
        }

        final TextView err = label(error == null ? "" : error, 14, Color.parseColor("#FF6B6B"), false);
        err.setPadding(0, dp(10), 0, 0);

        Button go = new Button(this);
        go.setText("Connect");
        go.setAllCaps(false);
        go.setBackgroundColor(ACCENT);
        go.setTextColor(Color.parseColor("#06101F"));

        View.OnClickListener connect = v -> {
            String normalized = normalize(input.getText().toString());
            if (normalized == null) {
                err.setText("That doesn't look like an address. Example: 100.64.0.5");
                return;
            }
            url = normalized;
            prefs().edit().putString(KEY_URL, url).apply();
            showWeb();
        };
        go.setOnClickListener(connect);
        input.setOnEditorActionListener((v, actionId, event) -> {
            connect.onClick(v);
            return true;
        });

        col.addView(title);
        col.addView(hint);
        col.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        col.addView(err);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** "100.64.0.5" -> "http://100.64.0.5:8765"; anything with a scheme or port is kept as typed. */
    static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty() || s.contains(" ")) {
            return null;
        }
        if (!s.contains("://")) {
            s = "http://" + s;
        }
        Uri u = Uri.parse(s);
        String host = u.getHost();
        if (host == null || host.isEmpty()) {
            return null;
        }
        if (u.getPort() == -1 && "http".equals(u.getScheme())) {
            s = "http://" + host + ":8765" + (u.getPath() == null ? "" : u.getPath());
        }
        return s;
    }

    // ------------------------------------------------------------------ the PocketDesk page

    private void showWeb() {
        destroyWeb();
        root.removeAllViews();

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // lets the Audio button start sound
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUserAgentString(s.getUserAgentString() + " PocketDeskApp/0.2");

        CookieManager.getInstance().setAcceptCookie(true); // keeps you logged in

        // the default WebChromeClient also gives you the normal dialogs (the Paste button can use one)
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // stay on the PC's page; ignore anything that tries to navigate elsewhere
                return !sameHost(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String pageUrl, android.graphics.Bitmap favicon) {
                hideError();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError(String.valueOf(error.getDescription()));
                }
            }

            @Override
            public void onPageFinished(WebView view, String pageUrl) {
                // the app is already full screen, so the page's own Fullscreen button is pointless here
                view.evaluateJavascript(
                        "var b=document.getElementById('fullBtn');if(b){b.style.display='none';}", null);
            }
        });

        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        immersive(true);
        web.loadUrl(url);
        web.requestFocus();
    }

    private boolean sameHost(Uri u) {
        String a = u == null ? null : u.getHost();
        String b = Uri.parse(url).getHost();
        return a != null && a.equals(b);
    }

    private void destroyWeb() {
        if (web != null) {
            web.destroy();
            web = null;
        }
    }

    private void showError(String detail) {
        if (errorView == null) {
            errorView = new LinearLayout(this);
            errorView.setOrientation(LinearLayout.VERTICAL);
            errorView.setGravity(Gravity.CENTER_VERTICAL);
            errorView.setBackgroundColor(BG);
            int pad = dp(24);
            errorView.setPadding(pad, pad, pad, pad);
            errorView.setClickable(true); // swallow touches so they don't reach the page underneath

            errorView.addView(label("Can't reach your PC", 22, Color.WHITE, true));
            errorText = label("", 14, DIM, false);
            errorText.setPadding(0, dp(10), 0, dp(20));
            errorView.addView(errorText);

            Button retry = new Button(this);
            retry.setText("Try again");
            retry.setAllCaps(false);
            retry.setBackgroundColor(ACCENT);
            retry.setTextColor(Color.parseColor("#06101F"));
            retry.setOnClickListener(v -> {
                hideError();
                if (web != null) {
                    web.loadUrl(url);
                }
            });
            Button change = new Button(this);
            change.setText("Change address");
            change.setAllCaps(false);
            change.setOnClickListener(v -> showSetup(null));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            lp.bottomMargin = dp(10);
            errorView.addView(retry, lp);
            errorView.addView(change, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        }
        errorText.setText(detail + "\n\nCheck that Tailscale is on (phone and PC) and that PocketDesk is running on the PC.\n\n" + url);
        if (errorView.getParent() == null) {
            root.addView(errorView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        immersive(false);
    }

    private void hideError() {
        if (errorView != null && errorView.getParent() != null) {
            root.removeView(errorView);
        }
        if (web != null) {
            immersive(true);
        }
    }

    // ------------------------------------------------------------------ plumbing

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("PocketDesk")
                .setItems(new CharSequence[]{"Keep using", "Reload", ScreenShareService.running ? "Stop sharing phone screen" : "Share phone screen", PocketAccessibilityService.connected ? "Phone control: ON (settings)" : "Turn on phone control (Accessibility)", "Change address", "Exit"}, (dialog, which) -> {
                    if (which == 1 && web != null) {
                        web.loadUrl(url);
                    } else if (which == 2) {
                        if (ScreenShareService.running) stopService(new Intent(this, ScreenShareService.class));
                        else startShare();
                    } else if (which == 3) {
                        startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } else if (which == 4) {
                        showSetup(null);
                    } else if (which == 5) {
                        finish();
                    }
                })
                .show();
    }

    private boolean permsAsked;

    private void startShare() {
        if (!permsAsked) {          // ask once for notifications (Android 13+) and for capturing sound (Android 10+), then carry on either way
            java.util.ArrayList<String> need = new java.util.ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add("android.permission.POST_NOTIFICATIONS");
            if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission("android.permission.RECORD_AUDIO") != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add("android.permission.RECORD_AUDIO");
            if (!need.isEmpty()) {
                permsAsked = true;
                requestPermissions(need.toArray(new String[0]), REQ_NOTIF);
                return;
            }
        }
        try {   // ask once to be exempt from battery limits so sharing survives a locked screen
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) {}
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SHARE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_AUTOSHARE, false) && !ScreenShareService.running && url != null && web != null) {
            intent.removeExtra(EXTRA_AUTOSHARE);
            startShare();     // the PocketDesk app on the PC asked for this
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_NOTIF) startShare();   // the notification is optional for the service to run; continue either way
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_SHARE) return;
        if (result != RESULT_OK || data == null || url == null) {
            Toast.makeText(this, "Screen sharing cancelled", Toast.LENGTH_SHORT).show();
            return;
        }
        String base = url.replaceAll("/+$", "");
        Intent svc = new Intent(this, ScreenShareService.class)
                .putExtra(ScreenShareService.EXTRA_CODE, result)
                .putExtra(ScreenShareService.EXTRA_DATA, data)
                .putExtra(ScreenShareService.EXTRA_URL, base);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
        Toast.makeText(this, "Sharing to PocketDesk. Open Phone screen on the PC.", Toast.LENGTH_LONG).show();
        // Get out of the way: the PC should see (and tap) your real screen, not the PocketDesk app itself.
        root.postDelayed(() -> moveTaskToBack(true), 600);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && web != null && (errorView == null || errorView.getParent() == null)) {
            immersive(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        // Keep the page (and the PC's audio) running while the phone is shared or controlled from the PC:
        // the app sits in the background then, and pausing the WebView would silence it.
        boolean keepAlive = ScreenShareService.running || PocketAccessibilityService.sharing || PocketAccessibilityService.connected;
        if (web != null && !keepAlive) {
            web.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        destroyWeb();
        super.onDestroy();
    }

    private void immersive(boolean on) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) {
                return;
            }
            if (on) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(on
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }
}
