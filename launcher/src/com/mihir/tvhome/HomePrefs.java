package com.mihir.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dock order and hidden apps as edited on the TV (hold OK on a tile). Until the first edit the dock comes
 * from res/values/config.xml, so changing config.xml still works on a fresh install.
 */
final class HomePrefs {
    private final SharedPreferences sp;
    private final String[] defaultDock;

    HomePrefs(Context c, String[] defaultDock) {
        sp = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        this.defaultDock = defaultDock;
    }

    List<String> dock() {
        String s = sp.getString("dock", null);
        return new ArrayList<>(s == null ? Arrays.asList(defaultDock) : split(s));
    }

    void setDock(List<String> pkgs) {
        sp.edit().putString("dock", TextUtils.join(",", pkgs)).apply();
    }

    Set<String> hidden() {
        return new LinkedHashSet<>(split(sp.getString("hidden", "")));
    }

    void setHidden(Set<String> pkgs) {
        sp.edit().putString("hidden", TextUtils.join(",", pkgs)).apply();
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }
}
