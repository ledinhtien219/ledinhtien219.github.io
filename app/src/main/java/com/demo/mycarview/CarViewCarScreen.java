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

/**
 * CarView Drive: a real Android Auto templated screen.
 *
 * Android Auto owns the template chrome. CarView owns the background map surface
 * and renders a lightweight road/route view there. Speed data comes from phone
 * GPS when location permission is already granted, while limit/source values are
 * read from the same SharedPreferences used by the phone UI/bubble.
 */
public class CarViewCarScreen extends Screen {
    private static final String PREFS = "carview_settings";

    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MapSurfaceRenderer renderer = new MapSurfaceRenderer();

    private LocationManager locationManager;
    private LocationListener locationListener;
    private boolean running;

    private int speedKmh;
    private String speedSource;
    private String speedLimit;
    private double latitude = Double.NaN;
    private double longitude = Double.NaN;

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            if (!running) return;
            readPrefs();
            invalidate();
            renderer.draw();
            handler.postDelayed(this, 1500L);
        }
    };

    public CarViewCarScreen(@NonNull CarContext carContext) {
        super(carContext);
        prefs = carContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        readPrefs();

        try {
            carContext.getCarService(AppManager.class).setSurfaceCallback(renderer);
        } catch (Throwable ignored) {
            // Older hosts can still use the non-map PaneTemplate fallback.
        }

        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(@NonNull LifecycleOwner owner) {
                running = true;
                startLocationUpdates();
                handler.removeCallbacks(refreshRunnable);
                handler.post(refreshRunnable);
            }

            @Override public void onStop(@NonNull LifecycleOwner owner) {
                running = false;
                handler.removeCallbacks(refreshRunnable);
                stopLocationUpdates();
            }

            @Override public void onDestroy(@NonNull LifecycleOwner owner) {
                running = false;
                handler.removeCallbacksAndMessages(null);
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
        readPrefs();

        Row speed = new Row.Builder()
                .setTitle("Tốc độ hiện tại")
                .addText(speedKmh + " km/h")
                .build();

        Row limit = new Row.Builder()
                .setTitle("Giới hạn tốc độ")
                .addText(displayLimit())
                .build();

        Row source = new Row.Builder()
                .setTitle("Nguồn dữ liệu")
                .addText(displaySource())
                .build();

        Action refresh = new Action.Builder()
                .setTitle("Làm mới")
                .setOnClickListener(() -> {
                    readPrefs();
                    invalidate();
                    renderer.draw();
                })
                .build();

        Pane pane = new Pane.Builder()
                .addRow(speed)
                .addRow(limit)
                .addRow(source)
                .addAction(refresh)
                .build();

        PaneTemplate content = new PaneTemplate.Builder(pane)
                .setHeaderAction(Action.APP_ICON)
                .setTitle("CarView Drive")
                .build();

        if (getCarContext().getCarAppApiLevel() >= 7) {
            return new MapWithContentTemplate.Builder()
                    .setContentTemplate(content)
                    .build();
        }

        return content;
    }

    private void readPrefs() {
        speedSource = prefs.getString("speed_source", "waze");
        speedLimit = prefs.getString("limit", "--");
        if (!"waze".equals(speedSource)) {
            try {
                speedKmh = Integer.parseInt(prefs.getString("speed", "0"));
            } catch (NumberFormatException ignored) {
                speedKmh = 0;
            }
        }
    }

    private String displayLimit() {
        if ("waze".equals(speedSource)) return "--";
        if (speedLimit == null || speedLimit.trim().isEmpty()) return "--";
        return speedLimit + " km/h";
    }

    private String displaySource() {
        if ("waze".equals(speedSource)) return "WAZE · GPS điện thoại";
        if ("vietmap".equals(speedSource)) return "VIETMAP · dữ liệu cấu hình";
        if ("wyn".equals(speedSource)) return "WYN · dữ liệu cấu hình";
        return speedSource == null ? "GPS" : speedSource;
    }

    private void startLocationUpdates() {
        if (!"waze".equals(speedSource)) return;
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
                longitude = location.getLongitude();
                if (location.hasSpeed()) {
                    speedKmh = Math.max(0, Math.min(300, Math.round(location.getSpeed() * 3.6f)));
                    prefs.edit().putString("speed", String.valueOf(speedKmh)).apply();
                }
                invalidate();
                renderer.draw();
            }

            @Override public void onProviderDisabled(@NonNull String provider) {}
            @Override public void onProviderEnabled(@NonNull String provider) {}
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        };

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener);
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) locationListener.onLocationChanged(last);
        } catch (SecurityException ignored) {
            locationListener = null;
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            }
        }
        locationListener = null;
    }

    private final class MapSurfaceRenderer implements SurfaceCallback {
        @Nullable private Surface surface;
        private int width;
        private int height;
        private Rect visibleArea = new Rect();

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path route = new Path();

        @Override public void onSurfaceAvailable(@NonNull SurfaceContainer surfaceContainer) {
            Surface next = surfaceContainer.getSurface();
            if (surface != null && surface != next) {
                try { surface.release(); } catch (Throwable ignored) {}
            }
            surface = next;
            width = surfaceContainer.getWidth();
            height = surfaceContainer.getHeight();
            draw();
        }

        @Override public void onSurfaceDestroyed(@NonNull SurfaceContainer surfaceContainer) {
            release();
        }

        @Override public void onVisibleAreaChanged(@NonNull Rect area) {
            visibleArea = new Rect(area);
            draw();
        }

        @Override public void onStableAreaChanged(@NonNull Rect stableArea) {
            draw();
        }

        void release() {
            if (surface != null) {
                try { surface.release(); } catch (Throwable ignored) {}
                surface = null;
            }
        }

        void draw() {
            Surface target = surface;
            if (target == null || !target.isValid() || width <= 0 || height <= 0) return;

            Canvas canvas = null;
            try {
                canvas = target.lockCanvas(null);
                drawMap(canvas, width, height);
            } catch (Throwable ignored) {
            } finally {
                if (canvas != null) {
                    try { target.unlockCanvasAndPost(canvas); } catch (Throwable ignored) {}
                }
            }
        }

        private void drawMap(Canvas canvas, int w, int h) {
            canvas.drawColor(0xFF111820);

            // Secondary road network.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, w / 520f));
            paint.setColor(0xFF263543);
            for (int i = -2; i <= 8; i++) {
                float y = h * (i / 7f);
                canvas.drawLine(0, y, w, y + h * 0.23f, paint);
            }
            for (int i = -2; i <= 10; i++) {
                float x = w * (i / 9f);
                canvas.drawLine(x, 0, x - w * 0.18f, h, paint);
            }

            // Main roads.
            paint.setStrokeWidth(Math.max(5f, w / 190f));
            paint.setColor(0xFF415363);
            canvas.drawLine(w * 0.06f, h * 0.76f, w * 0.92f, h * 0.28f, paint);
            canvas.drawLine(w * 0.18f, h * 0.08f, w * 0.78f, h * 0.94f, paint);

            // Active route.
            route.reset();
            route.moveTo(w * 0.18f, h * 0.72f);
            route.cubicTo(w * 0.35f, h * 0.68f, w * 0.34f, h * 0.48f, w * 0.49f, h * 0.50f);
            route.cubicTo(w * 0.64f, h * 0.52f, w * 0.65f, h * 0.31f, w * 0.84f, h * 0.27f);
            paint.setColor(0xFF59B7FF);
            paint.setStrokeWidth(Math.max(8f, w / 120f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawPath(route, paint);

            // Current position marker.
            float cx = w * 0.49f;
            float cy = h * 0.50f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, Math.max(13f, w / 70f), paint);
            paint.setColor(0xFF19A4F6);
            canvas.drawCircle(cx, cy, Math.max(8f, w / 110f), paint);

            // Small status card kept near the lower-left stable region.
            float left = visibleArea.isEmpty() ? w * 0.04f : Math.max(w * 0.03f, visibleArea.left + w * 0.015f);
            float bottom = visibleArea.isEmpty() ? h * 0.94f : Math.min(h * 0.96f, visibleArea.bottom - h * 0.03f);
            paint.setColor(0xD91A222C);
            canvas.drawRoundRect(left, bottom - h * 0.19f, left + w * 0.22f, bottom, 28f, 28f, paint);

            paint.setColor(0xFFFFFFFF);
            paint.setTextSize(Math.max(28f, w / 24f));
            paint.setFakeBoldText(true);
            canvas.drawText(speedKmh + " km/h", left + w * 0.025f, bottom - h * 0.095f, paint);

            paint.setColor(0xFF9FB2C3);
            paint.setTextSize(Math.max(16f, w / 45f));
            paint.setFakeBoldText(false);
            String gps = Double.isNaN(latitude) ? "GPS đang chờ" : "GPS đã khóa vị trí";
            canvas.drawText(gps, left + w * 0.025f, bottom - h * 0.035f, paint);
        }
    }
}
