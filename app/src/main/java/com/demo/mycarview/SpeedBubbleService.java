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

/**
 * Draggable road HUD shown as a system overlay on supported phone/head-unit setups.
 * Layout mirrors the compact VIETMAP-style strip: current limit, GPS speed,
 * upcoming camera distance, and next limit distance.
 */
public class SpeedBubbleService extends Service {
    private static final String PREFS = "carview_settings";
    private static final String CHANNEL_ID = "carview_speed_bubble";
    private static final int NOTIFICATION_ID = 1101;

    private WindowManager windowManager;
    private View hud;
    private WindowManager.LayoutParams params;
    private SharedPreferences prefs;
    private TextView sourceView;
    private TextView limitValue;
    private TextView speedValue;
    private TextView cameraValue;
    private TextView cameraDistance;
    private TextView nextLimitValue;
    private TextView nextLimitDistance;
    private LocationManager locationManager;
    private LocationListener locationListener;

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (!startAsForegroundSafely()) {
            stopSelf();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            setBubbleEnabled(false);
            stopSelf();
            return;
        }
        if (!showHudSafely()) {
            setBubbleEnabled(false);
            stopSelf();
            return;
        }
        startGpsSpeed();
        refreshHud();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            setBubbleEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        refreshHud();
        return START_STICKY;
    }

    private boolean startAsForegroundSafely() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "CarView Road HUD", NotificationManager.IMPORTANCE_LOW));
            }

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);

            Notification notification = builder
                    .setContentTitle("CarView AA")
                    .setContentText("Road HUD đang hoạt động")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= 34) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                if (hasLocationPermission()) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                startForeground(NOTIFICATION_ID, notification, type);
            } else if (Build.VERSION.SDK_INT >= 29 && hasLocationPermission()) {
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

    private boolean showHudSafely() {
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null || !Settings.canDrawOverlays(this)) return false;

            int scale = readInt("bubble_scale", 100);
            final float factor = Math.max(.60f, Math.min(1.55f, scale / 100f));
            boolean showHideButton = readBoolean("bubble_hide_button", false);

            FrameLayout wrapper = new FrameLayout(this);

            LinearLayout outer = new LinearLayout(this);
            outer.setOrientation(LinearLayout.VERTICAL);
            outer.setPadding(Ui.dp(this, 8 * factor), Ui.dp(this, 5 * factor),
                    Ui.dp(this, 8 * factor), Ui.dp(this, 7 * factor));
            outer.setBackground(Ui.rounded(0xF20D1218, 0xFF27313B, 20, this));
            wrapper.addView(outer, new FrameLayout.LayoutParams(-1, -1));

            sourceView = Ui.text(this, sourceLabel(), 10 * factor, 0xFFF0F2F4, true);
            sourceView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 26 * factor));
            outer.addView(sourceView, sourceLp);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, 0, 1);
            outer.addView(row, rowLp);

            row.addView(buildLimitTile(factor, false), tileParams());
            row.addView(buildSpeedTile(factor), tileParams());
            row.addView(buildCameraTile(factor), tileParams());
            row.addView(buildLimitTile(factor, true), tileParams());

            if (showHideButton) {
                TextView close = Ui.text(this, "×", 15 * factor, 0xFFFFFFFF, true);
                close.setGravity(Gravity.CENTER);
                close.setBackground(Ui.rounded(0xCC7E1F26, 0xFFFF5252, 14, this));
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

            int width = Ui.dp(this, 470 * factor);
            int height = Ui.dp(this, 118 * factor);
            params = new WindowManager.LayoutParams(
                    width, height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = readInt("bubble_x", Ui.dp(this, 18));
            params.y = readInt("bubble_y", Ui.dp(this, 64));

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
                                if (hud != null && windowManager != null) {
                                    windowManager.updateViewLayout(hud, params);
                                }
                            } catch (Throwable ignored) {
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

            windowManager.addView(wrapper, params);
            hud = wrapper;
            return true;
        } catch (Throwable ignored) {
            hud = null;
            return false;
        }
    }

    private LinearLayout.LayoutParams tileParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        return lp;
    }

    private View buildLimitTile(float factor, boolean next) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);

        TextView value = Ui.text(this, "--", 22 * factor, 0xFF101010, true);
        value.setGravity(Gravity.CENTER);
        value.setBackground(Ui.rounded(0xFFF8F8F8, 0xFFFF3030, 44, this));
        int circle = Ui.dp(this, 58 * factor);
        cell.addView(value, new LinearLayout.LayoutParams(circle, circle));

        TextView distance = Ui.text(this, next ? "--" : "", 10 * factor, 0xFFE7EAED, false);
        distance.setGravity(Gravity.CENTER);
        cell.addView(distance, new LinearLayout.LayoutParams(-1, Ui.dp(this, 20 * factor)));

        if (next) {
            nextLimitValue = value;
            nextLimitDistance = distance;
        } else {
            limitValue = value;
        }
        return cell;
    }

    private View buildSpeedTile(float factor) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);

        LinearLayout circle = new LinearLayout(this);
        circle.setOrientation(LinearLayout.VERTICAL);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(Ui.rounded(0xFFF8F8F8, 0xFF2B78FF, 44, this));

        speedValue = Ui.text(this, "--", 21 * factor, 0xFF101820, true);
        speedValue.setGravity(Gravity.CENTER);
        circle.addView(speedValue, new LinearLayout.LayoutParams(-1, 0, 2));
        TextView unit = Ui.text(this, "km/h", 9 * factor, 0xFF27313B, false);
        unit.setGravity(Gravity.CENTER);
        circle.addView(unit, new LinearLayout.LayoutParams(-1, 0, 1));

        int size = Ui.dp(this, 58 * factor);
        cell.addView(circle, new LinearLayout.LayoutParams(size, size));
        cell.addView(Ui.text(this, "", 10 * factor, 0xFFE7EAED, false),
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 20 * factor)));
        return cell;
    }

    private View buildCameraTile(float factor) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);

        cameraValue = Ui.text(this, "CAM", 14 * factor, 0xFF111111, true);
        cameraValue.setGravity(Gravity.CENTER);
        cameraValue.setBackground(Ui.rounded(0xFFF8F8F8, 0xFFFF3030, 44, this));
        int circle = Ui.dp(this, 58 * factor);
        cell.addView(cameraValue, new LinearLayout.LayoutParams(circle, circle));

        cameraDistance = Ui.text(this, "--", 10 * factor, 0xFFE7EAED, false);
        cameraDistance.setGravity(Gravity.CENTER);
        cell.addView(cameraDistance, new LinearLayout.LayoutParams(-1, Ui.dp(this, 20 * factor)));
        return cell;
    }

    private void refreshHud() {
        try {
            String source = readString("speed_source", "vietmap");
            if (sourceView != null) sourceView.setText(sourceLabel());
            if (limitValue != null) {
                limitValue.setText("waze".equals(source) ? "--" : normalizeLimit(readString("limit", "--")));
            }
            if (speedValue != null) {
                speedValue.setText(hasLocationPermission() ? readString("speed", "0") : "--");
            }
            if (cameraDistance != null) {
                int m = "waze".equals(source) ? -1 : readInt("camera_distance_m", -1);
                cameraDistance.setText(formatDistance(m));
            }
            if (nextLimitValue != null) {
                nextLimitValue.setText("waze".equals(source)
                        ? "--" : normalizeLimit(readString("next_limit", "--")));
            }
            if (nextLimitDistance != null) {
                int m = "waze".equals(source) ? -1 : readInt("next_limit_distance_m", -1);
                nextLimitDistance.setText(formatDistance(m));
            }
        } catch (Throwable ignored) {
        }
    }

    private String sourceLabel() {
        String source = readString("speed_source", "vietmap");
        if ("waze".equals(source)) return "WAZE · GPS";
        if ("wyn".equals(source)) return "WYN";
        return "VIETMAP LIVE";
    }

    private String normalizeLimit(String value) {
        try {
            int n = Integer.parseInt(value.trim());
            return (n > 0 && n <= 200) ? String.valueOf(n) : "--";
        } catch (Throwable ignored) {
            return "--";
        }
    }

    private String formatDistance(int meters) {
        if (meters < 0) return "--";
        if (meters < 1000) return meters + "m";
        float km = meters / 1000f;
        return km < 10f
                ? String.format(java.util.Locale.US, "%.1fkm", km)
                : Math.round(km) + "km";
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startGpsSpeed() {
        if (!hasLocationPermission()) return;
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
                    if (speedValue != null) speedValue.setText(String.valueOf(kmh));
                }

                @Override public void onProviderDisabled(String provider) {
                    if (speedValue != null) speedValue.setText("--");
                }
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            };

            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener);
        } catch (Throwable ignored) {
            locationListener = null;
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

        if (windowManager != null && hud != null) {
            try { windowManager.removeView(hud); }
            catch (Throwable ignored) {}
            hud = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
