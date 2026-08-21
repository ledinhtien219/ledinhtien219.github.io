package com.demo.mycarview;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;

/** Stable draggable GPS HUD overlay for phone / Android-based head units. */
public class SpeedBubbleService extends Service {
    private static final String PREFS = "carview_settings";
    private static final String CHANNEL_ID = "carhud_location";
    private static final int NOTIFICATION_ID = 1101;

    private SharedPreferences prefs;
    private WindowManager windowManager;
    private View hud;
    private WindowManager.LayoutParams params;

    private TextView speedView;
    private TextView limitView;
    private TextView nextLimitView;
    private TextView nextDistanceView;
    private TextView cameraView;
    private TextView cameraDistanceView;
    private TextView sourceView;

    private View limitTile;
    private View nextTile;
    private View cameraTile;

    private LocationManager locationManager;
    private LocationListener locationListener;
    private float smoothedSpeed = -1f;
    private long lastGpsFix;

    private ToneGenerator tone;
    private boolean cameraAlerted;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sharedPreferences, key) -> {
        if ("hud_style".equals(key) || "bubble_scale".equals(key)) return;
        refreshHud();
    };

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (!hasLocationPermission() || !Settings.canDrawOverlays(this)) {
            disableAndStop();
            return;
        }
        if (!startForegroundSafely()) {
            disableAndStop();
            return;
        }
        if (!showHudSafely()) {
            disableAndStop();
            return;
        }

        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        startGps();
        refreshHud();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!hasLocationPermission() || !Settings.canDrawOverlays(this)) {
            disableAndStop();
            return START_NOT_STICKY;
        }
        refreshHud();
        return START_NOT_STICKY;
    }

    private boolean startForegroundSafely() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "CarHUD GPS", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("GPS speed HUD foreground service");
                nm.createNotificationChannel(channel);
            }

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            Notification n = b
                    .setContentTitle("CarHUD")
                    .setContentText("HUD GPS đang hoạt động")
                    .setSmallIcon(R.drawable.ic_carhud)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .build();

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean showHudSafely() {
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) return false;

            int scalePct = clamp(readInt("bubble_scale", 100), 65, 145);
            float scale = scalePct / 100f;
            String style = readString("hud_style", "neon");

            FrameLayout wrapper = new FrameLayout(this);
            View content;
            int widthDp;
            int heightDp;
            if ("vertical".equals(style)) {
                content = buildVertical(scale);
                widthDp = Math.round(112 * scale);
                heightDp = Math.round(270 * scale);
            } else if ("compact".equals(style)) {
                content = buildCompact(scale);
                widthDp = Math.round(360 * scale);
                heightDp = Math.round(102 * scale);
            } else {
                content = buildNeon(scale);
                widthDp = Math.round(390 * scale);
                heightDp = Math.round(126 * scale);
            }
            wrapper.addView(content, new FrameLayout.LayoutParams(-1, -1));
            hud = wrapper;

            params = new WindowManager.LayoutParams(
                    Ui.dp(this, widthDp), Ui.dp(this, heightDp),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = readInt("bubble_x", Ui.dp(this, 18));
            params.y = readInt("bubble_y", Ui.dp(this, 82));
            clampPosition();

            wrapper.setOnTouchListener(new View.OnTouchListener() {
                float downX;
                float downY;
                int startX;
                int startY;

                @Override public boolean onTouch(View v, MotionEvent event) {
                    if (params == null) return false;
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getRawX();
                            downY = event.getRawY();
                            startX = params.x;
                            startY = params.y;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            params.x = startX + Math.round(event.getRawX() - downX);
                            params.y = startY + Math.round(event.getRawY() - downY);
                            clampPosition();
                            try {
                                if (windowManager != null && hud != null) {
                                    windowManager.updateViewLayout(hud, params);
                                }
                            } catch (Throwable ignored) {
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            try {
                                prefs.edit().putInt("bubble_x", params.x).putInt("bubble_y", params.y).apply();
                            } catch (Throwable ignored) {
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });

            windowManager.addView(wrapper, params);
            return true;
        } catch (Throwable ignored) {
            safeRemoveHud();
            return false;
        }
    }

    private View buildCompact(float scale) {
        LinearLayout outer = panel();
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);
        outer.setPadding(dp(10 * scale), dp(8 * scale), dp(10 * scale), dp(8 * scale));

        LinearLayout speed = column();
        speedView = label("--", 28 * scale, Ui.TEXT, true);
        speedView.setGravity(Gravity.CENTER);
        TextView unit = label("km/h", 9 * scale, Ui.MUTED, false);
        unit.setGravity(Gravity.CENTER);
        speed.addView(speedView, new LinearLayout.LayoutParams(-1, 0, 2));
        speed.addView(unit, new LinearLayout.LayoutParams(-1, 0, 1));
        outer.addView(speed, weighted());

        limitTile = compactSignColumn(false, scale);
        outer.addView(limitTile, weighted());

        cameraTile = compactCameraColumn(scale);
        outer.addView(cameraTile, weighted());

        nextTile = compactSignColumn(true, scale);
        outer.addView(nextTile, weighted());
        return outer;
    }

    private View buildNeon(float scale) {
        LinearLayout outer = panel();
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(8 * scale), dp(5 * scale), dp(8 * scale), dp(7 * scale));

        sourceView = label(sourceLabel(), 9 * scale, Ui.MUTED, true);
        sourceView.setGravity(Gravity.CENTER_VERTICAL);
        outer.addView(sourceView, new LinearLayout.LayoutParams(-1, dp(22 * scale)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        outer.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));

        limitTile = compactSignColumn(false, scale);
        row.addView(limitTile, weighted());

        LinearLayout center = column();
        LinearLayout neon = new LinearLayout(this);
        neon.setOrientation(LinearLayout.VERTICAL);
        neon.setGravity(Gravity.CENTER);
        neon.setBackground(circle(0xFF0A151F, 0xFF32E1C4, 2));
        speedView = label("--", 27 * scale, 0xFFFFFFFF, true);
        speedView.setGravity(Gravity.CENTER);
        TextView unit = label("KM/H", 8 * scale, 0xFF32E1C4, true);
        unit.setGravity(Gravity.CENTER);
        neon.addView(speedView, new LinearLayout.LayoutParams(-1, 0, 2));
        neon.addView(unit, new LinearLayout.LayoutParams(-1, 0, 1));
        int circle = dp(72 * scale);
        center.addView(neon, new LinearLayout.LayoutParams(circle, circle));
        row.addView(center, weighted());

        cameraTile = compactCameraColumn(scale);
        row.addView(cameraTile, weighted());

        nextTile = compactSignColumn(true, scale);
        row.addView(nextTile, weighted());
        return outer;
    }

    private View buildVertical(float scale) {
        LinearLayout outer = panel();
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        outer.setPadding(dp(8 * scale), dp(8 * scale), dp(8 * scale), dp(8 * scale));

        sourceView = label("HUD", 8 * scale, Ui.MUTED, true);
        sourceView.setGravity(Gravity.CENTER);
        outer.addView(sourceView, new LinearLayout.LayoutParams(-1, dp(20 * scale)));

        speedView = label("--", 25 * scale, 0xFF32E1C4, true);
        speedView.setGravity(Gravity.CENTER);
        outer.addView(speedView, new LinearLayout.LayoutParams(-1, dp(44 * scale)));
        TextView unit = label("KM/H", 8 * scale, Ui.MUTED, true);
        unit.setGravity(Gravity.CENTER);
        outer.addView(unit, new LinearLayout.LayoutParams(-1, dp(18 * scale)));

        limitTile = verticalSign(false, scale);
        outer.addView(limitTile, new LinearLayout.LayoutParams(-1, dp(65 * scale)));
        nextTile = verticalSign(true, scale);
        outer.addView(nextTile, new LinearLayout.LayoutParams(-1, dp(65 * scale)));
        cameraTile = verticalCamera(scale);
        outer.addView(cameraTile, new LinearLayout.LayoutParams(-1, dp(50 * scale)));
        return outer;
    }

    private View compactSignColumn(boolean next, float scale) {
        LinearLayout c = column();
        TextView sign = label("--", 17 * scale, 0xFF111111, true);
        sign.setGravity(Gravity.CENTER);
        sign.setBackground(circle(0xFFF9F9F9, 0xFFFF3B30, 3));
        int s = dp(48 * scale);
        c.addView(sign, new LinearLayout.LayoutParams(s, s));
        TextView d = label(next ? "--" : "", 9 * scale, Ui.MUTED, false);
        d.setGravity(Gravity.CENTER);
        c.addView(d, new LinearLayout.LayoutParams(-1, dp(18 * scale)));
        if (next) {
            nextLimitView = sign;
            nextDistanceView = d;
        } else {
            limitView = sign;
        }
        return c;
    }

    private View compactCameraColumn(float scale) {
        LinearLayout c = column();
        cameraView = label("CAM", 11 * scale, 0xFF111111, true);
        cameraView.setGravity(Gravity.CENTER);
        cameraView.setBackground(circle(0xFFFFB000, 0xFFFFB000, 1));
        int s = dp(48 * scale);
        c.addView(cameraView, new LinearLayout.LayoutParams(s, s));
        cameraDistanceView = label("--", 9 * scale, Ui.MUTED, false);
        cameraDistanceView.setGravity(Gravity.CENTER);
        c.addView(cameraDistanceView, new LinearLayout.LayoutParams(-1, dp(18 * scale)));
        return c;
    }

    private View verticalSign(boolean next, float scale) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        TextView sign = label("--", 14 * scale, 0xFF111111, true);
        sign.setGravity(Gravity.CENTER);
        sign.setBackground(circle(0xFFF9F9F9, 0xFFFF3B30, 3));
        int s = dp(42 * scale);
        row.addView(sign, new LinearLayout.LayoutParams(s, s));
        TextView dist = label(next ? "--" : "", 8 * scale, Ui.MUTED, false);
        dist.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(0, -1, 1);
        dp.setMargins(this.dp(4), 0, 0, 0);
        row.addView(dist, dp);
        if (next) {
            nextLimitView = sign;
            nextDistanceView = dist;
        } else {
            limitView = sign;
        }
        return row;
    }

    private View verticalCamera(float scale) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        cameraView = label("CAM", 10 * scale, 0xFF111111, true);
        cameraView.setGravity(Gravity.CENTER);
        cameraView.setBackground(circle(0xFFFFB000, 0xFFFFB000, 1));
        int s = dp(38 * scale);
        row.addView(cameraView, new LinearLayout.LayoutParams(s, s));
        cameraDistanceView = label("--", 8 * scale, Ui.MUTED, false);
        cameraDistanceView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        p.setMargins(dp(4), 0, 0, 0);
        row.addView(cameraDistanceView, p);
        return row;
    }

    private LinearLayout panel() {
        LinearLayout v = new LinearLayout(this);
        v.setBackground(Ui.rounded(0xF20B121A, 0xFF2B3D50, 20, this));
        return v;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        return Ui.text(this, text, sp, color, bold);
    }

    private GradientDrawable circle(int fill, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (stroke != Color.TRANSPARENT && strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private void refreshHud() {
        try {
            if (speedView != null) speedView.setText(readString("speed", "--"));
            if (sourceView != null) sourceView.setText(sourceLabel());

            String limit = normalizeLimit(readString("limit", "--"));
            String next = normalizeLimit(readString("next_limit", "--"));
            int nextDistance = readInt("next_limit_distance_m", -1);
            int cameraDistance = readInt("camera_distance_m", -1);

            if (limitView != null) limitView.setText(limit);
            if (nextLimitView != null) nextLimitView.setText(next);
            if (nextDistanceView != null) nextDistanceView.setText(formatDistance(nextDistance));
            if (cameraDistanceView != null) cameraDistanceView.setText(formatDistance(cameraDistance));

            if (limitTile != null) limitTile.setVisibility(readBoolean("show_limit", true) ? View.VISIBLE : View.GONE);
            if (nextTile != null) nextTile.setVisibility(readBoolean("show_next_limit", true) ? View.VISIBLE : View.GONE);
            if (cameraTile != null) cameraTile.setVisibility(readBoolean("show_camera", true) ? View.VISIBLE : View.GONE);

            maybeAlertCamera(cameraDistance);
        } catch (Throwable ignored) {
        }
    }

    private void maybeAlertCamera(int distance) {
        boolean enabled = readBoolean("alert_sound", false) && readBoolean("show_camera", true);
        if (!enabled || distance < 0 || distance > 600) {
            if (distance < 0 || distance > 600) cameraAlerted = false;
            return;
        }
        if (distance <= 500 && !cameraAlerted) {
            cameraAlerted = true;
            try {
                if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180);
            } catch (Throwable ignored) {
            }
        }
    }

    private String sourceLabel() {
        String source = readString("speed_source", "gps");
        if ("vietmap_api".equals(source)) return "VIETMAP API · GPS";
        if ("waze".equals(source)) return "WAZE · GPS";
        return "GPS";
    }

    private void startGps() {
        if (!hasLocationPermission()) return;
        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (locationManager == null) return;

            locationListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    if (location == null) return;
                    if (location.hasAccuracy() && location.getAccuracy() > 80f) return;
                    if (!location.hasSpeed()) return;

                    float raw = Math.max(0f, Math.min(300f, location.getSpeed() * 3.6f));
                    if (raw < 1.5f) raw = 0f;
                    if (smoothedSpeed < 0f) smoothedSpeed = raw;
                    else {
                        float alpha = raw > smoothedSpeed ? 0.45f : 0.28f;
                        smoothedSpeed += alpha * (raw - smoothedSpeed);
                    }
                    int kmh = Math.round(smoothedSpeed);
                    lastGpsFix = System.currentTimeMillis();
                    try { prefs.edit().putString("speed", String.valueOf(kmh)).apply(); }
                    catch (Throwable ignored) {}
                    if (speedView != null) speedView.setText(String.valueOf(kmh));
                }

                @Override public void onProviderDisabled(String provider) {
                    if (speedView != null && System.currentTimeMillis() - lastGpsFix > 3000L) {
                        speedView.setText("--");
                    }
                }
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            };

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1500L, 0f, locationListener);
            }
        } catch (Throwable ignored) {
            locationListener = null;
        }
    }

    private void clampPosition() {
        if (params == null) return;
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int maxX = Math.max(0, w - Math.max(1, params.width));
        int maxY = Math.max(0, h - Math.max(1, params.height));
        params.x = clamp(params.x, 0, maxX);
        params.y = clamp(params.y, 0, maxY);
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void disableAndStop() {
        try { prefs.edit().putBoolean("bubble_enabled", false).apply(); } catch (Throwable ignored) {}
        stopSelf();
    }

    private void safeRemoveHud() {
        if (windowManager != null && hud != null) {
            try { windowManager.removeView(hud); } catch (Throwable ignored) {}
        }
        hud = null;
    }

    private Map<String, ?> allPrefs() {
        try { return prefs == null ? Collections.emptyMap() : prefs.getAll(); }
        catch (Throwable ignored) { return Collections.emptyMap(); }
    }

    private String readString(String key, String fallback) {
        Object v = allPrefs().get(key);
        if (v == null) return fallback;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    private int readInt(String key, int fallback) {
        Object v = allPrefs().get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Throwable ignored) {}
        }
        return fallback;
    }

    private boolean readBoolean(String key, boolean fallback) {
        Object v = allPrefs().get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v != null) return Boolean.parseBoolean(String.valueOf(v));
        return fallback;
    }

    private String normalizeLimit(String value) {
        try {
            int n = Integer.parseInt(value.trim());
            return n > 0 && n <= 200 ? String.valueOf(n) : "--";
        } catch (Throwable ignored) { return "--"; }
    }

    private String formatDistance(int meters) {
        if (meters < 0) return "--";
        if (meters < 1000) return meters + "m";
        return String.format(java.util.Locale.US, "%.1fkm", meters / 1000f);
    }

    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private int dp(float v) { return Ui.dp(this, v); }

    @Override public void onDestroy() {
        try {
            if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        } catch (Throwable ignored) {}
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Throwable ignored) {}
        }
        safeRemoveHud();
        if (tone != null) {
            try { tone.release(); } catch (Throwable ignored) {}
            tone = null;
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
