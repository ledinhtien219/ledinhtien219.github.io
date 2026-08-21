package com.demo.mycarview;

import android.app.Notification;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Experimental Waze companion bridge.
 *
 * This only reads text fields Waze publishes through Android notifications after
 * the user explicitly grants Notification Access. It does not use Accessibility,
 * screen capture, OCR, hidden Waze APIs, or inspect Waze's private storage.
 */
public class WazeNotificationBridgeService extends NotificationListenerService {
    private static final String PREFS = "carview_settings";
    private static final String WAZE_PACKAGE = "com.waze";

    private SharedPreferences prefs;

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        storeStatus("Đã kết nối quyền thông báo Waze", true);
    }

    @Override public void onListenerDisconnected() {
        super.onListenerDisconnected();
        storeStatus("Mất kết nối quyền thông báo Waze", false);
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !WAZE_PACKAGE.equals(sbn.getPackageName())) return;

        Notification notification = sbn.getNotification();
        Bundle extras = notification == null ? null : notification.extras;
        String title = read(extras, Notification.EXTRA_TITLE);
        String text = read(extras, Notification.EXTRA_TEXT);
        String bigText = read(extras, Notification.EXTRA_BIG_TEXT);
        String subText = read(extras, Notification.EXTRA_SUB_TEXT);
        String infoText = read(extras, Notification.EXTRA_INFO_TEXT);

        SharedPreferences.Editor e = getPrefs().edit()
                .putBoolean("waze_notification_connected", true)
                .putString("waze_notification_status", "Đã nhận thông báo Waze")
                .putLong("waze_notification_when", System.currentTimeMillis())
                .putString("waze_notification_title", title)
                .putString("waze_notification_text", text)
                .putString("waze_notification_big_text", bigText)
                .putString("waze_notification_sub_text", subText)
                .putString("waze_notification_info_text", infoText);

        String summary = firstNonEmpty(bigText, text, title, subText, infoText);
        if (summary.length() > 120) summary = summary.substring(0, 120) + "…";
        e.putString("waze_notification_summary", summary).apply();
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !WAZE_PACKAGE.equals(sbn.getPackageName())) return;
        getPrefs().edit()
                .putString("waze_notification_status", "Thông báo Waze vừa kết thúc")
                .putLong("waze_notification_removed_when", System.currentTimeMillis())
                .apply();
    }

    private SharedPreferences getPrefs() {
        if (prefs == null) prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs;
    }

    private void storeStatus(String status, boolean connected) {
        getPrefs().edit()
                .putBoolean("waze_notification_connected", connected)
                .putString("waze_notification_status", status)
                .apply();
    }

    private static String read(Bundle extras, String key) {
        if (extras == null) return "";
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString().trim();
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }
}
