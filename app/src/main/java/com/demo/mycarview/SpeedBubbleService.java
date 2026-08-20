package com.demo.mycarview;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SpeedBubbleService extends Service {
    private WindowManager windowManager;
    private View bubble;
    private WindowManager.LayoutParams params;

    @Override public void onCreate() {
        super.onCreate();
        startAsForeground();
        showBubble();
    }

    private void startAsForeground() {
        String channelId = "carview_bubble_demo";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                channelId, "CarView speed bubble", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("MyCar View Demo")
                .setContentText("Bong bóng tốc độ demo đang bật")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
        startForeground(1101, notification);
    }

    private void showBubble() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        String limit = getSharedPreferences("demo", MODE_PRIVATE).getString("limit", "80");
        String speed = getSharedPreferences("demo", MODE_PRIVATE).getString("speed", "67");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        box.setBackground(Ui.rounded(0xEC161D27, 0xFFFF5252, 40, this));

        TextView limitView = Ui.text(this, limit, 24, 0xFFFFFFFF, true);
        limitView.setGravity(Gravity.CENTER);
        TextView speedView = Ui.text(this, speed, 14, Ui.ACCENT, true);
        speedView.setGravity(Gravity.CENTER);
        box.addView(limitView, new LinearLayout.LayoutParams(-1, 0, 2));
        box.addView(speedView, new LinearLayout.LayoutParams(-1, 0, 1));
        bubble = box;

        params = new WindowManager.LayoutParams(
                Ui.dp(this, 78), Ui.dp(this, 78),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = Ui.dp(this, 22);
        params.y = Ui.dp(this, 130);

        box.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = startX + (int) (event.getRawX() - downX);
                        params.y = startY + (int) (event.getRawY() - downY);
                        if (bubble != null) windowManager.updateViewLayout(bubble, params);
                        return true;
                    default:
                        return true;
                }
            }
        });

        windowManager.addView(bubble, params);
    }

    @Override public void onDestroy() {
        if (windowManager != null && bubble != null) {
            windowManager.removeView(bubble);
            bubble = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
