package com.mihir.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Lets the launcher's Settings menu open the system power menu (Power off / Restart). Apps cannot shut a TV down
 * themselves; an enabled accessibility service may perform the "power dialog" global action. This one listens
 * for no events and cannot read the screen.
 */
public class PowerService extends AccessibilityService {
    private static PowerService instance;

    /** The running service, or null until the user enables it in Accessibility settings. */
    static PowerService get() { return instance; }

    @Override protected void onServiceConnected() { instance = this; }

    @Override public boolean onUnbind(android.content.Intent i) { instance = null; return super.onUnbind(i); }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override public void onInterrupt() { }
}
