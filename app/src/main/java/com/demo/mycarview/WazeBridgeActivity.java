package com.demo.mycarview;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

/** Small test screen for the Waze notification bridge. */
public class WazeBridgeActivity extends Activity {
    private static final String PREFS = "carview_settings";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 900L);
        }
    };

    private SharedPreferences prefs;
    private TextView accessStatus;
    private TextView captureStatus;
    private TextView titleValue;
    private TextView textValue;
    private TextView subValue;
    private TextView bigValue;
    private TextView timeValue;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshLoop);
        handler.post(refreshLoop);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshLoop);
        super.onPause();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = Ui.text(this, "Waze Bridge · thử nghiệm", 30, Ui.TEXT, true);
        root.addView(title, margin(-1, -2, 0, 0, 0, 8));

        TextView note = Ui.text(this,
                "CarHUD chỉ đọc nội dung Waze công khai qua Android Notification Access sau khi bạn tự cấp quyền. " +
                        "Không dùng Accessibility, OCR, chụp màn hình hay API ẩn. Tốc độ HUD vẫn lấy từ GPS điện thoại.",
                15, Ui.MUTED, false);
        note.setLineSpacing(0, 1.08f);
        root.addView(note, margin(-1, -2, 0, 0, 0, 18));

        accessStatus = statusCard(root, "Quyền thông báo");
        captureStatus = statusCard(root, "Trạng thái bridge");

        TextView grant = button("Bật quyền đọc thông báo Waze");
        grant.setOnClickListener(v -> openNotificationAccess());
        root.addView(grant, margin(-1, Ui.dp(this, 66), 0, 12, 0, 10));

        TextView open = button("Mở Waze và đặt nguồn HUD = Waze/GPS");
        open.setOnClickListener(v -> openWaze());
        root.addView(open, margin(-1, Ui.dp(this, 66), 0, 0, 0, 18));

        TextView h = Ui.text(this, "DỮ LIỆU WAZE ĐÃ NHẬN", 16, Ui.ACCENT, true);
        root.addView(h, margin(-1, -2, 0, 0, 0, 10));

        titleValue = dataCard(root, "Title");
        textValue = dataCard(root, "Text");
        subValue = dataCard(root, "Sub text");
        bigValue = dataCard(root, "Big text");
        timeValue = dataCard(root, "Thời điểm");

        TextView clear = button("Xoá dữ liệu thử");
        clear.setOnClickListener(v -> {
            prefs.edit()
                    .remove("waze_notification_title")
                    .remove("waze_notification_text")
                    .remove("waze_notification_sub_text")
                    .remove("waze_notification_big_text")
                    .remove("waze_notification_info_text")
                    .remove("waze_notification_summary")
                    .remove("waze_notification_when")
                    .apply();
            refresh();
        });
        root.addView(clear, margin(-1, Ui.dp(this, 66), 0, 14, 0, 0));

        setContentView(scroll);
    }

    private TextView statusCard(LinearLayout root, String label) {
        TextView t = Ui.text(this, label + ": --", 17, Ui.TEXT, true);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        t.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 18, this));
        root.addView(t, margin(-1, -2, 0, 0, 0, 10));
        return t;
    }

    private TextView dataCard(LinearLayout root, String label) {
        TextView t = Ui.text(this, label + ": --", 16, Ui.TEXT, false);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 11), Ui.dp(this, 16), Ui.dp(this, 11));
        t.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 16, this));
        t.setLineSpacing(0, 1.06f);
        root.addView(t, margin(-1, -2, 0, 0, 0, 8));
        return t;
    }

    private TextView button(String label) {
        TextView t = Ui.text(this, label, 17, 0xFF071321, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.rounded(Ui.ACCENT, Ui.ACCENT, 20, this));
        Ui.press(t);
        return t;
    }

    private void refresh() {
        if (prefs == null) return;
        boolean access = hasNotificationAccess();
        if (accessStatus != null) accessStatus.setText("Quyền thông báo: " + (access ? "ĐÃ BẬT" : "CHƯA BẬT"));

        String status = prefs.getString("waze_notification_status", "Chưa nhận dữ liệu Waze");
        if (captureStatus != null) captureStatus.setText("Trạng thái bridge: " + status);

        setData(titleValue, "Title", prefs.getString("waze_notification_title", ""));
        setData(textValue, "Text", prefs.getString("waze_notification_text", ""));
        setData(subValue, "Sub text", prefs.getString("waze_notification_sub_text", ""));
        setData(bigValue, "Big text", prefs.getString("waze_notification_big_text", ""));

        long when = prefs.getLong("waze_notification_when", 0L);
        String formatted = when <= 0L ? "--" : DateFormat.format("dd/MM HH:mm:ss", new Date(when)).toString();
        if (timeValue != null) timeValue.setText("Thời điểm: " + formatted);
    }

    private void setData(TextView view, String label, String value) {
        if (view == null) return;
        String v = value == null || value.trim().isEmpty() ? "--" : value.trim();
        view.setText(label + ": " + v);
    }

    private boolean hasNotificationAccess() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return enabled != null && enabled.contains(getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Throwable t) {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        }
    }

    private void openWaze() {
        prefs.edit().putString("speed_source", "waze").apply();
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.waze");
        if (launch != null) {
            startActivity(launch);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.waze")));
        } catch (Throwable t) {
            Toast.makeText(this, "Chưa cài Waze.", Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout.LayoutParams margin(int w, int h, int leftDp, int topDp, int rightDp, int bottomDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(Ui.dp(this, leftDp), Ui.dp(this, topDp), Ui.dp(this, rightDp), Ui.dp(this, bottomDp));
        return p;
    }
}
