package com.demo.mycarview;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Guarded bridge to VietMap's public Tracking / Speed Alert SDK.
 *
 * The public VietMap navigation integration initializes VietmapTrackingSDK,
 * configures Alert API credentials, starts speedAlertManager and feeds external
 * GPS locations. CarHUD follows the same public SDK path. Reflection is used
 * only to keep CarHUD resilient across minor SDK binary revisions; no VIETMAP
 * LIVE process, private IPC or private app data is inspected.
 */
public final class VietmapSpeedAlertBridge {
    private static final String PREFS = "carview_settings";
    private static final Object LOCK = new Object();

    private static Object sdk;
    private static Object manager;
    private static boolean configured;
    private static boolean listenersBound;
    private static final List<Object> listenerRefs = new ArrayList<>();

    private VietmapSpeedAlertBridge() {}

    public static boolean hasCredentials(Context context) {
        SharedPreferences p = prefs(context);
        return !read(p, "vietmap_alert_api_key").isEmpty()
                && !read(p, "vietmap_alert_api_id").isEmpty();
    }

    public static String status(Context context) {
        return prefs(context).getString("vietmap_alert_status", "Chưa cấu hình");
    }

    /** Configure and start VietMap Speed Alert when dedicated credentials exist. */
    public static boolean start(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            SharedPreferences p = prefs(app);
            String key = read(p, "vietmap_alert_api_key");
            String id = read(p, "vietmap_alert_api_id");
            if (key.isEmpty() || id.isEmpty()) {
                configured = false;
                setStatus(p, "Chưa cấu hình · cần Alert key + ID", null);
                return false;
            }

            try {
                Class<?> sdkClass = Class.forName("com.vietmap.trackingsdk.VietmapTrackingSDK");
                Method getInstance = findCompatible(sdkClass, "getInstance", Context.class);
                if (getInstance == null) throw new NoSuchMethodException("getInstance(Context)");
                sdk = getInstance.invoke(null, app);
                if (sdk == null) throw new IllegalStateException("SDK instance null");

                // Public VietMap navigation sample initializes the tracking SDK
                // with the VIETMAP_ALERT client identifier before Alert API setup.
                invokeBest(sdk, "initialize", "VIETMAP_ALERT");
                invokeBest(sdk, "configureAlertAPI", key, id);
                configureVehicleIfPossible(p);

                manager = invokeGetter(sdk, "getSpeedAlertManager", "speedAlertManager");
                if (manager == null) throw new NoSuchMethodException("speedAlertManager");

                bindPublicAlertListeners(p);
                invokeBest(manager, "startSpeedAlerts");
                configured = true;

                String methods = compactMethodList(manager.getClass());
                p.edit()
                        .putString("vietmap_alert_status", "Đang chạy")
                        .putString("vietmap_alert_sdk", sdkClass.getName())
                        .putString("vietmap_alert_manager", manager.getClass().getName())
                        .putString("vietmap_alert_methods", methods)
                        .putLong("vietmap_alert_started_ms", System.currentTimeMillis())
                        .remove("vietmap_alert_error")
                        .apply();
                return true;
            } catch (Throwable t) {
                configured = false;
                manager = null;
                String detail = rootMessage(t);
                setStatus(p, "Không khởi động được SDK", detail);
                return false;
            }
        }
    }

    /** Feed the same GPS fix used by CarHUD to VietMap's official alert manager. */
    public static void processLocation(Context context, Location location) {
        if (context == null || location == null) return;
        if (!hasCredentials(context)) return;

        synchronized (LOCK) {
            if (!configured || manager == null) {
                if (!start(context)) return;
            }
            try {
                double speedMps = location.hasSpeed() ? Math.max(0d, location.getSpeed()) : 0d;
                double bearing = location.hasBearing() ? location.getBearing() : 0d;
                invokeBest(manager, "processExternalLocation",
                        location.getLatitude(), location.getLongitude(), speedMps, bearing);
                prefs(context).edit()
                        .putLong("vietmap_alert_last_location_ms", System.currentTimeMillis())
                        .apply();
            } catch (Throwable t) {
                setStatus(prefs(context), "SDK chạy nhưng feed GPS lỗi", rootMessage(t));
            }
        }
    }

    public static void stop(Context context) {
        synchronized (LOCK) {
            try {
                if (manager != null) invokeBest(manager, "stopSpeedAlerts");
            } catch (Throwable ignored) {}
            configured = false;
            listenersBound = false;
            manager = null;
            sdk = null;
            listenerRefs.clear();
            SharedPreferences p = prefs(context);
            if (hasCredentials(context)) setStatus(p, "Đã dừng", null);
        }
    }

    /** Used by the Settings test button. */
    public static String test(Context context) {
        boolean ok = start(context);
        SharedPreferences p = prefs(context);
        if (ok) {
            String listener = p.getString("vietmap_alert_listener_status", "");
            return "Speed Alert SDK: Đang chạy" + (listener.isEmpty() ? "" : " · " + listener);
        }
        return "Speed Alert: " + p.getString("vietmap_alert_status", "Lỗi");
    }

    private static void configureVehicleIfPossible(SharedPreferences p) {
        try {
            String vehicleId = read(p, "vietmap_vehicle_id");
            if (vehicleId.isEmpty()) vehicleId = "CARHUD";
            int type = clampInt(p.getInt("vietmap_vehicle_type", 1), 0, 20);
            int seats = clampInt(p.getInt("vietmap_vehicle_seats", 5), 1, 100);
            double weight = readDouble(p, "vietmap_vehicle_weight_kg", 1500d);

            Class<?> vehicleTypeClass = Class.forName("com.vietmap.trackingsdk.VehicleType");
            Object vehicleType = null;
            try {
                Method fromValue = vehicleTypeClass.getMethod("fromValue", int.class);
                vehicleType = fromValue.invoke(null, type);
            } catch (Throwable ignored) {}
            if (vehicleType == null && vehicleTypeClass.isEnum()) {
                Object[] values = vehicleTypeClass.getEnumConstants();
                if (values != null && values.length > 0) vehicleType = values[0];
            }
            if (vehicleType != null) {
                invokeBest(sdk, "configureVehicle", vehicleId, vehicleType, seats, weight);
            }
        } catch (Throwable ignored) {
            // Vehicle configuration improves commercial-vehicle rules but is not
            // allowed to prevent the basic Speed Alert subsystem from starting.
        }
    }

    /**
     * Bind only public, alert-related listener/callback interfaces exposed by
     * the SDK revision. Callback payloads are reduced to simple scalar fields
     * and never persist images, raw request bodies or credentials.
     */
    private static void bindPublicAlertListeners(SharedPreferences p) {
        if (listenersBound || manager == null) return;
        int bound = 0;
        try {
            for (Method method : manager.getClass().getMethods()) {
                String n = method.getName().toLowerCase(Locale.US);
                if (!(n.startsWith("set") || n.startsWith("add") || n.startsWith("register"))) continue;
                if (!(n.contains("alert") || n.contains("speed") || n.contains("warning"))) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || !params[0].isInterface()) continue;
                String pkg = params[0].getName().toLowerCase(Locale.US);
                if (!(pkg.contains("vietmap") || pkg.contains("tracking"))) continue;

                Object proxy = Proxy.newProxyInstance(
                        params[0].getClassLoader(), new Class<?>[]{params[0]},
                        new AlertInvocationHandler(p));
                try {
                    method.invoke(manager, proxy);
                    listenerRefs.add(proxy);
                    bound++;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        listenersBound = true;
        p.edit().putString("vietmap_alert_listener_status",
                bound > 0 ? ("callback " + bound) : "SDK không công bố callback dữ liệu")
                .apply();
    }

    private static final class AlertInvocationHandler implements InvocationHandler {
        private final SharedPreferences prefs;
        AlertInvocationHandler(SharedPreferences prefs) { this.prefs = prefs; }

        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                String name = method.getName();
                if ("toString".equals(name)) return "CarHUDVietmapAlertListener";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
            }
            try {
                captureEvent(prefs, method.getName(), args);
            } catch (Throwable ignored) {}
            return defaultValue(method.getReturnType());
        }
    }

    private static void captureEvent(SharedPreferences prefs, String eventName, Object[] args) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                collectScalars(args[i], "arg" + i, fields, 0);
            }
        }

        Integer currentLimit = pickNumber(fields, false,
                "speedlimit", "maxspeed", "limitspeed", "speed_limit");
        Integer nextLimit = pickNumber(fields, true,
                "speedlimit", "limit", "maxspeed");
        Integer nextDistance = pickDistance(fields, "next", "limit", "sign");
        Integer cameraDistance = pickDistance(fields, "camera", "speedcamera", "cam");

        SharedPreferences.Editor e = prefs.edit()
                .putString("vietmap_alert_last_event", eventName)
                .putLong("vietmap_alert_last_event_ms", System.currentTimeMillis())
                .putString("vietmap_alert_event_fields", compactFields(fields));

        if (validLimit(currentLimit)) e.putString("limit", String.valueOf(currentLimit));
        if (validLimit(nextLimit)) e.putString("next_limit", String.valueOf(nextLimit));
        if (validDistance(nextDistance)) e.putInt("next_limit_distance_m", nextDistance);
        if (validDistance(cameraDistance)) e.putInt("camera_distance_m", cameraDistance);
        e.apply();
    }

    private static void collectScalars(Object value, String path,
                                       Map<String, Object> out, int depth) {
        if (value == null || depth > 2 || out.size() > 80) return;
        Class<?> c = value.getClass();
        if (value instanceof Number || value instanceof Boolean || value instanceof CharSequence) {
            out.put(path, value);
            return;
        }
        if (c.isEnum()) {
            out.put(path, String.valueOf(value));
            return;
        }
        if (value instanceof Map) {
            for (Object entryObj : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObj;
                collectScalars(entry.getValue(), path + "." + entry.getKey(), out, depth + 1);
            }
            return;
        }
        if (value instanceof Iterable) {
            int i = 0;
            for (Object item : (Iterable<?>) value) {
                if (i >= 5) break;
                collectScalars(item, path + "[" + i + "]", out, depth + 1);
                i++;
            }
            return;
        }

        String className = c.getName();
        if (!(className.contains("vietmap") || className.contains("tracking"))) return;

        try {
            for (Method m : c.getMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                String mn = m.getName();
                if ("getClass".equals(mn) || !(mn.startsWith("get") || mn.startsWith("is"))) continue;
                Class<?> rt = m.getReturnType();
                if (rt == Void.TYPE || rt == Class.class) continue;
                try {
                    Object child = m.invoke(value);
                    collectScalars(child, path + "." + propertyName(mn), out, depth + 1);
                } catch (Throwable ignored) {}
            }
            for (Field f : c.getFields()) {
                try { collectScalars(f.get(value), path + "." + f.getName(), out, depth + 1); }
                catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static Integer pickNumber(Map<String, Object> fields, boolean requireNext, String... tokens) {
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String k = normalize(entry.getKey());
            if (requireNext && !(k.contains("next") || k.contains("upcoming"))) continue;
            if (!requireNext && (k.contains("next") || k.contains("upcoming"))) continue;
            if (k.contains("vehiclespeed") || k.contains("currentspeed") && !k.contains("limit")) continue;
            boolean match = false;
            for (String token : tokens) if (k.contains(normalize(token))) { match = true; break; }
            if (!match) continue;
            Integer n = intValue(entry.getValue());
            if (validLimit(n)) return n;
        }
        return null;
    }

    private static Integer pickDistance(Map<String, Object> fields, String... tokens) {
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String k = normalize(entry.getKey());
            if (!(k.contains("distance") || k.endsWith("dist") || k.contains("meter"))) continue;
            boolean match = false;
            for (String token : tokens) if (k.contains(normalize(token))) { match = true; break; }
            if (!match) continue;
            Integer n = intValue(entry.getValue());
            if (validDistance(n)) return n;
        }
        return null;
    }

    private static String compactFields(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (count++ >= 20 || sb.length() > 700) break;
            if (sb.length() > 0) sb.append(" · ");
            String v = String.valueOf(e.getValue());
            if (v.length() > 60) v = v.substring(0, 60);
            sb.append(e.getKey()).append('=').append(v);
        }
        return sb.toString();
    }

    private static String compactMethodList(Class<?> type) {
        List<String> names = new ArrayList<>();
        try {
            for (Method m : type.getMethods()) {
                String n = m.getName();
                String lower = n.toLowerCase(Locale.US);
                if (lower.contains("alert") || lower.contains("speed") || lower.contains("location")
                        || lower.contains("listener") || lower.contains("callback")) {
                    if (!names.contains(n)) names.add(n);
                }
            }
        } catch (Throwable ignored) {}
        Collections.sort(names);
        String joined = android.text.TextUtils.join(",", names);
        return joined.length() > 800 ? joined.substring(0, 800) : joined;
    }

    private static Method findCompatible(Class<?> type, String name, Class<?>... params) {
        try { return type.getMethod(name, params); }
        catch (Throwable ignored) { return null; }
    }

    private static Object invokeGetter(Object target, String methodName, String fieldName) throws Exception {
        try { return target.getClass().getMethod(methodName).invoke(target); }
        catch (Throwable ignored) {
            try {
                Field f = target.getClass().getField(fieldName);
                return f.get(target);
            } catch (Throwable t) {
                if (t instanceof Exception) throw (Exception) t;
                throw new Exception(t);
            }
        }
    }

    private static Object invokeBest(Object target, String name, Object... args) throws Exception {
        if (target == null) throw new NullPointerException(name + " target");
        Method best = null;
        for (Method m : target.getClass().getMethods()) {
            if (!name.equals(m.getName())) continue;
            Class<?>[] types = m.getParameterTypes();
            if (types.length != args.length) continue;
            boolean ok = true;
            for (int i = 0; i < types.length; i++) {
                if (!compatible(types[i], args[i])) { ok = false; break; }
            }
            if (ok) { best = m; break; }
        }
        if (best == null) throw new NoSuchMethodException(name + "/" + args.length);
        try { return best.invoke(target, args); }
        catch (Throwable t) {
            Throwable cause = t.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new Exception(cause == null ? t : cause);
        }
    }

    private static boolean compatible(Class<?> type, Object arg) {
        if (arg == null) return !type.isPrimitive();
        Class<?> a = arg.getClass();
        if (type.isAssignableFrom(a)) return true;
        if (!type.isPrimitive()) return false;
        if (type == int.class) return a == Integer.class;
        if (type == long.class) return a == Long.class || a == Integer.class;
        if (type == double.class) return Number.class.isAssignableFrom(a);
        if (type == float.class) return Number.class.isAssignableFrom(a);
        if (type == boolean.class) return a == Boolean.class;
        return false;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    private static String propertyName(String method) {
        String s = method.startsWith("get") ? method.substring(3)
                : method.startsWith("is") ? method.substring(2) : method;
        if (s.isEmpty()) return method;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private static Integer intValue(Object v) {
        if (v instanceof Number) return (int) Math.round(((Number) v).doubleValue());
        if (v != null) {
            try {
                String s = String.valueOf(v).replaceAll("[^0-9.-]", "");
                if (!s.isEmpty()) return (int) Math.round(Double.parseDouble(s));
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean validLimit(Integer v) { return v != null && v > 0 && v <= 200; }
    private static boolean validDistance(Integer v) { return v != null && v >= 0 && v <= 100000; }

    private static String read(SharedPreferences p, String key) {
        try {
            String v = p.getString(key, "");
            return v == null ? "" : v.trim();
        } catch (Throwable ignored) { return ""; }
    }

    private static double readDouble(SharedPreferences p, String key, double fallback) {
        try {
            Object v = p.getAll().get(key);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v != null) return Double.parseDouble(String.valueOf(v));
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int clampInt(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static void setStatus(SharedPreferences p, String status, String error) {
        SharedPreferences.Editor e = p.edit().putString("vietmap_alert_status", status);
        if (error == null || error.trim().isEmpty()) e.remove("vietmap_alert_error");
        else e.putString("vietmap_alert_error", error.length() > 300 ? error.substring(0, 300) : error);
        e.apply();
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        String n = cur.getClass().getSimpleName();
        return m == null || m.trim().isEmpty() ? n : (n + ": " + m);
    }
}
