package com.demo.mycarview;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.GridItem;
import androidx.car.app.model.GridTemplate;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Template;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

/**
 * Compact Android Auto dashboard for CarHUD.
 *
 * Android Auto owns the actual template rendering, so this deliberately uses a
 * GridTemplate rather than a system overlay. It keeps the useful VIETMAP-style
 * 4-cell information layout inside CarHUD's own Android Auto screen.
 */
public class CarViewCarScreen extends Screen {
    private static final String PREFS = "carview_settings";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean updating;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!updating) return;
            try { invalidate(); } catch (Throwable ignored) {}
            handler.postDelayed(this, 1000L);
        }
    };

    public CarViewCarScreen(@NonNull CarContext carContext) {
        super(carContext);
        getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (event == Lifecycle.Event.ON_START) {
                updating = true;
                handler.removeCallbacks(refresh);
                handler.post(refresh);
            } else if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                updating = false;
                handler.removeCallbacks(refresh);
            }
        });
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

        ItemList list = new ItemList.Builder()
                .addItem(tile(hasLocation ? speed : "--", "km/h", "TỐC ĐỘ"))
                .addItem(tile(limit, "km/h", "GIỚI HẠN"))
                .addItem(tile(nextLimit, formatDistance(nextDistance), "GIỚI HẠN KẾ"))
                .addItem(tile("CAM", formatDistance(camera), "CAMERA"))
                .build();

        return new GridTemplate.Builder()
                .setTitle("CarHUD · " + sourceLabel(source))
                .setHeaderAction(Action.APP_ICON)
                .setSingleList(list)
                .build();
    }

    private static GridItem tile(String title, String text, String label) {
        String main = (title == null || title.trim().isEmpty()) ? "--" : title.trim();
        String detail = (text == null || text.trim().isEmpty()) ? "--" : text.trim();
        return new GridItem.Builder()
                .setTitle(main)
                .setText(label + " · " + detail)
                .setOnClickListener(() -> {})
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
        if (meters < 1000) return meters + "m";
        return String.format(java.util.Locale.US, "%.1fkm", meters / 1000f);
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
