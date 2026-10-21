package com.mihir.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * Screensaver: a big thin clock on a dim gradient tinted with the last app colour. Nothing animates; the clock
 * text updates once a minute and the block shifts a little each time, so no pixel stays lit (burn-in) and the
 * TV does no per-frame work while it idles.
 */
public class ClockDream extends DreamService {
    private LinearLayout stage;
    private TextView time, date;
    private final Random random = new Random();
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { update(); }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);

        int c = getSharedPreferences("home", MODE_PRIVATE).getInt("glow", 0xFF3A3A3A);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.BLACK, mix(c, 0.20f), mix(c, 0.38f)});
        FrameLayout root = new FrameLayout(this);
        root.setBackground(bg);

        stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setGravity(Gravity.CENTER_HORIZONTAL);
        time = new TextView(this);
        time.setTextColor(0xE6FFFFFF);
        time.setTypeface(android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL));
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 130);
        time.setIncludeFontPadding(false);
        date = new TextView(this);
        date.setTextColor(0x99FFFFFF);
        date.setLetterSpacing(0.2f);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        stage.addView(time);
        stage.addView(date);
        root.addView(stage, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        update();
    }

    @Override
    public void onDreamingStopped() {
        try { unregisterReceiver(tick); } catch (IllegalArgumentException ignored) { }
        super.onDreamingStopped();
    }

    private void update() {
        Calendar now = Calendar.getInstance();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        time.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
        float d = getResources().getDisplayMetrics().density;
        stage.setTranslationX((random.nextFloat() - 0.5f) * 240 * d);
        stage.setTranslationY((random.nextFloat() - 0.5f) * 160 * d);
    }

    private static int mix(int color, float t) {
        return Color.rgb((int) (Color.red(color) * t), (int) (Color.green(color) * t), (int) (Color.blue(color) * t));
    }
}
