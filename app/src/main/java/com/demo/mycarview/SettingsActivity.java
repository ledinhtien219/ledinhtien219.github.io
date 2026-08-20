package com.demo.mycarview;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private static final String PREFS = "carview_settings";
    private static final int REQ_MIC = 2001;
    private static final int REQ_LOCATION = 2002;

    interface ToggleCallback { void onChanged(boolean enabled); }

    private SharedPreferences prefs;
    private LinearLayout expandedPanel;
    private TextView expandedArrow;
    private TextView micButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        build();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = Ui.text(this, "Cài đặt CarView AA", 32, Ui.TEXT, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 24));
        root.addView(title, titleParams);

        LinearLayout language = addSection(root, "NGÔN NGỮ");
        buildLanguage(language);

        LinearLayout voice = addSection(root, "GIỌNG NÓI");
        buildVoice(voice);

        LinearLayout video = addSection(root, "PHÁT VIDEO");
        buildVideo(video);

        LinearLayout carUi = addSection(root, "GIAO DIỆN MÀN HÌNH XE");
        buildCarUi(carUi);

        LinearLayout bubble = addSection(root, "BONG BÓNG TỐC ĐỘ");
        buildBubble(bubble);

        TextView back = actionButton("Quay lại", false);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 76));
        bp.setMargins(0, Ui.dp(this, 20), 0, 0);
        root.addView(back, bp);

        setContentView(scroll);
    }

    private LinearLayout addSection(LinearLayout root, String label) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 14), 0);
        header.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 22, this));

        TextView text = Ui.text(this, label, 18, Ui.ACCENT, true);
        TextView arrow = Ui.text(this, "›", 40, Ui.ACCENT, false);
        arrow.setGravity(Gravity.CENTER);
        header.addView(text, new LinearLayout.LayoutParams(0, -1, 1));
        header.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(this, 48), -1));
        Ui.press(header);

        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 104));
        hp.setMargins(0, 0, 0, Ui.dp(this, 12));
        root.addView(header, hp);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18));
        panel.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 22, this));
        panel.setVisibility(View.GONE);

        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.setMargins(0, Ui.dp(this, -4), 0, Ui.dp(this, 14));
        root.addView(panel, pp);

        header.setOnClickListener(v -> toggleSection(panel, arrow));
        return panel;
    }

    private void toggleSection(LinearLayout panel, TextView arrow) {
        if (panel.getVisibility() == View.VISIBLE) {
            panel.setVisibility(View.GONE);
            arrow.setText("›");
            if (expandedPanel == panel) {
                expandedPanel = null;
                expandedArrow = null;
            }
            return;
        }
        if (expandedPanel != null) {
            expandedPanel.setVisibility(View.GONE);
            if (expandedArrow != null) expandedArrow.setText("›");
        }
        panel.setVisibility(View.VISIBLE);
        arrow.setText("⌄");
        expandedPanel = panel;
        expandedArrow = arrow;
    }

    private void buildLanguage(LinearLayout panel) {
        TextView heading = body("Ngôn ngữ hiển thị", 18, Ui.TEXT, true);
        panel.addView(heading, rowParams());

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        String current = prefs.getString("language", "vi");
        RadioButton vi = radio("Tiếng Việt", "vi".equals(current));
        RadioButton en = radio("English", "en".equals(current));
        vi.setId(View.generateViewId());
        en.setId(View.generateViewId());
        group.addView(vi, radioParams());
        group.addView(en, radioParams());
        vi.setOnClickListener(v -> prefs.edit().putString("language", "vi").apply());
        en.setOnClickListener(v -> prefs.edit().putString("language", "en").apply());
        panel.addView(group, rowParams());
    }

    private void buildVoice(LinearLayout panel) {
        addSwitchSetting(panel,
                "voice_control",
                "Bật điều khiển giọng nói trên xe",
                "Cho phép tìm kiếm bằng nút mic hoặc nhấn 2 lần nút quay lại (Previous) trên vô lăng.",
                true,
                null);

        addSwitchSetting(panel,
                "voice_auto_play_first",
                "Tự phát kết quả tìm kiếm đầu tiên",
                "Sau khi nhận lệnh giọng nói, tự động mở và phát video đầu tiên trong kết quả tìm kiếm.",
                true,
                null);

        micButton = actionButton(hasMicPermission() ? "Đã cấp quyền microphone" : "Cấp quyền microphone", true);
        micButton.setOnClickListener(v -> requestMic());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 68));
        p.setMargins(0, Ui.dp(this, 10), 0, 0);
        panel.addView(micButton, p);
    }

    private void buildVideo(LinearLayout panel) {
        addSwitchSetting(panel,
                "auto_resume",
                "Tự phát nội dung gần nhất khi mở Android Auto",
                "Khi bật, CarView AA sẽ mở lại nội dung gần nhất. Khi tắt, ứng dụng bắt đầu tại trang chủ YouTube.",
                true,
                null);

        addSwitchSetting(panel,
                "background_playback",
                "Tiếp tục phát khi chạy nền",
                "Giữ phiên trình duyệt và dịch vụ phát khi chuyển sang ứng dụng khác.",
                true,
                enabled -> {
                    Intent svc = new Intent(this, BrowserKeepAliveService.class);
                    if (enabled) startForegroundService(svc); else stopService(svc);
                });

        addSwitchSetting(panel,
                "ad_filter",
                "Lọc quảng cáo và tracker trong WebView",
                "Chặn các request quảng cáo/tracker phổ biến. YouTube có thể thay đổi cách phân phối quảng cáo nên không bảo đảm chặn tuyệt đối.",
                true,
                null);
    }

    private void buildCarUi(LinearLayout panel) {
        addSwitchSetting(panel,
                "car_auto_fullscreen",
                "Tự động toàn màn hình video trên xe",
                "Khi video bắt đầu phát, CarView AA sẽ ưu tiên chế độ toàn màn hình.",
                true,
                null);

        addSwitchSetting(panel,
                "show_web_browser",
                "Hiển thị nút trình duyệt web / Show Web Browser button",
                null,
                true,
                null);

        TextView heading = body("Nút menu bên trái", 17, Ui.ACCENT, true);
        LinearLayout.LayoutParams hp = rowParams();
        hp.setMargins(0, Ui.dp(this, 14), 0, 0);
        panel.addView(heading, hp);

        TextView hint = body("Chọn các nút sẽ hiển thị trong menu bên trái trên màn hình xe.", 15, Ui.MUTED, false);
        panel.addView(hint, rowParams());

        addSlider(panel, "car_icon_scale", "Kích thước biểu tượng", 60, 220, 100,
                "Kéo để điều chỉnh kích thước. Khi ẩn bớt nút, các nút còn lại có thể dùng nhiều không gian hơn.");

        addSwitchSetting(panel, "car_btn_voice", "Hiển thị nút tìm kiếm giọng nói", null, true, null);
        addSwitchSetting(panel, "car_btn_previous", "Hiển thị nút Previous", null, false, null);
        addSwitchSetting(panel, "car_btn_play_pause", "Hiển thị nút Play/Pause", null, true, null);
        addSwitchSetting(panel, "car_btn_next", "Hiển thị nút Next", null, true, null);
        addSwitchSetting(panel, "car_btn_back", "Hiển thị nút Back", null, true, null);
        addSwitchSetting(panel, "car_btn_home", "Hiển thị nút Home", null, false, null);
        addSwitchSetting(panel, "car_btn_refresh", "Hiển thị nút Refresh", null, false, null);
        addSwitchSetting(panel, "car_btn_aspect", "Hiển thị nút đổi tỉ lệ video", null, true, null);

        TextView preview = actionButton("Xem thử giao diện màn hình xe", true);
        preview.setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra("car_preview", true);
            startActivity(i);
        });
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 68));
        pp.setMargins(0, Ui.dp(this, 14), 0, 0);
        panel.addView(preview, pp);
    }

    private void buildBubble(LinearLayout panel) {
        addSwitchSetting(panel,
                "bubble_enabled",
                "Hiển thị bong bóng tốc độ",
                "Hiển thị thông tin tốc độ từ nguồn đã chọn trong một bong bóng nổi.",
                false,
                enabled -> {
                    if (enabled) {
                        if (!Settings.canDrawOverlays(this)) {
                            prefs.edit().putBoolean("bubble_enabled", false).apply();
                            Toast.makeText(this, "Cần cấp quyền Hiển thị trên ứng dụng khác. Sau khi cấp, bật lại công tắc.", Toast.LENGTH_LONG).show();
                            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } else {
                            startBubble();
                        }
                    } else {
                        stopService(new Intent(this, SpeedBubbleService.class));
                    }
                });

        TextView sourceTitle = body("Nguồn dữ liệu tốc độ", 17, Ui.TEXT, true);
        LinearLayout.LayoutParams st = rowParams();
        st.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        panel.addView(sourceTitle, st);

        RadioGroup sources = new RadioGroup(this);
        sources.setOrientation(RadioGroup.VERTICAL);
        String selected = prefs.getString("speed_source", "vietmap");
        RadioButton vietmap = radio("VIETMAP LIVE", "vietmap".equals(selected));
        RadioButton waze = radio("Waze (tốc độ GPS)", "waze".equals(selected));
        RadioButton wyn = radio("Wyn", "wyn".equals(selected));
        vietmap.setId(View.generateViewId());
        waze.setId(View.generateViewId());
        wyn.setId(View.generateViewId());
        sources.addView(vietmap, radioParams());
        sources.addView(waze, radioParams());
        sources.addView(wyn, radioParams());

        vietmap.setOnClickListener(v -> {
            prefs.edit().putString("speed_source", "vietmap").apply();
            restartBubbleIfEnabled();
        });
        waze.setOnClickListener(v -> {
            prefs.edit().putString("speed_source", "waze").apply();
            if (!hasLocationPermission()) requestLocation();
            else restartBubbleIfEnabled();
        });
        wyn.setOnClickListener(v -> {
            prefs.edit().putString("speed_source", "wyn").apply();
            restartBubbleIfEnabled();
        });
        panel.addView(sources, rowParams());

        addSlider(panel, "bubble_scale", "Kích thước bong bóng", 60, 220, 100,
                "Điều chỉnh từ 60% đến 220%. Toàn bộ nội dung thay đổi kích thước cùng bong bóng.");

        addSwitchSetting(panel,
                "bubble_hide_button",
                "Hiển thị nút ẩn/hiện bong bóng tốc độ",
                null,
                false,
                null);

        addSwitchSetting(panel,
                "bubble_camera_zone",
                "Hiển thị camera và biển khu dân cư",
                "VIETMAP có thể dùng phần này ở bản kết nối dữ liệu sau. Waze hiện chỉ dùng tốc độ GPS vì Waze không cấp API public cho speed-limit/camera.",
                true,
                null);

        TextView note = body("Waze: CarView lấy tốc độ hiện tại từ GPS của điện thoại và mở Waze để dẫn đường. Giới hạn tốc độ/camera của Waze không được đọc trực tiếp.", 15, Ui.MUTED, false);
        LinearLayout.LayoutParams np = rowParams();
        np.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 10));
        panel.addView(note, np);

        TextView connectVietmap = actionButton("Kết nối VIETMAP LIVE", true);
        connectVietmap.setOnClickListener(v -> connectVietmap());
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 68));
        vp.setMargins(0, 0, 0, Ui.dp(this, 10));
        panel.addView(connectVietmap, vp);

        TextView connectWaze = actionButton(hasLocationPermission() ? "Mở Waze · GPS đã sẵn sàng" : "Kết nối Waze · Cấp quyền GPS", true);
        connectWaze.setOnClickListener(v -> connectWaze());
        panel.addView(connectWaze, new LinearLayout.LayoutParams(-1, Ui.dp(this, 68)));
    }

    private Switch addSwitchSetting(LinearLayout parent,
                                    String key,
                                    String title,
                                    String description,
                                    boolean defaultValue,
                                    ToggleCallback callback) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = body(title, 18, Ui.TEXT, false);
        Switch sw = new Switch(this);
        sw.setShowText(false);
        tintSwitch(sw);
        sw.setChecked(prefs.getBoolean(key, defaultValue));

        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(this, 70), Ui.dp(this, 54));
        sp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        row.addView(sw, sp);
        block.addView(row, new LinearLayout.LayoutParams(-1, -2));

        if (description != null && !description.isEmpty()) {
            TextView desc = body(description, 15, Ui.MUTED, false);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
            dp.setMargins(0, Ui.dp(this, 5), Ui.dp(this, 54), 0);
            block.addView(desc, dp);
        }

        sw.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            if (callback != null) callback.onChanged(checked);
        });
        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));

        parent.addView(block, new LinearLayout.LayoutParams(-1, -2));
        return sw;
    }

    private void addSlider(LinearLayout parent,
                           String key,
                           String title,
                           int min,
                           int max,
                           int defaultValue,
                           String description) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 12));

        int saved = prefs.getInt(key, defaultValue);
        TextView value = body(title + ": " + saved + "%", 18, Ui.TEXT, false);
        block.addView(value, rowParams());

        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(saved - min);
        seek.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54));
        sp.setMargins(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), 0);
        block.addView(seek, sp);

        TextView desc = body(description, 15, Ui.MUTED, false);
        block.addView(desc, rowParams());

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int actual = min + progress;
                value.setText(title + ": " + actual + "%");
                prefs.edit().putInt(key, actual).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                restartBubbleIfEnabled();
            }
        });

        parent.addView(block, new LinearLayout.LayoutParams(-1, -2));
    }

    private RadioButton radio(String label, boolean checked) {
        RadioButton r = new RadioButton(this);
        r.setText(label);
        r.setTextColor(Ui.TEXT);
        r.setTextSize(18);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setChecked(checked);
        r.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Ui.ACCENT, 0xFFB7BEC8}));
        return r;
    }

    private LinearLayout.LayoutParams radioParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 66));
        p.setMargins(0, 0, 0, Ui.dp(this, 1));
        return p;
    }

    private TextView body(String text, float sp, int color, boolean bold) {
        TextView t = Ui.text(this, text, sp, color, bold);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setLineSpacing(0, 1.05f);
        return t;
    }

    private TextView actionButton(String label, boolean accent) {
        int bg = accent ? Ui.ACCENT : Ui.PANEL;
        int stroke = accent ? Ui.ACCENT : Ui.STROKE;
        int fg = accent ? 0xFF071321 : Ui.TEXT;
        TextView t = Ui.text(this, label, 18, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.rounded(bg, stroke, 22, this));
        Ui.press(t);
        return t;
    }

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private void tintSwitch(Switch sw) {
        sw.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Ui.ACCENT, 0xFFD0D0D0}));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFF236E68, 0xFF596575}));
    }

    private boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMic() {
        if (hasMicPermission()) {
            Toast.makeText(this, "Microphone đã được cấp quyền.", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocation() {
        if (hasLocationPermission()) {
            restartBubbleIfEnabled();
            return;
        }
        Toast.makeText(this, "Chọn vị trí chính xác để tốc độ GPS cập nhật ổn định.", Toast.LENGTH_LONG).show();
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        }, REQ_LOCATION);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && micButton != null) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            micButton.setText(granted ? "Đã cấp quyền microphone" : "Cấp quyền microphone");
            return;
        }
        if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                Toast.makeText(this, "GPS đã sẵn sàng cho bong bóng Waze.", Toast.LENGTH_SHORT).show();
                restartBubbleIfEnabled();
            } else {
                Toast.makeText(this, "Cần quyền vị trí chính xác để hiển thị tốc độ GPS.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startBubble() {
        String source = prefs.getString("speed_source", "vietmap");
        if ("waze".equals(source) && !hasLocationPermission()) {
            prefs.edit().putBoolean("bubble_enabled", true).putString("speed", "0").apply();
            requestLocation();
            return;
        }
        if ("waze".equals(source)) {
            prefs.edit().putString("speed", "0").apply();
        } else if (prefs.getString("speed", null) == null) {
            prefs.edit().putString("speed", "67").apply();
        }
        startForegroundService(new Intent(this, SpeedBubbleService.class));
        Toast.makeText(this, "Đã bật bong bóng tốc độ.", Toast.LENGTH_SHORT).show();
    }

    private void restartBubbleIfEnabled() {
        if (!prefs.getBoolean("bubble_enabled", false) || !Settings.canDrawOverlays(this)) return;
        stopService(new Intent(this, SpeedBubbleService.class));
        startBubble();
    }

    private void connectVietmap() {
        prefs.edit().putString("speed_source", "vietmap").apply();
        restartBubbleIfEnabled();
        String[] packages = {"vn.vietmap.live", "vn.vietmap.live.v2"};
        for (String pkg : packages) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                startActivity(launch);
                return;
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=vn.vietmap.live")));
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=vn.vietmap.live")));
        }
    }

    private void connectWaze() {
        prefs.edit().putString("speed_source", "waze").apply();
        if (!hasLocationPermission()) requestLocation();
        else restartBubbleIfEnabled();

        Intent launch = getPackageManager().getLaunchIntentForPackage("com.waze");
        if (launch != null) {
            startActivity(launch);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.waze")));
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.waze")));
        }
    }
}
