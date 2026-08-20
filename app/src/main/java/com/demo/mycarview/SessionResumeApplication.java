package com.demo.mycarview;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.WeakHashMap;

/**
 * Persists the WebView session independently from WebView page callbacks.
 * YouTube mobile is a SPA, so navigation between videos can happen without a
 * reliable onPageFinished() for the final /watch URL. Capturing WebView#getUrl()
 * from the activity lifecycle and periodically while visible makes cold-start
 * resume much more reliable.
 *
 * This application object also runs the best-effort YouTube ad skipper while
 * the main activity is visible. Keeping it outside page callbacks means it also
 * survives YouTube SPA navigation where onPageFinished() may not fire again.
 */
public class SessionResumeApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private static final String PREFS = "carview_settings";
    private static final long CAPTURE_INTERVAL_MS = 2000L;
    private static final long AD_SKIP_INTERVAL_MS = 450L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final WeakHashMap<Activity, Boolean> freshlyCreated = new WeakHashMap<>();
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
        mainHandler.post(periodicCapture);
        mainHandler.post(adSkipLoop);

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
        }
        freshlyCreated.remove(activity);
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

        if (!isVideoUrl(url)) return;
        final String capturedUrl = url;
        try {
            web.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');return v?Math.floor(v.currentTime):-1;})()",
                    value -> {
                        int seconds = parseJsInt(value, -1);
                        if (seconds >= 0) {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putInt("last_video_position_sec", seconds)
                                    .putString("last_video_position_url", capturedUrl)
                                    .apply();
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private void scheduleColdStartResume(Activity activity) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("auto_resume", true)) return;

        // YouTube creates/replaces its <video> element asynchronously. A few
        // delayed attempts handle both normal /watch pages and SPA hydration.
        long[] delays = {900L, 2200L, 4200L, 6500L};
        WeakReference<Activity> ref = new WeakReference<>(activity);
        for (long delay : delays) {
            mainHandler.postDelayed(() -> {
                Activity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                resumeVideoIfPossible(a);
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

    private int parseJsInt(String value, int fallback) {
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
