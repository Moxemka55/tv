package com.mihir.tvhome;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * The photos in assets/wallpapers (NN.jpg), their captions, and which time of day each suits.
 * res/values/captions.xml has one caption and one mood per photo, in file order.
 */
final class Wallpapers {
    final String[] files;
    private final String[] captions, moods;
    private final Random rnd = new Random();

    Wallpapers(Context c) {
        String[] f;
        try {
            f = c.getAssets().list("wallpapers");
        } catch (java.io.IOException e) {
            f = null;
        }
        files = f == null ? new String[0] : f;
        Arrays.sort(files);
        captions = c.getResources().getStringArray(R.array.wallpaper_captions);
        moods = c.getResources().getStringArray(R.array.wallpaper_moods);
    }

    /** "morning" 05:00-10:59, "day" 11:00-16:59, "evening" otherwise. */
    static String period() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 5 && h < 11 ? "morning" : h >= 11 && h < 17 ? "day" : "evening";
    }

    /** Photos for the current time of day; every photo if fewer than three are tagged for it. */
    List<String> forNow() {
        String p = period();
        List<String> out = new ArrayList<>();
        for (String f : files) if (mood(f).contains(p)) out.add(f);
        return out.size() >= 3 ? out : Arrays.asList(files);
    }

    /** The photo after {@code last} for the current time of day, or a random one to start. */
    String next(String last) {
        List<String> l = forNow();
        if (l.isEmpty()) return null;
        int i = l.indexOf(last);
        return l.get(i < 0 ? rnd.nextInt(l.size()) : (i + 1) % l.size());
    }

    String caption(String file) {
        int n = number(file);
        return n >= 1 && n <= captions.length ? captions[n - 1] : "";
    }

    private String mood(String file) {
        int n = number(file);
        return n >= 1 && n <= moods.length ? moods[n - 1] : "";
    }

    /** "13.jpg" -> 13; 0 for files without a number. */
    private static int number(String file) {
        try {
            return Integer.parseInt(file.substring(0, file.indexOf('.')));
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
