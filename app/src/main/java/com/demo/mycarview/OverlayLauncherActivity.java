package com.demo.mycarview;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/**
 * One-tap launcher for native Android/head-unit overlay mode.
 *
 * This mode is for devices where CarHUD is installed on the same Android system
 * that renders Maps/video. It does not bypass Android Auto's projection host.
 */
public class OverlayLauncherActivity extends Activity {
    private static final int REQ_LOCATION = 4101;
    private boolean requestedOverlay;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        startOrRequest();
    }

    @Override protected void onResume() {
        super.onResume();
        if (requestedOverlay && Settings.canDrawOverlays(this)) {
            requestedOverlay = false;
            startOrRequest();
        }
    }

    private void startOrRequest() {
        if (!Settings.canDrawOverlays(this)) {
            requestedOverlay = true;
            Toast.makeText(this,
                    "Cấp quyền Hiển thị trên ứng dụng khác cho CarHUD.",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) {
                finish();
            }
            return;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQ_LOCATION);
            return;
        }

        enableAndStart();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION) enableAndStart();
    }

    private void enableAndStart() {
        SharedPreferences prefs = getSharedPreferences("carview_settings", MODE_PRIVATE);
        prefs.edit()
                .putBoolean("bubble_enabled", true)
                .putString("speed_source", "waze")
                .apply();

        try {
            startForegroundService(new Intent(this, SpeedBubbleService.class));
            Toast.makeText(this,
                    "CarHUD overlay đã bật. Mở Maps/VIETMAP/video trên thiết bị này.",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            prefs.edit().putBoolean("bubble_enabled", false).apply();
            Toast.makeText(this,
                    "Không bật được overlay. Kiểm tra quyền vị trí và quyền hiển thị nổi.",
                    Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
