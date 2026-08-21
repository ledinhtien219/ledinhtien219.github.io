package com.demo.mycarview;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Clean CarHUD control center.
 *
 * The old WebView/video runtime is intentionally not part of the launcher path
 * anymore. This activity only manages the GPS HUD, permissions, appearance and
 * Android Auto status so failures in unrelated browser code cannot take down
 * the car app session.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "carview_settings";
    private static final int REQ_LOCATION = 5101;
    private static final int REQ_NOTIFICATIONS = 5102;

    private SharedPreferences prefs;
    private TextView permissionStatus;
    private TextView runtimeStatus;
    private TextView crashStatus;
    private Switch hudSwitch;
    private boolean pendingStart;

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
            startHudService();
        }
    }

    private void applyDefaults() {
        if (!prefs.contains("hud_style")) prefs.edit().putString("hud_style", "neon").apply();
        if (!prefs.contains("show_limit")) prefs.edit().putBoolean("show_limit", true).apply();
        if (!prefs.contains("show_next_limit")) prefs.edit().putBoolean("show_next_limit", true).apply();
        if (!prefs.contains("show_camera")) prefs.edit().putBoolean("show_camera", true).apply();
        if (!prefs.contains("alert_sound")) prefs.edit().putBoolean("alert_sound", false).apply();
        if (!prefs.contains("bubble_scale")) prefs.edit().putInt("bubble_scale", 100).apply();
        if (!prefs.contains("speed_source")) prefs.edit().putString("speed_source", "gps").apply();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 32));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = Ui.text(this, "CarHUD", 34, Ui.TEXT, true);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = Ui.text(this,
                "GPS HUD + cảnh báo đường · Android Auto compatibility build 0.8", 15, Ui.MUTED, false);
        LinearLayout.LayoutParams subp = new LinearLayout.LayoutParams(-1, -2);
        subp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 20));
        root.addView(subtitle, subp);

        LinearLayout statusCard = card();
        statusCard.addView(sectionTitle("TRẠNG THÁI"));
        permissionStatus = body("", 15, Ui.TEXT, false);
        runtimeStatus = body("", 15, Ui.TEXT, false);
        crashStatus = body("", 13, Ui.MUTED, false);
        statusCard.addView(permissionStatus, rowWrap());
        statusCard.addView(runtimeStatus, rowWrap());
        statusCard.addView(crashStatus, rowWrap());
        root.addView(statusCard, cardParams());

        LinearLayout hudCard = card();
        hudCard.addView(sectionTitle("HUD NỔI"));
        hudSwitch = addSwitch(hudCard, "Bật HUD", prefs.getBoolean("bubble_enabled", false), enabled -> {
            if (enabled) beginStartFlow(); else stopHud();
        });
        hudCard.addView(body(
                "Tốc độ lấy trực tiếp từ GPS. Giới hạn/camera chỉ hiện khi có nguồn dữ liệu hợp lệ; app không đọc lén dữ liệu private của VIETMAP/Waze.",
                13, Ui.MUTED, false), rowWrap());

        hudCard.addView(body("Kiểu hiển thị", 16, Ui.TEXT, true), spacedRow());
        RadioGroup styles = new RadioGroup(this);
        styles.setOrientation(RadioGroup.HORIZONTAL);
        String style = prefs.getString("hud_style", "neon");
        addStyle(styles, "Gọn", "compact", style);
        addStyle(styles, "Neon", "neon", style);
        addStyle(styles, "Cột", "vertical", style);
        styles.setOnCheckedChangeListener((group, checkedId) -> {
            View v = group.findViewById(checkedId);
            if (v != null && v.getTag() instanceof String) {
                prefs.edit().putString("hud_style", (String) v.getTag()).apply();
                restartHudIfRunning();
            }
        });
        hudCard.addView(styles, rowWrap());

        addSwitch(hudCard, "Hiện giới hạn tốc độ", prefs.getBoolean("show_limit", true),
                v -> { prefs.edit().putBoolean("show_limit", v).apply(); restartHudIfRunning(); });
        addSwitch(hudCard, "Hiện giới hạn kế tiếp", prefs.getBoolean("show_next_limit", true),
                v -> { prefs.edit().putBoolean("show_next_limit", v).apply(); restartHudIfRunning(); });
        addSwitch(hudCard, "Hiện camera/cảnh báo", prefs.getBoolean("show_camera", true),
                v -> { prefs.edit().putBoolean("show_camera", v).apply(); restartHudIfRunning(); });
        addSwitch(hudCard, "Âm báo khi camera còn ≤ 500 m", prefs.getBoolean("alert_sound", false),
                v -> prefs.edit().putBoolean("alert_sound", v).apply());
        root.addView(hudCard, cardParams());

        LinearLayout permissions = card();
        permissions.addView(sectionTitle("QUYỀN & HỆ THỐNG"));
        addButton(permissions, "Cấp quyền GPS", this::requestLocation);
        addButton(permissions, "Cấp quyền hiển thị trên ứng dụng khác", this::openOverlaySettings);
        addButton(permissions, "Mở cài đặt thông báo", () -> {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        });
        addButton(permissions, "Mở cài đặt tối ưu pin", () -> {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Throwable t) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        root.addView(permissions, cardParams());

        LinearLayout testData = card();
        testData.addView(sectionTitle("DỮ LIỆU TEST / CONNECTOR"));
        testData.addView(body(
                "Các ô dưới đây chỉ để kiểm tra giao diện HUD. Khi có connector API chính thức, connector sẽ ghi đè các giá trị này.",
                13, Ui.MUTED, false), rowWrap());
        limitInput = field("Giới hạn hiện tại, ví dụ 60", readString("limit", ""));
        nextLimitInput = field("Giới hạn kế tiếp, ví dụ 80", readString("next_limit", ""));
        nextDistanceInput = field("Khoảng cách biển kế tiếp (m)", valueOrBlank(readInt("next_limit_distance_m", -1)));
        cameraDistanceInput = field("Khoảng cách camera (m)", valueOrBlank(readInt("camera_distance_m", -1)));
        testData.addView(limitInput, fieldParams());
        testData.addView(nextLimitInput, fieldParams());
        testData.addView(nextDistanceInput, fieldParams());
        testData.addView(cameraDistanceInput, fieldParams());
        addButton(testData, "Lưu dữ liệu test", this::saveTestData);
        root.addView(testData, cardParams());

        LinearLayout actions = card();
        actions.addView(sectionTitle("CHẠY"));
        addPrimaryButton(actions, "Bật HUD và mở Google Maps", () -> {
            pendingStart = true;
            beginStartFlow();
            if (hasLocationPermission() && Settings.canDrawOverlays(this)) {
                openGoogleMaps();
            }
        });
        addButton(actions, "Chỉ mở Google Maps", this::openGoogleMaps);
        addButton(actions, "Tắt HUD", this::stopHud);
        root.addView(actions, cardParams());

        LinearLayout aa = card();
        aa.addView(sectionTitle("ANDROID AUTO"));
        aa.addView(body(
                "CarHUD có entry Car App Library tối giản riêng. Màn này được tách khỏi browser/video cũ để tránh lỗi host. Android Auto stock không cho overlay của điện thoại chèn trực tiếp lên Google Maps của Android Auto.",
                14, Ui.TEXT, false), rowWrap());
        aa.addView(body(
                "Nếu head unit vẫn báo lỗi, mở lại CarHUD trên điện thoại: mục TRẠNG THÁI sẽ giữ crash log của tiến trình app nếu có.",
                13, Ui.MUTED, false), rowWrap());
        root.addView(aa, cardParams());

        setContentView(scroll);
        refreshStatus();
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
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
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
            if (hudSwitch != null && !hudSwitch.isChecked()) hudSwitch.setChecked(true);
            startForegroundService(new Intent(this, SpeedBubbleService.class));
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

    private void requestLocation() {
        if (hasLocationPermission()) {
            Toast.makeText(this, "GPS đã được cấp quyền", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
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
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")));
        } catch (Throwable t) {
            Toast.makeText(this, "Không mở được Google Maps", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatus();
        if (requestCode == REQ_LOCATION && hasLocationPermission()) {
            if (pendingStart) beginStartFlow();
        } else if (requestCode == REQ_NOTIFICATIONS) {
            if (pendingStart && hasLocationPermission() && Settings.canDrawOverlays(this)) {
                pendingStart = false;
                startHudService();
            }
        }
    }

    private void refreshStatus() {
        if (prefs == null || permissionStatus == null) return;
        boolean gps = hasLocationPermission();
        boolean overlay = Settings.canDrawOverlays(this);
        boolean notify = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText("GPS: " + mark(gps) + "   Overlay: " + mark(overlay) + "   Thông báo: " + mark(notify));
        runtimeStatus.setText("HUD: " + (prefs.getBoolean("bubble_enabled", false) ? "BẬT" : "TẮT")
                + "   Kiểu: " + prefs.getString("hud_style", "neon")
                + "   Speed: " + readString("speed", "--") + " km/h");

        SharedPreferences d = getSharedPreferences("carhud_diag", MODE_PRIVATE);
        String crash = d.getString("last_crash", "");
        if (crash == null || crash.isEmpty()) {
            crashStatus.setText("Crash log: chưa ghi nhận lỗi tiến trình CarHUD.");
        } else {
            String first = crash.split("\\n", 2)[0];
            crashStatus.setText("Crash log gần nhất: " + first);
        }
    }

    private String mark(boolean ok) { return ok ? "OK" : "CHƯA"; }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void saveTestData() {
        SharedPreferences.Editor e = prefs.edit();
        String limit = limitInput.getText().toString().trim();
        String next = nextLimitInput.getText().toString().trim();
        e.putString("limit", normalizeLimit(limit));
        e.putString("next_limit", normalizeLimit(next));
        e.putInt("next_limit_distance_m", parsePositive(nextDistanceInput.getText().toString(), -1));
        e.putInt("camera_distance_m", parsePositive(cameraDistanceInput.getText().toString(), -1));
        e.apply();
        restartHudIfRunning();
        Toast.makeText(this, "Đã lưu dữ liệu test", Toast.LENGTH_SHORT).show();
    }

    private String normalizeLimit(String value) {
        try {
            int n = Integer.parseInt(value);
            return n > 0 && n <= 200 ? String.valueOf(n) : "--";
        } catch (Throwable ignored) { return "--"; }
    }

    private int parsePositive(String value, int fallback) {
        try {
            int n = Integer.parseInt(value.trim());
            return n >= 0 ? n : fallback;
        } catch (Throwable ignored) { return fallback; }
    }

    private String valueOrBlank(int value) { return value < 0 ? "" : String.valueOf(value); }

    private String readString(String key, String fallback) {
        try {
            Object v = prefs.getAll().get(key);
            if (v == null) return fallback;
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? fallback : s;
        } catch (Throwable ignored) { return fallback; }
    }

    private int readInt(String key, int fallback) {
        try {
            Object v = prefs.getAll().get(key);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v != null) return Integer.parseInt(String.valueOf(v));
        } catch (Throwable ignored) {}
        return fallback;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        c.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 22, this));
        return c;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, Ui.dp(this, 14));
        return p;
    }

    private TextView sectionTitle(String text) {
        TextView v = Ui.text(this, text, 15, Ui.ACCENT, true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 36));
        v.setLayoutParams(p);
        return v;
    }

    private TextView body(String text, int size, int color, boolean bold) {
        TextView v = Ui.text(this, text, size, color, bold);
        v.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        return v;
    }

    private LinearLayout.LayoutParams rowWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams spacedRow() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        return p;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);
        e.setTextSize(15);
        e.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        e.setBackground(Ui.rounded(0xFF0D1A29, Ui.STROKE, 16, this));
        return e;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 58));
        p.setMargins(0, Ui.dp(this, 8), 0, 0);
        return p;
    }

    private interface BoolCallback { void changed(boolean value); }

    private Switch addSwitch(LinearLayout parent, String label, boolean checked, BoolCallback callback) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Ui.TEXT);
        s.setTextSize(16);
        s.setGravity(Gravity.CENTER_VERTICAL);
        s.setChecked(checked);
        s.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        s.setOnCheckedChangeListener((buttonView, isChecked) -> callback.changed(isChecked));
        parent.addView(s, new LinearLayout.LayoutParams(-1, Ui.dp(this, 54)));
        return s;
    }

    private void addStyle(RadioGroup group, String label, String value, String selected) {
        RadioButton b = new RadioButton(this);
        b.setId(View.generateViewId());
        b.setTag(value);
        b.setText(label);
        b.setTextColor(Ui.TEXT);
        b.setChecked(value.equals(selected));
        group.addView(b, new RadioGroup.LayoutParams(0, Ui.dp(this, 48), 1f));
    }

    private void addButton(LinearLayout parent, String label, Runnable action) {
        TextView b = Ui.text(this, label, 15, Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.rounded(0xFF14263A, Ui.STROKE, 16, this));
        b.setOnClickListener(v -> action.run());
        Ui.press(b);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 56));
        p.setMargins(0, Ui.dp(this, 8), 0, 0);
        parent.addView(b, p);
    }

    private void addPrimaryButton(LinearLayout parent, String label, Runnable action) {
        TextView b = Ui.text(this, label, 16, 0xFF06131F, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.rounded(Ui.ACCENT, Ui.ACCENT, 18, this));
        b.setOnClickListener(v -> action.run());
        Ui.press(b);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 62));
        p.setMargins(0, Ui.dp(this, 8), 0, 0);
        parent.addView(b, p);
    }
}
