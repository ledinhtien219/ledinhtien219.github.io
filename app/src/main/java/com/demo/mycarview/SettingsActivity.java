package com.demo.mycarview;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 30));
        scroll.addView(root);

        TextView title = Ui.text(this, "Cài đặt MyCar View", 32, Ui.TEXT, true);
        root.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 92)));

        addCard(root, "NGÔN NGỮ", v -> Toast.makeText(this, "Demo: Tiếng Việt", Toast.LENGTH_SHORT).show());
        addCard(root, "GIỌNG NÓI", v -> Toast.makeText(this, "Demo: phần SpeechRecognizer/TTS sẽ làm ở bản sau", Toast.LENGTH_LONG).show());
        addCard(root, "PHÁT VIDEO", v -> Toast.makeText(this, "HTML5 autoplay + fullscreen WebChromeClient đang bật", Toast.LENGTH_LONG).show());
        addCard(root, "GIAO DIỆN MÀN HÌNH XE", v -> {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra("car_preview", true);
            startActivity(i);
        });
        addCard(root, "BONG BÓNG TỐC ĐỘ", v -> toggleBubble());

        TextView note = Ui.text(this,
                "Bong bóng hiện tại là dữ liệu demo 80 / 67 do app tự vẽ, chưa lấy dữ liệu từ VIETMAP LIVE. Car Preview cũng chỉ là preview landscape trên điện thoại, chưa phải projection Android Auto.",
                15, Ui.MUTED, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.setMargins(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 18));
        root.addView(note, np);

        TextView stop = action("Tắt bong bóng");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, SpeedBubbleService.class));
            Toast.makeText(this, "Đã tắt bong bóng", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop, actionParams());

        TextView back = action("Quay lại");
        back.setOnClickListener(v -> finish());
        root.addView(back, actionParams());

        setContentView(scroll);
    }

    private void toggleBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Cấp quyền 'Hiển thị trên ứng dụng khác', sau đó bấm lại BONG BÓNG TỐC ĐỘ.", Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        }
        getSharedPreferences("demo", MODE_PRIVATE).edit()
                .putString("limit", "80")
                .putString("speed", "67")
                .apply();
        startForegroundService(new Intent(this, SpeedBubbleService.class));
        Toast.makeText(this, "Đã bật bong bóng demo. Có thể kéo nó trên màn hình.", Toast.LENGTH_LONG).show();
    }

    private void addCard(LinearLayout root, String label, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 18), 0);
        row.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 22, this));

        TextView t = Ui.text(this, label, 18, Ui.ACCENT, true);
        TextView arrow = Ui.text(this, "›", 40, Ui.ACCENT, false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(t, new LinearLayout.LayoutParams(0, -1, 1));
        row.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(this, 46), -1));
        row.setOnClickListener(click);
        Ui.press(row);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 104));
        p.setMargins(0, 0, 0, Ui.dp(this, 14));
        root.addView(row, p);
    }

    private TextView action(String label) {
        TextView t = Ui.text(this, label, 18, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.rounded(Ui.PANEL, Ui.STROKE, 22, this));
        Ui.press(t);
        return t;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 76));
        p.setMargins(0, Ui.dp(this, 12), 0, 0);
        return p;
    }
}
