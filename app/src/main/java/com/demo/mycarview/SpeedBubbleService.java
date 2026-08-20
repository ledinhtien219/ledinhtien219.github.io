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
        String limit = prefs.getString("limit", "80");
        String speed = prefs.getString("speed", "0");
        int scale = prefs.getInt("bubble_scale", 100);
        float factor = Math.max(.6f, Math.min(2.2f, scale / 100f));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        box.setBackground(Ui.rounded(0xEC161D27, 0xFFFF5252, 40, this));

        sourceView = Ui.text(this, sourceLabel(), 10 * factor, Ui.MUTED, true);
        sourceView.setGravity(Gravity.CENTER);
        box.addView(sourceView, new LinearLayout.LayoutParams(-1, 0, 1));

        limitView = Ui.text(this, displayLimit(limit), 23 * factor, 0xFFFFFFFF, true);
        limitView.setGravity(Gravity.CENTER);
        box.addView(limitView, new LinearLayout.LayoutParams(-1, 0, 2));

        speedView = Ui.text(this, speed + " km/h", 12 * factor, Ui.ACCENT, true);
        speedView.setGravity(Gravity.CENTER);
        box.addView(speedView, new LinearLayout.LayoutParams(-1, 0, 1));
        bubble = box;

        int size = Ui.dp(this, 86 * factor);
        params = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs.getInt("bubble_x", Ui.dp(this, 22));
        params.y = prefs.getInt("bubble_y", Ui.dp(this, 130));

        box.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = startX + (int) (event.getRawX() - downX);
                        params.y = startY + (int) (event.getRawY() - downY);
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
        if ("wyn".equals(source)) return "WYN";
        return "VIETMAP LIVE";
    }

    private String displayLimit(String savedLimit) {
        return "waze".equals(prefs.getString("speed_source", "vietmap")) ? "--" : savedLimit;
    }

    private void refreshSourceLabel() {
        if (sourceView != null) sourceView.setText(sourceLabel());
        if (limitView != null) limitView.setText(displayLimit(prefs.getString("limit", "80")));
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
            windowManager.removeView(bubble);
            bubble = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
