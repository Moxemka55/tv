package com.mihir.tvhome;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/** Slow pan-and-zoom on a full-screen photo, and the shared wallpaper loader. */
final class KenBurns {
    private KenBurns() { }

    /**
     * Zoom between 106% and 116% (in or out, at random) while drifting toward a random corner. The photo
     * always stays at least 106% of the screen, so the pan never shows an edge.
     *
     * Applied in steps rather than every frame: over a 10-minute photo the image moves well under a pixel
     * per second, so redrawing at 60 fps would keep a weak TV GPU busy for nothing (measured: 49 fps of
     * full-screen redraws while idle, which made D-pad navigation stutter).
     */
    static final class Drift {
        private final View v;
        private final long durationMs, stepMs;
        private final float s0, s1, x0, x1, y0, y1;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable tick = this::tick;
        private long startedAt, elapsed;
        private boolean running;

        Drift(View v, long durationMs, long stepMs, Random r) {
            this.v = v;
            this.durationMs = durationMs;
            this.stepMs = stepMs;
            int w = v.getResources().getDisplayMetrics().widthPixels;
            int h = v.getResources().getDisplayMetrics().heightPixels;
            boolean zoomIn = r.nextBoolean();
            s0 = zoomIn ? 1.06f : 1.16f;
            s1 = zoomIn ? 1.16f : 1.06f;
            // Max safe offset at the smaller scale is (s-1)/2 of the screen; stay inside it.
            float mx = w * 0.026f, my = h * 0.026f;
            x0 = (r.nextFloat() * 2 - 1) * mx;
            y0 = (r.nextFloat() * 2 - 1) * my;
            x1 = -Math.signum(x0) * mx * (0.5f + r.nextFloat() * 0.5f);
            y1 = -Math.signum(y0) * my * (0.5f + r.nextFloat() * 0.5f);
            apply(0f);
        }

        Drift start() {
            if (!running) {
                running = true;
                startedAt = SystemClock.uptimeMillis() - elapsed;
                tick();
            }
            return this;
        }

        void pause() {
            if (!running) return;
            running = false;
            elapsed = SystemClock.uptimeMillis() - startedAt;
            handler.removeCallbacks(tick);
        }

        void cancel() {
            running = false;
            handler.removeCallbacks(tick);
        }

        private void tick() {
            if (!running) return;
            float p = Math.min(1f, (SystemClock.uptimeMillis() - startedAt) / (float) durationMs);
            apply(p);
            if (p < 1f) handler.postDelayed(tick, stepMs);
            else running = false;
        }

        private void apply(float p) {
            float s = s0 + (s1 - s0) * p;
            v.setScaleX(s);
            v.setScaleY(s);
            v.setTranslationX(x0 + (x1 - x0) * p);
            v.setTranslationY(y0 + (y1 - y0) * p);
        }
    }

    /**
     * Decode a wallpaper from assets/wallpapers at 16 bits per pixel (half the memory, no visible loss) and
     * paint the scrim straight into it, so the GPU draws one full-screen layer per frame instead of two.
     */
    static Bitmap load(AssetManager assets, String file, int[] scrim) {
        Bitmap bm;
        try (java.io.InputStream in = assets.open("wallpapers/" + file)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inMutable = scrim != null;
            bm = BitmapFactory.decodeStream(in, null, o);
        } catch (java.io.IOException e) {
            return null;
        }
        if (bm != null && scrim != null) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, scrim);
            g.setDither(true); // avoids banding in the dark gradient at 16 bits per pixel
            g.setBounds(0, 0, bm.getWidth(), bm.getHeight());
            g.draw(new Canvas(bm));
        }
        return bm;
    }
}
