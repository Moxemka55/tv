package com.mihir.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sends the remote's Home key (and boot) to this launcher. Only needed when the stock home app can't be
 * disabled: Android always picks a privileged home app with priority > 0 over the user's choice, and on
 * the AT&T TV / DIRECTV box that app is also live TV. Off unless enabled in enabled_accessibility_services.
 * Only real key presses reach it; `adb shell input keyevent` bypasses accessibility.
 */
public class HomeKeyService extends AccessibilityService {
    /** How long after boot the stock home app may not take over by itself (DIRECTV auto-starts at boot). */
    private static final long BOOT_GUARD_MS = 60_000;

    private long connectedAt;
    private boolean userPressedKey;
    private String stockHome;

    @Override
    protected void onServiceConnected() {
        connectedAt = SystemClock.uptimeMillis();
        ResolveInfo ri = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        if (ri != null && !getPackageName().equals(ri.activityInfo.packageName)) stockHome = ri.activityInfo.packageName;
        goHome(); // at boot the system has just opened the stock home
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        userPressedKey = true;
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) goHome();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (userPressedKey || stockHome == null || SystemClock.uptimeMillis() - connectedAt > BOOT_GUARD_MS) return;
        if (stockHome.contentEquals(String.valueOf(event.getPackageName()))) goHome();
    }

    private void goHome() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override
    public void onInterrupt() { }
}
