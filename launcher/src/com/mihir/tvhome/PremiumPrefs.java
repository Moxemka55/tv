package com.mihir.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Premium settings, stored on the TV itself (SharedPreferences).
 *
 * The old behaviour was: dock order, hidden apps and grid columns only lived
 * in res/values/config.xml, so changing them needed a rebuild. Premium keeps
 * config.xml as the starting default, and every on-TV change in
 * SettingsActivity is saved here and re-read by MainActivity on resume.
 * Reset clears everything back to the config.xml defaults.
 */
public final class PremiumPrefs {
    private static final String FILE = "premium";
    private static final String KEY_DOCK = "dock_order";      // comma-separated package names
    private static final String KEY_HIDDEN = "user_hidden";   // comma-separated package names
    private static final String KEY_GRID = "grid_order";      // comma-separated package names
    private static final String KEY_COLUMNS = "columns";      // 0 = use config.xml
    private static final String KEY_WALLPAPER = "wallpaper_on"; // default true

    private PremiumPrefs() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Null when the user has never edited the dock: caller falls back to config.xml. */
    public static List<String> getDockOrder(Context c) {
        String v = prefs(c).getString(KEY_DOCK, null);
        if (v == null || v.isEmpty()) return null;
        return new ArrayList<>(Arrays.asList(v.split(",")));
    }

    public static void setDockOrder(Context c, List<String> pkgs) {
        prefs(c).edit().putString(KEY_DOCK, join(pkgs)).apply();
    }

    public static Set<String> getUserHidden(Context c) {
        String v = prefs(c).getString(KEY_HIDDEN, "");
        Set<String> out = new HashSet<>();
        if (!v.isEmpty()) out.addAll(Arrays.asList(v.split(",")));
        return out;
    }

    public static void setUserHidden(Context c, Set<String> pkgs) {
        prefs(c).edit().putString(KEY_HIDDEN, join(new ArrayList<>(pkgs))).apply();
    }

    /** Null when the user has never reordered the grid: caller sorts alphabetically. */
    public static List<String> getGridOrder(Context c) {
        String v = prefs(c).getString(KEY_GRID, null);
        if (v == null || v.isEmpty()) return null;
        return new ArrayList<>(Arrays.asList(v.split(",")));
    }

    public static void setGridOrder(Context c, List<String> pkgs) {
        prefs(c).edit().putString(KEY_GRID, join(pkgs)).apply();
    }

    /** 0 means "use config.xml". */
    public static int getColumns(Context c) {
        return prefs(c).getInt(KEY_COLUMNS, 0);
    }

    public static void setColumns(Context c, int cols) {
        prefs(c).edit().putInt(KEY_COLUMNS, cols).apply();
    }

    public static boolean isWallpaperOn(Context c) {
        return prefs(c).getBoolean(KEY_WALLPAPER, true);
    }

    public static void setWallpaperOn(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_WALLPAPER, on).apply();
    }

    public static void reset(Context c) {
        prefs(c).edit().clear().apply();
    }

    private static String join(List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        return sb.toString();
    }
}
