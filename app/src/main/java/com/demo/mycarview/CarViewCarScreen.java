package com.demo.mycarview;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;

/**
 * Maximum-compatibility Android Auto screen for CarHUD.
 *
 * Some OEM/AA hosts reject GridTemplate or multi-row PaneTemplate for this POI
 * entry and display the generic "unexpected error" screen. MessageTemplate is
 * the same conservative template family that previously opened successfully on
 * this host, so this screen deliberately avoids lists, grids, surface callbacks
 * and periodic invalidation.
 */
public class CarViewCarScreen extends Screen {
    private static final String PREFS = "carview_settings";

    public CarViewCarScreen(@NonNull CarContext carContext) {
        super(carContext);
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        SharedPreferences prefs = getCarContext().getSharedPreferences(PREFS, CarContext.MODE_PRIVATE);

        String speed = readString(prefs, "speed", "0");
        String limit = normalizeLimit(readString(prefs, "limit", "--"));
        int camera = readInt(prefs, "camera_distance_m", -1);
        String nextLimit = normalizeLimit(readString(prefs, "next_limit", "--"));
        int nextDistance = readInt(prefs, "next_limit_distance_m", -1);
        String source = readString(prefs, "speed_source", "vietmap");

        boolean hasLocation = getCarContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || getCarContext().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        String message =
                "Tốc độ: " + (hasLocation ? speed : "--") + " km/h"
                + "\nGiới hạn: " + limit + " km/h"
                + "\nGiới hạn kế: " + nextLimit + " km/h · " + formatDistance(nextDistance)
                + "\nCamera: " + formatDistance(camera)
                + "\nNguồn: " + sourceLabel(source);

        return new MessageTemplate.Builder(message)
                .setTitle("CarHUD")
                .setHeaderAction(Action.APP_ICON)
                .build();
    }

    private static String sourceLabel(String source) {
        if ("waze".equals(source)) return "WAZE + GPS";
        if ("wyn".equals(source)) return "WYN + GPS";
        return "VIETMAP + GPS";
    }

    private static String normalizeLimit(String value) {
        try {
            int n = Integer.parseInt(value.trim());
            return n > 0 && n <= 200 ? String.valueOf(n) : "--";
        } catch (Throwable ignored) {
            return "--";
        }
    }

    private static String formatDistance(int meters) {
        if (meters < 0) return "--";
        if (meters < 1000) return meters + " m";
        return String.format(java.util.Locale.US, "%.1f km", meters / 1000f);
    }

    private static String readString(SharedPreferences prefs, String key, String fallback) {
        try {
            Object v = prefs.getAll().get(key);
            if (v == null) return fallback;
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? fallback : s;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int readInt(SharedPreferences prefs, String key, int fallback) {
        try {
            Object v = prefs.getAll().get(key);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v != null) return Integer.parseInt(String.valueOf(v).trim());
        } catch (Throwable ignored) {}
        return fallback;
    }
}
