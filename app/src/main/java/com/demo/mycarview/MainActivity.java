package com.demo.mycarview;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Compact CarHUD control center. */
public class MainActivity extends Activity {
    private static final String PREFS = "carview_settings";
    private static final int REQ_LOCATION = 5101;
    private static final int REQ_NOTIFICATIONS = 5102;

    private SharedPreferences prefs;
    private TextView permissionStatus;
    private TextView runtimeStatus;
    private TextView vietmapStatus;
    private TextView scaleValue;
    private TextView crashStatus;
    private Switch hudSwitch;
    private boolean pendingStart;

    private EditText mapsKeyInput;
    private EditText alertKeyInput;
    private EditText alertIdInput;
    private EditText limitInput;
    private EditText nextLimitInput;
    private EditText nextDistanceInput;
    private EditText cameraDistanceInput;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        applyDefaults();
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        if (pendingStart && hasLocationPermission() && Settings.canDrawOverlays(this)) {
            pendingStart = false;
            beginStartFlow();
        }
    }

    private void applyDefaults() {
        SharedPreferences.Editor e = prefs.edit();
        if (!prefs.contains("hud_style")) e.putString("hud_style", "neon");
        if (!prefs.contains("show_limit")) e.putBoolean("show_limit", true);
        if (!prefs.contains("show_next_limit")) e.putBoolean("show_next_limit", true);
        if (!prefs.contains("show_camera")) e.putBoolean("show_camera", true);
        if (!prefs.contains("alert_sound")) e.putBoolean("alert_sound", false);
        if (!prefs.contains("bubble_scale")) e.putInt("bubble_scale", 80);
        if (!prefs.contains("speed_source")) e.putString("speed_source", "gps");
        if (!prefs.contains("vietmap_vehicle_seats")) e.putInt("vietmap_vehicle_seats", 5);
        if (!prefs.contains("vietmap_vehicle_weight_kg")) e.putInt("vietmap_vehicle_weight_kg", 1500);
        e.apply();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = Ui.text(this, "CarHUD", 27, Ui.TEXT, true);
        root.addView(title, wrap());
        TextView subtitle = Ui.text(this, "GPS HUD · VIETMAP Maps + Speed Alert · v0.8.2", 11, Ui.MUTED, false);
        LinearLayout.LayoutParams sp = wrap();
        sp.setMargins(0, dp(2), 0, dp(12));
        root.addView(subtitle, sp);

        LinearLayout status = card();
        status.addView(sectionTitle("TRẠNG THÁI"));
        permissionStatus = body("", 12, Ui.TEXT, false);
        runtimeStatus = body("", 12, Ui.TEXT, false);
        vietmapStatus = body("", 11, Ui.TEXT, false);
        crashStatus = body("", 10, Ui.MUTED, false);
        status.addView(permissionStatus, wrap());
        status.addView(runtimeStatus, wrap());
        status.addView(vietmapStatus, wrap());
        status.addView(crashStatus, wrap());
        root.addView(status, cardParams());

        LinearLayout hud = card();
        hud.addView(sectionTitle("HUD NỔI"));
        hudSwitch = addSwitch(hud, "Bật HUD", prefs.getBoolean("bubble_enabled", false), enabled -> {
            if (enabled) beginStartFlow(); else stopHud();
        });

        hud.addView(body("Kiểu HUD", 13, Ui.TEXT, true), smallGap());
        RadioGroup styles = new RadioGroup(this);
        styles.setOrientation(RadioGroup.HORIZONTAL);
        String style = prefs.getString("hud_style", "neon");
        addRadio(styles, "Gọn", "compact", style);
        addRadio(styles, "Neon", "neon", style);
        addRadio(styles, "Cột", "vertical", style);
        styles.setOnCheckedChangeListener((group, checkedId) -> {
            View v = group.findViewById(checkedId);
            if (v != null && v.getTag() instanceof String) {
                prefs.edit().putString("hud_style", String.valueOf(v.getTag())).apply();
                restartHudIfRunning();
            }
        });
        hud.addView(styles, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout scaleRow = new LinearLayout(this);
        scaleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView scaleLabel = body("Kích thước HUD", 13, Ui.TEXT, true);
        scaleValue = body(prefs.getInt("bubble_scale", 80) + "%", 13, Ui.ACCENT, true);
        scaleValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        scaleRow.addView(scaleLabel, new LinearLayout.LayoutParams(0, dp(32), 1f));
        scaleRow.addView(scaleValue, new LinearLayout.LayoutParams(dp(64), dp(32)));
        hud.addView(scaleRow, smallGap());

        SeekBar scale = new SeekBar(this);
        scale.setMax(90); // 60..150
        scale.setProgress(clamp(prefs.getInt("bubble_scale", 80), 60, 150) - 60);
        scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (scaleValue != null) scaleValue.setText((60 + progress) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt("bubble_scale", 60 + seekBar.getProgress()).apply();
                restartHudIfRunning();
            }
        });
        hud.addView(scale, new LinearLayout.LayoutParams(-1, dp(44)));

        addSwitch(hud, "Hiện giới hạn tốc độ", prefs.getBoolean("show_limit", true),
                v -> { prefs.edit().putBoolean("show_limit", v).apply(); refreshHudPrefs(); });
        addSwitch(hud, "Hiện giới hạn kế tiếp", prefs.getBoolean("show_next_limit", true),
                v -> { prefs.edit().putBoolean("show_next_limit", v).apply(); refreshHudPrefs(); });
        addSwitch(hud, "Hiện camera/cảnh báo", prefs.getBoolean("show_camera", true),
                v -> { prefs.edit().putBoolean("show_camera", v).apply(); refreshHudPrefs(); });
        addSwitch(hud, "Âm báo camera ≤ 500 m", prefs.getBoolean("alert_sound", false),
                v -> prefs.edit().putBoolean("alert_sound", v).apply());
        root.addView(hud, cardParams());

        LinearLayout sourceCard = card();
        sourceCard.addView(sectionTitle("NGUỒN DỮ LIỆU"));
        RadioGroup sources = new RadioGroup(this);
        sources.setOrientation(RadioGroup.HORIZONTAL);
        String src = prefs.getString("speed_source", "gps");
        addRadio(sources, "GPS", "gps", src);
        addRadio(sources, "VIETMAP", "vietmap_api", src);
        sources.setOnCheckedChangeListener((group, checkedId) -> {
            View v = group.findViewById(checkedId);
            if (v != null && v.getTag() instanceof String) {
                prefs.edit().putString("speed_source", String.valueOf(v.getTag())).apply();
                restartHudIfRunning();
                refreshStatus();
            }
        });
        sourceCard.addView(sources, new LinearLayout.LayoutParams(-1, dp(44)));

        sourceCard.addView(body(
                "Maps Service key lấy tên đường/route. Speed limit + camera của VIETMAP dùng Speed Alert service riêng (Alert key + API ID).",
                10, Ui.MUTED, false), wrap());

        mapsKeyInput = secretField("VIETMAP Service API key (Maps)", read("vietmap_api_key"));
        alertKeyInput = secretField("VIETMAP Speed Alert API key", read("vietmap_alert_api_key"));
        alertIdInput = field("VIETMAP Speed Alert API ID", read("vietmap_alert_api_id"));
        sourceCard.addView(mapsKeyInput, fieldParams());
        sourceCard.addView(alertKeyInput, fieldParams());
        sourceCard.addView(alertIdInput, fieldParams());

        addButton(sourceCard, "Lưu cấu hình VIETMAP", this::saveVietmapConfig);
        addPrimaryButton(sourceCard, "Kiểm tra Maps + Speed Alert", this::testVietmap);
        sourceCard.addView(body(
                "Nếu chỉ có Service key: tên đường vẫn hoạt động, nhưng limit/camera có thể vẫn --. Khi có Alert key + ID, CarHUD khởi động SDK Speed Alert chính thức.",
                10, Ui.MUTED, false), smallGap());
        root.addView(sourceCard, cardParams());

        LinearLayout test = card();
        test.addView(sectionTitle("DỮ LIỆU HUD / CHẨN ĐOÁN"));
        limitInput = field("Giới hạn hiện tại", read("limit"));
        nextLimitInput = field("Giới hạn kế tiếp", read("next_limit"));
        nextDistanceInput = field("Khoảng cách biển kế (m)", intBlank("next_limit_distance_m"));
        cameraDistanceInput = field("Khoảng cách camera (m)", intBlank("camera_distance_m"));
        test.addView(limitInput, fieldParams());
        test.addView(nextLimitInput, fieldParams());
        test.addView(nextDistanceInput, fieldParams());
        test.addView(cameraDistanceInput, fieldParams());
        addButton(test, "Lưu dữ liệu test", this::saveTestData);
        TextView diag = body("", 9, Ui.MUTED, false);
        diag.setId(View.generateViewId());
        String route = prefs.getString("vietmap_route_status", "Route v4: chưa kiểm tra");
        String event = prefs.getString("vietmap_alert_last_event", "");
        diag.setText(route + (event.isEmpty() ? "" : "\nAlert callback: " + event));
        test.addView(diag, smallGap());
        root.addView(test, cardParams());

        LinearLayout permissions = card();
        permissions.addView(sectionTitle("QUYỀN"));
        addButton(permissions, "Cấp quyền GPS", this::requestLocation);
        addButton(permissions, "Quyền hiển thị trên ứng dụng khác", this::openOverlaySettings);
        addButton(permissions, "Cài đặt thông báo", () -> {
            try {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(i);
            } catch (Throwable ignored) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        root.addView(permissions, cardParams());

        LinearLayout actions = card();
        actions.addView(sectionTitle("CHẠY"));
        addPrimaryButton(actions, "Bật HUD + mở Google Maps", () -> {
            pendingStart = true;
            beginStartFlow();
            if (hasLocationPermission() && Settings.canDrawOverlays(this)) openGoogleMaps();
        });
        addButton(actions, "Chỉ mở Google Maps", this::openGoogleMaps);
        addButton(actions, "Tắt HUD", this::stopHud);
        root.addView(actions, cardParams());

        setContentView(scroll);
        refreshStatus();
    }

    private void saveVietmapConfig() {
        String maps = mapsKeyInput.getText().toString().trim();
        String alert = alertKeyInput.getText().toString().trim();
        String alertId = alertIdInput.getText().toString().trim();
        prefs.edit()
                .putString("vietmap_api_key", maps)
                .putString("vietmap_alert_api_key", alert)
                .putString("vietmap_alert_api_id", alertId)
                .apply();
        VietmapSpeedAlertBridge.stop(this);
        Toast.makeText(this, "Đã lưu cấu hình VIETMAP", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void testVietmap() {
        saveVietmapConfig();
        if (!hasLocationPermission()) {
            requestLocation();
            Toast.makeText(this, "Cần GPS để test VIETMAP", Toast.LENGTH_LONG).show();
            return;
        }
        Location location = bestLastLocation();
        if (location == null) {
            Toast.makeText(this, "Chưa có GPS fix. Ra ngoài trời/mở Maps rồi thử lại.", Toast.LENGTH_LONG).show();
            return;
        }
        vietmapStatus.setText("VIETMAP: đang kiểm tra...");
        VietmapApiClient.test(this, location, (ok, message, road) -> {
            String alert = VietmapSpeedAlertBridge.test(this);
            Toast.makeText(this, message + "\n" + alert, Toast.LENGTH_LONG).show();
            refreshStatus();
            restartHudIfRunning();
        });
    }

    private Location bestLastLocation() {
        if (!hasLocationPermission()) return null;
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location l;
                try { l = lm.getLastKnownLocation(provider); } catch (SecurityException e) { continue; }
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            return best;
        } catch (Throwable ignored) { return null; }
    }

    private void beginStartFlow() {
        prefs.edit().putBoolean("bubble_enabled", true).apply();
        if (!hasLocationPermission()) {
            pendingStart = true;
            requestLocation();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true;
            openOverlaySettings();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        pendingStart = false;
        startHudService();
    }

    private void startHudService() {
        try {
            prefs.edit().putBoolean("bubble_enabled", true).apply();
            startForegroundService(new Intent(this, SpeedBubbleService.class));
            if (VietmapSpeedAlertBridge.hasCredentials(this)) VietmapSpeedAlertBridge.start(this);
            if (hudSwitch != null && !hudSwitch.isChecked()) hudSwitch.setChecked(true);
            Toast.makeText(this, "CarHUD đã bật", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            prefs.edit().putBoolean("bubble_enabled", false).apply();
            if (hudSwitch != null && hudSwitch.isChecked()) hudSwitch.setChecked(false);
            Toast.makeText(this, "Không bật được HUD: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
        refreshStatus();
    }

    private void stopHud() {
        prefs.edit().putBoolean("bubble_enabled", false).apply();
        try { stopService(new Intent(this, SpeedBubbleService.class)); } catch (Throwable ignored) {}
        VietmapSpeedAlertBridge.stop(this);
        if (hudSwitch != null && hudSwitch.isChecked()) hudSwitch.setChecked(false);
        refreshStatus();
    }

    private void restartHudIfRunning() {
        if (!prefs.getBoolean("bubble_enabled", false)) return;
        try {
            stopService(new Intent(this, SpeedBubbleService.class));
            startForegroundService(new Intent(this, SpeedBubbleService.class));
        } catch (Throwable ignored) {}
    }

    private void refreshHudPrefs() {
        // SpeedBubbleService listens to preference changes for value/visibility;
        // restart only if a device/OEM dropped that callback.
        if (prefs.getBoolean("bubble_enabled", false)) refreshStatus();
    }

    private void requestLocation() {
        if (hasLocationPermission()) {
            Toast.makeText(this, "GPS đã được cấp quyền", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void openGoogleMaps() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.maps");
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")));
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Không mở được Google Maps", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatus();
        if (requestCode == REQ_LOCATION && hasLocationPermission() && pendingStart) {
            beginStartFlow();
        } else if (requestCode == REQ_NOTIFICATIONS && pendingStart
                && hasLocationPermission() && Settings.canDrawOverlays(this)) {
            pendingStart = false;
            startHudService();
        }
    }

    private void refreshStatus() {
        if (permissionStatus == null) return;
        boolean gps = hasLocationPermission();
        boolean overlay = Settings.canDrawOverlays(this);
        boolean notify = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText("GPS " + mark(gps) + " · Overlay " + mark(overlay) + " · TB " + mark(notify));
        runtimeStatus.setText("HUD " + (prefs.getBoolean("bubble_enabled", false) ? "BẬT" : "TẮT")
                + " · " + prefs.getString("hud_style", "neon")
                + " · " + prefs.getInt("bubble_scale", 80) + "%"
                + " · " + read("speed", "--") + " km/h");

        String road = read("vietmap_road");
        String maps = prefs.getString("vietmap_maps_status", prefs.getString("vietmap_status", "Chưa cấu hình"));
        String alert = prefs.getString("vietmap_alert_status", "Chưa cấu hình");
        String route = prefs.getString("vietmap_route_status", "Route v4 chưa kiểm tra");
        vietmapStatus.setText("Maps: " + maps + (road.isEmpty() ? "" : " · " + road)
                + "\nSpeed Alert: " + alert + "\n" + route);

        SharedPreferences d = getSharedPreferences("carhud_diag", MODE_PRIVATE);
        String crash = d.getString("last_crash", "");
        crashStatus.setText(crash == null || crash.isEmpty()
                ? "Crash log: không có"
                : "Crash: " + crash.split("\\n", 2)[0]);
    }

    private String mark(boolean ok) { return ok ? "OK" : "CHƯA"; }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void saveTestData() {
        prefs.edit()
                .putString("limit", normalizeLimit(limitInput.getText().toString()))
                .putString("next_limit", normalizeLimit(nextLimitInput.getText().toString()))
                .putInt("next_limit_distance_m", positive(nextDistanceInput.getText().toString(), -1))
                .putInt("camera_distance_m", positive(cameraDistanceInput.getText().toString(), -1))
                .apply();
        restartHudIfRunning();
        Toast.makeText(this, "Đã lưu dữ liệu test", Toast.LENGTH_SHORT).show();
    }

    private String normalizeLimit(String value) {
        try {
            int n = Integer.parseInt(value.trim());
            return n > 0 && n <= 200 ? String.valueOf(n) : "--";
        } catch (Throwable ignored) { return "--"; }
    }

    private int positive(String value, int fallback) {
        try {
            int n = Integer.parseInt(value.trim());
            return n >= 0 ? n : fallback;
        } catch (Throwable ignored) { return fallback; }
    }

    private String intBlank(String key) {
        try {
            Object v = prefs.getAll().get(key);
            int n = v instanceof Number ? ((Number) v).intValue() : Integer.parseInt(String.valueOf(v));
            return n < 0 ? "" : String.valueOf(n);
        } catch (Throwable ignored) { return ""; }
    }

    private String read(String key) { return read(key, ""); }
    private String read(String key, String fallback) {
        try {
            Object v = prefs.getAll().get(key);
            if (v == null) return fallback;
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? fallback : s;
        } catch (Throwable ignored) { return fallback; }
    }

    // ---------- compact UI helpers ----------

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(13), dp(12), dp(13), dp(12));
        c.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 18, this));
        return c;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = wrap();
        p.setMargins(0, 0, 0, dp(10));
        return p;
    }

    private TextView sectionTitle(String text) {
        TextView v = Ui.text(this, text, 13, Ui.ACCENT, true);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(30)));
        return v;
    }

    private TextView body(String text, int size, int color, boolean bold) {
        TextView v = Ui.text(this, text, size, color, bold);
        v.setPadding(0, dp(2), 0, dp(2));
        return v;
    }

    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams smallGap() {
        LinearLayout.LayoutParams p = wrap();
        p.setMargins(0, dp(7), 0, dp(2));
        return p;
    }

    private interface BoolCallback { void changed(boolean value); }

    private Switch addSwitch(LinearLayout parent, String label, boolean checked, BoolCallback cb) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Ui.TEXT);
        s.setTextSize(13);
        s.setGravity(Gravity.CENTER_VERTICAL);
        s.setChecked(checked);
        s.setPadding(0, 0, 0, 0);
        s.setOnCheckedChangeListener((buttonView, isChecked) -> cb.changed(isChecked));
        parent.addView(s, new LinearLayout.LayoutParams(-1, dp(44)));
        return s;
    }

    private void addRadio(RadioGroup group, String label, String value, String selected) {
        RadioButton b = new RadioButton(this);
        b.setId(View.generateViewId());
        b.setTag(value);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(Ui.TEXT);
        b.setChecked(value.equals(selected));
        group.addView(b, new RadioGroup.LayoutParams(0, dp(40), 1f));
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);
        e.setTextSize(12);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(Ui.rounded(0xFF0D1A29, Ui.STROKE, 13, this));
        return e;
    }

    private EditText secretField(String hint, String value) {
        EditText e = field(hint, value);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
        p.setMargins(0, dp(6), 0, 0);
        return p;
    }

    private void addButton(LinearLayout parent, String label, Runnable action) {
        TextView b = Ui.text(this, label, 12, Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.rounded(0xFF14263A, Ui.STROKE, 13, this));
        b.setOnClickListener(v -> action.run());
        Ui.press(b);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(46));
        p.setMargins(0, dp(6), 0, 0);
        parent.addView(b, p);
    }

    private void addPrimaryButton(LinearLayout parent, String label, Runnable action) {
        TextView b = Ui.text(this, label, 13, 0xFF06131F, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.rounded(Ui.ACCENT, Ui.ACCENT, 14, this));
        b.setOnClickListener(v -> action.run());
        Ui.press(b);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(50));
        p.setMargins(0, dp(7), 0, 0);
        parent.addView(b, p);
    }

    private int dp(float v) { return Ui.dp(this, v); }
    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
