package com.demo.mycarview;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small connector for VIETMAP's documented Maps API.
 *
 * It deliberately does not inspect the VIETMAP LIVE app or private IPC. The
 * connector verifies the user's Service API key with Reverse v4, records the
 * current VIETMAP road name, and probes Route v4 while the vehicle is moving.
 * If a VIETMAP response exposes speed-limit/camera fields, the parser copies
 * those values into CarHUD's shared model; otherwise the HUD leaves them "--".
 */
public final class VietmapApiClient {
    private static final String PREFS = "carview_settings";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong LAST_UPDATE = new AtomicLong(0L);
    private static final long UPDATE_INTERVAL_MS = 20_000L;

    public interface Callback {
        void done(boolean ok, String message, String road);
    }

    private VietmapApiClient() {}

    public static void test(Context context, Location location, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            Result r = fetch(app, location, true);
            MAIN.post(() -> {
                if (callback != null) callback.done(r.ok, r.message, r.road);
            });
        });
    }

    public static void maybeUpdate(Context context, Location location) {
        if (context == null || location == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!"vietmap_api".equals(p.getString("speed_source", "gps"))) return;
        String key = p.getString("vietmap_api_key", "");
        if (key == null || key.trim().isEmpty()) return;

        long now = System.currentTimeMillis();
        long before = LAST_UPDATE.get();
        if (now - before < UPDATE_INTERVAL_MS) return;
        if (!LAST_UPDATE.compareAndSet(before, now)) return;

        Location copy = new Location(location);
        IO.execute(() -> fetch(app, copy, false));
    }

    private static Result fetch(Context context, Location location, boolean forceRouteProbe) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = prefs.getString("vietmap_api_key", "");
        if (key == null || key.trim().isEmpty()) {
            setStatus(prefs, "Chưa có API key", null);
            return new Result(false, "Chưa có VIETMAP API key", "");
        }

        try {
            Uri reverse = Uri.parse("https://maps.vietmap.vn/api/reverse/v4").buildUpon()
                    .appendQueryParameter("apikey", key.trim())
                    .appendQueryParameter("lat", String.format(Locale.US, "%.7f", location.getLatitude()))
                    .appendQueryParameter("lng", String.format(Locale.US, "%.7f", location.getLongitude()))
                    .appendQueryParameter("display_type", "2")
                    .build();

            String reverseBody = get(reverse.toString());
            Object reverseJson = new JSONTokener(reverseBody).nextValue();
            String road = firstNonEmpty(
                    findString(reverseJson, "street", 0),
                    findString(reverseJson, "name", 0),
                    findString(reverseJson, "display", 0));
            if (road == null) road = "";

            SharedPreferences.Editor edit = prefs.edit()
                    .putString("vietmap_status", "Đã kết nối")
                    .putString("vietmap_road", road)
                    .putLong("vietmap_last_update_ms", System.currentTimeMillis());

            // Some service plans can return additional navigation metadata. We
            // only consume clearly named fields when present; missing fields are
            // not invented.
            Integer reverseLimit = findIntByKeys(reverseJson, 0,
                    "speed_limit", "speedLimit", "max_speed", "maxSpeed", "speed_limit_kmh");
            if (isValidLimit(reverseLimit)) edit.putString("limit", String.valueOf(reverseLimit));
            edit.apply();

            boolean moving = location.hasBearing() || (location.hasSpeed() && location.getSpeed() > 1.0f);
            if (forceRouteProbe || moving) {
                probeRoute(prefs, key.trim(), location);
            }

            return new Result(true, "VIETMAP API đã kết nối", road);
        } catch (HttpError e) {
            String msg = "HTTP " + e.code;
            setStatus(prefs, msg, e.getMessage());
            return new Result(false, "VIETMAP lỗi " + msg, "");
        } catch (Throwable t) {
            String name = t.getClass().getSimpleName();
            setStatus(prefs, "Lỗi " + name, t.getMessage());
            return new Result(false, "Không kết nối được VIETMAP: " + name, "");
        }
    }

    private static void probeRoute(SharedPreferences prefs, String key, Location location) {
        try {
            float bearing = location.hasBearing() ? location.getBearing() : 0f;
            double[] ahead = project(location.getLatitude(), location.getLongitude(), bearing, 1200.0);
            Uri route = Uri.parse("https://maps.vietmap.vn/api/route/v4").buildUpon()
                    .appendQueryParameter("apikey", key)
                    .appendQueryParameter("point", String.format(Locale.US, "%.7f,%.7f", location.getLatitude(), location.getLongitude()))
                    .appendQueryParameter("point", String.format(Locale.US, "%.7f,%.7f", ahead[0], ahead[1]))
                    .appendQueryParameter("vehicle", "car")
                    .appendQueryParameter("heading", String.format(Locale.US, "%.0f", bearing))
                    .appendQueryParameter("points_encoded", "false")
                    .build();

            String body = get(route.toString());
            Object root = new JSONTokener(body).nextValue();
            String code = findString(root, "code", 0);
            if (code != null && !"OK".equalsIgnoreCase(code)) return;

            SharedPreferences.Editor e = prefs.edit();
            Integer limit = findIntByKeys(root, 0,
                    "speed_limit", "speedLimit", "max_speed", "maxSpeed", "speed_limit_kmh");
            Integer nextLimit = findIntByKeys(root, 0,
                    "next_speed_limit", "nextSpeedLimit", "next_limit", "nextLimit");
            Integer nextDistance = findIntByKeys(root, 0,
                    "next_speed_limit_distance", "nextLimitDistance", "next_limit_distance_m");
            Integer cameraDistance = findIntByKeys(root, 0,
                    "camera_distance", "cameraDistance", "distance_to_camera", "camera_distance_m");

            if (isValidLimit(limit)) e.putString("limit", String.valueOf(limit));
            if (isValidLimit(nextLimit)) e.putString("next_limit", String.valueOf(nextLimit));
            if (isValidDistance(nextDistance)) e.putInt("next_limit_distance_m", nextDistance);
            if (isValidDistance(cameraDistance)) e.putInt("camera_distance_m", cameraDistance);

            String street = firstInstructionStreet(root);
            if (street != null && !street.isEmpty()) e.putString("vietmap_route_road", street);
            e.putLong("vietmap_route_update_ms", System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
            // Reverse v4 connection remains valid even if a route probe is not
            // available for the current plan/location.
        }
    }

    private static String firstInstructionStreet(Object root) {
        try {
            if (!(root instanceof JSONObject)) return null;
            JSONArray paths = ((JSONObject) root).optJSONArray("paths");
            if (paths == null || paths.length() == 0) return null;
            JSONArray instructions = paths.optJSONObject(0).optJSONArray("instructions");
            if (instructions == null || instructions.length() == 0) return null;
            for (int i = 0; i < instructions.length(); i++) {
                JSONObject o = instructions.optJSONObject(i);
                if (o == null) continue;
                String s = o.optString("street_name", "").trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(7000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "CarHUD/0.8.1");
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = read(in);
        c.disconnect();
        if (code < 200 || code >= 300) throw new HttpError(code, body);
        return body;
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[2048];
            int n;
            while ((n = r.read(buf)) > 0 && sb.length() < 262144) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static void setStatus(SharedPreferences prefs, String status, String error) {
        SharedPreferences.Editor e = prefs.edit().putString("vietmap_status", status);
        if (error == null) e.remove("vietmap_last_error");
        else e.putString("vietmap_last_error", error.length() > 240 ? error.substring(0, 240) : error);
        e.apply();
    }

    private static String findString(Object node, String key, int depth) {
        if (node == null || depth > 6) return null;
        try {
            if (node instanceof JSONObject) {
                JSONObject o = (JSONObject) node;
                if (o.has(key)) {
                    String s = String.valueOf(o.opt(key)).trim();
                    if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
                }
                JSONArray names = o.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        Object child = o.opt(names.optString(i));
                        String found = findString(child, key, depth + 1);
                        if (found != null) return found;
                    }
                }
            } else if (node instanceof JSONArray) {
                JSONArray a = (JSONArray) node;
                for (int i = 0; i < a.length(); i++) {
                    String found = findString(a.opt(i), key, depth + 1);
                    if (found != null) return found;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Integer findIntByKeys(Object node, int depth, String... keys) {
        if (node == null || depth > 7) return null;
        try {
            if (node instanceof JSONObject) {
                JSONObject o = (JSONObject) node;
                for (String key : keys) {
                    if (!o.has(key)) continue;
                    Object v = o.opt(key);
                    Integer parsed = toInt(v);
                    if (parsed != null) return parsed;
                }
                JSONArray names = o.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        Integer found = findIntByKeys(o.opt(names.optString(i)), depth + 1, keys);
                        if (found != null) return found;
                    }
                }
            } else if (node instanceof JSONArray) {
                JSONArray a = (JSONArray) node;
                for (int i = 0; i < a.length(); i++) {
                    Integer found = findIntByKeys(a.opt(i), depth + 1, keys);
                    if (found != null) return found;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number) return (int) Math.round(((Number) value).doubleValue());
        if (value != null) {
            try {
                String s = String.valueOf(value).replaceAll("[^0-9.-]", "");
                if (!s.isEmpty()) return (int) Math.round(Double.parseDouble(s));
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean isValidLimit(Integer n) { return n != null && n > 0 && n <= 200; }
    private static boolean isValidDistance(Integer n) { return n != null && n >= 0 && n <= 100000; }

    private static String firstNonEmpty(String... values) {
        for (String s : values) if (s != null && !s.trim().isEmpty()) return s.trim();
        return null;
    }

    private static double[] project(double latDeg, double lonDeg, double bearingDeg, double meters) {
        double r = 6378137.0;
        double brng = Math.toRadians(bearingDeg);
        double lat1 = Math.toRadians(latDeg);
        double lon1 = Math.toRadians(lonDeg);
        double d = meters / r;
        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(d)
                + Math.cos(lat1) * Math.sin(d) * Math.cos(brng));
        double lon2 = lon1 + Math.atan2(Math.sin(brng) * Math.sin(d) * Math.cos(lat1),
                Math.cos(d) - Math.sin(lat1) * Math.sin(lat2));
        return new double[]{Math.toDegrees(lat2), Math.toDegrees(lon2)};
    }

    private static final class Result {
        final boolean ok;
        final String message;
        final String road;
        Result(boolean ok, String message, String road) {
            this.ok = ok;
            this.message = message;
            this.road = road;
        }
    }

    private static final class HttpError extends Exception {
        final int code;
        HttpError(int code, String body) {
            super(body == null ? "" : body);
            this.code = code;
        }
    }
}
