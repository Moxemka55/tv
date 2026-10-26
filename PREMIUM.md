# Premium launcher — Xiaomi MiTv-AFMU0

This is a premium fork of the tv-home launcher in this repo, built for
ABD's Xiaomi Mi TV, model **MiTv-AFMU0**.

## What was wrong in the original, and what premium fixes

1. **Not the default Home, Home button did nothing useful**
   - Original: just installing the APK does not make it Home. You had to
     run `build-and-install.sh`, which sets the home activity and disables
     the stock launcher, or open the app manually / map a key.
   - Premium: a **Premium ★ pill** on the home screen opens on-TV settings
     with a **Default Home** section. It tells you if this launcher is
     currently the default, opens Android Home settings, and shows the
     exact Xiaomi ADB commands below. No key-mapping workaround needed
     once Home is set correctly.

2. **No editing, no reorder, no settings on the TV**
   - Original: dock, hidden apps and grid columns only lived in
     `launcher/res/values/config.xml`. Changing them needed a rebuild.
     The Settings pill only opened Android Settings.
   - Premium: **Premium ★ → Launcher Settings** on the TV itself:
     - Dock: Add, Remove, Move ◀ / ▶
     - All-apps grid: Move ▲ / ▼
     - Hide / Show any app
     - Grid columns: 4, 5, 6 or 7
     - Wallpaper on / off
     - Reset all premium settings to defaults
   - Everything is saved in SharedPreferences (`PremiumPrefs.java`) and
     MainActivity re-reads it on resume. config.xml is only the starting
     default.

3. **TCL-only services, Xiaomi services missing**
   - Original: Live TV was hard-coded to `com.tcl.tv` and the input picker
     to `com.tcl.suspension`. On Xiaomi neither exists, so Live TV / inputs
     silently did nothing.
   - Premium: brand-aware. On Xiaomi (`Build.MANUFACTURER` = Xiaomi, or
     model containing AFMU) Live TV tries, in order:
     `com.mediatek.wwtv.tvcenter`, `com.google.android.tv`, `com.android.tv`.
     The TCL input picker is not used on Xiaomi; the TV's own passthrough
     inputs from `TvInputManager` are shown instead, with Settings as fallback.

4. **Many apps / services did not appear at all**
   - Original: only `CATEGORY_LEANBACK_LAUNCHER` was queried. Sideloaded
     apps, Play Store, file managers and other non-Leanback services
     never showed up, so the home screen could look like "only background".
   - Premium: queries both `LEANBACK_LAUNCHER` and `LAUNCHER`, prefers
     the Leanback activity when both exist, and launches non-Leanback
     apps with the plain LAUNCHER category. The manifest `<queries>` block
     was updated the same way.

5. **Small errors that could crash or blank the screen**
   - `getAssets().list("wallpapers")` could return null → now guarded to
     an empty list.
   - `unregisterReceiver` in onPause/onDestroy could throw if the receiver
     was not registered → now guarded.
   - Wallpaper rotation now respects the on/off setting instead of always
     running.

## Xiaomi MiTv-AFMU0 — make Home button open this launcher

Same Wi-Fi as the TV, Developer options on, USB / Network debugging on.
Install the premium APK first (see Build below), then run, in order:

```
cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity
pm disable-user --user 0 com.mitv.tvhome.atv
pm disable-user --user 0 com.google.android.apps.tv.launcherx
am force-stop com.android.tv.settings
input keyevent KEYCODE_HOME
```

Notes, read these before you run it:
- `com.mitv.tvhome.atv` is Xiaomi PatchWall, the Xiaomi stock home.
  PatchWall is listed as package `com.mitv.tvhome.atv` in public listings.
- Only disable the stock launcher(s) above if they are actually present.
  Check first with: `pm list packages | grep -E "mitv.tvhome|launcherx|tvlauncher"`
- **Never disable core Android packages** — permissioncontroller,
  packageinstaller, ext.services, ext.shared, modmetadata, overlay.modules,
  etc. Disabling those can stop the TV booting, as reported upstream.
  Premium deliberately does NOT touch them.
- If your set is Google TV rather than Android TV, also disable
  `com.google.android.tungsten.setupwraith` — the original script does
  this, and `restore.sh` re-enables it.
- Or use the helper in this premium copy:
  `tools/set-default-home-xiaomi.sh` — it runs only the safe commands above.

Restore (undo default Home, keep the app installed):
```
pm enable com.mitv.tvhome.atv
pm enable com.google.android.apps.tv.launcherx
```
Then in Settings → Apps → Default apps → Home app, pick PatchWall / stock
again, or run the repo's `tools/restore.sh` with your debloat list.

## Build

Same as upstream, it needs JDK 17 and Android SDK platform 34. The
original `build-and-install.sh` defaults to macOS Homebrew paths; on
Linux / Windows set `JAVA_HOME` and `ANDROID_HOME` first, then:

```
./setup.sh
launcher/build-and-install.sh
```

The build script compiles every `*.java` under `launcher/src`, so the new
`PremiumPrefs.java` and `SettingsActivity.java` are included automatically.
The manifest version is bumped to 2.0-premium.

## Files changed for premium

- `launcher/src/com/mihir/tvhome/PremiumPrefs.java` — new, on-TV settings storage
- `launcher/src/com/mihir/tvhome/SettingsActivity.java` — new, D-pad settings UI
- `launcher/src/com/mihir/tvhome/MainActivity.java` — premium prefs, Xiaomi
  services, both launcher categories, grid order, wallpaper on/off, Premium pill
- `launcher/AndroidManifest.xml` — version 2.0-premium, LAUNCHER query,
  SettingsActivity registered
- `tools/set-default-home-xiaomi.sh` — new, safe Xiaomi default-Home commands
- `PREMIUM.md` — this file
