package com.demo.mycarview;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.MapWithContentTemplate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.Map;

/**
 * Stable Android Auto Road HUD.
 *
 * Dynamic speed/road data is rendered on the app-owned map surface. The Android
 * Auto template itself stays mostly static so the host is not rebuilt every second.
 * Surface access is serialized to avoid drawing while the host destroys/replaces it.
 */
public class CarViewCarScreen extends Screen {
    private static final String PREFS = "carview_settings";

    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MapSurfaceRenderer renderer = new MapSurfaceRenderer();

    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean running;

    private volatile int speedKmh;
    private volatile String speedSource = "waze";
    private volatile String speedLimit = "--";
    private volatile String nextSpeedLimit = "--";
    private volatile int cameraDistanceM = -1;
    private volatile int nextLimitDistanceM = -1;
    private volatile double latitude = Double.NaN;

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            if (!running) return;
            readPrefsCompat();
            renderer.scheduleDraw();
            main.postDelayed(this, 1000L);
        }
    };

    public CarViewCarScreen(@NonNull CarContext carContext) {
        super(carContext);
        prefs = carContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        readPrefsCompat();

        try {
            carContext.getCarService(AppManager.class).setSurfaceCallback(renderer);
        } catch (Throwable ignored) {
            // A host without a drawable map surface can still show the Pane fallback.
        }

        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(@NonNull LifecycleOwner owner) {
                running = true;
                startLocationUpdates();
                main.removeCallbacks(refreshRunnable);
                main.post(refreshRunnable);
            }

            @Override public void onStop(@NonNull LifecycleOwner owner) {
                running = false;
                main.removeCallbacks(refreshRunnable);
                stopLocationUpdates();
            }

            @Override public void onDestroy(@NonNull LifecycleOwner owner) {
                running = false;
                main.removeCallbacks(refreshRunnable);
                stopLocationUpdates();
                try {
                    getCarContext().getCarService(AppManager.class).setSurfaceCallback(null);
                } catch (Throwable ignored) {
                }
                renderer.release();
            }
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        readPrefsCompat();
        PaneTemplate content = buildSafePane();

        if (getCarContext().getCarAppApiLevel() >= 7) {
            try {
                return new MapWithContentTemplate.Builder()
                        .setContentTemplate(content)
                        .build();
            } catch (Throwable ignored) {
                // Some OEM hosts are stricter than DHU; fall back instead of disconnecting.
            }
        }
        return content;
    }

    private PaneTemplate buildSafePane() {
        Row status = new Row.Builder()
                .setTitle(displaySource())
                .addText("Road HUD · dữ liệu cập nhật trên bảng phía trên")
                .build();

        Row detail = new Row.Builder()
                .setTitle("Tốc độ " + speedKmh + " km/h")
                .addText("Giới hạn " + displayLimitText() + " · " + displayCameraText())
                .build();

        Pane pane = new Pane.Builder()
                .addRow(status)
                .addRow(detail)
                .build();

        return new PaneTemplate.Builder(pane)
                .setHeaderAction(Action.APP_ICON)
                .setTitle("CarView Road HUD")
                .build();
    }

    /** Reads legacy String/Int preference values without ClassCastException. */
    private void readPrefsCompat() {
        Map<String, ?> all;
        try {
            all = prefs.getAll();
        } catch (Throwable ignored) {
            return;
        }

        speedSource = valueAsString(all.get("speed_source"), "waze").toLowerCase();
        speedLimit = normalizeLimit(valueAsString(all.get("limit"), "--"));
        nextSpeedLimit = normalizeLimit(valueAsString(all.get("next_limit"), "--"));
        cameraDistanceM = valueAsInt(all.get("camera_distance_m"), -1);
        nextLimitDistanceM = valueAsInt(all.get("next_limit_distance_m"), -1);
        speedKmh = clamp(valueAsInt(all.get("speed"), speedKmh), 0, 300);

        if ("waze".equals(speedSource)) {
            // Waze consumer app has no public camera/limit feed; never fabricate it.
            speedLimit = "--";
            nextSpeedLimit = "--";
            cameraDistanceM = -1;
            nextLimitDistanceM = -1;
        }
    }

    private String valueAsString(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private int valueAsInt(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt(((String) value).trim()); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String normalizeLimit(String value) {
        try {
            int n = Integer.parseInt(value.trim());
            return (n > 0 && n <= 200) ? String.valueOf(n) : "--";
        } catch (Throwable ignored) {
            return "--";
        }
    }

    private String displayLimitText() {
        return "--".equals(speedLimit) ? "--" : speedLimit + " km/h";
    }

    private String displayCameraText() {
        return cameraDistanceM >= 0 ? "Camera " + formatDistance(cameraDistanceM) : "Camera --";
    }

    private String displaySource() {
        if ("waze".equals(speedSource)) return "WAZE · GPS";
        if ("vietmap".equals(speedSource)) return "VIETMAP LIVE";
        if ("wyn".equals(speedSource)) return "WYN";
        return speedSource == null ? "GPS" : speedSource.toUpperCase();
    }

    private String formatDistance(int meters) {
        if (meters < 0) return "--";
        if (meters < 1000) return meters + "m";
        float km = meters / 1000f;
        return km < 10f
                ? String.format(java.util.Locale.US, "%.1fkm", km)
                : Math.round(km) + "km";
    }

    private void startLocationUpdates() {
        if (getCarContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                getCarContext().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (locationManager == null) {
            locationManager = (LocationManager) getCarContext().getSystemService(Context.LOCATION_SERVICE);
        }
        if (locationManager == null || locationListener != null) return;

        locationListener = new LocationListener() {
            @Override public void onLocationChanged(@NonNull Location location) {
                latitude = location.getLatitude();
                if (location.hasSpeed()) {
                    speedKmh = clamp(Math.round(location.getSpeed() * 3.6f), 0, 300);
                    prefs.edit().putString("speed", String.valueOf(speedKmh)).apply();
                }
                renderer.scheduleDraw();
            }

            @Override public void onProviderDisabled(@NonNull String provider) {}
            @Override public void onProviderEnabled(@NonNull String provider) {}
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        };

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener);
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) locationListener.onLocationChanged(last);
        } catch (Throwable ignored) {
            locationListener = null;
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); }
            catch (Throwable ignored) {}
        }
        locationListener = null;
    }

    private final class MapSurfaceRenderer implements SurfaceCallback {
        private final Object surfaceLock = new Object();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path cameraShape = new Path();

        @Nullable private Surface surface;
        private int width;
        private int height;
        private Rect visibleArea = new Rect();
        private boolean drawQueued;

        @Override public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
            synchronized (surfaceLock) {
                Surface next = container.getSurface();
                if (surface != null && surface != next) {
                    try { surface.release(); } catch (Throwable ignored) {}
                }
                surface = next;
                width = Math.max(0, container.getWidth());
                height = Math.max(0, container.getHeight());
            }
            scheduleDraw();
        }

        @Override public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
            release();
        }

        @Override public void onVisibleAreaChanged(@NonNull Rect area) {
            synchronized (surfaceLock) {
                visibleArea = new Rect(area);
            }
            scheduleDraw();
        }

        @Override public void onStableAreaChanged(@NonNull Rect stableArea) {
            scheduleDraw();
        }

        void scheduleDraw() {
            synchronized (surfaceLock) {
                if (drawQueued) return;
                drawQueued = true;
            }
            main.post(() -> {
                synchronized (surfaceLock) { drawQueued = false; }
                drawNow();
            });
        }

        void release() {
            synchronized (surfaceLock) {
                if (surface != null) {
                    try { surface.release(); } catch (Throwable ignored) {}
                    surface = null;
                }
                width = 0;
                height = 0;
            }
        }

        private void drawNow() {
            synchronized (surfaceLock) {
                Surface target = surface;
                if (target == null || !target.isValid() || width <= 0 || height <= 0) return;

                Canvas canvas = null;
                try {
                    canvas = target.lockCanvas(null);
                    drawBackground(canvas, width, height);
                    drawRoadHud(canvas, width, height, visibleArea);
                } catch (Throwable ignored) {
                    // Do not let an OEM Surface lifecycle race disconnect the car session.
                } finally {
                    if (canvas != null) {
                        try { target.unlockCanvasAndPost(canvas); } catch (Throwable ignored) {}
                    }
                }
            }
        }

        private void drawBackground(Canvas canvas, int w, int h) {
            canvas.drawColor(0xFF080C10);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, w / 500f));
            paint.setColor(0xFF1A2730);
            for (int i = 0; i < 8; i++) {
                float y = h * (0.18f + i * 0.11f);
                canvas.drawLine(0, y, w, y - h * 0.10f, paint);
            }
            paint.setStrokeWidth(Math.max(5f, w / 190f));
            paint.setColor(0xFF364854);
            canvas.drawLine(w * 0.05f, h * 0.78f, w * 0.95f, h * 0.30f, paint);
        }

        private void drawRoadHud(Canvas canvas, int w, int h, Rect area) {
            float left = area.isEmpty() ? w * 0.025f : Math.max(w * 0.025f, area.left + w * 0.012f);
            float right = area.isEmpty() ? w * 0.975f : Math.min(w * 0.975f, area.right - w * 0.012f);
            if (right <= left) { left = w * 0.025f; right = w * 0.975f; }

            float top = area.isEmpty() ? h * 0.035f : Math.max(h * 0.035f, area.top + h * 0.02f);
            float panelW = Math.max(w * 0.60f, right - left);
            panelW = Math.min(panelW, w - left - w * 0.02f);
            float panelH = Math.max(110f, Math.min(h * 0.30f, h - top - 20f));
            float bottom = top + panelH;
            float radius = Math.max(18f, w / 70f);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xEE11171D);
            canvas.drawRoundRect(left, top, left + panelW, bottom, radius, radius, paint);

            paint.setColor(0xFFE9EEF2);
            paint.setFakeBoldText(true);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(Math.max(15f, panelH * 0.14f));
            canvas.drawText(displaySource(), left + panelW * 0.03f, top + panelH * 0.19f, paint);
            paint.setFakeBoldText(false);

            float contentTop = top + panelH * 0.28f;
            float contentH = panelH * 0.62f;
            float colW = panelW / 4f;
            float cy = contentTop + contentH * 0.42f;
            float r = Math.min(colW * 0.22f, contentH * 0.34f);

            for (int i = 1; i < 4; i++) {
                float x = left + colW * i;
                paint.setColor(0xFF2A333A);
                paint.setStrokeWidth(1.5f);
                canvas.drawLine(x, contentTop, x, bottom - panelH * 0.08f, paint);
            }

            drawLimit(canvas, left + colW * 0.5f, cy, r, speedLimit, null);
            drawSpeed(canvas, left + colW * 1.5f, cy, r, speedKmh);
            drawCamera(canvas, left + colW * 2.5f, cy, r, cameraDistanceM);
            drawLimit(canvas, left + colW * 3.5f, cy, r, nextSpeedLimit,
                    nextLimitDistanceM >= 0 ? formatDistance(nextLimitDistanceM) : "--");

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setFakeBoldText(false);
        }

        private void drawLimit(Canvas canvas, float cx, float cy, float r, String limit, @Nullable String distance) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFF7F7F7);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(4f, r * 0.15f));
            paint.setColor(0xFFFF3030);
            canvas.drawCircle(cx, cy, r * 0.91f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xFF121212);
            paint.setFakeBoldText(true);
            paint.setTextSize(Math.max(16f, r * 0.76f));
            canvas.drawText(limit == null ? "--" : limit, cx, cy + r * 0.27f, paint);
            paint.setFakeBoldText(false);

            if (distance != null) {
                paint.setColor(0xFFE5E9EC);
                paint.setTextSize(Math.max(10f, r * 0.40f));
                canvas.drawText(distance, cx, cy + r * 1.50f, paint);
            }
        }

        private void drawSpeed(Canvas canvas, float cx, float cy, float r, int speed) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFF8F8F8);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(4f, r * 0.11f));
            paint.setColor(0xFF2878FF);
            canvas.drawCircle(cx, cy, r * 0.93f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xFF101820);
            paint.setFakeBoldText(true);
            paint.setTextSize(Math.max(16f, r * 0.74f));
            canvas.drawText(String.valueOf(speed), cx, cy + r * 0.10f, paint);
            paint.setFakeBoldText(false);
            paint.setTextSize(Math.max(9f, r * 0.28f));
            canvas.drawText("km/h", cx, cy + r * 0.52f, paint);
        }

        private void drawCamera(Canvas canvas, float cx, float cy, float r, int distanceM) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFF7F7F7);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(4f, r * 0.15f));
            paint.setColor(0xFFFF3030);
            canvas.drawCircle(cx, cy, r * 0.91f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF111111);
            float bw = r * 0.90f;
            float bh = r * 0.42f;
            canvas.drawRoundRect(cx - bw * 0.46f, cy - bh * 0.45f,
                    cx + bw * 0.28f, cy + bh * 0.45f, r * 0.08f, r * 0.08f, paint);
            cameraShape.reset();
            cameraShape.moveTo(cx + bw * 0.23f, cy - bh * 0.28f);
            cameraShape.lineTo(cx + bw * 0.53f, cy - bh * 0.58f);
            cameraShape.lineTo(cx + bw * 0.53f, cy + bh * 0.58f);
            cameraShape.lineTo(cx + bw * 0.23f, cy + bh * 0.28f);
            cameraShape.close();
            canvas.drawPath(cameraShape, paint);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xFFE5E9EC);
            paint.setTextSize(Math.max(10f, r * 0.40f));
            canvas.drawText(distanceM >= 0 ? formatDistance(distanceM) : "--",
                    cx, cy + r * 1.50f, paint);
        }
    }
}
