package com.mihir.tvhome;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * App usage, for sorting the grid by recency and for Back-to-resume. Needs usage access, granted once over ADB:
 * adb shell appops set com.mihir.tvhome GET_USAGE_STATS allow. Without it everything here returns empty.
 */
final class Usage {
    private Usage() { }

    static boolean granted(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        return ops != null && ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** Package -> last time it was in front, over the last 90 days. */
    static Map<String, Long> lastUsed(Context c) {
        if (!granted(c)) return Collections.emptyMap();
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        Map<String, Long> out = new HashMap<>();
        for (UsageStats s : usm.queryAndAggregateUsageStats(now - 90L * 86_400_000L, now).values()) {
            if (s.getLastTimeUsed() > 0) out.put(s.getPackageName(), s.getLastTimeUsed());
        }
        return out;
    }

    /** The most recent app among {@code candidates} that came to the front in the last 12 hours, or null. */
    static String lastInFront(Context c, Set<String> candidates) {
        if (!granted(c)) return null;
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 12L * 3_600_000L, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String last = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED && candidates.contains(e.getPackageName())) {
                last = e.getPackageName();
            }
        }
        return last;
    }
}
