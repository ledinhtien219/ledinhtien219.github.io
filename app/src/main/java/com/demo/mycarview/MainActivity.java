package com.demo.mycarview;

import android.Manifest;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final String HOME = "https://m.youtube.com";
    private static final String PREFS = "carview_settings";

    private FrameLayout browserHost;
    private FrameLayout rootHost;
    private LinearLayout carAddressBar;
    private LinearLayout carBubble;
    private WebView web;
    private EditText address;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private SharedPreferences prefs;
    private SpeechRecognizer speechRecognizer;
    private boolean carMode;
    private boolean pendingVoiceAutoPlay;
    private int aspectMode = 0;
    private String appliedUiSignature;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sharedPreferences, key) -> {
        if (carMode && carBubble != null && ("speed".equals(key) || "limit".equals(key) || "speed_source".equals(key))) {
            boolean expanded = prefs.getBoolean("car_bubble_expanded", false);
            renderCarBubble(carBubble, expanded);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        syncBackgroundService();

        carMode = getIntent().getBooleanExtra("car_preview", false);
        if (carMode) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        build();
        appliedUiSignature = uiSignature();

        String startUrl = HOME;
        if (prefs.getBoolean("auto_resume", true)) {
            String saved = prefs.getString("last_url", HOME);
            if (saved != null && !saved.isEmpty()) startUrl = saved;
        }
        web.loadUrl(startUrl);
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs == null) return;
        syncBackgroundService();

        String signature = uiSignature();
        if (appliedUiSignature != null && !appliedUiSignature.equals(signature)) {
            appliedUiSignature = signature;
            recreate();
        }
    }

    private String uiSignature() {
        return prefs.getString("language", "vi") + "|" +
                prefs.getBoolean("voice_control", true) + "|" +
                prefs.getBoolean("voice_auto_play_first", true) + "|" +
                prefs.getBoolean("car_auto_fullscreen", true) + "|" +
                prefs.getBoolean("show_web_browser", true) + "|" +
                prefs.getInt("car_icon_scale", 100) + "|" +
                prefs.getBoolean("car_btn_voice", true) + "|" +
                prefs.getBoolean("car_btn_previous", false) + "|" +
                prefs.getBoolean("car_btn_play_pause", true) + "|" +
                prefs.getBoolean("car_btn_next", true) + "|" +
                prefs.getBoolean("car_btn_back", true) + "|" +
                prefs.getBoolean("car_btn_home", false) + "|" +
                prefs.getBoolean("car_btn_refresh", false) + "|" +
                prefs.getBoolean("car_btn_aspect", true) + "|" +
                prefs.getBoolean("bubble_enabled", false) + "|" +
                prefs.getInt("bubble_scale", 100) + "|" +
                prefs.getBoolean("bubble_camera_zone", true);
    }

    private boolean isEnglish() {
        return "en".equals(prefs.getString("language", "vi"));
    }

    private String tr(String vi, String en) {
        return isEnglish() ? en : vi;
    }

    private void syncBackgroundService() {
        Intent svc = new Intent(this, BrowserKeepAliveService.class);
        if (prefs.getBoolean("background_playback", true)) {
            startForegroundService(svc);
        } else {
            stopService(svc);
        }
    }

    private void build() {
        rootHost = new FrameLayout(this);
        rootHost.setBackgroundColor(Ui.BG);

        if (carMode) buildCarLayout(rootHost);
        else buildPhoneLayout(rootHost);

        if (carMode && prefs.getBoolean("bubble_enabled", false)) {
            addDraggableCarBubble(rootHost);
        }

        setContentView(rootHost);
    }

    private LinearLayout makeTitleArea(boolean compact) {
        LinearLayout titleArea = new LinearLayout(this);
        titleArea.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "CarView AA", compact ? 16 : 28, Ui.TEXT, true);
        TextView subtitle = Ui.text(this,
                tr("YouTube cho hành trình", "YouTube for the road"),
                compact ? 10 : 14,
                Ui.MUTED,
                false);
        titleArea.addView(title, new LinearLayout.LayoutParams(-1, 0, 2));
        titleArea.addView(subtitle, new LinearLayout.LayoutParams(-1, 0, 1));
        return titleArea;
    }

    private void buildPhoneLayout(FrameLayout root) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        page.addView(makeTitleArea(false), new LinearLayout.LayoutParams(-1, Ui.dp(this, 68)));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 72)));
        address = createAddressField();
        bar.addView(address, new LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1));

        TextView go = button(tr("Đi", "Go"), 18, Ui.ACCENT, 0xFF071321);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 58));
        gp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        bar.addView(go, gp);
        go.setOnClickListener(v -> loadInput());

        browserHost = new FrameLayout(this);
        page.addView(browserHost, new LinearLayout.LayoutParams(-1, 0, 1));
        web = new WebView(this);
        browserHost.addView(web, new FrameLayout.LayoutParams(-1, -1));
        configureWebView();

        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 78));
        tp.setMargins(0, Ui.dp(this, 7), 0, 0);
        page.addView(tools, tp);

        addPhoneTool(tools, "←", v -> { if (web.canGoBack()) web.goBack(); });
        addPhoneTool(tools, "→", v -> { if (web.canGoForward()) web.goForward(); });
        addPhoneTool(tools, "⌂", v -> web.loadUrl(HOME));
        addPhoneTool(tools, "↻", v -> web.reload());
        if (prefs.getBoolean("voice_control", true)) {
            addPhoneTool(tools, "🎤", v -> startVoiceSearch());
        }
        addPhoneTool(tools, "⚙", v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void buildCarLayout(FrameLayout root) {
        LinearLayout whole = new LinearLayout(this);
        whole.setOrientation(LinearLayout.HORIZONTAL);
        whole.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        root.addView(whole, new FrameLayout.LayoutParams(-1, -1));

        int scale = prefs.getInt("car_icon_scale", 100);
        float factor = Math.max(.6f, Math.min(2.2f, scale / 100f));
        int railWidth = Ui.dp(this, 58 * factor);

        ScrollView railScroll = new ScrollView(this);
        railScroll.setFillViewport(true);
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        rail.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
        railScroll.addView(rail, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(railWidth, -1);
        rp.setMargins(0, 0, Ui.dp(this, 6), 0);
        whole.addView(railScroll, rp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        whole.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        content.addView(makeTitleArea(true), new LinearLayout.LayoutParams(-1, Ui.dp(this, 38)));

        carAddressBar = new LinearLayout(this);
        carAddressBar.setGravity(Gravity.CENTER_VERTICAL);
        carAddressBar.setVisibility(View.GONE);
        address = createAddressField();
        carAddressBar.addView(address, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
        TextView go = button(tr("Đi", "Go"), 14, Ui.ACCENT, 0xFF071321);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(Ui.dp(this, 68), Ui.dp(this, 44));
        gp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        carAddressBar.addView(go, gp);
        go.setOnClickListener(v -> loadInput());
        LinearLayout.LayoutParams cap = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        cap.setMargins(0, 0, 0, Ui.dp(this, 4));
        content.addView(carAddressBar, cap);

        browserHost = new FrameLayout(this);
        content.addView(browserHost, new LinearLayout.LayoutParams(-1, 0, 1));
        web = new WebView(this);
        browserHost.addView(web, new FrameLayout.LayoutParams(-1, -1));
        configureWebView();

        if (prefs.getBoolean("car_btn_voice", true)) {
            addCarTool(rail, "🎤", factor, v -> startVoiceSearch());
        }
        if (prefs.getBoolean("car_btn_previous", false)) {
            addCarTool(rail, "⏮", factor, v -> seekVideo(-10));
        }
        if (prefs.getBoolean("car_btn_play_pause", true)) {
            addCarTool(rail, "▶", factor, v -> togglePlayPause());
        }
        if (prefs.getBoolean("car_btn_next", true)) {
            addCarTool(rail, "⏭", factor, v -> seekVideo(10));
        }
        if (prefs.getBoolean("car_btn_back", true)) {
            addCarTool(rail, "←", factor, v -> { if (web.canGoBack()) web.goBack(); });
        }
        if (prefs.getBoolean("car_btn_home", false)) {
            addCarTool(rail, "⌂", factor, v -> web.loadUrl(HOME));
        }
        if (prefs.getBoolean("car_btn_refresh", false)) {
            addCarTool(rail, "↻", factor, v -> web.reload());
        }
        if (prefs.getBoolean("car_btn_aspect", true)) {
            addCarTool(rail, "▣", factor, v -> cycleAspect());
        }
        if (prefs.getBoolean("show_web_browser", true)) {
            addCarTool(rail, "🌐", factor, v -> toggleCarAddressBar());
        }
        addCarTool(rail, "⚙", factor, v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    private EditText createAddressField() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(HOME);
        field.setTextColor(Ui.TEXT);
        field.setHintTextColor(Ui.MUTED);
        field.setTextSize(carMode ? 14 : 17);
        field.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        field.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 18, this));
        field.setImeOptions(EditorInfo.IME_ACTION_GO);
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                loadInput();
                return true;
            }
            return false;
        });
        return field;
    }

    private void toggleCarAddressBar() {
        if (carAddressBar == null) return;
        carAddressBar.setVisibility(carAddressBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void addPhoneTool(LinearLayout tools, String label, View.OnClickListener click) {
        TextView b = Ui.text(this, label, 28, Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 18, this));
        b.setOnClickListener(click);
        Ui.press(b);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        p.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        tools.addView(b, p);
    }

    private void addCarTool(LinearLayout rail, String label, float factor, View.OnClickListener click) {
        float capped = Math.max(.7f, Math.min(1.7f, factor));
        TextView b = Ui.text(this, label, 22 * capped, Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 15, this));
        b.setOnClickListener(click);
        Ui.press(b);
        int h = Ui.dp(this, 46 * capped);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, h);
        p.setMargins(0, Ui.dp(this, 2), 0, Ui.dp(this, 3));
        rail.addView(b, p);
    }

    private void addDraggableCarBubble(FrameLayout root) {
        carBubble = new LinearLayout(this);
        carBubble.setGravity(Gravity.CENTER);
        boolean expanded = prefs.getBoolean("car_bubble_expanded", false);
        renderCarBubble(carBubble, expanded);

        float factor = Math.max(.6f, Math.min(2.2f, prefs.getInt("bubble_scale", 100) / 100f));
        int compact = Ui.dp(this, 88 * factor);
        int expandedWidth = Ui.dp(this, 360 * factor);
        int height = Ui.dp(this, 88 * factor);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(expanded ? expandedWidth : compact, height);
        lp.leftMargin = prefs.getInt("car_bubble_x", Ui.dp(this, 72));
        lp.topMargin = prefs.getInt("car_bubble_y", Ui.dp(this, 60));
        root.addView(carBubble, lp);
        carBubble.bringToFront();

        carBubble.setOnTouchListener(new View.OnTouchListener() {
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
                            renderCarBubble(carBubble, nowExpanded);
                            p.width = nowExpanded ? expandedWidth : compact;
                            int maxExpandedX = Math.max(0, root.getWidth() - p.width);
                            p.leftMargin = Math.min(p.leftMargin, maxExpandedX);
                            carBubble.setLayoutParams(p);
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
        bubble.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        bubble.setBackground(Ui.rounded(0xF2171D26, 0xFF2A3A4D, 26, this));

        String source = prefs.getString("speed_source", "vietmap");
        String limit = "waze".equals(source) ? "--" : prefs.getString("limit", "80");
        String speed = prefs.getString("speed", "0");
        float factor = Math.max(.6f, Math.min(2.2f, prefs.getInt("bubble_scale", 100) / 100f));

        if (!expanded) {
            TextView limitView = Ui.text(this, limit, 22 * factor, Ui.TEXT, true);
            limitView.setGravity(Gravity.CENTER);
            limitView.setBackground(Ui.rounded(0xFF0B0E13, 0xFFFF3B30, 32, this));
            bubble.addView(limitView, new LinearLayout.LayoutParams(-1, 0, 2));

            TextView speedView = Ui.text(this, speed + " km/h", 10 * factor, Ui.ACCENT, true);
            speedView.setGravity(Gravity.CENTER);
            bubble.addView(speedView, new LinearLayout.LayoutParams(-1, 0, 1));
            return;
        }

        bubble.addView(speedTile(limit, tr("GIỚI HẠN", "LIMIT"), true, factor), new LinearLayout.LayoutParams(0, -1, 1));
        bubble.addView(speedTile(speed, "km/h", false, factor), new LinearLayout.LayoutParams(0, -1, 1));
        if (prefs.getBoolean("bubble_camera_zone", true) && !"waze".equals(source)) {
            bubble.addView(speedTile("CAM", "--", true, factor), new LinearLayout.LayoutParams(0, -1, 1));
            bubble.addView(speedTile("KDC", "--", true, factor), new LinearLayout.LayoutParams(0, -1, 1));
        }
    }

    private View speedTile(String main, String sub, boolean warning, float factor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));

        TextView primary = Ui.text(this, main, 17 * factor, Ui.TEXT, true);
        primary.setGravity(Gravity.CENTER);
        primary.setBackground(Ui.rounded(0xFF090D12, warning ? 0xFFE43A35 : 0xFF4EA7FF, 26, this));
        box.addView(primary, new LinearLayout.LayoutParams(Ui.dp(this, 50 * factor), Ui.dp(this, 43 * factor)));

        TextView secondary = Ui.text(this, sub, 9 * factor, Ui.MUTED, false);
        secondary.setGravity(Gravity.CENTER);
        box.addView(secondary, new LinearLayout.LayoutParams(-1, Ui.dp(this, 18 * factor)));
        return box;
    }

    private TextView button(String label, float sp, int bg, int fg) {
        TextView t = Ui.text(this, label, sp, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.rounded(bg, bg, 18, this));
        Ui.press(t);
        return t;
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

                if (carMode && prefs.getBoolean("car_auto_fullscreen", true)) {
                    injectAutoFullscreen();
                }

                if (pendingVoiceAutoPlay && url != null && url.contains("/results")) {
                    pendingVoiceAutoPlay = false;
                    web.evaluateJavascript(
                            "(function(){var a=document.querySelector('a[href^=\\\"/watch\\\"]');if(a){a.click();return 'ok';}return 'none';})()",
                            null);
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

    private void injectAutoFullscreen() {
        web.evaluateJavascript(
                "(function(){if(window.__cvFs)return;window.__cvFs=1;document.addEventListener('play',function(e){var v=e.target;if(v&&v.tagName==='VIDEO'){try{var f=v.requestFullscreen||v.webkitRequestFullscreen;if(f)f.call(v);}catch(x){}}},true);})()",
                null);
    }

    private void togglePlayPause() {
        web.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(!v)return 'none';if(v.paused){v.play();return 'play';}v.pause();return 'pause';})()",
                null);
    }

    private void seekVideo(int seconds) {
        web.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(!v)return 'none';v.currentTime=Math.max(0,v.currentTime+" + seconds + ");return v.currentTime;})()",
                null);
    }

    private void cycleAspect() {
        aspectMode = (aspectMode + 1) % 3;
        String fit = aspectMode == 0 ? "contain" : (aspectMode == 1 ? "cover" : "fill");
        web.evaluateJavascript(
                "(function(){var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){vs[i].style.objectFit='" + fit + "';vs[i].style.width='100%';vs[i].style.height='100%';}return '" + fit + "';})()",
                null);
        Toast.makeText(this, tr("Tỉ lệ video: ", "Video fit: ") + fit, Toast.LENGTH_SHORT).show();
    }

    private void startVoiceSearch() {
        if (!prefs.getBoolean("voice_control", true)) {
            Toast.makeText(this, tr("Điều khiển giọng nói đang tắt trong Cài đặt.", "Voice control is disabled in Settings."), Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, tr("Hãy cấp quyền microphone trong Cài đặt.", "Grant microphone permission in Settings."), Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, tr("Thiết bị không có dịch vụ nhận dạng giọng nói.", "Speech recognition is unavailable."), Toast.LENGTH_LONG).show();
            return;
        }

        if (speechRecognizer != null) speechRecognizer.destroy();
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                Toast.makeText(MainActivity.this, tr("Đang nghe...", "Listening..."), Toast.LENGTH_SHORT).show();
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) {
                Toast.makeText(MainActivity.this, tr("Không nhận được giọng nói.", "Voice recognition failed."), Toast.LENGTH_SHORT).show();
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches == null || matches.isEmpty()) return;
                String query = matches.get(0).trim();
                if (query.isEmpty()) return;
                pendingVoiceAutoPlay = prefs.getBoolean("voice_auto_play_first", true);
                web.loadUrl("https://m.youtube.com/results?search_query=" + Uri.encode(query));
            }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, isEnglish() ? "en-US" : "vi-VN");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        speechRecognizer.startListening(intent);
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
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
