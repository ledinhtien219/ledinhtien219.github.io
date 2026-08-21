package com.demo.mycarview;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;

/**
 * Maximum-compatibility Android Auto root screen.
 *
 * Deliberately avoids headers, app-icon actions, lists, grids, map surfaces and
 * timer invalidation. Every API used here exists at Car App API level 1.
 */
public class CarViewCarScreen extends Screen {
    private static final String PREFS = "carview_settings";

    public CarViewCarScreen(@NonNull CarContext carContext) {
        super(carContext);
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        try {
            SharedPreferences p = getCarContext().getSharedPreferences(PREFS, CarContext.MODE_PRIVATE);
            String speed = readString(p, "speed", "--");
            String limit = normalizeLimit(readString(p, "limit", "--"));
            String next = normalizeLimit(readString(p, "next_limit", "--"));
            int nextMeters = readInt(p, "next_limit_distance_m", -1);
            int cameraMeters = readInt(p, "camera_distance_m", -1);

            String message = "Tốc độ  " + speed + " km/h"
                    + "\nGiới hạn  " + limit + " km/h"
                    + "\nGiới hạn kế  " + next + " km/h · " + distance(nextMeters)
                    + "\nCamera  " + distance(cameraMeters)
                    + "\n\nCarHUD · GPS";

            return new MessageTemplate.Builder(message).build();
        } catch (Throwable t) {
            try {
                getCarContext().getSharedPreferences("carhud_diag", CarContext.MODE_PRIVATE)
                        .edit().putString("last_car_screen_error", t.toString()).apply();
            } catch (Throwable ignored) {}
            return new MessageTemplate.Builder("CarHUD sẵn sàng").build();
        }
    }

    private static String readString(SharedPreferences p, String key, String fallback) {
        try {
            Object v = p.getAll().get(key);
            if (v == null) return fallback;
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? fallback : s;
        } catch (Throwable ignored) { return fallback; }
    }

    private static int readInt(SharedPreferences p, String key, int fallback) {
        try {
            Object v = p.getAll().get(key);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v != null) return Integer.parseInt(String.valueOf(v).trim());
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static String normalizeLimit(String value) {
        try {
            int n = Integer.parseInt(value.trim());
            return n > 0 && n <= 200 ? String.valueOf(n) : "--";
        } catch (Throwable ignored) { return "--"; }
    }

    private static String distance(int meters) {
        if (meters < 0) return "--";
        if (meters < 1000) return meters + " m";
        return String.format(java.util.Locale.US, "%.1f km", meters / 1000f);
    }
}
