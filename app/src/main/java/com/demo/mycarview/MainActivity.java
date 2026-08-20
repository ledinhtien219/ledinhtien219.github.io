package com.demo.mycarview;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String HOME = "https://m.youtube.com";

    private FrameLayout browserHost;
    private WebView web;
    private EditText address;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean preview = getIntent().getBooleanExtra("car_preview", false);
        if (preview) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        build(preview);
        web.loadUrl(HOME);
    }

    private void build(boolean preview) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, preview ? 10 : 16), Ui.dp(this, 10), Ui.dp(this, preview ? 10 : 16), Ui.dp(this, 10));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        TextView title = Ui.text(this, preview ? "MyCar View AA  •  CAR PREVIEW" : "MyCar View AA", preview ? 20 : 28, Ui.TEXT, true);
        page.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, preview ? 54 : 64)));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, preview ? 58 : 72)));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setText(HOME);
        address.setTextColor(Ui.TEXT);
        address.setHintTextColor(Ui.MUTED);
        address.setTextSize(preview ? 14 : 17);
        address.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        address.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 22, this));
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        bar.addView(address, new LinearLayout.LayoutParams(0, Ui.dp(this, preview ? 48 : 58), 1));

        TextView go = button("Đi", preview ? 15 : 18, Ui.ACCENT, 0xFF071321);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(Ui.dp(this, preview ? 72 : 84), Ui.dp(this, preview ? 48 : 58));
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

        browserHost = new FrameLayout(this);
        page.addView(browserHost, new LinearLayout.LayoutParams(-1, 0, 1));
        web = new WebView(this);
        browserHost.addView(web, new FrameLayout.LayoutParams(-1, -1));
        configureWebView();

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, Ui.dp(this, preview ? 62 : 78));
        tp.setMargins(0, Ui.dp(this, 8), 0, 0);
        page.addView(tools, tp);

        addTool(tools, "←", v -> { if (web.canGoBack()) web.goBack(); });
        addTool(tools, "→", v -> { if (web.canGoForward()) web.goForward(); });
        addTool(tools, "⌂", v -> web.loadUrl(HOME));
        addTool(tools, "↻", v -> web.reload());
        addTool(tools, "⚙", v -> startActivity(new Intent(this, SettingsActivity.class)));

        setContentView(root);
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

            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                address.setText(url);
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
        String u = address.getText().toString().trim();
        if (u.isEmpty()) return;
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://www.google.com/search?q=" + Uri.encode(u);
        }
        web.loadUrl(u);
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
