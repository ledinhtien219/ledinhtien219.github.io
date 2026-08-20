package com.demo.mycarview;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SpeedBubbleService extends Service {
    private static final String PREFS = "carview_settings";

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
        startAsForeground();
        showBubble();
        startSelectedSource();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        refreshSourceLabel();
        return START_STICKY;
    }

    private void startAsForeground() {
        String channelId = "carview_speed_bubble";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                channelId, "Bong bóng tốc độ", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("CarView AA")
                .setContentText("Bong bóng tốc độ đang hoạt động")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        startForeground(1101, notification);
    }

    private void showBubble() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        String source = prefs.getString("speed_source", "vietmap");
        String limit = prefs.getString("limit", "80");
        String speed = prefs.getString("speed", "0");
        int scale = prefs.getInt("bubble_scale", 100);
        float factor = Math.max(.6f, Math.min(2.2f, scale / 100f));
        boolean showHideButton = prefs.getBoolean("bubble_hide_button", false);
        boolean showWarnings = prefs.getBoolean("bubble_camera_zone", true) && !"waze".equals(source);

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
                prefs.edit().putBoolean("bubble_enabled", false).apply();
                stopSelf();
            });
            int closeSize = Ui.dp(this, 24 * factor);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(closeSize, closeSize, Gravity.TOP | Gravity.END);
            cp.setMargins(0, Ui.dp(this, -3), Ui.dp(this, -3), 0);
            wrapper.addView(close, cp);
        }

        bubble = wrapper;

        int width = Ui.dp(this, (showWarnings ? 118 : 90) * factor);
        int height = Ui.dp(this, (showWarnings ? 100 : 88) * factor);
        params = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs.getInt("bubble_x", Ui.dp(this, 22));
        params.y = prefs.getInt("bubble_y", Ui.dp(this, 130));

        wrapper.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - downX);
                        int dy = (int) (event.getRawY() - downY);
                        if (Math.abs(dx) > Ui.dp(SpeedBubbleService.this, 3) || Math.abs(dy) > Ui.dp(SpeedBubbleService.this, 3)) {
                            moved = true;
                        }
                        params.x = startX + dx;
                        params.y = startY + dy;
                        if (bubble != null) windowManager.updateViewLayout(bubble, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        prefs.edit()
                                .putInt("bubble_x", params.x)
                                .putInt("bubble_y", params.y)
                                .apply();
                        return true;
                    default:
                        return true;
                }
            }
        });

        windowManager.addView(bubble, params);
    }

    private String sourceLabel() {
        String source = prefs.getString("speed_source", "vietmap");
        if ("waze".equals(source)) return "WAZE · GPS";
        if ("wyn".equals(source)) return "WYN · CHỜ DỮ LIỆU";
        return "VIETMAP · CHỜ DỮ LIỆU";
    }

    private String displayLimit(String savedLimit) {
        return "waze".equals(prefs.getString("speed_source", "vietmap")) ? "--" : savedLimit;
    }

    private String displaySpeed(String savedSpeed) {
        if ("waze".equals(prefs.getString("speed_source", "vietmap"))) {
            return savedSpeed + " km/h";
        }
        return "-- km/h";
    }

    private void refreshSourceLabel() {
        if (sourceView != null) sourceView.setText(sourceLabel());
        if (limitView != null) limitView.setText(displayLimit(prefs.getString("limit", "80")));
        if (speedView != null) speedView.setText(displaySpeed(prefs.getString("speed", "0")));
    }

    private void startSelectedSource() {
        if ("waze".equals(prefs.getString("speed_source", "vietmap"))) {
            startGpsSpeed();
        } else {
            refreshSourceLabel();
        }
    }

    private void startGpsSpeed() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (sourceView != null) sourceView.setText("WAZE · CẦN GPS");
            return;
        }

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                int kmh = 0;
                if (location != null && location.hasSpeed()) {
                    kmh = Math.round(location.getSpeed() * 3.6f);
                }
                kmh = Math.max(0, Math.min(300, kmh));
                prefs.edit().putString("speed", String.valueOf(kmh)).apply();
                if (speedView != null) speedView.setText(kmh + " km/h");
                if (sourceView != null) sourceView.setText("WAZE · GPS");
            }

            @Override public void onProviderDisabled(String provider) {
                if (sourceView != null) sourceView.setText("WAZE · BẬT GPS");
            }

            @Override public void onProviderEnabled(String provider) {}
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        };

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener);
        } catch (SecurityException ignored) {
            if (sourceView != null) sourceView.setText("WAZE · CẦN GPS");
        }
    }

    @Override public void onDestroy() {
        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            }
        }
        if (windowManager != null && bubble != null) {
            try {
                windowManager.removeView(bubble);
            } catch (Throwable ignored) {
            }
            bubble = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
