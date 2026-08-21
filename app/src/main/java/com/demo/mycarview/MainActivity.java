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
    private TextView crashStatus;
    private TextView sizeValue;
    private Switch hudSwitch;
    private EditText vietmapKeyInput;
    private boolean pendingStart;

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
        SharedPreferences.Editor e = prefs.edit();
        if (!prefs.contains("hud_style")) e.putString("hud_style", "neon");
        if (!prefs.contains("show_limit")) e.putBoolean("show_limit", true);
        if (!prefs.contains("show_next_limit")) e.putBoolean("show_next_limit", true);
        if (!prefs.contains("show_camera")) e.putBoolean("show_camera", true);
        if (!prefs.contains("alert_sound")) e.putBoolean("alert_sound", false);
        if (!prefs.contains("bubble_scale")) e.putInt("bubble_scale", 90);
        if (!prefs.contains("speed_source")) e.putString("speed_source", "gps");
        e.apply();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(16), dp(14), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = Ui.text(this, "CarHUD", 28, Ui.TEXT, true);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = body("GPS HUD · VIETMAP API · Android Auto", 12, Ui.MUTED, false);
        LinearLayout.LayoutParams subp = new LinearLayout.LayoutParams(-1, -2);
        subp.setMargins(0, dp(2), 0, dp(12));
        root.addView(subtitle, subp);

        LinearLayout status = card();
        status.addView(sectionTitle("TRẠNG THÁI"));
        permissionStatus = body("", 12, Ui.TEXT, false);
        runtimeStatus = body("", 12, Ui.TEXT, false);
        vietmapStatus = body("", 12, Ui.TEXT, false);
        crashStatus = body("", 11, Ui.MUTED, false);
        status.addView(permissionStatus, rowWrap());
        status.addView(runtimeStatus, rowWrap());
        status.addView(vietmapStatus, rowWrap());
        status.addView(crashStatus, rowWrap());
        root.addView(status, cardParams());

        LinearLayout hud = card();
        hud.addView(sectionTitle("HUD NỔI"));
        hudSwitch = addSwitch(hud, "Bật HUD", prefs.getBoolean("bubble_enabled", false), enabled -> {
            if (enabled) beginStartFlow(); else stopHud();
        });

        hud.addView(body("Kiểu HUD", 13, Ui.TEXT, true), spacedRow());
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
        hud.addView(styles, rowWrap());

        LinearLayout scaleRow = new LinearLayout(this);
        scaleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView scaleTitle = body("Kích thước HUD", 13, Ui.TEXT, true);
        sizeValue = body(prefs.getInt("bubble_scale", 90) + "%", 12, Ui.ACCENT, true);
        sizeValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        scaleRow.addView(scaleTitle, new LinearLayout.LayoutParams(0, dp(30), 1));
        scaleRow.addView(sizeValue, new LinearLayout.LayoutParams(dp(64), dp(30)));
        hud.addView(scaleRow, spacedRow());

        SeekBar size = new SeekBar(this);
        size.setMax(90); // 60..150
        int scaleNow = clamp(prefs.getInt("bubble_scale", 90), 60, 150);
        size.setProgress(scaleNow - 60);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int pending = scaleNow;
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pending = progress + 60;
                if (sizeValue != null) sizeValue.setText(pending + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt("bubble_scale", pending).apply();
                restartHudIfRunning();
            }
        });
        hud.addView(size, new LinearLayout.LayoutParams(-1, dp(42)));

        addSwitch(hud, "Hiện giới hạn tốc độ", prefs.getBoolean("show_limit", true),
                v -> { prefs.edit().putBoolean("show_limit", v).apply(); restartHudIfRunning(); });
        addSwitch(hud, "Hiện giới hạn kế tiếp", prefs.getBoolean("show_next_limit", true),
                v -> { prefs.edit().putBoolean("show_next_limit", v).apply(); restartHudIfRunning(); });
        addSwitch(hud, "Hiện camera/cảnh báo", prefs.getBoolean("show_camera", true),
                v -> { prefs.edit().putBoolean("show_camera", v).apply(); restartHudIfRunning(); });
        addSwitch(hud, "Âm báo camera ≤ 500 m", prefs.getBoolean("alert_sound", false),
                v -> prefs.edit().putBoolean("alert_sound", v).apply());
        root.addView(hud, cardParams());

        LinearLayout data = card();
        data.addView(sectionTitle("NGUỒN DỮ LIỆU"));
        String currentSource = prefs.getString("speed_source", "gps");
        RadioGroup sources = new RadioGroup(this);
        sources.setOrientation(RadioGroup.HORIZONTAL);
        addSource(sources, "GPS", "gps", currentSource);
        addSource(sources, "VIETMAP", "vietmap_api", currentSource);
        sources.setOnCheckedChangeListener((group, checkedId) -> {
            View v = group.findViewById(checkedId);
            if (v != null && v.getTag() instanceof String) {
                String source = (String) v.getTag();
                prefs.edit().putString("speed_source", source).apply();
                restartHudIfRunning();
                refreshStatus();
            }
        });
        data.addView(sources, new LinearLayout.LayoutParams(-1, dp(42)));

        data.addView(body(
                "VIETMAP: nhập Service API key để CarHUD lấy tên đường/vị trí thật từ VIETMAP. Tốc độ xe vẫn lấy GPS. Limit/camera chỉ tự điền khi API trả trường tương ứng.",
                11, Ui.MUTED, false), rowWrap());

        vietmapKeyInput = field("VIETMAP Service API key", prefs.getString("vietmap_api_key", ""));
        vietmapKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        data.addView(vietmapKeyInput, fieldParams());
        addButton(data, "Lưu & kiểm tra kết nối VIETMAP", this::testVietmap);
        addButton(data, "Xóa VIETMAP key", () -> {
            prefs.edit().remove("vietmap_api_key").remove("vietmap_road").putString("vietmap_status", "Chưa cấu hình").apply();
            vietmapKeyInput.setText("");
            refreshStatus();
        });
        root.addView(data, cardParams());

        LinearLayout permissions = card();
        permissions.addView(sectionTitle("QUYỀN"));
        addButton(permissions, "Cấp quyền GPS", this::requestLocation);
        addButton(permissions, "Cấp quyền hiển thị trên ứng dụng khác", this::openOverlaySettings);
        addButton(permissions, "Cài đặt thông báo", () -> {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
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

        LinearLayout aa = card();
        aa.addView(sectionTitle("ANDROID AUTO"));
        aa.addView(body(
                "Android Auto stock không cho overlay điện thoại chèn trực tiếp lên Google Maps của Android Auto. HUD overlay này dành cho màn Android/head unit chạy app trực tiếp; entry Android Auto của CarHUD vẫn tối giản riêng.",
                11, Ui.MUTED, false), rowWrap());
        root.addView(aa, cardParams());

        setContentView(scroll);
        refreshStatus();
    }

    private void testVietmap() {
        String key = vietmapKeyInput == null ? "" : vietmapKeyInput.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "Nhập VIETMAP Service API key trước", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putString("vietmap_api_key", key).putString("speed_source", "vietmap_api")
                .putString("vietmap_status", "Đang kiểm tra…").apply();
        refreshStatus();

        if (!hasLocationPermission()) {
            Toast.makeText(this, "Cần quyền GPS để test VIETMAP tại vị trí hiện tại", Toast.LENGTH_LONG).show();
            requestLocation();
            return;
        }
        Location location = bestLastLocation();
        if (location == null) {
            Toast.makeText(this, "Chưa có tọa độ GPS. Bật HUD/GPS vài giây rồi thử lại.", Toast.LENGTH_LONG).show();
            return;
        }
        VietmapApiClient.test(this, location, (ok, message, road) -> {
            refreshStatus();
            Toast.makeText(this, message + (road == null || road.isEmpty() ? "" : " · " + road), Toast.LENGTH_LONG).show();
            restartHudIfRunning();
        });
    }

    private Location bestLastLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return null;
            Location a = null, b = null;
            try { a = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Throwable ignored) {}
            try { b = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Throwable ignored) {}
            if (a == null) return b;
            if (b == null) return a;
            return a.getTime() >= b.getTime() ? a : b;
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
            startForegroundService(new Intent(this, SpeedBubbleService.class));
            Toast.makeText(this, "CarHUD đã bật", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            prefs.edit().putBoolean("bubble_enabled", false).apply();
            Toast.makeText(this, "Không bật được HUD: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
        refreshStatus();
    }

    private void stopHud() {
        prefs.edit().putBoolean("bubble_enabled", false).apply();
        try { stopService(new Intent(this, SpeedBubbleService.class)); } catch (Throwable ignored) {}
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
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
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
        permissionStatus.setText("GPS " + mark(gps) + " · Overlay " + mark(overlay) + " · TB " + mark(notify));
        runtimeStatus.setText("HUD " + (prefs.getBoolean("bubble_enabled", false) ? "BẬT" : "TẮT")
                + " · " + prefs.getString("hud_style", "neon")
                + " · " + prefs.getInt("bubble_scale", 90) + "%"
                + " · " + readString("speed", "--") + " km/h");
        String source = prefs.getString("speed_source", "gps");
        if ("vietmap_api".equals(source)) {
            String vm = prefs.getString("vietmap_status", "Chưa kiểm tra");
            String road = prefs.getString("vietmap_road", "");
            vietmapStatus.setText("VIETMAP: " + vm + (road == null || road.isEmpty() ? "" : " · " + road));
        } else {
            vietmapStatus.setText("Nguồn: GPS");
        }

        SharedPreferences d = getSharedPreferences("carhud_diag", MODE_PRIVATE);
        String crash = d.getString("last_crash", "");
        if (crash == null || crash.isEmpty()) crashStatus.setText("Crash log: không có");
        else crashStatus.setText("Crash: " + crash.split("\\n", 2)[0]);
    }

    private String mark(boolean ok) { return ok ? "OK" : "CHƯA"; }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        c.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 18, this));
        return c;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(10));
        return p;
    }

    private TextView sectionTitle(String text) {
        TextView v = Ui.text(this, text, 12, Ui.ACCENT, true);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(28)));
        return v;
    }

    private TextView body(String text, int size, int color, boolean bold) {
        TextView v = Ui.text(this, text, size, color, bold);
        v.setPadding(0, dp(2), 0, dp(2));
        return v;
    }

    private LinearLayout.LayoutParams rowWrap() { return new LinearLayout.LayoutParams(-1, -2); }

    private LinearLayout.LayoutParams spacedRow() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(7), 0, dp(2));
        return p;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);
        e.setTextSize(13);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(Ui.rounded(0xFF0D1A29, Ui.STROKE, 14, this));
        return e;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
        p.setMargins(0, dp(6), 0, 0);
        return p;
    }

    private interface BoolCallback { void changed(boolean value); }

    private Switch addSwitch(LinearLayout parent, String label, boolean checked, BoolCallback callback) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Ui.TEXT);
        s.setTextSize(13);
        s.setGravity(Gravity.CENTER_VERTICAL);
        s.setChecked(checked);
        s.setPadding(0, dp(1), 0, dp(1));
        s.setOnCheckedChangeListener((buttonView, isChecked) -> callback.changed(isChecked));
        parent.addView(s, new LinearLayout.LayoutParams(-1, dp(44)));
        return s;
    }

    private void addStyle(RadioGroup group, String label, String value, String selected) {
        RadioButton b = new RadioButton(this);
        b.setId(View.generateViewId());
        b.setTag(value);
        b.setText(label);
        b.setTextSize(12);
        b.setTextColor(Ui.TEXT);
        b.setChecked(value.equals(selected));
        group.addView(b, new RadioGroup.LayoutParams(0, dp(40), 1f));
    }

    private void addSource(RadioGroup group, String label, String value, String selected) {
        addStyle(group, label, value, selected);
    }

    private void addButton(LinearLayout parent, String label, Runnable action) {
        TextView b = Ui.text(this, label, 13, Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.rounded(0xFF14263A, Ui.STROKE, 14, this));
        b.setOnClickListener(v -> action.run());
        Ui.press(b);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
        p.setMargins(0, dp(6), 0, 0);
        parent.addView(b, p);
    }

    private void addPrimaryButton(LinearLayout parent, String label, Runnable action) {
        TextView b = Ui.text(this, label, 14, 0xFF06131F, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.rounded(Ui.ACCENT, Ui.ACCENT, 15, this));
        b.setOnClickListener(v -> action.run());
        Ui.press(b);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.setMargins(0, dp(6), 0, 0);
        parent.addView(b, p);
    }

    private String readString(String key, String fallback) {
        try {
            Object v = prefs.getAll().get(key);
            if (v == null) return fallback;
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? fallback : s;
        } catch (Throwable ignored) { return fallback; }
    }

    private int dp(float v) { return Ui.dp(this, v); }
    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
