package com.demo.mycarview;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;

/** Stable Android Auto screen for CarHUD. */
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
        String limit = readString(prefs, "limit", "--");
        int camera = readInt(prefs, "camera_distance_m", -1);
        String nextLimit = readString(prefs, "next_limit", "--");
        int nextDistance = readInt(prefs, "next_limit_distance_m", -1);
        String source = readString(prefs, "speed_source", "vietmap");

        boolean hasLocation = getCarContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || getCarContext().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        Pane.Builder pane = new Pane.Builder();
        pane.addRow(new Row.Builder()
                .setTitle((hasLocation ? speed : "--") + " km/h")
                .addText("Tốc độ GPS")
                .build());
        pane.addRow(new Row.Builder()
                .setTitle("Giới hạn: " + normalizeLimit(limit))
                .addText("Nguồn: " + sourceLabel(source))
                .build());
        pane.addRow(new Row.Builder()
                .setTitle("Camera: " + formatDistance(camera))
                .addText("Cảnh báo phía trước")
                .build());
        pane.addRow(new Row.Builder()
                .setTitle("Giới hạn kế: " + normalizeLimit(nextLimit))
                .addText("Sau " + formatDistance(nextDistance))
                .build());

        return new PaneTemplate.Builder(pane.build())
                .setTitle("CarHUD")
                .setHeaderAction(Action.APP_ICON)
                .build();
    }

    private static String sourceLabel(String source) {
        if ("waze".equals(source)) return "Waze + GPS";
        if ("wyn".equals(source)) return "Wyn + GPS";
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
