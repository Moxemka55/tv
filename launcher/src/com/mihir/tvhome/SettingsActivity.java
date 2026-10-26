package com.mihir.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Premium on-TV settings. D-pad only: Up/Down to move, OK to press a button,
 * Left/Right to move between buttons on the same row.
 *
 * Everything saves instantly to PremiumPrefs, so MainActivity picks it up on
 * return (onResume). No rebuild, no config.xml edit, no laptop needed.
 */
public class SettingsActivity extends Activity {

    static final class SimpleApp {
        String pkg, label;
    }

    private LinearLayout list;
    private List<SimpleApp> installed = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        loadInstalled();
        rebuild();
    }

    // ---------------------------------------------------------------- data

    private void loadInstalled() {
        installed.clear();
        PackageManager pm = getPackageManager();
        Set<String> configHidden = new HashSet<>(Arrays.asList(
                getResources().getStringArray(R.array.hidden_apps)));
        Set<String> seen = new HashSet<>();
        String[] cats = new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (ai.packageName.equals(getPackageName())) continue;
                if (configHidden.contains(ai.packageName)) continue;
                if (seen.contains(ai.packageName)) continue;
                seen.add(ai.packageName);
                SimpleApp a = new SimpleApp();
                a.pkg = ai.packageName;
                a.label = String.valueOf(ri.loadLabel(pm));
                installed.add(a);
            }
        }
        Collections.sort(installed, (x, y) -> x.label.compareToIgnoreCase(y.label));
    }

    private SimpleApp find(String pkg) {
        for (SimpleApp a : installed) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private List<String> currentDock() {
        List<String> saved = PremiumPrefs.getDockOrder(this);
        List<String> dock = saved != null ? new ArrayList<>(saved)
                : new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.dock_apps)));
        // Keep only installed, user-visible apps, in saved order.
        Set<String> hidden = PremiumPrefs.getUserHidden(this);
        List<String> out = new ArrayList<>();
        for (String p : dock) {
            if (find(p) != null && !hidden.contains(p) && !out.contains(p)) out.add(p);
        }
        return out;
    }

    private List<String> currentGrid() {
        List<String> dock = currentDock();
        Set<String> hidden = PremiumPrefs.getUserHidden(this);
        List<String> saved = PremiumPrefs.getGridOrder(this);
        List<String> out = new ArrayList<>();
        if (saved != null) {
            for (String p : saved) {
                if (find(p) != null && !dock.contains(p) && !hidden.contains(p) && !out.contains(p)) out.add(p);
            }
        }
        for (SimpleApp a : installed) {
            if (!dock.contains(a.pkg) && !hidden.contains(a.pkg) && !out.contains(a.pkg)) out.add(a.pkg);
        }
        return out;
    }

    // ---------------------------------------------------------------- ui

    private void rebuild() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(48), dp(32), dp(48), dp(48));
        scroll.addView(list, new LinearLayout.LayoutParams(-1, -2));

        TextView head = text("PREMIUM SETTINGS", 28, Color.WHITE);
        head.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        list.addView(head);
        TextView sub = text("Up / Down to move  •  OK to press  •  Changes save instantly, no rebuild needed.", 13, 0x99FFFFFF);
        list.addView(sub, pad(0, 8, 0, 24));

        // Default Home
        section("DEFAULT HOME  —  Xiaomi MiTv-AFMU0");
        boolean isDefault = isDefaultHome();
        TextView status = text(isDefault
                ? "Status: YES — this launcher is the default Home. Home button should open it."
                : "Status: NO — Home button will still open PatchWall / stock launcher until you set it.",
                14, isDefault ? 0xFF8FE388 : 0xFFFFC46B);
        list.addView(status, pad(0, 0, 0, 12));
        buttonRow("Open Android Home settings", () -> {
            try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        TextView adb = text(
                "Or over ADB, same Wi-Fi, in order:\n"
                + "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity\n"
                + "pm disable-user --user 0 com.mitv.tvhome.atv   (PatchWall, Xiaomi stock)\n"
                + "pm disable-user --user 0 com.google.android.apps.tv.launcherx   (only if present)\n"
                + "am force-stop com.android.tv.settings\n"
                + "input keyevent KEYCODE_HOME\n"
                + "Restore: pm enable com.mitv.tvhome.atv, then pick Home again in Settings.\n"
                + "Never disable core Android packages (permissioncontroller, packageinstaller, etc.) — that can brick boot.",
                12, 0xCCFFFFFF);
        list.addView(adb, pad(0, 8, 0, 8));

        // Dock editor
        section("DOCK  —  move, remove, add");
        List<String> dock = currentDock();
        if (dock.isEmpty()) {
            list.addView(text("Dock is empty. Add apps below.", 14, 0x99FFFFFF));
        }
        for (int i = 0; i < dock.size(); i++) {
            final int idx = i;
            final String pkg = dock.get(i);
            SimpleApp a = find(pkg);
            if (a == null) continue;
            LinearLayout row = row(a.label);
            row.addView(action("◀", () -> {
                List<String> d = currentDock();
                if (idx > 0) { Collections.swap(d, idx, idx - 1); PremiumPrefs.setDockOrder(this, d); rebuild(); }
            }));
            row.addView(action("▶", () -> {
                List<String> d = currentDock();
                if (idx < d.size() - 1) { Collections.swap(d, idx, idx + 1); PremiumPrefs.setDockOrder(this, d); rebuild(); }
            }));
            row.addView(action("Remove", () -> {
                List<String> d = currentDock();
                d.remove(pkg); PremiumPrefs.setDockOrder(this, d); rebuild();
            }));
        }
        section("ADD TO DOCK");
        for (SimpleApp a : installed) {
            if (dock.contains(a.pkg) || PremiumPrefs.getUserHidden(this).contains(a.pkg)) continue;
            final String pkg = a.pkg;
            LinearLayout row = row(a.label);
            row.addView(action("Add", () -> {
                List<String> d = currentDock();
                if (!d.contains(pkg)) d.add(pkg);
                PremiumPrefs.setDockOrder(this, d); rebuild();
            }));
        }

        // Hide / show
        section("HIDE / SHOW APPS");
        for (SimpleApp a : installed) {
            final String pkg = a.pkg;
            boolean isHidden = PremiumPrefs.getUserHidden(this).contains(pkg);
            LinearLayout row = row(a.label + (isHidden ? "  (hidden)" : ""));
            row.addView(action(isHidden ? "Show" : "Hide", () -> {
                Set<String> h = PremiumPrefs.getUserHidden(this);
                if (isHidden) h.remove(pkg); else {
                    h.add(pkg);
                    List<String> d = currentDock();
                    if (d.remove(pkg)) PremiumPrefs.setDockOrder(this, d);
                }
                PremiumPrefs.setUserHidden(this, h); rebuild();
            }));
        }

        // Grid reorder
        section("ALL-APPS GRID ORDER  —  ▲ / ▼");
        List<String> grid = currentGrid();
        for (int i = 0; i < grid.size(); i++) {
            final int idx = i;
            final String pkg = grid.get(i);
            SimpleApp a = find(pkg);
            if (a == null) continue;
            LinearLayout row = row(a.label);
            row.addView(action("▲", () -> {
                List<String> g = currentGrid();
                if (idx > 0) { Collections.swap(g, idx, idx - 1); PremiumPrefs.setGridOrder(this, g); rebuild(); }
            }));
            row.addView(action("▼", () -> {
                List<String> g = currentGrid();
                if (idx < g.size() - 1) { Collections.swap(g, idx, idx + 1); PremiumPrefs.setGridOrder(this, g); rebuild(); }
            }));
        }

        // Columns
        section("GRID COLUMNS");
        LinearLayout colRow = row("Tiles per row");
        int curCols = PremiumPrefs.getColumns(this) > 0 ? PremiumPrefs.getColumns(this)
                : getResources().getInteger(R.integer.columns);
        for (int c : new int[]{4, 5, 6, 7}) {
            final int cols = c;
            colRow.addView(action(c == curCols ? "● " + c : String.valueOf(c), () -> {
                PremiumPrefs.setColumns(this, cols); rebuild();
            }));
        }

        // Wallpaper
        section("WALLPAPER");
        boolean wallOn = PremiumPrefs.isWallpaperOn(this);
        LinearLayout wallRow = row(wallOn ? "Wallpapers: ON" : "Wallpapers: OFF");
        wallRow.addView(action(wallOn ? "Turn off" : "Turn on", () -> {
            PremiumPrefs.setWallpaperOn(this, !wallOn); rebuild();
        }));

        // Reset / done
        section("RESET");
        buttonRow("Reset all premium settings to defaults", () -> {
            PremiumPrefs.reset(this); rebuild();
        });
        buttonRow("Done — back to Home", this::finish);

        setContentView(scroll);
        // Focus the first button so the remote works immediately.
        if (list.getChildCount() > 0) {
            View first = findFirstFocusable(list);
            if (first != null) first.requestFocus();
        }
    }

    private boolean isDefaultHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    private View findFirstFocusable(View v) {
        if (v.isFocusable()) return v;
        if (v instanceof LinearLayout) {
            LinearLayout l = (LinearLayout) v;
            for (int i = 0; i < l.getChildCount(); i++) {
                View f = findFirstFocusable(l.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private void section(String s) {
        TextView t = text(s, 13, 0x99FFFFFF);
        t.setLetterSpacing(0.22f);
        list.addView(t, pad(0, 28, 0, 10));
    }

    private LinearLayout row(String label) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(label, 16, Color.WHITE);
        l.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        r.addView(l, lp);
        list.addView(r, pad(0, 0, 0, 10));
        return r;
    }

    private void buttonRow(String label, Runnable onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(action(label, onClick));
        list.addView(r, pad(0, 0, 0, 10));
    }

    private TextView action(String label, Runnable onClick) {
        TextView b = text(label, 14, Color.WHITE);
        b.setFocusable(true);
        b.setClickable(true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            b.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(6), 0, dp(6), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams pad(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
