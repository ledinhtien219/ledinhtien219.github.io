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
    interface SliderCallback { void onStopped(int value); }

    private SharedPreferences prefs;
    private LinearLayout expandedPanel;
    private TextView expandedArrow;
    private TextView micButton;
    private TextView wazeButton;
    private Switch bubbleSwitch;
    private boolean pendingOverlayEnable;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs == null) return;

        if (pendingOverlayEnable && Settings.canDrawOverlays(this)) {
            pendingOverlayEnable = false;
            prefs.edit().putBoolean("bubble_enabled", true).apply();
            if (bubbleSwitch != null) bubbleSwitch.setChecked(true);
            startBubble();
        } else if (prefs.getBoolean("bubble_enabled", false)) {
            if (Settings.canDrawOverlays(this)) {
                startBubbleServiceOnly();
            } else {
                prefs.edit().putBoolean("bubble_enabled", false).apply();
                if (bubbleSwitch != null) bubbleSwitch.setChecked(false);
            }
        }

        if (micButton != null) {
            micButton.setText(hasMicPermission()
                    ? tr("Đã cấp quyền microphone", "Microphone granted")
                    : tr("Cấp quyền microphone", "Grant microphone"));
        }
        if (wazeButton != null) updateWazeButton();
    }

    private boolean isEnglish() {
        return "en".equals(prefs.getString("language", "vi"));
    }

    private String tr(String vi, String en) {
        return isEnglish() ? en : vi;
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = Ui.text(this, tr("Cài đặt CarView AA", "CarView AA Settings"), 32, Ui.TEXT, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 24));
        root.addView(title, titleParams);

        LinearLayout language = addSection(root, tr("NGÔN NGỮ", "LANGUAGE"));
        buildLanguage(language);

        LinearLayout voice = addSection(root, tr("GIỌNG NÓI", "VOICE"));
        buildVoice(voice);

        LinearLayout video = addSection(root, tr("PHÁT VIDEO", "VIDEO PLAYBACK"));
        buildVideo(video);

        LinearLayout carUi = addSection(root, tr("GIAO DIỆN MÀN HÌNH XE", "CAR SCREEN UI"));
        buildCarUi(carUi);

        LinearLayout bubble = addSection(root, tr("BONG BÓNG TỐC ĐỘ", "SPEED BUBBLE"));
        buildBubble(bubble);

        TextView back = actionButton(tr("Quay lại", "Back"), false);
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
        panel.addView(body(tr("Ngôn ngữ hiển thị", "Display language"), 18, Ui.TEXT, true), rowParams());

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        String current = prefs.getString("language", "vi");
        RadioButton vi = radio("Tiếng Việt", "vi".equals(current));
        RadioButton en = radio("English", "en".equals(current));
        vi.setId(View.generateViewId());
        en.setId(View.generateViewId());
        group.addView(vi, radioParams());
        group.addView(en, radioParams());
        vi.setOnClickListener(v -> changeLanguage("vi"));
        en.setOnClickListener(v -> changeLanguage("en"));
        panel.addView(group, rowParams());
    }

    private void changeLanguage(String language) {
        if (language.equals(prefs.getString("language", "vi"))) return;
        prefs.edit().putString("language", language).apply();
        recreate();
    }

    private void buildVoice(LinearLayout panel) {
        addSwitchSetting(panel,
                "voice_control",
                tr("Bật điều khiển giọng nói trên xe", "Enable voice control on car screen"),
                tr("Nút mic sẽ tìm kiếm YouTube bằng giọng nói.", "The microphone button searches YouTube by voice."),
                true,
                null);

        addSwitchSetting(panel,
                "voice_auto_play_first",
                tr("Tự phát kết quả tìm kiếm đầu tiên", "Auto-play first voice result"),
                tr("Sau khi nhận lệnh, mở kết quả YouTube đầu tiên thay vì chỉ hiện danh sách.", "After recognition, open the first YouTube result instead of only showing the result list."),
                true,
                null);

        micButton = actionButton(hasMicPermission()
                ? tr("Đã cấp quyền microphone", "Microphone granted")
                : tr("Cấp quyền microphone", "Grant microphone"), true);
        micButton.setOnClickListener(v -> requestMic());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 68));
        p.setMargins(0, Ui.dp(this, 10), 0, 0);
        panel.addView(micButton, p);
    }

    private void buildVideo(LinearLayout panel) {
        addSwitchSetting(panel,
                "auto_resume",
                tr("Mở lại nội dung gần nhất", "Resume last page"),
                tr("Khi bật, CarView mở lại URL gần nhất. Khi tắt, luôn bắt đầu tại YouTube.", "When enabled, CarView reopens the last URL. When disabled, it starts at YouTube."),
                true,
                null);

        addSwitchSetting(panel,
                "background_playback",
                tr("Tiếp tục phát khi chạy nền", "Keep background session"),
                tr("Bật dịch vụ nền để giữ phiên trình duyệt. Video fullscreen có thể chuyển sang PiP khi rời app.", "Runs a foreground service to keep the browser session. Fullscreen video may enter PiP when leaving the app."),
                true,
                enabled -> {
                    Intent svc = new Intent(this, BrowserKeepAliveService.class);
                    if (enabled) startForegroundService(svc); else stopService(svc);
                });

        addSwitchSetting(panel,
                "ad_filter",
                tr("Lọc quảng cáo và tracker trong WebView", "Filter ads and trackers in WebView"),
                tr("Chặn các host quảng cáo/tracker phổ biến. Không bảo đảm chặn toàn bộ quảng cáo YouTube.", "Blocks common ad/tracker hosts. This does not guarantee all YouTube ads are blocked."),
                true,
                null);
    }

    private void buildCarUi(LinearLayout panel) {
        addSwitchSetting(panel,
                "car_auto_fullscreen",
                tr("Tự động toàn màn hình video trên xe", "Auto fullscreen video on car screen"),
                tr("Khi video bắt đầu phát trong Car Preview, CarView sẽ yêu cầu fullscreen nếu trang cho phép.", "When video starts in Car Preview, CarView requests fullscreen when the page allows it."),
                true,
                null);

        addSwitchSetting(panel,
                "show_web_browser",
                tr("Hiển thị nút trình duyệt web", "Show web browser button"),
                tr("Hiện nút 🌐 ở menu trái để mở/đóng thanh URL.", "Shows a 🌐 button in the left rail to toggle the URL bar."),
                true,
                null);

        TextView heading = body(tr("Nút menu bên trái", "Left-side menu buttons"), 17, Ui.ACCENT, true);
        LinearLayout.LayoutParams hp = rowParams();
        hp.setMargins(0, Ui.dp(this, 14), 0, 0);
        panel.addView(heading, hp);

        TextView hint = body(tr(
                "Các công tắc dưới đây thay đổi trực tiếp menu trái trong Car Preview.",
                "The switches below directly control the left rail in Car Preview."), 15, Ui.MUTED, false);
        panel.addView(hint, rowParams());

        addSlider(panel, "car_icon_scale", tr("Kích thước biểu tượng", "Icon size"), 60, 220, 100,
                tr("Có hiệu lực khi quay lại Car Preview.", "Applies when returning to Car Preview."),
                null);

        addSwitchSetting(panel, "car_btn_voice", tr("Hiển thị nút tìm kiếm giọng nói", "Show voice search"), null, true, null);
        addSwitchSetting(panel, "car_btn_previous", tr("Hiển thị nút Previous / tua -10 giây", "Show Previous / seek -10s"), null, false, null);
        addSwitchSetting(panel, "car_btn_play_pause", tr("Hiển thị nút Play/Pause", "Show Play/Pause"), null, true, null);
        addSwitchSetting(panel, "car_btn_next", tr("Hiển thị nút Next / tua +10 giây", "Show Next / seek +10s"), null, true, null);
        addSwitchSetting(panel, "car_btn_back", tr("Hiển thị nút Back", "Show Back"), null, true, null);
        addSwitchSetting(panel, "car_btn_home", tr("Hiển thị nút Home", "Show Home"), null, false, null);
        addSwitchSetting(panel, "car_btn_refresh", tr("Hiển thị nút Refresh", "Show Refresh"), null, false, null);
        addSwitchSetting(panel, "car_btn_aspect", tr("Hiển thị nút đổi tỉ lệ video", "Show video aspect button"), null, true, null);

        TextView preview = actionButton(tr("Xem thử giao diện màn hình xe", "Open Car Preview"), true);
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
        bubbleSwitch = addSwitchSetting(panel,
                "bubble_enabled",
                tr("Hiển thị bong bóng tốc độ", "Show speed bubble"),
                tr("Bong bóng nổi trên ứng dụng điện thoại khác. Car Preview cũng hiển thị bubble riêng trong giao diện xe.", "The phone overlay floats above other phone apps. Car Preview also renders its own bubble inside the car UI."),
                false,
                enabled -> {
                    if (enabled) {
                        if (!Settings.canDrawOverlays(this)) {
                            prefs.edit().putBoolean("bubble_enabled", false).apply();
                            pendingOverlayEnable = true;
                            if (bubbleSwitch != null && bubbleSwitch.isChecked()) {
                                bubbleSwitch.post(() -> bubbleSwitch.setChecked(false));
                            }
                            Toast.makeText(this,
                                    tr("Cấp quyền 'Hiển thị trên ứng dụng khác', rồi quay lại CarView.", "Grant 'Display over other apps', then return to CarView."),
                                    Toast.LENGTH_LONG).show();
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

        TextView sourceTitle = body(tr("Nguồn dữ liệu tốc độ", "Speed data source"), 17, Ui.TEXT, true);
        LinearLayout.LayoutParams st = rowParams();
        st.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        panel.addView(sourceTitle, st);

        RadioGroup sources = new RadioGroup(this);
        sources.setOrientation(RadioGroup.VERTICAL);
        String selected = prefs.getString("speed_source", "vietmap");
        RadioButton vietmap = radio("VIETMAP LIVE", "vietmap".equals(selected));
        RadioButton waze = radio("Waze · GPS", "waze".equals(selected));
        RadioButton wyn = radio("Wyn", "wyn".equals(selected));
        vietmap.setId(View.generateViewId());
        waze.setId(View.generateViewId());
        wyn.setId(View.generateViewId());
        sources.addView(vietmap, radioParams());
        sources.addView(waze, radioParams());
        sources.addView(wyn, radioParams());

        vietmap.setOnClickListener(v -> changeSpeedSource("vietmap"));
        waze.setOnClickListener(v -> {
            changeSpeedSource("waze");
            if (!hasLocationPermission()) requestLocation();
        });
        wyn.setOnClickListener(v -> changeSpeedSource("wyn"));
        panel.addView(sources, rowParams());

        addSlider(panel, "bubble_scale", tr("Kích thước bong bóng", "Bubble size"), 60, 220, 100,
                tr("Thay đổi kích thước overlay ngay sau khi thả thanh trượt.", "Resizes the overlay after releasing the slider."),
                value -> restartBubbleIfEnabled());

        addSwitchSetting(panel,
                "bubble_hide_button",
                tr("Hiển thị nút × để tắt bong bóng", "Show × button to hide bubble"),
                tr("Nút × nằm ở góc bong bóng và tắt overlay ngay lập tức.", "The × button sits on the bubble and immediately turns the overlay off."),
                false,
                enabled -> restartBubbleIfEnabled());

        addSwitchSetting(panel,
                "bubble_camera_zone",
                tr("Hiển thị vùng camera / khu dân cư khi nguồn hỗ trợ", "Show camera / residential status when supported"),
                tr("Hiện tại Waze chỉ dùng tốc độ GPS nên mục này không hiển thị dữ liệu Waze giả.", "Waze currently supplies GPS speed only, so CarView does not fabricate Waze camera data."),
                true,
                enabled -> restartBubbleIfEnabled());

        TextView note = body(tr(
                "Waze: tốc độ hiện tại lấy trực tiếp từ GPS điện thoại. VIETMAP/Wyn hiện mới có nút mở ứng dụng; chưa đọc dữ liệu private của ứng dụng khác.",
                "Waze: current speed comes directly from phone GPS. VIETMAP/Wyn currently only launch their apps; CarView does not read private app data."), 15, Ui.MUTED, false);
        LinearLayout.LayoutParams np = rowParams();
        np.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 10));
        panel.addView(note, np);

        TextView connectVietmap = actionButton(tr("Mở VIETMAP LIVE", "Open VIETMAP LIVE"), true);
        connectVietmap.setOnClickListener(v -> connectVietmap());
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 68));
        vp.setMargins(0, 0, 0, Ui.dp(this, 10));
        panel.addView(connectVietmap, vp);

        wazeButton = actionButton("", true);
        updateWazeButton();
        wazeButton.setOnClickListener(v -> connectWaze());
        panel.addView(wazeButton, new LinearLayout.LayoutParams(-1, Ui.dp(this, 68)));
    }

    private void updateWazeButton() {
        if (wazeButton == null) return;
        wazeButton.setText(hasLocationPermission()
                ? tr("Mở Waze · GPS sẵn sàng", "Open Waze · GPS ready")
                : tr("Kết nối Waze · Cấp quyền GPS", "Connect Waze · Grant GPS"));
    }

    private void changeSpeedSource(String source) {
        prefs.edit().putString("speed_source", source).apply();
        if ("waze".equals(source)) prefs.edit().putString("speed", "0").apply();
        restartBubbleIfEnabled();
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
                           String description,
                           SliderCallback callback) {
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
                if (callback != null) callback.onStopped(min + seekBar.getProgress());
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
            Toast.makeText(this, tr("Microphone đã được cấp quyền.", "Microphone permission is already granted."), Toast.LENGTH_SHORT).show();
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
            updateWazeButton();
            return;
        }
        Toast.makeText(this,
                tr("Chọn vị trí chính xác để tốc độ GPS ổn định.", "Choose precise location for reliable GPS speed."),
                Toast.LENGTH_LONG).show();
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        }, REQ_LOCATION);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (micButton != null) {
                micButton.setText(hasMicPermission()
                        ? tr("Đã cấp quyền microphone", "Microphone granted")
                        : tr("Cấp quyền microphone", "Grant microphone"));
            }
            return;
        }
        if (requestCode == REQ_LOCATION) {
            updateWazeButton();
            if (hasLocationPermission()) {
                Toast.makeText(this, tr("GPS đã sẵn sàng cho Waze.", "GPS is ready for Waze."), Toast.LENGTH_SHORT).show();
                restartBubbleIfEnabled();
            } else {
                Toast.makeText(this,
                        tr("Cần quyền vị trí chính xác để hiển thị tốc độ GPS.", "Precise location is required for GPS speed."),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startBubble() {
        String source = prefs.getString("speed_source", "vietmap");
        if ("waze".equals(source) && !hasLocationPermission()) {
            requestLocation();
            return;
        }
        if ("waze".equals(source)) prefs.edit().putString("speed", "0").apply();
        startBubbleServiceOnly();
        Toast.makeText(this, tr("Đã bật bong bóng tốc độ.", "Speed bubble enabled."), Toast.LENGTH_SHORT).show();
    }

    private void startBubbleServiceOnly() {
        if (!Settings.canDrawOverlays(this)) return;
        startForegroundService(new Intent(this, SpeedBubbleService.class));
    }

    private void restartBubbleIfEnabled() {
        if (!prefs.getBoolean("bubble_enabled", false) || !Settings.canDrawOverlays(this)) return;
        stopService(new Intent(this, SpeedBubbleService.class));
        startBubble();
    }

    private void connectVietmap() {
        changeSpeedSource("vietmap");
        String[] packages = {"vn.vietmap.live", "vn.vietmap.live.v2"};
        for (String pkg : packages) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                startActivity(launch);
                return;
            }
        }
        openStore("vn.vietmap.live");
    }

    private void connectWaze() {
        changeSpeedSource("waze");
        if (!hasLocationPermission()) requestLocation();

        Intent launch = getPackageManager().getLaunchIntentForPackage("com.waze");
        if (launch != null) {
            startActivity(launch);
            return;
        }
        openStore("com.waze");
    }

    private void openStore(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
        }
    }
}
