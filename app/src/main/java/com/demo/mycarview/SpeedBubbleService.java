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

/** Stable draggable CarHUD overlay with GPS + official VietMap connector feed. */
public class SpeedBubbleService extends Service {
    private static final String PREFS = "carview_settings";
    private static final String CHANNEL_ID = "carhud_location";
    private static final int NOTIFICATION_ID = 1101;

    private SharedPreferences prefs;
    private WindowManager wm;
    private View hud;
    private WindowManager.LayoutParams params;

    private TextView speedView;
    private TextView limitView;
    private TextView nextLimitView;
    private TextView nextDistanceView;
    private TextView cameraDistanceView;
    private TextView sourceView;
    private View limitTile;
    private View nextTile;
    private View cameraTile;

    private LocationManager locationManager;
    private LocationListener locationListener;
    private float smoothedSpeed = -1f;
    private ToneGenerator tone;
    private boolean cameraAlerted;
    private boolean rebuilding;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (p, key) -> {
        if (rebuilding) return;
        if ("hud_style".equals(key) || "bubble_scale".equals(key)
                || "show_limit".equals(key) || "show_next_limit".equals(key)
                || "show_camera".equals(key)) {
            rebuildHud();
        } else if ("speed_source".equals(key)) {
            if ("vietmap_api".equals(readString("speed_source", "gps"))) {
                VietmapSpeedAlertBridge.start(this);
            } else {
                VietmapSpeedAlertBridge.stop(this);
            }
            refreshHud();
        } else {
            refreshHud();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!hasLocationPermission() || !Settings.canDrawOverlays(this)) {
            disableAndStop();
            return;
        }
        if (!startForegroundSafely() || !showHudSafely()) {
            disableAndStop();
            return;
        }
        try { prefs.registerOnSharedPreferenceChangeListener(prefListener); } catch (Throwable ignored) {}
        if ("vietmap_api".equals(readString("speed_source", "gps"))) {
            VietmapSpeedAlertBridge.start(this);
        }
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
            if (nm != null && Build.VERSION.SDK_INT >= 26) {
                NotificationChannel c = new NotificationChannel(
                        CHANNEL_ID, "CarHUD GPS", NotificationManager.IMPORTANCE_LOW);
                c.setDescription("GPS + VIETMAP road HUD");
                nm.createNotificationChannel(c);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
            Notification n = b.setContentTitle("CarHUD")
                    .setContentText("HUD đang hoạt động")
                    .setSmallIcon(R.drawable.ic_carhud)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else startForeground(NOTIFICATION_ID, n);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private void rebuildHud() {
        if (rebuilding) return;
        rebuilding = true;
        try {
            safeRemoveHud();
            clearRefs();
            showHudSafely();
            refreshHud();
        } finally { rebuilding = false; }
    }

    private boolean showHudSafely() {
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return false;
            int scalePct = clamp(readInt("bubble_scale", 80), 60, 150);
            float scale = scalePct / 100f;
            String style = readString("hud_style", "neon");

            FrameLayout wrapper = new FrameLayout(this);
            View content;
            int widthDp;
            int heightDp;
            if ("vertical".equals(style)) {
                content = buildVertical(scale); widthDp = Math.round(110 * scale); heightDp = Math.round(258 * scale);
            } else if ("compact".equals(style)) {
                content = buildCompact(scale); widthDp = Math.round(344 * scale); heightDp = Math.round(94 * scale);
            } else {
                content = buildNeon(scale); widthDp = Math.round(382 * scale); heightDp = Math.round(118 * scale);
            }
            wrapper.addView(content, new FrameLayout.LayoutParams(-1, -1));
            hud = wrapper;

            params = new WindowManager.LayoutParams(
                    dp(widthDp), dp(heightDp), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = readInt("bubble_x", dp(16));
            params.y = readInt("bubble_y", dp(76));
            clampPosition();

            wrapper.setOnTouchListener(new View.OnTouchListener() {
                float downX, downY;
                int startX, startY;
                @Override public boolean onTouch(View v, MotionEvent event) {
                    if (params == null) return false;
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getRawX(); downY = event.getRawY();
                            startX = params.x; startY = params.y;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            params.x = startX + Math.round(event.getRawX() - downX);
                            params.y = startY + Math.round(event.getRawY() - downY);
                            clampPosition();
                            try { if (wm != null && hud != null) wm.updateViewLayout(hud, params); }
                            catch (Throwable ignored) {}
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            prefs.edit().putInt("bubble_x", params.x).putInt("bubble_y", params.y).apply();
                            return true;
                        default: return false;
                    }
                }
            });
            wm.addView(wrapper, params);
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
        outer.setPadding(dp(7 * scale), dp(5 * scale), dp(7 * scale), dp(5 * scale));

        LinearLayout speed = column();
        speedView = label("--", 25 * scale, Ui.TEXT, true);
        speedView.setGravity(Gravity.CENTER);
        TextView unit = label("km/h", 7 * scale, Ui.MUTED, false); unit.setGravity(Gravity.CENTER);
        speed.addView(speedView, new LinearLayout.LayoutParams(-1, 0, 2));
        speed.addView(unit, new LinearLayout.LayoutParams(-1, 0, 1));
        outer.addView(speed, weighted());
        limitTile = signColumn(false, scale); outer.addView(limitTile, weighted());
        cameraTile = cameraColumn(scale); outer.addView(cameraTile, weighted());
        nextTile = signColumn(true, scale); outer.addView(nextTile, weighted());
        return outer;
    }

    private View buildNeon(float scale) {
        LinearLayout outer = panel();
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(6 * scale), dp(3 * scale), dp(6 * scale), dp(5 * scale));
        sourceView = label(sourceLabel(), 7.5f * scale, Ui.MUTED, true);
        sourceView.setGravity(Gravity.CENTER_VERTICAL);
        outer.addView(sourceView, new LinearLayout.LayoutParams(-1, dp(18 * scale)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER);
        outer.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        limitTile = signColumn(false, scale); row.addView(limitTile, weighted());

        LinearLayout center = column();
        LinearLayout neon = new LinearLayout(this);
        neon.setOrientation(LinearLayout.VERTICAL); neon.setGravity(Gravity.CENTER);
        neon.setBackground(circle(0xFF0A151F, 0xFF32E1C4, 2));
        speedView = label("--", 25 * scale, Color.WHITE, true); speedView.setGravity(Gravity.CENTER);
        TextView unit = label("KM/H", 7 * scale, 0xFF32E1C4, true); unit.setGravity(Gravity.CENTER);
        neon.addView(speedView, new LinearLayout.LayoutParams(-1, 0, 2));
        neon.addView(unit, new LinearLayout.LayoutParams(-1, 0, 1));
        int s = dp(67 * scale);
        center.addView(neon, new LinearLayout.LayoutParams(s, s));
        row.addView(center, weighted());
        cameraTile = cameraColumn(scale); row.addView(cameraTile, weighted());
        nextTile = signColumn(true, scale); row.addView(nextTile, weighted());
        return outer;
    }

    private View buildVertical(float scale) {
        LinearLayout outer = panel();
        outer.setOrientation(LinearLayout.VERTICAL); outer.setGravity(Gravity.CENTER_HORIZONTAL);
        outer.setPadding(dp(6 * scale), dp(6 * scale), dp(6 * scale), dp(6 * scale));
        sourceView = label(sourceLabel(), 6.5f * scale, Ui.MUTED, true); sourceView.setGravity(Gravity.CENTER);
        outer.addView(sourceView, new LinearLayout.LayoutParams(-1, dp(18 * scale)));
        speedView = label("--", 23 * scale, 0xFF32E1C4, true); speedView.setGravity(Gravity.CENTER);
        outer.addView(speedView, new LinearLayout.LayoutParams(-1, dp(39 * scale)));
        TextView unit = label("KM/H", 6.5f * scale, Ui.MUTED, true); unit.setGravity(Gravity.CENTER);
        outer.addView(unit, new LinearLayout.LayoutParams(-1, dp(15 * scale)));
        limitTile = verticalSign(false, scale); outer.addView(limitTile, new LinearLayout.LayoutParams(-1, dp(61 * scale)));
        nextTile = verticalSign(true, scale); outer.addView(nextTile, new LinearLayout.LayoutParams(-1, dp(61 * scale)));
        cameraTile = verticalCamera(scale); outer.addView(cameraTile, new LinearLayout.LayoutParams(-1, dp(46 * scale)));
        return outer;
    }

    private View signColumn(boolean next, float scale) {
        LinearLayout c = column();
        TextView sign = label("--", 15 * scale, 0xFF111111, true); sign.setGravity(Gravity.CENTER);
        sign.setBackground(circle(0xFFF9F9F9, 0xFFFF3B30, 3));
        int s = dp(44 * scale); c.addView(sign, new LinearLayout.LayoutParams(s, s));
        TextView d = label(next ? "--" : "", 7 * scale, Ui.MUTED, false); d.setGravity(Gravity.CENTER);
        c.addView(d, new LinearLayout.LayoutParams(-1, dp(16 * scale)));
        if (next) { nextLimitView = sign; nextDistanceView = d; } else limitView = sign;
        return c;
    }

    private View cameraColumn(float scale) {
        LinearLayout c = column();
        TextView cam = label("CAM", 9 * scale, 0xFF111111, true); cam.setGravity(Gravity.CENTER);
        cam.setBackground(circle(0xFFFFB000, 0xFFFFB000, 1));
        int s = dp(44 * scale); c.addView(cam, new LinearLayout.LayoutParams(s, s));
        cameraDistanceView = label("--", 7 * scale, Ui.MUTED, false); cameraDistanceView.setGravity(Gravity.CENTER);
        c.addView(cameraDistanceView, new LinearLayout.LayoutParams(-1, dp(16 * scale)));
        return c;
    }

    private View verticalSign(boolean next, float scale) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER);
        TextView sign = label("--", 12 * scale, 0xFF111111, true); sign.setGravity(Gravity.CENTER);
        sign.setBackground(circle(0xFFF9F9F9, 0xFFFF3B30, 3));
        int s = dp(38 * scale); row.addView(sign, new LinearLayout.LayoutParams(s, s));
        TextView dist = label(next ? "--" : "", 6.5f * scale, Ui.MUTED, false); dist.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1); p.setMargins(dp(4), 0, 0, 0);
        row.addView(dist, p);
        if (next) { nextLimitView = sign; nextDistanceView = dist; } else limitView = sign;
        return row;
    }

    private View verticalCamera(float scale) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER);
        TextView cam = label("CAM", 8 * scale, 0xFF111111, true); cam.setGravity(Gravity.CENTER);
        cam.setBackground(circle(0xFFFFB000, 0xFFFFB000, 1));
        int s = dp(34 * scale); row.addView(cam, new LinearLayout.LayoutParams(s, s));
        cameraDistanceView = label("--", 6.5f * scale, Ui.MUTED, false); cameraDistanceView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1); p.setMargins(dp(4), 0, 0, 0);
        row.addView(cameraDistanceView, p);
        return row;
    }

    private void refreshHud() {
        try {
            if (speedView != null) speedView.setText(readString("speed", "--"));
            if (sourceView != null) sourceView.setText(sourceLabel());
            if (limitView != null) limitView.setText(normalizeLimit(readString("limit", "--")));
            if (nextLimitView != null) nextLimitView.setText(normalizeLimit(readString("next_limit", "--")));
            if (nextDistanceView != null) nextDistanceView.setText(formatDistance(readInt("next_limit_distance_m", -1)));
            int camera = readInt("camera_distance_m", -1);
            if (cameraDistanceView != null) cameraDistanceView.setText(formatDistance(camera));
            if (limitTile != null) limitTile.setVisibility(readBoolean("show_limit", true) ? View.VISIBLE : View.GONE);
            if (nextTile != null) nextTile.setVisibility(readBoolean("show_next_limit", true) ? View.VISIBLE : View.GONE);
            if (cameraTile != null) cameraTile.setVisibility(readBoolean("show_camera", true) ? View.VISIBLE : View.GONE);
            maybeAlertCamera(camera);
        } catch (Throwable ignored) {}
    }

    private String sourceLabel() {
        if ("vietmap_api".equals(readString("speed_source", "gps"))) {
            String road = readString("vietmap_road", "");
            if (road.isEmpty()) road = readString("vietmap_route_road", "");
            String alert = readString("vietmap_alert_status", "");
            if (!road.isEmpty()) {
                if (road.length() > 28) road = road.substring(0, 28) + "…";
                return "VIETMAP · " + road;
            }
            if (alert.startsWith("Đang")) return "VIETMAP SPEED ALERT · GPS";
            return "VIETMAP MAPS · GPS";
        }
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
                    if (location.hasAccuracy() && location.getAccuracy() > 100f) return;
                    float raw = location.hasSpeed() ? Math.max(0f, Math.min(300f, location.getSpeed() * 3.6f)) : 0f;
                    if (raw < 1.5f) raw = 0f;
                    if (smoothedSpeed < 0f) smoothedSpeed = raw;
                    else smoothedSpeed += (raw > smoothedSpeed ? .45f : .28f) * (raw - smoothedSpeed);
                    int kmh = Math.round(smoothedSpeed);
                    prefs.edit().putString("speed", String.valueOf(kmh)).apply();
                    if (speedView != null) speedView.setText(String.valueOf(kmh));

                    if ("vietmap_api".equals(readString("speed_source", "gps"))) {
                        // Every fix feeds the official Speed Alert bridge. The
                        // Maps reverse/route part is throttled internally.
                        VietmapApiClient.maybeUpdate(SpeedBubbleService.this, location);
                    }
                }
                @Override public void onProviderDisabled(String provider) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            };
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 700L, 0f, locationListener);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1600L, 0f, locationListener);
            }
        } catch (Throwable ignored) { locationListener = null; }
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
            } catch (Throwable ignored) {}
        }
    }

    private LinearLayout panel() {
        LinearLayout v = new LinearLayout(this);
        v.setBackground(Ui.rounded(0xF20B121A, 0xFF2B3D50, 18, this));
        return v;
    }
    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setGravity(Gravity.CENTER); return v;
    }
    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f); p.setMargins(dp(2), 0, dp(2), 0); return p;
    }
    private TextView label(String t, float sp, int color, boolean bold) { return Ui.text(this, t, sp, color, bold); }
    private GradientDrawable circle(int fill, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(fill);
        if (stroke != Color.TRANSPARENT && strokeDp > 0) d.setStroke(dp(strokeDp), stroke); return d;
    }

    private void clampPosition() {
        if (params == null) return;
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        params.x = clamp(params.x, 0, Math.max(0, w - Math.max(1, params.width)));
        params.y = clamp(params.y, 0, Math.max(0, h - Math.max(1, params.height)));
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
        if (wm != null && hud != null) try { wm.removeView(hud); } catch (Throwable ignored) {}
        hud = null;
    }
    private void clearRefs() {
        speedView = null; limitView = null; nextLimitView = null; nextDistanceView = null;
        cameraDistanceView = null; sourceView = null; limitTile = null; nextTile = null; cameraTile = null;
    }
    private Map<String, ?> all() {
        try { return prefs == null ? Collections.emptyMap() : prefs.getAll(); }
        catch (Throwable ignored) { return Collections.emptyMap(); }
    }
    private String readString(String key, String fallback) {
        Object v = all().get(key); if (v == null) return fallback;
        String s = String.valueOf(v).trim(); return s.isEmpty() ? fallback : s;
    }
    private int readInt(String key, int fallback) {
        Object v = all().get(key); if (v instanceof Number) return ((Number) v).intValue();
        try { return v == null ? fallback : Integer.parseInt(String.valueOf(v).trim()); }
        catch (Throwable ignored) { return fallback; }
    }
    private boolean readBoolean(String key, boolean fallback) {
        Object v = all().get(key); if (v instanceof Boolean) return (Boolean) v;
        return v == null ? fallback : Boolean.parseBoolean(String.valueOf(v));
    }
    private String normalizeLimit(String value) {
        try { int n = Integer.parseInt(value.trim()); return n > 0 && n <= 200 ? String.valueOf(n) : "--"; }
        catch (Throwable ignored) { return "--"; }
    }
    private String formatDistance(int meters) {
        if (meters < 0) return "--";
        if (meters < 1000) return meters + "m";
        return String.format(java.util.Locale.US, "%.1fkm", meters / 1000f);
    }
    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private int dp(float v) { return Ui.dp(this, v); }

    @Override public void onDestroy() {
        try { if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener); } catch (Throwable ignored) {}
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Throwable ignored) {}
        }
        VietmapSpeedAlertBridge.stop(this);
        safeRemoveHud();
        if (tone != null) try { tone.release(); } catch (Throwable ignored) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
