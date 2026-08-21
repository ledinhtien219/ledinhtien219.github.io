package com.demo.mycarview;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(7, 19, 33);
    static final int PANEL = Color.rgb(16, 32, 51);
    static final int STROKE = Color.rgb(40, 61, 82);
    static final int ACCENT = Color.rgb(50, 225, 196);
    static final int TEXT = Color.rgb(246, 247, 250);
    static final int MUTED = Color.rgb(154, 169, 185);

    private Ui() {}

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(int fill, int stroke, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != Color.TRANSPARENT) d.setStroke(dp(c, 1), stroke);
        return d;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    static void press(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getAction()) {
                case 0: view.setAlpha(.72f); break;
                case 1:
                case 3: view.setAlpha(1f); break;
            }
            return false;
        });
    }
}
