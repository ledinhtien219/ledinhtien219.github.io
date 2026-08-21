package com.demo.mycarview;

import android.app.Activity;
import android.app.Application;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Persists the WebView session independently from page callbacks and keeps the
 * user-enabled media session wired to the currently alive MainActivity WebView.
 */
public class SessionResumeApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private static final String PREFS = "carview_settings";
    private static final String HOME = "https://m.youtube.com";
    private static final long CAPTURE_INTERVAL_MS = 2000L;
    private static final long AD_SKIP_INTERVAL_MS = 450L;
    private static final long ASPECT_UI_INTERVAL_MS = 900L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final WeakHashMap<Activity, Boolean> freshlyCreated = new WeakHashMap<>();
    private final WeakHashMap<View, Boolean> patchedControls = new WeakHashMap<>();
    private WeakReference<Activity> currentMain = new WeakReference<>(null);

    private final Runnable periodicCapture = new Runnable() {
        @Override public void run() {
            Activity activity = currentMain.get();
            if (activity != null) {
                captureSession(activity);
                mainHandler.postDelayed(this, CAPTURE_INTERVAL_MS);
            }
        }
    };

    private final Runnable adSkipLoop = new Runnable() {
        @Override public void run() {
            Activity activity = currentMain.get();
            if (activity != null) {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                if (prefs.getBoolean("ad_filter", true)) {
                    WebView web = findWebView(activity);
                    if (web != null) YoutubeAdSkipper.run(web);
                }
                mainHandler.postDelayed(this, AD_SKIP_INTERVAL_MS);
            }
        }
    };

    private final Runnable aspectUiLoop = new Runnable() {
        @Override public void run() {
            Activity activity = currentMain.get();
            if (activity != null) {
                installUiFixes(activity);
                applySavedAspect(activity);
                configureAutoPip(activity);
                mainHandler.postDelayed(this, ASPECT_UI_INTERVAL_MS);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        if (activity instanceof MainActivity) {
            freshlyCreated.put(activity, true);
        }
    }

    @Override public void onActivityResumed(Activity activity) {
        if (!(activity instanceof MainActivity)) return;

        currentMain = new WeakReference<>(activity);
        mainHandler.removeCallbacks(periodicCapture);
        mainHandler.removeCallbacks(adSkipLoop);
        mainHandler.removeCallbacks(aspectUiLoop);
        mainHandler.post(periodicCapture);
        mainHandler.post(adSkipLoop);
        mainHandler.post(aspectUiLoop);

        installUiFixes(activity);
        applySavedAspect(activity);
        configureAutoPip(activity);

        boolean isFresh = Boolean.TRUE.equals(freshlyCreated.remove(activity));
        if (isFresh) {
            scheduleColdStartResume(activity);
        }
    }

    @Override public void onActivityPaused(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        captureSession(activity);
        mainHandler.removeCallbacks(periodicCapture);
        mainHandler.removeCallbacks(adSkipLoop);
        mainHandler.removeCallbacks(aspectUiLoop);
    }

    @Override public void onActivityStopped(Activity activity) {
        if (activity instanceof MainActivity) captureSession(activity);
    }

    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        if (activity instanceof MainActivity) captureSession(activity);
    }

    @Override public void onActivityStarted(Activity activity) {}

    @Override public void onActivityDestroyed(Activity activity) {
        if (activity instanceof MainActivity && currentMain.get() == activity) {
            currentMain.clear();
            mainHandler.removeCallbacks(periodicCapture);
            mainHandler.removeCallbacks(adSkipLoop);
            mainHandler.removeCallbacks(aspectUiLoop);
        }
        freshlyCreated.remove(activity);
    }

    /** Called by BrowserKeepAliveService / MediaSession transport controls. */
    public boolean dispatchPlaybackCommand(String command) {
        Activity activity = currentMain.get();
        if (activity == null || activity.isDestroyed()) return false;
        WebView web = findWebView(activity);
        if (web == null) return false;

        String js;
        if (BrowserKeepAliveService.CMD_PLAY.equals(command)) {
            js = "(function(){var v=document.querySelector('video');if(!v)return 'none';try{var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(e){}return 'play';})()";
        } else if (BrowserKeepAliveService.CMD_PAUSE.equals(command)) {
            js = "(function(){var v=document.querySelector('video');if(!v)return 'none';try{v.pause();}catch(e){}return 'pause';})()";
        } else if (BrowserKeepAliveService.CMD_REWIND.equals(command)) {
            js = "(function(){var v=document.querySelector('video');if(!v)return 'none';v.currentTime=Math.max(0,v.currentTime-10);return v.currentTime;})()";
        } else if (BrowserKeepAliveService.CMD_FORWARD.equals(command)) {
            js = "(function(){var v=document.querySelector('video');if(!v)return 'none';var d=isFinite(v.duration)?v.duration:1e12;v.currentTime=Math.min(d,Math.max(0,v.currentTime+10));return v.currentTime;})()";
        } else {
            return false;
        }

        try {
            web.evaluateJavascript(js, value -> mainHandler.postDelayed(() -> captureSession(activity), 250L));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Android 12+ can automatically enter PiP when the user leaves the app.
     * We only enable auto-PiP while a video page is open and background playback
     * is enabled. Older Android versions keep MainActivity's existing fullscreen
     * PiP behaviour.
     */
    private void configureAutoPip(Activity activity) {
        if (Build.VERSION.SDK_INT < 31) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        WebView web = findWebView(activity);
        boolean enabled = prefs.getBoolean("background_playback", true) &&
                web != null && isVideoUrl(web.getUrl());
        try {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .setAutoEnterEnabled(enabled)
                    .setSeamlessResizeEnabled(true)
                    .build();
            activity.setPictureInPictureParams(params);
        } catch (Throwable ignored) {
        }
    }

    private void installUiFixes(Activity activity) {
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (decor == null) return;
        patchViewTree(activity, decor);
    }

    private void patchViewTree(Activity activity, View view) {
        if (view instanceof TextView && !patchedControls.containsKey(view)) {
            String label = ((TextView) view).getText() == null ? "" : ((TextView) view).getText().toString();
            if ("←".equals(label)) {
                view.setOnClickListener(v -> performBackNavigation(activity));
                patchedControls.put(view, true);
            } else if ("▣".equals(label)) {
                view.setOnClickListener(v -> cycleAspect(activity));
                patchedControls.put(view, true);
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                patchViewTree(activity, group.getChildAt(i));
            }
        }
    }

    private void performBackNavigation(Activity activity) {
        if (findCustomView(activity) != null) {
            invokeHideCustomView(activity);
            return;
        }

        WebView web = findWebView(activity);
        if (web == null) return;

        if (web.canGoBack()) {
            web.goBack();
            return;
        }

        final String before = web.getUrl();
        try {
            web.evaluateJavascript(
                    "(function(){if(window.history&&history.length>1){history.back();return 'back';}return 'none';})()",
                    result -> {
                        if (result == null || result.contains("none")) {
                            fallbackBack(web, before);
                            return;
                        }
                        mainHandler.postDelayed(() -> {
                            String now = web.getUrl();
                            if (!isHttpUrl(now) || sameUrl(now, before)) {
                                fallbackBack(web, before);
                            }
                        }, 900L);
                    });
        } catch (Throwable ignored) {
            fallbackBack(web, before);
        }
    }

    private void fallbackBack(WebView web, String before) {
        if (web == null) return;
        if (before == null || !isYoutubeHome(before)) {
            web.loadUrl(HOME);
        }
    }

    private boolean isYoutubeHome(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath();
            boolean youtube = host != null && (host.equals("youtube.com") || host.endsWith(".youtube.com"));
            return youtube && (path == null || path.isEmpty() || "/".equals(path));
        } catch (Throwable ignored) {
            return HOME.equals(url);
        }
    }

    private boolean sameUrl(String a, String b) {
        if (a == null || b == null) return a == null && b == null;
        return a.equals(b);
    }

    private void cycleAspect(Activity activity) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String current = prefs.getString("video_aspect_mode", "contain");
        String next;
        if ("contain".equals(current)) next = "cover";
        else if ("cover".equals(current)) next = "fill";
        else next = "contain";

        prefs.edit().putString("video_aspect_mode", next).apply();
        applySavedAspect(activity);

        boolean english = "en".equals(prefs.getString("language", "vi"));
        String name;
        if ("cover".equals(next)) name = english ? "Crop / Fill screen" : "Cắt viền / Phủ kín";
        else if ("fill".equals(next)) name = english ? "Stretch" : "Kéo giãn";
        else name = english ? "Fit / Original ratio" : "Vừa khung / Giữ tỉ lệ";
        Toast.makeText(activity,
                (english ? "Video ratio: " : "Tỉ lệ video: ") + name,
                Toast.LENGTH_SHORT).show();
    }

    private void applySavedAspect(Activity activity) {
        WebView web = findWebView(activity);
        if (web == null) return;

        // Never resize generic YouTube player shells on Home/search pages. The
        // old code forced ytm-player to height:100% + black background, which
        // could become a full-page black overlay even when no video was open.
        if (!isVideoUrl(web.getUrl())) {
            String cleanup = "(function(){" +
                    "var vs=document.querySelectorAll('[data-cv-aspect-video=\\\"1\\\"]');" +
                    "for(var i=0;i<vs.length;i++){var v=vs[i];" +
                    "v.style.removeProperty('object-fit');v.style.removeProperty('width');v.style.removeProperty('height');" +
                    "v.style.removeProperty('max-width');v.style.removeProperty('max-height');v.removeAttribute('data-cv-aspect-video');}" +
                    "var cs=document.querySelectorAll('[data-cv-aspect-container=\\\"1\\\"]');" +
                    "for(var j=0;j<cs.length;j++){var c=cs[j];" +
                    "c.style.removeProperty('width');c.style.removeProperty('height');c.style.removeProperty('overflow');" +
                    "c.style.removeProperty('background');c.removeAttribute('data-cv-aspect-container');}" +
                    "return vs.length+cs.length;" +
                    "})()";
            try {
                web.evaluateJavascript(cleanup, null);
            } catch (Throwable ignored) {
            }
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = prefs.getString("video_aspect_mode", "contain");
        if (!"contain".equals(mode) && !"cover".equals(mode) && !"fill".equals(mode)) {
            mode = "contain";
        }

        String js = "(function(){" +
                "var m='" + mode + "';" +
                "var vs=document.querySelectorAll('video');" +
                "if(!vs.length)return 0;" +
                "for(var i=0;i<vs.length;i++){" +
                "var v=vs[i];v.setAttribute('data-cv-aspect-video','1');" +
                "v.style.setProperty('object-fit',m,'important');" +
                "v.style.setProperty('width','100%','important');" +
                "v.style.setProperty('height','100%','important');" +
                "v.style.setProperty('max-width','none','important');" +
                "v.style.setProperty('max-height','none','important');" +
                "var c=v.closest('.html5-video-container,.html5-video-player,.player-container,ytm-player');" +
                "if(c){c.setAttribute('data-cv-aspect-container','1');" +
                "c.style.setProperty('width','100%','important');" +
                "c.style.setProperty('height','100%','important');" +
                "c.style.setProperty('overflow','hidden','important');}" +
                "}" +
                "return vs.length;" +
                "})()";
        try {
            web.evaluateJavascript(js, null);
        } catch (Throwable ignored) {
        }

        View custom = findCustomView(activity);
        if (custom != null) {
            custom.setBackgroundColor(Color.BLACK);
            custom.setScaleX(1f);
            custom.setScaleY(1f);
        }
    }

    private void captureSession(Activity activity) {
        WebView web = findWebView(activity);
        if (web == null) return;

        String url = web.getUrl();
        if (!isHttpUrl(url)) return;

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit().putString("last_url", url);
        if (isVideoUrl(url)) {
            edit.putString("last_video_url", url);
        }
        edit.apply();

        if (!isVideoUrl(url)) {
            reportPlaybackState(false);
            return;
        }

        final String capturedUrl = url;
        try {
            web.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');if(!v)return '-1|0';return Math.floor(v.currentTime)+'|'+((!v.paused&&!v.ended)?1:0);})()",
                    value -> {
                        String clean = value == null ? "" : value.replace("\"", "").trim();
                        String[] parts = clean.split("\\|");
                        int seconds = parts.length > 0 ? parseInt(parts[0], -1) : -1;
                        boolean isPlaying = parts.length > 1 && "1".equals(parts[1]);
                        if (seconds >= 0) {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putInt("last_video_position_sec", seconds)
                                    .putString("last_video_position_url", capturedUrl)
                                    .putBoolean("last_video_was_playing", isPlaying)
                                    .apply();
                        }
                        reportPlaybackState(isPlaying);
                    });
        } catch (Throwable ignored) {
        }
    }

    private void reportPlaybackState(boolean playing) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("background_playback", true)) return;
        try {
            Intent i = new Intent(this, BrowserKeepAliveService.class)
                    .setAction(BrowserKeepAliveService.ACTION_UPDATE_STATE)
                    .putExtra(BrowserKeepAliveService.EXTRA_PLAYING, playing);
            startService(i);
        } catch (Throwable ignored) {
        }
    }

    private void scheduleColdStartResume(Activity activity) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("auto_resume", true)) return;

        long[] delays = {900L, 2200L, 4200L, 6500L};
        WeakReference<Activity> ref = new WeakReference<>(activity);
        for (long delay : delays) {
            mainHandler.postDelayed(() -> {
                Activity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                resumeVideoIfPossible(a);
                applySavedAspect(a);
                configureAutoPip(a);
            }, delay);
        }
    }

    private void resumeVideoIfPossible(Activity activity) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("auto_resume", true)) return;

        WebView web = findWebView(activity);
        if (web == null) return;
        String currentUrl = web.getUrl();
        if (!isVideoUrl(currentUrl)) return;

        int position = 0;
        String positionUrl = prefs.getString("last_video_position_url", "");
        if (sameVideo(currentUrl, positionUrl)) {
            position = Math.max(0, prefs.getInt("last_video_position_sec", 0));
        }

        String js = "(function(){" +
                "var v=document.querySelector('video');" +
                "if(!v)return 'missing';" +
                "var p=" + position + ";" +
                "if(p>1 && isFinite(v.duration) && p<v.duration-2 && Math.abs(v.currentTime-p)>3){v.currentTime=p;}" +
                "try{var r=v.play();if(r&&r.catch)r.catch(function(){});}catch(e){}" +
                "return 'ok';" +
                "})()";
        try {
            web.evaluateJavascript(js, null);
        } catch (Throwable ignored) {
        }
    }

    private WebView findWebView(Activity activity) {
        try {
            Field field = MainActivity.class.getDeclaredField("web");
            field.setAccessible(true);
            Object value = field.get(activity);
            return value instanceof WebView ? (WebView) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private View findCustomView(Activity activity) {
        try {
            Field field = MainActivity.class.getDeclaredField("customView");
            field.setAccessible(true);
            Object value = field.get(activity);
            return value instanceof View ? (View) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void invokeHideCustomView(Activity activity) {
        try {
            Method method = MainActivity.class.getDeclaredMethod("hideCustomView");
            method.setAccessible(true);
            method.invoke(activity);
        } catch (Throwable ignored) {
            activity.onBackPressed();
        }
    }

    private boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://"));
    }

    private boolean isVideoUrl(String url) {
        if (url == null) return false;
        return url.contains("youtube.com/watch") ||
                url.contains("youtu.be/") ||
                url.contains("youtube.com/shorts/");
    }

    private boolean sameVideo(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        try {
            Uri ua = Uri.parse(a);
            Uri ub = Uri.parse(b);
            String va = ua.getQueryParameter("v");
            String vb = ub.getQueryParameter("v");
            if (va != null && vb != null) return va.equals(vb);
            return ua.getPath() != null && ua.getPath().equals(ub.getPath());
        } catch (Throwable ignored) {
            return a.equals(b);
        }
    }

    private int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            String clean = value.replace("\"", "").trim();
            if (clean.contains(".")) clean = clean.substring(0, clean.indexOf('.'));
            return Integer.parseInt(clean);
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
