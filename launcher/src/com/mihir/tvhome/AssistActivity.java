package com.mihir.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * The remote's Assistant button. Android opens the Assistant role holder for it, so with Google
 * Assistant disabled and this app given the role (build-and-install.sh does that), this invisible
 * activity opens whatever button_apps maps KEYCODE_ASSIST to. Without a mapping it just closes.
 */
public class AssistActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String pkg = ButtonService.buttonApps(this).get(KeyEvent.KEYCODE_ASSIST);
        if (pkg != null) ButtonService.launch(this, pkg);
        finish();
    }
}
