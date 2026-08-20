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
import android.graphics.PixelFormat;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
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

import java.util.Map;

public class SpeedBubbleService extends Service {
    private static final String PREFS = "carview_settings";
    private static final String CHANNEL_ID = "carview_speed_bubble";
    private static final int NOTIFICATION_ID = 1101;

    private WindowManager windowManager;
    private View bubble;
    private WindowManager.LayoutParams params;
    private SharedPreferences prefs;
    private TextView speedView;
    private TextView sourceView;
    private TextView limitView;
    private LocationManager locationManager;
    private LocationListener locationListener;

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        // startForegroundService() must be satisfied quickly. Any failure is handled
        // here so a rejected overlay/FGS never takes down the whole application.
        if (!startAsForegroundSafely()) {
            stopSelf();
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            setBubbleEnabled(false);
            stopSelf();
            return;
        }

        if (!showBubbleSafely()) {
            setBubbleEnabled(false);
            stopSelf();
            return;
        }

        startSelectedSource();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            setBubbleEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        refreshSourceLabel();
        return START_STICKY;
    }

    private boolean startAsForegroundSafely() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "Bong bóng tốc độ", NotificationManager.IMPORTANCE_LOW));
            }

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);

            Notification notification = builder
                    .setContentTitle("CarView AA")
                    .setContentText("Bong bóng tốc độ đang hoạt động")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= 34) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                if ("waze".equals(readString("speed_source", "vietmap")) && hasLocationPermission()) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                }
                startForeground(NOTIFICATION_ID, notification, type);
            } else if (Build.VERSION.SDK_INT >= 29
                    && "waze".equals(readString("speed_source", "vietmap"))
                    && hasLocationPermission()) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean showBubbleSafely() {
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null || !Settings.canDrawOverlays(this)) return false;

            String source = readString("speed_source", "vietmap");
            String limit = readString("limit", "80");
            String speed = readString("speed", "0");
            int scale = readInt("bubble_scale", 100);
            float factor = Math.max(.6f, Math.min(2.2f, scale / 100f));
            boolean showHideButton = readBoolean("bubble_hide_button", false);
            boolean showWarnings = readBoolean("bubble_camera_zone", true) && !"waze".equals(source);

            FrameLayout wrapper = new FrameLayout(this);

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(Ui.dp(this, 7), Ui.dp(this, 5), Ui.dp(this, 7), Ui.dp(this, 5));
            box.setBackground(Ui.rounded(0xEC161D27, 0xFFFF5252, 34, this));
            wrapper.addView(box, new FrameLayout.LayoutParams(-1, -1));

            sourceView = Ui.text(this, sourceLabel(), 9 * factor, Ui.MUTED, true);
            sourceView.setGravity(Gravity.CENTER);
            box.addView(sourceView, new LinearLayout.LayoutParams(-1, 0, 1));

            limitView = Ui.text(this, displayLimit(limit), 22 * factor, 0xFFFFFFFF, true);
            limitView.setGravity(Gravity.CENTER);
            box.addView(limitView, new LinearLayout.LayoutParams(-1, 0, 2));

            speedView = Ui.text(this, displaySpeed(speed), 11 * factor, Ui.ACCENT, true);
            speedView.setGravity(Gravity.CENTER);
            box.addView(speedView, new LinearLayout.LayoutParams(-1, 0, 1));

            if (showWarnings) {
                TextView warning = Ui.text(this, "CAM --  ·  KDC --", 8 * factor, Ui.MUTED, false);
                warning.setGravity(Gravity.CENTER);
                box.addView(warning, new LinearLayout.LayoutParams(-1, 0, 1));
            }

            if (showHideButton) {
                TextView close = Ui.text(this, "×", 15 * factor, 0xFFFFFFFF, true);
                close.setGravity(Gravity.CENTER);
                close.setBackground(Ui.rounded(0xCC7E1F26, 0xFFFF5252, 16, this));
                close.setOnClickListener(v -> {
                    setBubbleEnabled(false);
                    stopSelf();
                });
                int closeSize = Ui.dp(this, 24 * factor);
                FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                        closeSize, closeSize, Gravity.TOP | Gravity.END);
                cp.setMargins(0, Ui.dp(this, -3), Ui.dp(this, -3), 0);
                wrapper.addView(close, cp);
            }

            int width = Ui.dp(this, (showWarnings ? 118 : 90) * factor);
            int height = Ui.dp(this, (showWarnings ? 100 : 88) * factor);
            params = new WindowManager.LayoutParams(
                    width, height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = readInt("bubble_x", Ui.dp(this, 22));
            params.y = readInt("bubble_y", Ui.dp(this, 130));

            wrapper.setOnTouchListener(new View.OnTouchListener() {
                float downX, downY;
                int startX, startY;

                @Override public boolean onTouch(View v, MotionEvent event) {
                    if (params == null) return true;
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getRawX();
                            downY = event.getRawY();
                            startX = params.x;
                            startY = params.y;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            params.x = startX + (int) (event.getRawX() - downX);
                            params.y = startY + (int) (event.getRawY() - downY);
                            try {
                                if (bubble != null && windowManager != null) {
                                    windowManager.updateViewLayout(bubble, params);
                                }
                            } catch (Throwable ignored) {
                                // Overlay may be revoked while dragging. Do not crash.
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            try {
                                prefs.edit()
                                        .putInt("bubble_x", params.x)
                                        .putInt("bubble_y", params.y)
                                        .apply();
                            } catch (Throwable ignored) {
                            }
                            return true;
                        default:
                            return true;
                    }
                }
            });

            // Assign only after addView succeeds, so onDestroy never removes an
            // unattached view after a permission race.
            windowManager.addView(wrapper, params);
            bubble = wrapper;
            return true;
        } catch (Throwable ignored) {
            bubble = null;
            return false;
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String sourceLabel() {
        String source = readString("speed_source", "vietmap");
        if ("waze".equals(source)) return hasLocationPermission() ? "WAZE · GPS" : "WAZE · CẦN GPS";
        if ("wyn".equals(source)) return "WYN · CHỜ DỮ LIỆU";
        return "VIETMAP · CHỜ DỮ LIỆU";
    }

    private String displayLimit(String savedLimit) {
        return "waze".equals(readString("speed_source", "vietmap")) ? "--" : savedLimit;
    }

    private String displaySpeed(String savedSpeed) {
        if ("waze".equals(readString("speed_source", "vietmap"))) {
            return savedSpeed + " km/h";
        }
        return "-- km/h";
    }

    private void refreshSourceLabel() {
        try {
            if (sourceView != null) sourceView.setText(sourceLabel());
            if (limitView != null) limitView.setText(displayLimit(readString("limit", "80")));
            if (speedView != null) speedView.setText(displaySpeed(readString("speed", "0")));
        } catch (Throwable ignored) {
        }
    }

    private void startSelectedSource() {
        if ("waze".equals(readString("speed_source", "vietmap"))) {
            startGpsSpeed();
        } else {
            refreshSourceLabel();
        }
    }

    private void startGpsSpeed() {
        if (!hasLocationPermission()) {
            if (sourceView != null) sourceView.setText("WAZE · CẦN GPS");
            return;
        }

        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (locationManager == null) return;

            locationListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    int kmh = 0;
                    if (location != null && location.hasSpeed()) {
                        kmh = Math.round(location.getSpeed() * 3.6f);
                    }
                    kmh = Math.max(0, Math.min(300, kmh));
                    try { prefs.edit().putString("speed", String.valueOf(kmh)).apply(); }
                    catch (Throwable ignored) {}
                    if (speedView != null) speedView.setText(kmh + " km/h");
                    if (sourceView != null) sourceView.setText("WAZE · GPS");
                }

                @Override public void onProviderDisabled(String provider) {
                    if (sourceView != null) sourceView.setText("WAZE · BẬT GPS");
                }

                @Override public void onProviderEnabled(String provider) {}
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            };

            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener);
        } catch (Throwable ignored) {
            locationListener = null;
            if (sourceView != null) sourceView.setText("WAZE · CẦN GPS");
        }
    }

    private void setBubbleEnabled(boolean enabled) {
        try { prefs.edit().putBoolean("bubble_enabled", enabled).apply(); }
        catch (Throwable ignored) {}
    }

    private Map<String, ?> allPrefs() {
        try { return prefs.getAll(); }
        catch (Throwable ignored) { return java.util.Collections.emptyMap(); }
    }

    private String readString(String key, String fallback) {
        Object value = allPrefs().get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private int readInt(String key, int fallback) {
        Object value = allPrefs().get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt(((String) value).trim()); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    private boolean readBoolean(String key, boolean fallback) {
        Object value = allPrefs().get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean(((String) value).trim());
        return fallback;
    }

    @Override public void onDestroy() {
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); }
            catch (Throwable ignored) {}
        }
        locationListener = null;

        if (windowManager != null && bubble != null) {
            try { windowManager.removeView(bubble); }
            catch (Throwable ignored) {}
            bubble = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
