package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX), no network, no background work.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps;
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow;
    private View gridShade, backdrop;
    // One drawable per layer, recoloured in place so focus changes allocate nothing.
    private final GradientDrawable backdropDrawable = new GradientDrawable();
    private final GradientDrawable glowDrawable = new GradientDrawable();
    private TextView greeting, clock, ampm, date, title, wifiPill, appsLabel;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup;
    // Rearranging: all launchable apps, their display order (dock first), and the tile being moved.
    private Map<String, App> allApps = new HashMap<>();
    private List<String> orderedPkgs = new ArrayList<>();
    private int dockN;
    private String editPkg;
    private android.animation.ObjectAnimator wiggle;
    private float prevFocusX = -1, prevFocusY = -1;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = getResources().getStringArray(R.array.dock_apps);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = getResources().getInteger(R.integer.columns);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(tick);
        endEdit();
        getSharedPreferences("home", MODE_PRIVATE).edit().putInt("glow", glowColor).apply();
        if (inputsPopup != null) inputsPopup.dismiss();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (inputsPopup != null) inputsPopup.dismiss();
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Backdrop: a diagonal gradient from near-black to the focused app's colour.
        backdrop = new View(this);
        backdropDrawable.setOrientation(GradientDrawable.Orientation.TL_BR);
        backdrop.setBackground(backdropDrawable);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        // Film grain: a tiny tiled noise bitmap breaks up gradient banding and adds depth.
        View grain = new View(this);
        grain.setBackground(grainDrawable());
        root.addView(grain, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0x80000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));

        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(dp(8), 0, 0, dp(14));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("ALL APPS");
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(10, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(10, "sans-serif-medium", 0x99FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        left.addView(date);
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        TextView inputs = pill("Inputs", R.drawable.ic_input);
        inputs.setOnClickListener(v -> showInputs(v));
        wifiPill = pill("Wi-Fi", R.drawable.ic_wifi);
        wifiPill.setOnClickListener(v -> showNetwork(v));
        TextView settings = pill("Settings", R.drawable.ic_settings);
        settings.setOnClickListener(v -> showSettings(v));
        for (TextView p : new TextView[]{inputs, wifiPill, settings}) {
            right.addView(p, llp(-2, dp(36), dp(12), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    /** Frosted-glass pill with a vector icon; the icon tints with the text on focus. */
    private TextView pill(String label, int iconRes) {
        TextView p = text(12, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(label);
        p.setSingleLine(true);
        p.setEllipsize(android.text.TextUtils.TruncateAt.END);
        p.setMaxWidth(dp(300));
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(16), 0, dp(18), 0);
        p.setCompoundDrawablePadding(dp(8));
        p.setFocusable(true);
        p.setId(View.generateViewId());
        setPillIcon(p, iconRes, 0xE6FFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0x2EFFFFFF);
        bg.setStroke(dp(1), 0x4DFFFFFF);
        p.setBackground(bg);
        java.util.function.Consumer<Boolean> look = on -> {
            int ink = on ? 0xFF111111 : 0xE6FFFFFF;
            bg.setColor(on ? 0xF2FFFFFF : 0x2EFFFFFF);
            p.setTextColor(ink);
            Drawable icon = p.getCompoundDrawablesRelative()[0];
            if (icon != null) icon.setTint(ink);
        };
        p.setTag(look);
        p.setOnFocusChangeListener((v, has) -> {
            look.accept(has);
            v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return p;
    }

    private void setPillIcon(TextView p, int iconRes, int tint) {
        Drawable d = getDrawable(iconRes).mutate();
        d.setBounds(0, 0, dp(16), dp(16));
        d.setTint(tint);
        p.setCompoundDrawablesRelative(d, null, null, null);
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }
        allApps = byPkg;
        relayout();
    }

    /** Lays out the dock and grid from the saved order (or config + alphabetical), without re-scanning apps. */
    private void relayout() {
        Map<String, App> byPkg = new HashMap<>(allApps);
        List<String> configDock = new ArrayList<>();
        for (String p : dockApps) if (byPkg.containsKey(p)) configDock.add(p);
        dockN = configDock.size();
        List<String> alpha = new ArrayList<>(byPkg.keySet());
        alpha.removeAll(configDock);
        Collections.sort(alpha, (x, y) -> byPkg.get(x).label.compareToIgnoreCase(byPkg.get(y).label));
        List<String> order = new ArrayList<>();
        for (String p : getSharedPreferences("home", MODE_PRIVATE).getString("order", "").split(",")) {
            if (byPkg.containsKey(p) && !order.contains(p)) order.add(p);
        }
        if (order.isEmpty()) order.addAll(configDock);
        for (String p : alpha) if (!order.contains(p)) order.add(p);   // new installs go last
        for (String p : configDock) if (!order.contains(p)) order.add(p);
        orderedPkgs = order;
        List<App> dock = new ArrayList<>(), rest = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) (i < dockN ? dock : rest).add(byPkg.get(order.get(i)));

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        for (int i = 0; i < dock.size(); i++) {
            View tile = tile(dock.get(i), true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == dock.size() - 1 ? 0 : dp(10), 0);
            dockRow.addView(tile, p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();

        if (!dock.isEmpty()) { title.setText(dock.get(0).label); setGlow(dock.get(0).color); }
        if (editPkg != null) {
            View c = cardFor(editPkg);
            if (c != null) { c.requestFocus(); startWiggle(c); }
            return;
        }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null) last.requestFocus();
        else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- rearranging

    private View cardFor(String pkg) {
        for (View c : dockCards) if (((App) c.getTag()).pkg.equals(pkg)) return c;
        for (View c : gridCards) if (((App) c.getTag()).pkg.equals(pkg)) return c;
        return null;
    }

    private void startEdit(String pkg) {
        editPkg = pkg;
        View c = cardFor(pkg);
        if (c != null) { c.setPressed(false); startWiggle(c); }
        if (inputsPopup != null) inputsPopup.dismiss();
        android.widget.Toast.makeText(this, "Move with the arrow keys \u00b7 press OK to drop", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void endEdit() {
        editPkg = null;
        stopWiggle();
    }

    /** Only the picked-up tile animates, and only while moving. */
    private void startWiggle(View v) {
        stopWiggle();
        wiggle = android.animation.ObjectAnimator.ofFloat(v, View.ROTATION, -1.8f, 1.8f);
        wiggle.setDuration(130);
        wiggle.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        wiggle.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        wiggle.start();
    }

    private void stopWiggle() {
        if (wiggle == null) return;
        View v = (View) ((android.animation.ObjectAnimator) wiggle).getTarget();
        wiggle.cancel();
        wiggle = null;
        if (v != null) v.setRotation(0f);
    }

    /** Swaps the picked-up tile with its neighbour in the pressed direction (dock row, then the grid rows). */
    private void moveEdit(int key) {
        int i = orderedPkgs.indexOf(editPkg), n = orderedPkgs.size(), g = n - dockN, j = -1;
        switch (key) {
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: j = i - 1; break;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: j = i + 1; break;
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
                j = i < dockN ? -1 : (i - dockN < cols ? Math.min(i - dockN, dockN - 1) : i - cols);
                break;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                j = i < dockN ? (g > 0 ? dockN + Math.min(i, g - 1) : -1) : i + cols;
                break;
            default: break;
        }
        if (i < 0 || j < 0 || j >= n || j == i) return;
        Collections.swap(orderedPkgs, i, j);
        getSharedPreferences("home", MODE_PRIVATE).edit().putString("order", android.text.TextUtils.join(",", orderedPkgs)).apply();
        relayout();
    }

    /** While a tile is picked up, the arrows move it and OK/Back drops it; returns true if the key was handled. */
    private boolean handleEditKey(android.view.KeyEvent e) {
        int k = e.getKeyCode();
        boolean arrow = k == android.view.KeyEvent.KEYCODE_DPAD_LEFT || k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                || k == android.view.KeyEvent.KEYCODE_DPAD_UP || k == android.view.KeyEvent.KEYCODE_DPAD_DOWN;
        if (arrow) {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) moveEdit(k);
            return true;
        }
        if (k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER
                || k == android.view.KeyEvent.KEYCODE_BACK) {
            // The held press that started the move repeats; only a fresh press drops the tile.
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) endEdit();
            return true;
        }
        return false;
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        card.setLongClickable(true);
        card.setOnLongClickListener(v -> { startEdit(app.pkg); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            if (has) {
                // A slight tilt as focus arrives, leaning the way you moved, then settles flat.
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                float cx = loc[0] + v.getWidth() / 2f, cy = loc[1] + v.getHeight() / 2f;
                if (prevFocusX >= 0 && editPkg == null) {
                    v.setCameraDistance(getResources().getDisplayMetrics().density * 6000f);
                    float dx = cx - prevFocusX, dy = cy - prevFocusY;
                    v.setRotationY(Math.abs(dx) > 20 ? (dx > 0 ? -5f : 5f) : 0f);
                    v.setRotationX(Math.abs(dy) > 20 ? (dy > 0 ? 4f : -4f) : 0f);
                }
                prevFocusX = cx;
                prevFocusY = cy;
            }
            v.animate().scaleX(s).scaleY(s).rotationX(0f).rotationY(0f).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        int topId = pills.get(0).getId();
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    private static final int GREEN = 0xFF34C759;

    /** Shows `content` as a rounded dropdown under `anchor`; the anchor pill stays highlighted while it is open. */
    private PopupWindow dropdown(View content, int widthDp, View anchor, int gravity) {
        PopupWindow pw = new PopupWindow(content, dp(widthDp), -2, true);
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        setPillActive(anchor, true);
        pw.setOnDismissListener(() -> { inputsPopup = null; setPillActive(anchor, false); anchor.requestFocus(); });
        inputsPopup = pw;
        pw.showAsDropDown(anchor, 0, dp(8), gravity);
        return pw;
    }

    @SuppressWarnings("unchecked")
    private void setPillActive(View pill, boolean on) {
        if (pill.getTag() instanceof java.util.function.Consumer) ((java.util.function.Consumer<Boolean>) pill.getTag()).accept(on);
    }

    private LinearLayout panel() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0xF21E1E22);
        bg.setStroke(dp(1), 0x26FFFFFF);
        l.setBackground(bg);
        return l;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
        p.setMargins(dp(10), dp(6), dp(10), dp(6));
        v.setLayoutParams(p);
        return v;
    }

    /** Focusable dropdown row: icon, label and (optionally) a green "Connected" status on the right. */
    private LinearLayout menuRow(int iconRes, String label, boolean connected, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), 0, dp(14), 0);
        r.setFocusable(true);
        r.setId(View.generateViewId());
        r.setTag(action);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);

        ImageView icon = new ImageView(this);
        Drawable d = getDrawable(iconRes).mutate();
        d.setTint(Color.WHITE);
        icon.setImageDrawable(d);
        r.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView t = text(13, "sans-serif-medium", Color.WHITE);
        t.setText(label);
        t.setSingleLine(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMargins(dp(12), 0, dp(8), 0);
        r.addView(t, tp);

        TextView status = null;
        if (connected) {
            View dot = new View(this);
            GradientDrawable dd = new GradientDrawable();
            dd.setShape(GradientDrawable.OVAL);
            dd.setColor(GREEN);
            dot.setBackground(dd);
            LinearLayout.LayoutParams dp7 = new LinearLayout.LayoutParams(dp(6), dp(6));
            dp7.setMargins(0, 0, dp(6), 0);
            r.addView(dot, dp7);
            status = text(10, "sans-serif", 0x80FFFFFF);
            status.setText("Connected");
            r.addView(status);
        }
        final TextView st = status;
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            d.setTint(has ? 0xFF111111 : Color.WHITE);
            icon.invalidate();
            t.setTextColor(has ? 0xFF111111 : Color.WHITE);
            if (st != null) st.setTextColor(has ? 0x99000000 : 0x80FFFFFF);
        });
        return r;
    }

    private void addRows(LinearLayout list, List<View> rows, PopupWindow[] pw) {
        for (View r : rows) {
            r.setOnClickListener(v -> { pw[0].dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(42)));
        }
    }

    private void linkFocus(List<View> rows) {
        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
    }

    private void showInputs(View anchor) {
        LinearLayout list = panel();
        PopupWindow[] pw = new PopupWindow[1];
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> hdmi = new ArrayList<>(), av = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (!t.isPassthroughInput() || t.isHidden(this)) continue;
            (t.getType() == TvInputInfo.TYPE_HDMI ? hdmi : av).add(t);
        }
        Collections.sort(hdmi, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        Collections.sort(av, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));

        // Groups as in Apple-style menus: HDMI, then AV, then Live TV, with a divider between groups.
        List<List<View>> groups = new ArrayList<>();
        groups.add(inputRows(hdmi, R.drawable.ic_hdmi, tim));
        groups.add(inputRows(av, R.drawable.ic_input, tim));
        if (isInstalled(liveTvPkg)) groups.add(Collections.singletonList(
                (View) menuRow(R.drawable.ic_tv, "Live TV", false, () -> openPackage(liveTvPkg))));
        List<View> rows = new ArrayList<>();
        for (List<View> g : groups) {
            if (g.isEmpty()) continue;
            if (!rows.isEmpty()) list.addView(divider());
            addRows(list, g, pw);
            rows.addAll(g);
        }
        if (rows.isEmpty()) {
            List<View> fallback = new ArrayList<>();
            fallback.add(isInstalled(inputPickerPkg)
                    ? menuRow(R.drawable.ic_input, "All sources…", false, () -> openPackage(inputPickerPkg))
                    : menuRow(R.drawable.ic_settings, "Settings", false, () -> launch(new Intent(Settings.ACTION_SETTINGS))));
            addRows(list, fallback, pw);
            rows.addAll(fallback);
        }
        linkFocus(rows);
        pw[0] = dropdown(list, 250, anchor, Gravity.START);
        rows.get(0).requestFocus();
    }

    private List<View> inputRows(List<TvInputInfo> inputs, int icon, TvInputManager tim) {
        List<View> rows = new ArrayList<>();
        for (TvInputInfo t : inputs) {
            boolean connected = tim != null && tim.getInputState(t.getId()) == TvInputManager.INPUT_STATE_CONNECTED;
            rows.add(menuRow(icon, inputLabel(t), connected, () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        return rows;
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    // ---------------------------------------------------------------- settings dropdown

    /** Shortcuts to the settings pages people actually reach for; each falls back to the main Settings app. */
    private void showSettings(View anchor) {
        LinearLayout list = panel();
        PopupWindow[] pw = new PopupWindow[1];
        List<View> rows = new ArrayList<>();
        rows.add(settingRow(R.drawable.ic_wifi, "Network & Internet", Settings.ACTION_WIFI_SETTINGS));
        rows.add(settingRow(R.drawable.ic_tv, "Picture & display", Settings.ACTION_DISPLAY_SETTINGS));
        rows.add(settingRow(R.drawable.ic_sound, "Sound", Settings.ACTION_SOUND_SETTINGS));
        rows.add(settingRow(R.drawable.ic_bluetooth, "Remotes & accessories", Settings.ACTION_BLUETOOTH_SETTINGS));
        rows.add(settingRow(R.drawable.ic_apps, "Apps", Settings.ACTION_APPLICATION_SETTINGS));
        rows.add(settingRow(R.drawable.ic_accessibility, "Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS));
        addRows(list, rows, pw);
        list.addView(divider());
        List<View> more = new ArrayList<>();
        more.add(settingRow(R.drawable.ic_settings, "All settings", Settings.ACTION_SETTINGS));
        addRows(list, more, pw);
        rows.addAll(more);
        list.addView(divider());
        // Philips' own restart screen (exported, no permission needed); it asks for confirmation itself.
        List<View> power = new ArrayList<>();
        power.add(menuRow(R.drawable.ic_restart, "Restart", false, () -> launch(new Intent("org.droidtv.intent.action.CONFIRM_REBOOT")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        power.add(menuRow(R.drawable.ic_power, "Shut down", false, () -> {
            PowerService svc = PowerService.get();
            if (svc != null) { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG); return; }
            // Not enabled yet: send the user to Accessibility settings to switch on "Home power menu".
            android.widget.Toast.makeText(this, "Turn on \"Home power menu\" in Accessibility to use Shut down", android.widget.Toast.LENGTH_LONG).show();
            launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }));
        addRows(list, power, pw);
        rows.addAll(power);
        linkFocus(rows);
        pw[0] = dropdown(list, 270, anchor, Gravity.END);
        rows.get(0).requestFocus();
    }

    private View settingRow(int icon, String label, String action) {
        return menuRow(icon, label, false, () -> {
            Intent i = new Intent(action);
            if (!launch(i)) launch(new Intent(Settings.ACTION_SETTINGS));
        });
    }

    // ---------------------------------------------------------------- network dropdown

    /** SSID (or Ethernet) with live download/upload speed, link speed and this TV's IP address. */
    private void showNetwork(View anchor) {
        LinearLayout list = panel();
        PopupWindow[] pw = new PopupWindow[1];
        String name = "Offline", ip = "\u2014";
        int icon = R.drawable.ic_wifi, linkMbps = 0;
        boolean online = false;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network net = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities caps = net == null ? null : cm.getNetworkCapabilities(net);
            if (caps != null) {
                online = true;
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    name = "Ethernet";
                    icon = R.drawable.ic_ethernet;
                } else {
                    name = "Wi-Fi";
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    if (wm != null && wm.getConnectionInfo() != null) {
                        String s = wm.getConnectionInfo().getSSID();
                        if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                        linkMbps = wm.getConnectionInfo().getTxLinkSpeedMbps();
                        if (linkMbps <= 0) linkMbps = wm.getConnectionInfo().getLinkSpeed();
                    }
                }
                android.net.LinkProperties lp = cm.getLinkProperties(net);
                if (lp != null) for (android.net.LinkAddress a : lp.getLinkAddresses()) {
                    java.net.InetAddress ad = a.getAddress();
                    if (ad instanceof java.net.Inet4Address && !ad.isLoopbackAddress()) { ip = ad.getHostAddress(); break; }
                }
            }
        } catch (SecurityException ignored) { }

        // Header: network name with a green "Connected" status, not focusable.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(6), dp(14), dp(6));
        ImageView hi = new ImageView(this);
        Drawable hd = getDrawable(icon).mutate();
        hd.setTint(Color.WHITE);
        hi.setImageDrawable(hd);
        head.addView(hi, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView ssid = text(15, "sans-serif-medium", Color.WHITE);
        ssid.setText(name);
        ssid.setSingleLine(true);
        ssid.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1f);
        sp.setMargins(dp(12), 0, dp(10), 0);
        head.addView(ssid, sp);
        View dot = new View(this);
        GradientDrawable dd = new GradientDrawable();
        dd.setShape(GradientDrawable.OVAL);
        dd.setColor(online ? GREEN : 0xFFFF453A);
        dot.setBackground(dd);
        LinearLayout.LayoutParams dpar = new LinearLayout.LayoutParams(dp(6), dp(6));
        dpar.setMargins(0, 0, dp(6), 0);
        head.addView(dot, dpar);
        TextView st = text(10, "sans-serif", 0x80FFFFFF);
        st.setText(online ? "Connected" : "Offline");
        head.addView(st);
        list.addView(head);
        list.addView(divider());

        TextView down = statRow(list, "Download");
        TextView up = statRow(list, "Upload");
        if (linkMbps > 0) statRow(list, "Link speed").setText(linkMbps + " Mbps");
        statRow(list, "IP address").setText(ip);
        list.addView(divider());

        List<View> rows = new ArrayList<>();
        rows.add(menuRow(R.drawable.ic_settings, "Network settings", false, () -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS))));
        addRows(list, rows, pw);
        linkFocus(rows);
        pw[0] = dropdown(list, 300, anchor, Gravity.END);
        rows.get(0).requestFocus();

        // Live throughput, sampled once a second while the dropdown is open.
        final long[] last = {android.net.TrafficStats.getTotalRxBytes(), android.net.TrafficStats.getTotalTxBytes(),
                android.os.SystemClock.elapsedRealtime()};
        down.setText(mbps(0));
        up.setText(mbps(0));
        list.postDelayed(new Runnable() {
            @Override public void run() {
                if (!pw[0].isShowing()) return;
                long rx = android.net.TrafficStats.getTotalRxBytes(), tx = android.net.TrafficStats.getTotalTxBytes();
                long now = android.os.SystemClock.elapsedRealtime();
                double dt = Math.max(0.2, (now - last[2]) / 1000.0);
                down.setText(mbps((rx - last[0]) * 8 / 1e6 / dt));
                up.setText(mbps((tx - last[1]) * 8 / 1e6 / dt));
                last[0] = rx; last[1] = tx; last[2] = now;
                list.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private static String mbps(double v) {
        if (v < 0) v = 0;
        return String.format(Locale.US, v >= 100 ? "%.0f Mbps" : "%.1f Mbps", v);
    }

    /** A read-only "label ... value" line; returns the value view so it can be filled or updated. */
    private TextView statRow(LinearLayout list, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), 0, dp(14), 0);
        TextView l = text(12, "sans-serif", 0x99FFFFFF);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = text(13, "sans-serif-medium", Color.WHITE);
        v.setSingleLine(true);
        r.addView(v);
        list.addView(r, new LinearLayout.LayoutParams(-1, dp(34)));
        return v;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING");
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
    }

    /** Ethernet when wired, otherwise the Wi-Fi network name; "Offline" when neither is connected. */
    private void updateWifi() {
        String name = "Offline";
        int icon = R.drawable.ic_wifi;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                name = "Ethernet";
                icon = R.drawable.ic_ethernet;
            } else if (caps != null) {
                name = "Wi-Fi";
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null && wm.getConnectionInfo() != null) {
                    String s = wm.getConnectionInfo().getSSID();
                    if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                }
            }
        } catch (SecurityException ignored) { }
        wifiPill.setText(name);
        setPillIcon(wifiPill, icon, wifiPill.isFocused() ? 0xFF111111 : 0xE6FFFFFF);
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        float sat = hsv[1], val = hsv[2];
        // Neutral (grey) apps stay neutral; coloured apps drift 40 degrees for a two-tone gradient.
        hsv[0] = (hsv[0] + 40f) % 360f;
        hsv[1] = sat; hsv[2] = val * 0.55f;
        int far = Color.HSVToColor(hsv);
        backdropDrawable.setColors(new int[]{mix(Color.BLACK, color, 0.14f), mix(Color.BLACK, color, 0.38f), far});
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientCenter(0.5f, 0.78f);
        glowDrawable.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        glowDrawable.setColors(new int[]{withAlpha(color, 0.45f), withAlpha(color, 0.12f), Color.TRANSPARENT});
        glow.setBackground(glowDrawable);
        backdrop.invalidate();
    }

    private android.graphics.drawable.BitmapDrawable grainDrawable() {
        Bitmap n = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        java.util.Random r = new java.util.Random(7);
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            int v = r.nextInt(256);
            n.setPixel(x, y, Color.argb(9, v, v, v));
        }
        android.graphics.drawable.BitmapDrawable bd = new android.graphics.drawable.BitmapDrawable(getResources(), n);
        bd.setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        return bd;
    }


    private static int mix(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (editPkg != null && handleEditKey(e)) return true;
        // Menu key on the remote opens the Inputs dropdown.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && !pills.isEmpty()) {
            showInputs(pills.get(0));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
