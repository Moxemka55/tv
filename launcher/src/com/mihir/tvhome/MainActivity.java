package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
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
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import android.widget.Toast;

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
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Hold OK on a tile to edit the dock or hide an app; Back on the home screen returns to the last app.
 * Plain framework views only (no AndroidX), no network.
 */
public class MainActivity extends Activity {

    // Defaults for dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] defaultDock;
    private final Set<String> configHidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg, screenOffCmp;
    private int cols, maxDock;
    private boolean sortByRecent;
    private HomePrefs prefs;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    /** Result of a background app scan: every launchable app plus recent-use times. */
    static final class Loaded {
        final Map<String, App> byPkg = new HashMap<>();
        Map<String, Long> used = Collections.emptyMap();
    }

    private Loaded loaded = new Loaded();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private int loadGen;
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();
    private final Map<String, View> cardByKey = new HashMap<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow, header;
    private final GradientDrawable glowDrawable = new GradientDrawable();
    private ImageView wallBack, wallFront;
    private View gridShade;
    private Wallpapers walls;
    private String wallFile, wallPeriod;
    private long wallChangedAt;
    private boolean windowBgCleared;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    private long wallpaperIntervalMs;
    private final Random rnd = new Random();
    private KenBurns.Drift drift;
    private TextView greeting, clock, ampm, date, title, resumeHint, wifiPill, appsLabel;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private String focusKey;
    private boolean rebuilding; // removing the focused tile moves focus elsewhere; don't remember that
    private int lastDir;
    private boolean firstLoad = true;
    private int packageSeq;
    private PopupWindow popup;
    private String resumePkg;
    private boolean screenOff;
    private long ignoreKeysUntil;

    /** Scrim painted into the home wallpaper: darker at the top (clock, pills) and bottom (dock). */
    private static final int[] HOME_SCRIM = {0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000};

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        defaultDock = getResources().getStringArray(R.array.dock_apps);
        configHidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        configHidden.add(getPackageName());
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        screenOffCmp = getString(R.string.screen_off_component);
        cols = getResources().getInteger(R.integer.columns);
        maxDock = getResources().getInteger(R.integer.max_dock_apps);
        sortByRecent = getResources().getBoolean(R.bool.sort_grid_by_recent);
        wallpaperIntervalMs = getResources().getInteger(R.integer.wallpaper_minutes) * 60_000L;
        prefs = new HomePrefs(this, defaultDock);
        walls = new Wallpapers(this);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        loadApps();

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
        restoreFocus();
        if (screenOff) { screenOff = false; ignoreKeysUntil = SystemClock.uptimeMillis() + 700; }
        // Catch installs/removals that happened while another app was in front, else just re-sort by use.
        if (getPackageManager().getChangedPackages(packageSeq) != null) loadApps();
        else refreshUsage();
        if (drift != null) drift.start();
        // New photo on return if the current one has been up for the full interval or the time of day changed.
        long shown = SystemClock.elapsedRealtime() - wallChangedAt;
        if (wallFile == null || shown >= wallpaperIntervalMs || !Wallpapers.period().equals(wallPeriod)) nextWallpaper();
        else scheduleWallpaper(wallpaperIntervalMs - shown);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        if (drift != null) drift.pause(); // nothing to draw while an app is in front
        unregisterReceiver(tick);
        dismissPopup();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        dismissPopup();
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Back from the grid returns to the dock; Back on the dock returns to the app you were watching.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
        else if (resumePkg != null) openApp(resumePkg);
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        // No background of its own: the wallpaper covers the screen, and every full-screen layer costs a
        // TV GPU a whole frame of fill. The theme's black window background shows until the first photo.
        FrameLayout root = new FrameLayout(this);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one. The scrim is
        // painted into each photo when it's decoded (KenBurns.load).
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));

        // The glow only lights the lower part of the screen, so its view covers just that.
        int screenH = getResources().getDisplayMetrics().heightPixels;
        glow = new View(this);
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientCenter(0.5f, (0.78f - 0.3f) / 0.7f);
        glowDrawable.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        glow.setBackground(glowDrawable);
        root.addView(glow, new FrameLayout.LayoutParams(-1, (int) (screenH * 0.7f), Gravity.BOTTOM));
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
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        header = buildHeader();
        hero.addView(header, lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.BOTTOM);
        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        resumeHint = text(10, "sans-serif-medium", 0x99FFFFFF);
        resumeHint.setLetterSpacing(0.2f);
        resumeHint.setSingleLine(true);
        resumeHint.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
        resumeHint.setVisibility(View.GONE);
        titleRow.addView(resumeHint, llp(-2, -2, 0, 0, dp(12), dp(10)));
        bottom.addView(titleRow, llp(-1, -2, dp(8), 0, 0, dp(14)));

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
        appsLabel.setText(sortByRecent && Usage.granted(this) ? "RECENTLY USED" : "ALL APPS");
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
        TextView inputs = pill("Inputs", "⇥");
        inputs.setOnClickListener(v -> showInputs(v));
        wifiPill = pill("Wi-Fi", "◉");
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        TextView settings = pill("Settings", "⚙");
        settings.setOnClickListener(v -> showQuickSettings(v));
        for (TextView p : new TextView[]{inputs, wifiPill, settings}) {
            right.addView(p, llp(-2, dp(30), dp(10), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, String glyph) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(glyph + "  " + label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(14), 0, dp(14), 0);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        String key = "p:" + label;
        cardByKey.put(key, p);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has && !rebuilding) { focusKey = key; scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return p;
    }

    // ---------------------------------------------------------------- apps

    /** Scan apps off the main thread (banners, colours and usage are the slow part), then build the tiles. */
    private void loadApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        final int gen = ++loadGen;
        bg.execute(() -> {
            Loaded r = queryApps();
            runOnUiThread(() -> {
                if (gen == loadGen && !isDestroyed()) {
                    loaded = r;
                    showApps();
                }
            });
        });
    }

    /** Re-read recent use only (banners are unchanged), and rebuild the grid only if its order changed. */
    private void refreshUsage() {
        if (!sortByRecent) { updateResume(); return; }
        final int gen = loadGen;
        bg.execute(() -> {
            Map<String, Long> used = Usage.lastUsed(this);
            runOnUiThread(() -> {
                if (gen != loadGen || isDestroyed()) return;
                String before = gridOrder();
                loaded.used = used;
                if (!before.equals(gridOrder())) showApps(); else updateResume();
            });
        });
    }

    private String gridOrder() {
        StringBuilder sb = new StringBuilder();
        for (App a : sortedRest(prefs.dock(), prefs.hidden())) sb.append(a.pkg).append(',');
        return sb.toString();
    }

    private List<App> sortedRest(List<String> dockPkgs, Set<String> userHidden) {
        List<App> rest = new ArrayList<>();
        for (App a : loaded.byPkg.values()) if (!dockPkgs.contains(a.pkg) && !userHidden.contains(a.pkg)) rest.add(a);
        Map<String, Long> used = loaded.used;
        Collections.sort(rest, (x, y) -> {
            long ux = used.getOrDefault(x.pkg, 0L), uy = used.getOrDefault(y.pkg, 0L);
            return ux != uy ? Long.compare(uy, ux) : x.label.compareToIgnoreCase(y.label);
        });
        return rest;
    }

    private Loaded queryApps() {
        PackageManager pm = getPackageManager();
        SharedPreferences colors = getSharedPreferences("colors", MODE_PRIVATE);
        SharedPreferences.Editor colorEdit = colors.edit();
        Loaded r = new Loaded();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (configHidden.contains(ai.packageName) || r.byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            // Banner colours only change when the app updates, so cache them across launches.
            String ck = a.pkg + "@" + updateTime(pm, a.pkg);
            if (colors.contains(ck)) a.color = colors.getInt(ck, 0xFF3A3A3A);
            else colorEdit.putInt(ck, a.color = sampleColor(a.art));
            r.byPkg.put(a.pkg, a);
        }
        colorEdit.apply();
        if (sortByRecent) r.used = Usage.lastUsed(this);
        return r;
    }

    private static long updateTime(PackageManager pm, String pkg) {
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            return pi.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** Dock apps in saved order (skipping ones not installed), then everything else not hidden. */
    private void showApps() {
        Map<String, App> byPkg = loaded.byPkg;
        List<String> dockPkgs = prefs.dock();
        List<App> dock = new ArrayList<>();
        for (String p : dockPkgs) if (byPkg.containsKey(p)) dock.add(byPkg.get(p));
        List<App> rest = sortedRest(dockPkgs, prefs.hidden());
        appsLabel.setText(!loaded.used.isEmpty() ? "RECENTLY USED" : "ALL APPS");

        String keep = focusKey;
        rebuilding = true;
        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();
        cardByKey.values().removeIf(v -> !pills.contains(v));

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
        rebuilding = false;
        focusKey = keep;
        restoreFocus();
        if (firstLoad) { firstLoad = false; playEntrance(); }
        updateResume();
    }

    private void restoreFocus() {
        View v = focusKey == null ? null : cardByKey.get(focusKey);
        if (v == null && !dockCards.isEmpty()) v = dockCards.get(0);
        if (v != null) v.requestFocus();
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
        card.setLongClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setCameraDistance(dp(1200));
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

        // Glint: a soft band of light that sweeps across the tile when it gains focus (clipped to the card).
        View glint = new View(this);
        glint.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF, 0x00FFFFFF}));
        glint.setAlpha(0f);
        glint.setRotation(12);
        glint.setScaleY(1.6f);
        card.addView(glint, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        String key = (inDock ? "d:" : "g:") + app.pkg;
        cardByKey.put(key, card);
        card.setOnClickListener(v -> openApp(app.pkg));
        card.setOnLongClickListener(v -> { showAppMenu(app, inDock, v); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0).rotationX(0).rotationY(0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 420 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) { glint.animate().cancel(); glint.setAlpha(0f); return; }
            tilt(v);
            int w = v.getWidth();
            glint.setTranslationX(-w);
            glint.setAlpha(1f);
            glint.animate().translationX(w).setDuration(750).setStartDelay(60)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .withEndAction(() -> glint.setAlpha(0f)).start();
            if (!rebuilding) focusKey = key;
            if (inDock) {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                resumeHint.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                resumeHint.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /**
     * Apple TV-style parallax: the newly focused tile starts leaning the way focus moved, as if it caught
     * the motion, then settles flat (the focus animation above brings rotation back to 0).
     */
    private void tilt(View card) {
        float rx = 0, ry = 0;
        switch (lastDir) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: ry = 7; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: ry = -7; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: rx = -6; break;
            case KeyEvent.KEYCODE_DPAD_UP: rx = 6; break;
            default: return;
        }
        card.setRotationX(rx);
        card.setRotationY(ry);
    }

    private void openApp(String pkg) {
        App a = loaded.byPkg.get(pkg);
        if (a != null) {
            launch(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(a.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } else {
            openPackage(pkg);
        }
        getSharedPreferences("home", MODE_PRIVATE).edit().putString("last_launched", pkg).apply();
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

    // ---------------------------------------------------------------- dock editing

    /** Hold OK on a tile: move it in the dock, add/remove it, hide it, or open its app info. */
    private void showAppMenu(App app, boolean inDock, View card) {
        List<String> visibleDock = new ArrayList<>();
        for (String p : prefs.dock()) if (loaded.byPkg.containsKey(p)) visibleDock.add(p);
        int i = visibleDock.indexOf(app.pkg);
        List<TextView> rows = new ArrayList<>();
        if (inDock) {
            if (i > 0) rows.add(menuRow("‹   Move left", () -> moveInDock(app.pkg, -1)));
            if (i >= 0 && i < visibleDock.size() - 1) rows.add(menuRow("Move right   ›", () -> moveInDock(app.pkg, 1)));
            rows.add(menuRow("Remove from dock", () -> {
                List<String> dock = prefs.dock();
                dock.remove(app.pkg);
                prefs.setDock(dock);
                focusKey = "g:" + app.pkg;
                showApps();
            }));
        } else {
            rows.add(menuRow("Add to dock", () -> {
                if (visibleDock.size() >= maxDock) {
                    Toast.makeText(this, "The dock is full. Remove an app first.", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<String> dock = prefs.dock();
                dock.remove(app.pkg);
                dock.add(app.pkg);
                prefs.setDock(dock);
                focusKey = "d:" + app.pkg;
                showApps();
            }));
            rows.add(menuRow("Hide from home", () -> {
                Set<String> h = prefs.hidden();
                h.add(app.pkg);
                prefs.setHidden(h);
                focusKey = null;
                showApps();
            }));
        }
        rows.add(menuRow("App info", () -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.pkg, null)))));
        int nHidden = prefs.hidden().size();
        if (nHidden > 0) rows.add(menuRow("Show hidden apps (" + nHidden + ")", () -> {
            prefs.setHidden(new HashSet<>());
            showApps();
        }));
        showMenu(card, rows, dp(220), inDock);
    }

    /** Swap with the neighbouring installed dock app (uninstalled entries keep their place). */
    private void moveInDock(String pkg, int delta) {
        List<String> dock = prefs.dock();
        List<String> visible = new ArrayList<>();
        for (String p : dock) if (loaded.byPkg.containsKey(p)) visible.add(p);
        int vi = visible.indexOf(pkg), vj = vi + delta;
        if (vi < 0 || vj < 0 || vj >= visible.size()) return;
        Collections.swap(dock, dock.indexOf(pkg), dock.indexOf(visible.get(vj)));
        prefs.setDock(dock);
        focusKey = "d:" + pkg;
        showApps();
    }

    // ---------------------------------------------------------------- menus

    private LinearLayout menuPanel() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);
        return list;
    }

    /** A focusable popup list next to {@code anchor}: below it, or above it for tiles in the dock. */
    private void showMenu(View anchor, List<TextView> rows, int width, boolean above) {
        dismissPopup();
        LinearLayout list = menuPanel();
        PopupWindow pw = new PopupWindow(list, width, -2, true);
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        chainFocus(rows);
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { popup = null; if (anchor.isAttachedToWindow()) anchor.requestFocus(); });
        popup = pw;
        if (above) {
            list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
            pw.showAsDropDown(anchor, (anchor.getWidth() - width) / 2,
                    -anchor.getHeight() - list.getMeasuredHeight() - dp(16), Gravity.START);
        } else {
            pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        }
        rows.get(0).requestFocus();
    }

    private void chainFocus(List<? extends View> rows) {
        for (int i = 0; i < rows.size(); i++) {
            View r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
    }

    private void dismissPopup() {
        if (popup != null) popup.dismiss();
    }

    private void showInputs(View anchor) {
        List<TextView> rows = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(menuRow(inputLabel(t), () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        if (isInstalled(liveTvPkg)) rows.add(menuRow("Live TV", () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(menuRow("All sources…", () -> openPackage(inputPickerPkg)));
        if (rows.isEmpty()) rows.add(menuRow("Settings", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        showMenu(anchor, rows, dp(200), false);
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView menuRow(String label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- quick settings

    /**
     * The Settings pill opens a side panel of the settings people change most, like Google TV's quick
     * settings. Rows whose screen this TV doesn't have are left out.
     */
    private void showQuickSettings(View anchor) {
        dismissPopup();
        List<TextView> rows = new ArrayList<>();
        // \uFE0E asks for the plain text glyph, not the colour emoji.
        qsRow(rows, "☀\uFE0E", "Picture", new Intent(Settings.ACTION_DISPLAY_SETTINGS));
        Intent sound = new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS");
        qsRow(rows, "♪", "Sound", resolves(sound) ? sound : new Intent(Settings.ACTION_SOUND_SETTINGS));
        qsRow(rows, "◐", "Screen off, keep sound", componentIntent(screenOffCmp));
        qsRow(rows, "◉", "Network & Wi-Fi", new Intent(Settings.ACTION_WIFI_SETTINGS));
        qsRow(rows, "⇥", "Inputs", null);
        qsRow(rows, "⚙", "All settings", new Intent(Settings.ACTION_SETTINGS));

        int width = dp(300);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(36), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF51C1C1E);
        bg.setCornerRadii(new float[]{dp(22), dp(22), 0, 0, 0, 0, dp(22), dp(22)});
        panel.setBackground(bg);
        TextView head = text(18, "sans-serif-medium", Color.WHITE);
        head.setText("Quick settings");
        panel.addView(head, llp(-1, -2, dp(10), 0, 0, dp(18)));

        PopupWindow pw = new PopupWindow(panel, width, -1, true);
        for (TextView r : rows) {
            r.setOnClickListener(v -> {
                pw.dismiss();
                Intent i = (Intent) v.getTag();
                if (i == null) { showInputs(pills.get(0)); return; }
                // The key that wakes the screen from sound-only mode also reaches us; don't let it open an app.
                if (i.getComponent() != null && i.getComponent().flattenToShortString().equals(
                        ComponentName.unflattenFromString(screenOffCmp).flattenToShortString())) screenOff = true;
                launch(i);
            });
            panel.addView(r, llp(-1, dp(46), 0, 0, 0, dp(6)));
        }
        chainFocus(rows);
        pw.setElevation(dp(24));
        pw.setOnDismissListener(() -> { popup = null; if (anchor.isAttachedToWindow()) anchor.requestFocus(); });
        popup = pw;
        pw.showAtLocation(getWindow().getDecorView(), Gravity.END | Gravity.TOP, 0, 0);
        panel.setTranslationX(width);
        panel.animate().translationX(0).setDuration(240).setInterpolator(new DecelerateInterpolator(2f)).start();
        rows.get(0).requestFocus();
    }

    private void qsRow(List<TextView> rows, String glyph, String label, Intent target) {
        if (target != null && !resolves(target)) return;
        TextView r = menuRow(label, null);
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        // Glyphs differ in width; a fixed-size icon slot keeps the labels aligned.
        Glyph icon = new Glyph(r, glyph, dp(22));
        r.setCompoundDrawablesRelative(icon, null, null, null);
        r.setCompoundDrawablePadding(dp(16));
        r.setTag(target);
        rows.add(r);
    }

    private Intent componentIntent(String flat) {
        ComponentName cn = flat == null || flat.isEmpty() ? null : ComponentName.unflattenFromString(flat);
        return cn == null ? new Intent("tvhome.none") : new Intent().setComponent(cn);
    }

    private boolean resolves(Intent i) {
        return getPackageManager().resolveActivity(i, 0) != null;
    }

    // ---------------------------------------------------------------- resume

    /** Find the app you were last watching (needs usage access) and offer Back to return to it. */
    private void updateResume() {
        Set<String> candidates = new HashSet<>(loaded.byPkg.keySet());
        if (isInstalled(liveTvPkg)) candidates.add(liveTvPkg);
        bg.execute(() -> {
            String p = Usage.lastInFront(this, candidates);
            if (p == null && !Usage.granted(this)) {
                p = getSharedPreferences("home", MODE_PRIVATE).getString("last_launched", null);
                if (p != null && !candidates.contains(p)) p = null;
            }
            String found = p;
            runOnUiThread(() -> setResume(found));
        });
    }

    private void setResume(String pkg) {
        resumePkg = pkg;
        if (pkg == null || isDestroyed()) { resumeHint.setVisibility(View.GONE); return; }
        App a = loaded.byPkg.get(pkg);
        String label = a != null ? a.label : "Live TV";
        resumeHint.setText(("Back  ·  return to " + label).toUpperCase(Locale.getDefault()));
        resumeHint.setVisibility(View.VISIBLE);
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

    private void updateWifi() {
        String name = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                if (wm.getConnectionInfo().getNetworkId() == -1) name = "Offline";
            }
        } catch (SecurityException ignored) { }
        wifiPill.setText("◉  " + name);
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        // One drawable, recoloured in place: no allocation per animation frame.
        glowDrawable.setColors(new int[]{withAlpha(color, 0.5f), withAlpha(color, 0.14f), Color.TRANSPARENT});
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

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Decode the next photo for this time of day off the main thread, then crossfade it in. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        String file = walls.next(wallFile != null ? wallFile : sp.getString("wallpaper_file", null));
        if (file == null) return;
        wallFile = file;
        wallPeriod = Wallpapers.period();
        sp.edit().putString("wallpaper_file", file).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        new Thread(() -> {
            Bitmap result = KenBurns.load(getAssets(), file, HOME_SCRIM);
            runOnUiThread(() -> showWallpaper(result));
        }).start();
        scheduleWallpaper(wallpaperIntervalMs);
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        // The old photo keeps its last position while it fades out; the new one starts drifting.
        if (drift != null) drift.cancel();
        drift = new KenBurns.Drift(wallFront, wallpaperIntervalMs + 5000, 1000, rnd).start();
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
            if (!windowBgCleared) {
                // The photo now covers the screen; drop the window's black background (one less fill per frame).
                windowBgCleared = true;
                getWindow().setBackgroundDrawable(null);
            }
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private static int sampleColor(Drawable d) {
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
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(120).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(80 + i * 60L).start();
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
        int k = e.getKeyCode();
        if (SystemClock.uptimeMillis() < ignoreKeysUntil) return true;
        if (e.getAction() == KeyEvent.ACTION_DOWN && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
            lastDir = k; // which way focus is moving, for the tile tilt
        }
        // Menu key on the remote opens the Inputs dropdown.
        if (k == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && !pills.isEmpty()) {
            showInputs(pills.get(0));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** A text glyph drawn centred in a fixed square, in its TextView's current colour. */
    static final class Glyph extends Drawable {
        private final TextView owner;
        private final String glyph;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Glyph(TextView owner, String glyph, int size) {
            this.owner = owner;
            this.glyph = glyph;
            paint.setTextSize(size * 0.8f);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            setBounds(0, 0, size, size);
        }

        @Override public void draw(Canvas c) {
            paint.setColor(owner.getCurrentTextColor());
            android.graphics.Rect b = getBounds();
            android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
            c.drawText(glyph, b.exactCenterX(), b.exactCenterY() - (fm.ascent + fm.descent) / 2, paint);
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
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
