package com.mihir.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Opens an app for remote buttons that only the stock launcher knew how to handle. Google TV routes
 * the Chromecast remote's YouTube button (KEYCODE_BUTTON_3) to launcherx, so with launcherx
 * disabled it does nothing. An accessibility service sees keys before that routing happens.
 * Buttons and apps come from button_apps in res/values/config.xml; every other key passes through.
 * It can't see keys while the device is asleep, so those buttons don't wake it. The Assistant
 * button never reaches accessibility services; AssistActivity handles that one.
 *
 * With take_over_home on, it also stands in for the Home role on TVs where Android won't hand it
 * over (a Chromecast always picks Google TV's home, and disabling that boot-loops it): the Home
 * button opens this launcher, and so does the stock home screen whenever Android shows it (at
 * startup, on wake). Its other screens, like account checks Settings opens, are left alone, and so
 * is anything that opens while a cover is pending (an app cast from a phone right after waking).
 * It only sees which package and class each window change comes from, never window content.
 */
public class ButtonService extends AccessibilityService {

    private SparseArray<String> apps = new SparseArray<>();
    private String stockHome, stockHomeActivity; // what Android picks over this one, if take_over_home is on
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable cover = this::coverStockHome;
    private long coversSince;
    private int covers;

    @Override
    protected void onServiceConnected() {
        apps = buttonApps(this);
        if (!getResources().getBoolean(R.bool.take_over_home)) return;
        ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        String pkg = home == null ? null : home.activityInfo.packageName;
        if (pkg == null || pkg.equals(getPackageName()) || pkg.equals("android")) return; // already home, or a chooser
        stockHome = pkg;
        stockHomeActivity = home.activityInfo.name;
        // Window changes from every app (package and class names only): the stock home screen
        // schedules a cover, and any other activity opening meanwhile cancels it.
        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.packageNames = null;
        setServiceInfo(info);
        showHome();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        String pkg = apps.get(e.getKeyCode());
        if (stockHome != null && e.getKeyCode() == KeyEvent.KEYCODE_HOME) pkg = getPackageName();
        if (pkg == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (pkg.equals(getPackageName())) showHome();
            else launch(this, pkg);
        }
        return true; // swallow down, repeats and up
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (stockHome == null || pkg == null || cls == null) return;
        if (stockHome.contentEquals(pkg) && stockHomeActivity.contentEquals(cls)) {
            // Wait for it to settle: launcherx brings itself to the front again just after a Home intent.
            handler.removeCallbacks(cover);
            handler.postDelayed(cover, 250);
        } else if (handler.hasCallbacks(cover) && isActivity(pkg.toString(), cls.toString())) {
            handler.removeCallbacks(cover); // something else opened on top: leave it
        }
    }

    /** Window changes also come from views and dialogs; only an activity means a new screen. */
    private boolean isActivity(String pkg, String cls) {
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void coverStockHome() {
        long now = SystemClock.elapsedRealtime();
        if (now - coversSince > 5000) { coversSince = now; covers = 0; }
        if (++covers <= 5) showHome(); // never fight a stock home that keeps coming back
    }

    @Override public void onInterrupt() { }

    private void showHome() {
        try {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    /** button_apps from config.xml: key code to package. */
    static SparseArray<String> buttonApps(Context c) {
        SparseArray<String> apps = new SparseArray<>();
        for (String item : c.getResources().getStringArray(R.array.button_apps)) {
            String[] kv = item.split("=", 2);
            int code = KeyEvent.keyCodeFromString(kv[0].trim());
            if (kv.length == 2 && code != KeyEvent.KEYCODE_UNKNOWN) apps.put(code, kv[1].trim());
        }
        return apps;
    }

    static void launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException ignored) { }
    }
}
