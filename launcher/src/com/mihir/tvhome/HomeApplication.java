package com.mihir.tvhome;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Process;

/**
 * Keeps Chromecast built-in reachable. Google's Cast receiver (mediashell) only runs as a foreground
 * service while something is bound to its CastReceiverService. On stock Google TV, Assistant and the
 * Google TV home use it, which keeps it running; with those disabled it becomes a cached app, Android
 * freezes it, and phones can't find or connect to the TV. So this process keeps it bound for as long
 * as it runs. On TVs without mediashell the bind just fails.
 */
public class HomeApplication extends Application implements ServiceConnection {

    static final ComponentName CAST_RECEIVER = new ComponentName(
            "com.google.android.apps.mediashell", "com.google.android.apps.mediashell.CastReceiverService");

    @Override
    public void onCreate() {
        super.onCreate();
        bindCastReceiver();
    }

    private void bindCastReceiver() {
        // Our own action: mediashell goes foreground for any bind, and hands unknown actions a plain Binder.
        // It only goes foreground in onBind, and Android calls onRebind instead for an intent bound
        // before, so make each launcher process's intent distinct.
        Intent i = new Intent(getPackageName() + ".KEEP_CAST_READY").setComponent(CAST_RECEIVER)
                .setIdentifier(Integer.toString(Process.myPid()));
        try {
            if (!bindService(i, this, BIND_AUTO_CREATE)) unbindService(this);
        } catch (RuntimeException ignored) { }
    }

    @Override public void onServiceConnected(ComponentName n, IBinder b) { }

    @Override public void onServiceDisconnected(ComponentName n) { } // Android rebinds when it restarts

    @Override
    public void onBindingDied(ComponentName n) { // mediashell was updated
        unbindService(this);
        bindCastReceiver();
    }
}
