package com.demo.mycarview;

import android.app.Application;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Lightweight application object used only for crash diagnostics. */
public class CarHudApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                SharedPreferences p = getSharedPreferences("carhud_diag", MODE_PRIVATE);
                p.edit()
                        .putLong("last_crash_time", System.currentTimeMillis())
                        .putString("last_crash", sw.toString())
                        .apply();
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }
}
