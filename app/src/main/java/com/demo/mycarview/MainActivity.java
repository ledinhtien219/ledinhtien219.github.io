package com.demo.mycarview;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Rational;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String HOME = "https://m.youtube.com";
    private static final String PREFS = "carview_settings";

    private FrameLayout browserHost;
    private WebView web;
    private EditText address;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        syncBackgroundService();

        boolean preview = getIntent().getBooleanExtra("car_preview", false);
        if (preview) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        build(preview);

        String startUrl = HOME;
        if (prefs.getBoolean("auto_resume", true)) {
            String saved = prefs.getString("last_url", HOME);
            if (saved != null && !saved.isEmpty()) startUrl = saved;
        }
        web.loadUrl(startUrl);
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs != null) syncBackgroundService();
    }

    private void syncBackgroundService() {
        Intent svc = new Intent(this, BrowserKeepAliveService.class);
        if (prefs.getBoolean("background_playback", true)) {
            startForegroundService(svc);
        } else {
            stopService(svc);
        }
    }

    private void build(boolean preview) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, preview ? 8 : 16), Ui.dp(this, 8), Ui.dp(this, preview ? 8 : 16), Ui.dp(this, 8));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        TextView title = Ui.text(this, preview ? "CarView AA  •  CAR PREVIEW" : "CarView AA", preview ? 17 : 28, Ui.TEXT, true);
        page.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, preview ? 36 : 64)));

        if (preview && prefs.getBoolean("bubble_enabled", false)) {
            page.addView(buildVietMapWidget(), new LinearLayout.LayoutParams(-1, Ui.dp(this, 112)));
        }

        if (!preview) {
            LinearLayout bar = new LinearLayout(this);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            page.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 72)));

            address = new EditText(this);
            address.setSingleLine(true);
            address.setText(HOME);
            address.setTextColor(Ui.TEXT);
            address.setHintTextColor(Ui.MUTED);
            address.setTextSize(17);
            address.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
            address.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 22, this));
            address.setImeOptions(EditorInfo.IME_ACTION_GO);
            bar.addView(address, new LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1));

            TextView go = button("Đi", 18, Ui.ACCENT, 0xFF071321);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 58));
            gp.setMargins(Ui.dp(this, 10), 0, 0, 0);
            bar.addView(go, gp);
            go.setOnClickListener(v -> loadInput());
            address.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    loadInput();
                    return true;
                }
                return false;
            });
        }

        browserHost = new FrameLayout(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, 0, 1);
        if (preview) bp.setMargins(0, Ui.dp(this, 7), 0, 0);
        page.addView(browserHost, bp);
        web = new WebView(this);
        browserHost.addView(web, new FrameLayout.LayoutParams(-1, -1));
        configureWebView();

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, Ui.dp(this, preview ? 54 : 78));
        tp.setMargins(0, Ui.dp(this, 7), 0, 0);
        page.addView(tools, tp);

        addTool(tools, "←", v -> { if (web.canGoBack()) web.goBack(); });
        addTool(tools, "→", v -> { if (web.canGoForward()) web.goForward(); });
        addTool(tools, "⌂", v -> web.loadUrl(HOME));
        addTool(tools, "↻", v -> web.reload());
        addTool(tools, "⚙", v -> startActivity(new Intent(this, SettingsActivity.class)));

        setContentView(root);
    }

    private View buildVietMapWidget() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 8));
        card.setBackground(Ui.rounded(0xFF121A25, 0xFF253548, 18, this));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView vmIcon = Ui.text(this, "●", 15, 0xFFFFC928, true);
        TextView vmTitle = Ui.text(this, "  VIETMAP LIVE", 14, Ui.TEXT, true);
        header.addView(vmIcon, new LinearLayout.LayoutParams(Ui.dp(this, 20), -1));
        header.addView(vmTitle, new LinearLayout.LayoutParams(0, -1, 1));
        card.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 24)));

        LinearLayout data = new LinearLayout(this);
        data.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(data, new LinearLayout.LayoutParams(-1, 0, 1));

        String limit = prefs.getString("limit", "80");
        String speed = prefs.getString("speed", "0");
        data.addView(speedTile(limit, "GIỚI HẠN", true), new LinearLayout.LayoutParams(0, -1, 1));
        data.addView(speedTile(speed, "km/h", false), new LinearLayout.LayoutParams(0, -1, 1));
        if (prefs.getBoolean("bubble_camera_zone", true)) {
            data.addView(speedTile("📷", "camera", true), new LinearLayout.LayoutParams(0, -1, 1));
            data.addView(speedTile("50", "khu dân cư", true), new LinearLayout.LayoutParams(0, -1, 1));
        }
        return card;
    }

    private View speedTile(String main, String sub, boolean warning) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3));

        TextView primary = Ui.text(this, main, main.length() > 2 ? 20 : 25, Ui.TEXT, true);
        primary.setGravity(Gravity.CENTER);
        primary.setBackground(Ui.rounded(0xFF090D12, warning ? 0xFFE43A35 : 0xFF4EA7FF, 30, this));
        box.addView(primary, new LinearLayout.LayoutParams(Ui.dp(this, 62), Ui.dp(this, 52)));

        TextView secondary = Ui.text(this, sub, 12, Ui.MUTED, false);
        secondary.setGravity(Gravity.CENTER);
        box.addView(secondary, new LinearLayout.LayoutParams(-1, Ui.dp(this, 22)));
        return box;
    }

    private TextView button(String label, float sp, int bg, int fg) {
        TextView t = Ui.text(this, label, sp, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.rounded(bg, bg, 20, this));
        Ui.press(t);
        return t;
    }

    private void addTool(LinearLayout tools, String label, View.OnClickListener click) {
        TextView b = Ui.text(this, label, 30, Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 18, this));
        b.setOnClickListener(click);
        Ui.press(b);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        p.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        tools.addView(b, p);
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                return !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (prefs.getBoolean("ad_filter", true)) {
                    WebResourceResponse blocked = AdBlocker.intercept(request.getUrl().toString());
                    if (blocked != null) return blocked;
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (address != null) address.setText(url);
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    prefs.edit().putString("last_url", url).apply();
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                browserHost.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }

            @Override public void onHideCustomView() {
                hideCustomView();
            }
        });
    }

    private void hideCustomView() {
        if (customView == null) return;
        browserHost.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
    }

    private void loadInput() {
        if (address == null) return;
        String u = address.getText().toString().trim();
        if (u.isEmpty()) return;
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://www.google.com/search?q=" + Uri.encode(u);
        }
        web.loadUrl(u);
    }

    @Override protected void onUserLeaveHint() {
        if (customView != null && !isInPictureInPictureMode()) {
            try {
                PictureInPictureParams params = new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9))
                        .build();
                enterPictureInPictureMode(params);
            } catch (Throwable ignored) {
            }
        }
        super.onUserLeaveHint();
    }

    @Override public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
        } else if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
