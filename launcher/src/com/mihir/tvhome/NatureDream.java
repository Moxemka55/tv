package com.mihir.tvhome;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Apple TV aerial-style screensaver: the launcher's nature photos crossfade with a slow
 * pan-and-zoom, the place name bottom-left and the time bottom-right. Any key wakes the TV.
 */
public class NatureDream extends DreamService {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private ImageView back, front;
    private TextView place, clock;
    private Wallpapers walls;
    private String current;
    private long photoMs;
    private KenBurns.Drift drift;
    private boolean running;

    /** Light scrim painted into each photo so the caption and clock stay readable. */
    private static final int[] SCRIM = {0x00000000, 0x00000000, 0x00000000, 0x73000000};

    private final Runnable next = this::nextPhoto;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean is24 = android.text.format.DateFormat.is24HourFormat(NatureDream.this);
            clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(new Date()));
            handler.postDelayed(this, 15_000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        photoMs = getResources().getInteger(R.integer.screensaver_seconds) * 1000L;
        walls = new Wallpapers(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = photo();
        front = photo();
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));
        root.addView(front, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.BOTTOM);
        place = text(15, "sans-serif-light", 0xE6FFFFFF);
        place.setAlpha(0f);
        bar.addView(place, new LinearLayout.LayoutParams(0, -2, 1f));
        clock = text(30, "sans-serif-thin", Color.WHITE);
        bar.addView(clock, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(dp(56), 0, dp(56), dp(40));
        root.addView(bar, lp);

        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        current = null; // start somewhere random for this time of day
        tick.run();
        nextPhoto();
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (drift != null) drift.cancel();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        if (front != null) front.setImageDrawable(null);
        if (back != null) back.setImageDrawable(null);
        super.onDetachedFromWindow();
    }

    private void nextPhoto() {
        if (!running) return;
        String file = walls.next(current);
        if (file == null) return;
        current = file;
        String caption = walls.caption(file);
        new Thread(() -> {
            Bitmap bm = KenBurns.load(getAssets(), file, SCRIM);
            handler.post(() -> show(bm, caption));
        }).start();
        handler.postDelayed(next, photoMs);
    }

    private void show(Bitmap bm, String caption) {
        if (!running || bm == null) return;
        ImageView old = front;
        front = back;
        back = old;
        FrameLayout root = (FrameLayout) front.getParent();
        root.removeView(front);
        root.addView(front, 1, new FrameLayout.LayoutParams(-1, -1));
        front.setImageBitmap(bm);
        front.setAlpha(0f);
        front.animate().alpha(1f).setDuration(2500).withEndAction(() -> back.setImageDrawable(null)).start();
        if (drift != null) drift.cancel();
        // 25 steps a second: smooth at this speed, and half the redraws of a 60 fps animation.
        drift = new KenBurns.Drift(front, photoMs + 3000, 40, rnd).start();

        place.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            place.setText(caption);
            place.animate().alpha(1f).setDuration(1200).setStartDelay(800).start();
        }).start();
    }

    private ImageView photo() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        t.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
        t.setSingleLine(true);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
