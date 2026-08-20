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
import android.view.MotionEvent;
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

        boolean carMode = getIntent().getBooleanExtra("car_preview", false);
        if (carMode) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        build(carMode);

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

    private void build(boolean carMode) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, carMode ? 8 : 16), Ui.dp(this, 8), Ui.dp(this, carMode ? 8 : 16), Ui.dp(this, 8));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout titleArea = new LinearLayout(this);
        titleArea.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "CarView AA", carMode ? 17 : 28, Ui.TEXT, true);
        TextView subtitle = Ui.text(this, "YouTube for the road", carMode ? 11 : 14, Ui.MUTED, false);
        titleArea.addView(title, new LinearLayout.LayoutParams(-1, 0, 2));
        titleArea.addView(subtitle, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(titleArea, new LinearLayout.LayoutParams(-1, Ui.dp(this, carMode ? 42 : 68)));

        if (!carMode) {
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
        page.addView(browserHost, bp);
        web = new WebView(this);
        browserHost.addView(web, new FrameLayout.LayoutParams(-1, -1));
        configureWebView();

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, Ui.dp(this, carMode ? 54 : 78));
        tp.setMargins(0, Ui.dp(this, 7), 0, 0);
        page.addView(tools, tp);

        addTool(tools, "←", v -> { if (web.canGoBack()) web.goBack(); });
        addTool(tools, "→", v -> { if (web.canGoForward()) web.goForward(); });
        addTool(tools, "⌂", v -> web.loadUrl(HOME));
        addTool(tools, "↻", v -> web.reload());
        addTool(tools, "⚙", v -> startActivity(new Intent(this, SettingsActivity.class)));

        if (carMode && prefs.getBoolean("bubble_enabled", false)) {
            addDraggableCarBubble(root);
        }

        setContentView(root);
    }

    private void addDraggableCarBubble(FrameLayout root) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setGravity(Gravity.CENTER);
        boolean expanded = prefs.getBoolean("car_bubble_expanded", false);
        renderCarBubble(bubble, expanded);

        int compact = Ui.dp(this, 92);
        int expandedWidth = Ui.dp(this, 430);
        int height = Ui.dp(this, 92);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(expanded ? expandedWidth : compact, height);
        lp.leftMargin = prefs.getInt("car_bubble_x", Ui.dp(this, 24));
        lp.topMargin = prefs.getInt("car_bubble_y", Ui.dp(this, 70));
        root.addView(bubble, lp);
        bubble.bringToFront();

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY;
            int startX, startY;
            boolean dragged;

            @Override public boolean onTouch(View v, MotionEvent event) {
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = p.leftMargin;
                        startY = p.topMargin;
                        dragged = false;
                        v.setAlpha(.92f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - downRawX);
                        int dy = (int) (event.getRawY() - downRawY);
                        if (Math.abs(dx) > Ui.dp(MainActivity.this, 4) || Math.abs(dy) > Ui.dp(MainActivity.this, 4)) {
                            dragged = true;
                        }
                        int maxX = Math.max(0, root.getWidth() - v.getWidth());
                        int maxY = Math.max(0, root.getHeight() - v.getHeight());
                        p.leftMargin = Math.max(0, Math.min(maxX, startX + dx));
                        p.topMargin = Math.max(0, Math.min(maxY, startY + dy));
                        v.setLayoutParams(p);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(1f);
                        prefs.edit()
                                .putInt("car_bubble_x", p.leftMargin)
                                .putInt("car_bubble_y", p.topMargin)
                                .apply();
                        if (!dragged && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            boolean nowExpanded = !prefs.getBoolean("car_bubble_expanded", false);
                            prefs.edit().putBoolean("car_bubble_expanded", nowExpanded).apply();
                            renderCarBubble(bubble, nowExpanded);
                            p.width = nowExpanded ? expandedWidth : compact;
                            int maxX = Math.max(0, root.getWidth() - p.width);
                            p.leftMargin = Math.min(p.leftMargin, maxX);
                            bubble.setLayoutParams(p);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void renderCarBubble(LinearLayout bubble, boolean expanded) {
        bubble.removeAllViews();
        bubble.setOrientation(expanded ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        bubble.setPadding(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7));
        bubble.setBackground(Ui.rounded(0xF2171D26, 0xFF2A3A4D, 28, this));

        String limit = prefs.getString("limit", "80");
        String speed = prefs.getString("speed", "0");

        if (!expanded) {
            TextView limitView = Ui.text(this, limit, 25, Ui.TEXT, true);
            limitView.setGravity(Gravity.CENTER);
            limitView.setBackground(Ui.rounded(0xFF0B0E13, 0xFFFF3B30, 35, this));
            bubble.addView(limitView, new LinearLayout.LayoutParams(-1, 0, 2));

            TextView speedView = Ui.text(this, speed + " km/h", 11, Ui.ACCENT, true);
            speedView.setGravity(Gravity.CENTER);
            bubble.addView(speedView, new LinearLayout.LayoutParams(-1, 0, 1));
            return;
        }

        bubble.addView(speedTile(limit, "GIỚI HẠN", true), new LinearLayout.LayoutParams(0, -1, 1));
        bubble.addView(speedTile(speed, "km/h", false), new LinearLayout.LayoutParams(0, -1, 1));
        if (prefs.getBoolean("bubble_camera_zone", true)) {
            bubble.addView(speedTile("📷", "269m", true), new LinearLayout.LayoutParams(0, -1, 1));
            bubble.addView(speedTile("50", "67m", true), new LinearLayout.LayoutParams(0, -1, 1));
        }
    }

    private View speedTile(String main, String sub, boolean warning) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));

        TextView primary = Ui.text(this, main, main.length() > 2 ? 18 : 23, Ui.TEXT, true);
        primary.setGravity(Gravity.CENTER);
        primary.setBackground(Ui.rounded(0xFF090D12, warning ? 0xFFE43A35 : 0xFF4EA7FF, 30, this));
        box.addView(primary, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 50)));

        TextView secondary = Ui.text(this, sub, 11, Ui.MUTED, false);
        secondary.setGravity(Gravity.CENTER);
        box.addView(secondary, new LinearLayout.LayoutParams(-1, Ui.dp(this, 20)));
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
        if (prefs.getBoolean("background_playback", true) && customView != null && !isInPictureInPictureMode()) {
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
