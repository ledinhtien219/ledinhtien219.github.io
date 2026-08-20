package com.demo.mycarview;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
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
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.Map;

/**
 * Android Auto HUD-only screen.
 *
 * The app owns the map surface and keeps only a compact speed card inside the
 * host-provided visible area. No large Pane/MapWithContent overlay is requested.
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

        // NavigationTemplate leaves the app-owned surface visible without the
        // large host-rendered Pane that previously covered most of the screen.
        Action gps = new Action.Builder()
                .setTitle("GPS")
                .setOnClickListener(() -> {
                    readPrefsCompat();
                    renderer.scheduleDraw();
                })
                .build();

        ActionStrip strip = new ActionStrip.Builder()
                .addAction(gps)
                .build();

        return new NavigationTemplate.Builder()
                .setActionStrip(strip)
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
        speedKmh = clamp(valueAsInt(all.get("speed"), speedKmh), 0, 300);
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

    private String displaySource() {
        if ("waze".equals(speedSource)) return "WAZE · GPS";
        if ("vietmap".equals(speedSource)) return "VIETMAP LIVE";
        if ("wyn".equals(speedSource)) return "WYN";
        return speedSource == null ? "GPS" : speedSource.toUpperCase();
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
                    drawSpeedHud(canvas, width, height, visibleArea);
                } catch (Throwable ignored) {
                    // Surface lifecycle races must never disconnect the car session.
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

            paint.setStrokeWidth(Math.max(7f, w / 140f));
            paint.setColor(0xFF3FA7FF);
            canvas.drawLine(w * 0.18f, h * 0.67f, w * 0.83f, h * 0.34f, paint);
        }

        private void drawSpeedHud(Canvas canvas, int w, int h, Rect area) {
            float safeLeft = area.isEmpty() ? 0f : area.left;
            float safeTop = area.isEmpty() ? 0f : area.top;
            float safeRight = area.isEmpty() ? w : area.right;
            float safeBottom = area.isEmpty() ? h : area.bottom;

            if (safeRight <= safeLeft || safeBottom <= safeTop) {
                safeLeft = 0f;
                safeTop = 0f;
                safeRight = w;
                safeBottom = h;
            }

            float cardW = Math.max(165f, Math.min(w * 0.18f, 245f));
            float cardH = Math.max(115f, Math.min(h * 0.27f, 170f));
            cardW = Math.min(cardW, Math.max(120f, safeRight - safeLeft - 16f));
            cardH = Math.min(cardH, Math.max(100f, safeBottom - safeTop - 16f));

            float right = safeRight - 10f;
            float left = Math.max(safeLeft + 8f, right - cardW);
            float top = safeTop + 8f;
            float bottom = top + cardH;
            float radius = Math.max(16f, cardH * 0.12f);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xE9141A1F);
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(0xFF29343D);
            canvas.drawRoundRect(left + 1f, top + 1f, right - 1f, bottom - 1f, radius, radius, paint);

            float padding = Math.max(10f, cardW * 0.07f);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setFakeBoldText(true);
            paint.setColor(0xFFE8EDF2);
            paint.setTextSize(Math.max(15f, cardH * 0.12f));
            canvas.drawText(displaySource(), left + padding, top + cardH * 0.20f, paint);

            paint.setFakeBoldText(false);
            paint.setColor(0xFF94A5B2);
            paint.setTextSize(Math.max(10f, cardH * 0.085f));
            canvas.drawText(Double.isNaN(latitude) ? "GPS đang chờ" : "GPS trực tiếp",
                    left + padding, top + cardH * 0.34f, paint);

            float cx = left + cardW * 0.50f;
            float cy = top + cardH * 0.69f;
            float r = Math.min(cardW * 0.21f, cardH * 0.25f);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFF8F8F8);
            canvas.drawCircle(cx, cy, r, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(4f, r * 0.12f));
            paint.setColor(0xFF2878FF);
            canvas.drawCircle(cx, cy, r * 0.93f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xFF101820);
            paint.setFakeBoldText(true);
            paint.setTextSize(Math.max(17f, r * 0.78f));
            String speedText = Double.isNaN(latitude) ? "--" : String.valueOf(speedKmh);
            canvas.drawText(speedText, cx, cy + r * 0.10f, paint);

            paint.setFakeBoldText(false);
            paint.setTextSize(Math.max(9f, r * 0.28f));
            canvas.drawText("km/h", cx, cy + r * 0.52f, paint);

            paint.setTextAlign(Paint.Align.LEFT);
        }
    }
}
