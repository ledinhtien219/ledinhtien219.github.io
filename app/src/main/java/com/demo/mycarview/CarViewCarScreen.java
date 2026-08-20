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
 * CarView Drive dashboard for Android Auto.
 *
 * The car host owns template chrome. CarView renders the map/background surface and
 * a compact road-alert board inspired by the user's VIETMAP LIVE reference:
 * current limit, GPS speed, upcoming camera + distance, next limit + distance.
 *
 * Waze consumer app does not expose a public limit/camera feed, so Waze mode uses
 * phone GPS for current speed and leaves unsupported road-alert fields unknown.
 * VIETMAP road-alert fields are read from the shared CarView data store, ready for
 * a documented/authorized VIETMAP feed to populate them.
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
    private String nextSpeedLimit;
    private int cameraDistanceM;
    private int nextLimitDistanceM;
    private double latitude = Double.NaN;
    private double longitude = Double.NaN;

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            if (!running) return;
            readPrefs();
            invalidate();
            renderer.draw();
            handler.postDelayed(this, 1000L);
        }
    };

    public CarViewCarScreen(@NonNull CarContext carContext) {
        super(carContext);
        prefs = carContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        readPrefs();

        try {
            carContext.getCarService(AppManager.class).setSurfaceCallback(renderer);
        } catch (Throwable ignored) {
            // Older hosts can still use the PaneTemplate fallback.
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

        Row source = new Row.Builder()
                .setTitle(displaySource())
                .addText("Tốc độ " + speedKmh + " km/h · Giới hạn " + displayLimitText())
                .build();

        Row alerts = new Row.Builder()
                .setTitle("Cảnh báo phía trước")
                .addText(displayCameraText() + " · " + displayNextLimitText())
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
                .addRow(source)
                .addRow(alerts)
                .addAction(refresh)
                .build();

        PaneTemplate content = new PaneTemplate.Builder(pane)
                .setHeaderAction(Action.APP_ICON)
                .setTitle("CarView Road HUD")
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
        speedLimit = normalizeLimit(prefs.getString("limit", "--"));
        nextSpeedLimit = normalizeLimit(prefs.getString("next_limit", "--"));
        cameraDistanceM = prefs.getInt("camera_distance_m", -1);
        nextLimitDistanceM = prefs.getInt("next_limit_distance_m", -1);

        try {
            speedKmh = Integer.parseInt(prefs.getString("speed", "0"));
        } catch (NumberFormatException ignored) {
            speedKmh = 0;
        }
        speedKmh = Math.max(0, Math.min(300, speedKmh));

        // Do not invent Waze road-alert data. Only current GPS speed is available.
        if ("waze".equals(speedSource)) {
            speedLimit = "--";
            nextSpeedLimit = "--";
            cameraDistanceM = -1;
            nextLimitDistanceM = -1;
        }
    }

    private String normalizeLimit(String value) {
        if (value == null) return "--";
        value = value.trim();
        if (value.isEmpty()) return "--";
        try {
            int n = Integer.parseInt(value);
            if (n <= 0 || n > 200) return "--";
            return String.valueOf(n);
        } catch (NumberFormatException ignored) {
            return "--";
        }
    }

    private String displayLimitText() {
        return "--".equals(speedLimit) ? "--" : speedLimit + " km/h";
    }

    private String displayCameraText() {
        return cameraDistanceM >= 0 ? "Camera " + formatDistance(cameraDistanceM) : "Camera --";
    }

    private String displayNextLimitText() {
        if ("--".equals(nextSpeedLimit)) return "Giới hạn tiếp --";
        return "Giới hạn " + nextSpeedLimit + " · " +
                (nextLimitDistanceM >= 0 ? formatDistance(nextLimitDistanceM) : "--");
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
        return km < 10f ? String.format(java.util.Locale.US, "%.1fkm", km) : Math.round(km) + "km";
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
            canvas.drawColor(0xFF0A0F14);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, w / 520f));
            paint.setColor(0xFF1C2933);
            for (int i = -2; i <= 8; i++) {
                float y = h * (i / 7f);
                canvas.drawLine(0, y, w, y + h * 0.23f, paint);
            }
            for (int i = -2; i <= 10; i++) {
                float x = w * (i / 9f);
                canvas.drawLine(x, 0, x - w * 0.18f, h, paint);
            }

            paint.setStrokeWidth(Math.max(5f, w / 190f));
            paint.setColor(0xFF354753);
            canvas.drawLine(w * 0.06f, h * 0.76f, w * 0.92f, h * 0.28f, paint);
            canvas.drawLine(w * 0.18f, h * 0.08f, w * 0.78f, h * 0.94f, paint);

            route.reset();
            route.moveTo(w * 0.18f, h * 0.72f);
            route.cubicTo(w * 0.35f, h * 0.68f, w * 0.34f, h * 0.48f, w * 0.49f, h * 0.50f);
            route.cubicTo(w * 0.64f, h * 0.52f, w * 0.65f, h * 0.31f, w * 0.84f, h * 0.27f);
            paint.setColor(0xFF3FA7FF);
            paint.setStrokeWidth(Math.max(8f, w / 120f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawPath(route, paint);

            float cx = w * 0.49f;
            float cy = h * 0.50f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, Math.max(13f, w / 70f), paint);
            paint.setColor(0xFF19A4F6);
            canvas.drawCircle(cx, cy, Math.max(8f, w / 110f), paint);

            drawRoadHud(canvas, w, h);
        }

        private void drawRoadHud(Canvas canvas, int w, int h) {
            float visibleLeft = visibleArea.isEmpty() ? w * 0.02f : Math.max(w * 0.02f, visibleArea.left + w * 0.01f);
            float visibleRight = visibleArea.isEmpty() ? w * 0.98f : Math.min(w * 0.98f, visibleArea.right - w * 0.01f);
            float top = visibleArea.isEmpty() ? h * 0.025f : Math.max(h * 0.025f, visibleArea.top + h * 0.015f);
            float panelW = Math.max(w * 0.50f, visibleRight - visibleLeft);
            float panelH = Math.min(h * 0.27f, Math.max(120f, h * 0.22f));
            float bottom = top + panelH;
            float radius = Math.max(20f, w / 65f);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xE9141A1F);
            canvas.drawRoundRect(visibleLeft, top, visibleLeft + panelW, bottom, radius, radius, paint);

            paint.setColor(0xFF25313B);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, w / 700f));
            canvas.drawRoundRect(visibleLeft + 2f, top + 2f, visibleLeft + panelW - 2f, bottom - 2f, radius, radius, paint);

            float headerH = panelH * 0.28f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFE8EDF2);
            paint.setTextSize(Math.max(16f, panelH * 0.14f));
            paint.setFakeBoldText(true);
            canvas.drawText(displaySource(), visibleLeft + panelW * 0.035f, top + headerH * 0.72f, paint);

            paint.setFakeBoldText(false);
            paint.setColor(0xFF93A4B2);
            paint.setTextSize(Math.max(11f, panelH * 0.09f));
            String gps = Double.isNaN(latitude) ? "GPS đang chờ" : "GPS trực tiếp";
            canvas.drawText(gps, visibleLeft + panelW * 0.035f, top + headerH * 1.12f, paint);

            float contentTop = top + headerH;
            float contentH = bottom - contentTop;
            float colW = panelW / 4f;
            for (int i = 1; i < 4; i++) {
                float x = visibleLeft + colW * i;
                paint.setColor(0xFF232B32);
                paint.setStrokeWidth(1.5f);
                canvas.drawLine(x, contentTop + contentH * 0.12f, x, bottom - contentH * 0.12f, paint);
            }

            float circleY = contentTop + contentH * 0.46f;
            float circleR = Math.min(colW * 0.22f, contentH * 0.30f);

            drawLimitTile(canvas, visibleLeft + colW * 0.5f, circleY, circleR, speedLimit, null);
            drawSpeedTile(canvas, visibleLeft + colW * 1.5f, circleY, circleR, speedKmh);
            drawCameraTile(canvas, visibleLeft + colW * 2.5f, circleY, circleR, cameraDistanceM);
            drawLimitTile(canvas, visibleLeft + colW * 3.5f, circleY, circleR, nextSpeedLimit,
                    nextLimitDistanceM >= 0 ? formatDistance(nextLimitDistanceM) : "--");
        }

        private void drawLimitTile(Canvas canvas, float cx, float cy, float r, String limit, @Nullable String distance) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFF7F7F7);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(5f, r * 0.16f));
            paint.setColor(0xFFFF3030);
            canvas.drawCircle(cx, cy, r * 0.92f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xFF121212);
            paint.setFakeBoldText(true);
            paint.setTextSize(Math.max(18f, r * 0.78f));
            canvas.drawText(limit == null ? "--" : limit, cx, cy + r * 0.27f, paint);
            paint.setFakeBoldText(false);

            if (distance != null) {
                paint.setColor(0xFFE3E7EA);
                paint.setTextSize(Math.max(11f, r * 0.42f));
                canvas.drawText(distance, cx, cy + r * 1.55f, paint);
            }
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawSpeedTile(Canvas canvas, float cx, float cy, float r, int speed) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFAFAFA);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(4f, r * 0.12f));
            paint.setColor(0xFF2D78FF);
            canvas.drawCircle(cx, cy, r * 0.94f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xFF101820);
            paint.setFakeBoldText(true);
            paint.setTextSize(Math.max(18f, r * 0.78f));
            canvas.drawText(String.valueOf(speed), cx, cy + r * 0.12f, paint);
            paint.setFakeBoldText(false);
            paint.setTextSize(Math.max(10f, r * 0.30f));
            canvas.drawText("km/h", cx, cy + r * 0.55f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawCameraTile(Canvas canvas, float cx, float cy, float r, int distanceM) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFF7F7F7);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(5f, r * 0.16f));
            paint.setColor(0xFFFF3030);
            canvas.drawCircle(cx, cy, r * 0.92f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF111111);
            float bodyW = r * 0.95f;
            float bodyH = r * 0.46f;
            canvas.drawRoundRect(cx - bodyW * 0.48f, cy - bodyH * 0.45f,
                    cx + bodyW * 0.32f, cy + bodyH * 0.45f, r * 0.10f, r * 0.10f, paint);
            Path lens = new Path();
            lens.moveTo(cx + bodyW * 0.25f, cy - bodyH * 0.30f);
            lens.lineTo(cx + bodyW * 0.55f, cy - bodyH * 0.62f);
            lens.lineTo(cx + bodyW * 0.55f, cy + bodyH * 0.62f);
            lens.lineTo(cx + bodyW * 0.25f, cy + bodyH * 0.30f);
            lens.close();
            canvas.drawPath(lens, paint);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xFFE3E7EA);
            paint.setTextSize(Math.max(11f, r * 0.42f));
            canvas.drawText(distanceM >= 0 ? formatDistance(distanceM) : "--", cx, cy + r * 1.55f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }
}
