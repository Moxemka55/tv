# tv-home

**Make a slow, ad-filled Android TV fast and clean again.** Debloat it over Wi-Fi and give it a
tiny Apple TV-style home screen with rotating nature wallpapers. You can hand the whole thing to
Claude Code with one prompt, or do it by hand with the scripts here.

![Before: Google TV home with a sponsored hero. After: clean dock over a Lake Louise wallpaper](before-after.png)

On a 2 GB TCL Google TV this disabled 25 bloat apps and cut the home screen's memory use from
192 MB to 83 MB. Everything is reversible: packages are disabled, never uninstalled.

## Do it with Claude Code (easiest)

1. On the TV: Settings → System → About → press OK on **Build** 7 times → back → Developer options
   → turn on **USB debugging**. Keep the TV on, on the same Wi-Fi as your computer.
2. Clone this repo, open Claude Code inside it (so it can reuse the launcher and scripts), and paste:

   ```text
   My Android TV is slow and full of ads. Find it on my Wi-Fi, connect over ADB, and debloat it:
   disable the manufacturer's ads, telemetry and junk (reversibly, never uninstall), asking me first
   about apps and features I might use. Then build and install a fast, minimal Apple TV-style home
   screen with a dock of my favourite apps and rotating nature wallpapers. Back up before changing
   anything, check every step with screenshots from the TV, and give me a restore script.

   Heads-up from people who've done this: use the adb-shell Python package if macOS blocks adb; on
   Google TV also disable com.google.android.tungsten.setupwraith or it hijacks the Home button; and
   test the Home button from inside an app.
   ```

3. Accept "Allow USB debugging?" on the TV when asked, then answer a few questions: which apps you
   use, which go in the dock, whether you want wallpapers.

The prompt is short on purpose; Claude works out the details. The gotchas below are there in case it gets stuck.

## Do it by hand (macOS)

```bash
./setup.sh                                                  # Python ADB client, JDK 17, Android SDK
tools/find-tv.sh                                            # finds the TV, saves its IP
tools/venv/bin/python -I tools/tv.py --probe                # model, Android version
tools/disable.sh examples/tcl-google-tv-g07/debloat.txt     # or your own list, see examples/
launcher/build-and-install.sh                               # build + install the launcher
```

Undo everything with `tools/restore.sh <the same list>`.

### Linux, or a Chromecast with Google TV

`setup.sh` uses Homebrew, so install JDK 17 and the Android SDK (`platforms;android-34`,
`build-tools;34.0.0`) yourself and point `JAVA_HOME` and `ANDROID_HOME` at them. A Chromecast has no
classic ADB on port 5555, which is all `tools/tv.py` can use, so build with the script and install
with Google's platform-tools `adb`, over Wireless debugging or USB. Read
`examples/chromecast-google-tv-sabrina/NOTES.md` first, and copy its `config.xml` over
`launcher/res/values/config.xml` (it sets `take_over_home`):

```bash
adb pair IP:PAIR_PORT CODE     # Developer options → Wireless debugging → Pair device with pairing code
adb connect IP:PORT            # the IP:port shown under Wireless debugging
launcher/build-and-install.sh --build
adb install --user 0 -r launcher/build/tvhome.apk
adb shell pm grant com.mihir.tvhome android.permission.ACCESS_FINE_LOCATION
# turn on ButtonService, keeping any accessibility services already on
adb shell 's=$(settings get secure enabled_accessibility_services); case "$s" in *com.mihir.tvhome*) ;; null|"") settings put secure enabled_accessibility_services com.mihir.tvhome/com.mihir.tvhome.ButtonService ;; *) settings put secure enabled_accessibility_services "$s:com.mihir.tvhome/com.mihir.tvhome.ButtonService" ;; esac; settings put secure accessibility_enabled 1'
adb shell cmd role add-role-holder --user 0 android.app.role.ASSISTANT com.mihir.tvhome   # if KEYCODE_ASSIST is mapped
adb shell am start -n com.mihir.tvhome/.MainActivity
```

Leave the Google TV home and the setup app enabled (see the gotchas). Check
`adb shell settings get secure enabled_accessibility_services` after every update: an update once
wiped it. Then restart the Chromecast on purpose and check it comes back.

## What's here

| Path | What it is |
|---|---|
| [PROMPT.md](PROMPT.md) | The prompt for Claude Code / any coding agent |
| [launcher/](launcher/) | The home screen. 4.7 MB including the wallpapers; plain Android, no network; its one service is the optional accessibility service for remote buttons and Home |
| [launcher/res/values/config.xml](launcher/res/values/config.xml) | Dock apps, hidden apps, Live TV app, remote buttons, Home takeover, grid columns |
| [tools/tv.py](tools/tv.py) | Pure-Python ADB client: shell, batch, screenshots, chunked APK push |
| [tools/find-tv.sh](tools/find-tv.sh) | Finds Android TVs on your Wi-Fi |
| [tools/type.sh](tools/type.sh) | Type into the TV's on-screen keyboard from your laptop (`tools/type.sh` alone prompts hidden, for passwords) |
| [tools/disable.sh](tools/disable.sh), [tools/restore.sh](tools/restore.sh) | Apply / undo a debloat list |
| [examples/](examples/) | Debloat lists per TV model |

## The launcher

- Clock, greeting and date top-left. Frosted pills top-right for **Inputs** (HDMI/AV/Live TV
  dropdown; the remote's Menu key opens it too), **Wi-Fi** (shows the network name) and **Settings**.
- A dock of your favourite apps. The focused app's name appears above it over a glow coloured from
  its banner. Press Down for every other app in a grid.
- 12 nature photos crossfade every 10 minutes, with a scrim so text stays readable. Swap them in
  `launcher/assets/wallpapers/` (1920×1080 JPEGs, credits in `launcher/WALLPAPER-CREDITS.md`).
- Deterministic D-pad focus, remembers the last tile, picks up newly installed apps on return.
- Remote buttons that lose their owner when the stock launcher goes (Chromecast: YouTube,
  Assistant) can open an app of your choice: `button_apps` in `config.xml`.
- Where Android won't make it the real home screen (a Chromecast), `take_over_home` makes the
  remote's Home button open it, and covers the Google TV home whenever Android shows it.
- Keeps Chromecast built-in (Google Cast) awake once Google Assistant is disabled.

Change the dock: edit `config.xml`, then run `launcher/build-and-install.sh`.

## Gotchas we hit

- **macOS blocks the `adb` binary** on the local network ("No route to host"). `tools/tv.py` uses
  the `adb-shell` Python package instead.
- **Google TV hijacks the Home button.** Android picks the home by intent-filter priority, and only
  uses the Home role or a preferred home when priorities tie. launcherx has 2, the setup app's
  `RecoveryActivity` (`com.google.android.tungsten.setupwraith`) 1, any other launcher 0. So there
  are two safe setups, and the install script uses one of them:
  - Both off: the launcher is the real home. This is the default.
  - Both on, with `take_over_home`: the launcher's accessibility service catches the Home button
    and covers launcherx's home screen when Android shows it. Use this where the setup app is
    needed, like a Chromecast.
- **Never disable the stock launcher and leave the setup app on.** `RecoveryActivity` is a fallback
  home: it re-enables launcherx and opens it, and can't if the package is disabled, so it just
  closes. On wake that revealed our launcher underneath and looked fine. At the next restart there
  was nothing underneath, and a Chromecast boot-looped: Android restarted itself about once a
  minute, Wi-Fi dropping each time. ADB can't switch off just `RecoveryActivity` (Android 14 lets
  the shell change whole packages only). Before changing Home on a Google TV, leave USB debugging on
  and keep a computer paired, so you can get back in over USB, then reboot-test.
  `examples/chromecast-google-tv-sabrina/NOTES.md` has the rescue.
- **On a Chromecast, disabling the setup app breaks Cast.** Cast gets its signing key from the setup
  app, so phones end the session at once. Disabling it also deleted the Wi-Fi network it had saved
  during phone setup, taking the Chromecast offline: re-add Wi-Fi in Settings first.
- **Cast goes quiet once Google Assistant is disabled.** Nothing keeps Cast's receiver
  (`mediashell`) bound any more, so Android 14 freezes it like any cached app, and phones can't
  find or reach the TV. It works for about 30 minutes after each restart, which hides it in
  testing. The launcher binds the receiver's `CastReceiverService`, which makes it a foreground
  service (`HomeApplication.java`). `adb shell dumpsys activity lru | grep mediashell` should show
  `FGS`, not `cch`.
- **Remote buttons can belong to the stock launcher.** The Chromecast remote's YouTube button
  (`KEYCODE_BUTTON_3`) goes to launcherx; `dumpsys window policy` lists who owns which button. The
  launcher's accessibility service (`ButtonService`) catches such buttons. The Assistant button
  (`KEYCODE_ASSIST`) never reaches accessibility services, so the install script gives the launcher
  the Assistant role instead. Keys sent with `adb shell input keyevent` skip accessibility services,
  so test buttons with the real remote.
- **Home opened the launcher behind the current app**, because of a stale home task from switching
  launchers. Force-stopping the launcher and `com.android.tv.settings` and pressing Home fixes it.
  The install script does this.
- **Chained `pm` commands** (`pm disable a; pm disable b`) can silently stop after the first. The
  scripts run one per shell call. With the real `adb`, also give `adb shell` `</dev/null` in a
  loop, or it swallows the rest of the loop's input.
- **Chromecast with Google TV has no classic ADB on port 5555**, only Wireless debugging, which
  needs a pairing code and TLS that `adb-shell` can't do. Use Google's platform-tools `adb`.
  Wireless debugging switches off at every restart.
- **Apps installed from the Play Store didn't appear**, because the package broadcast was missed
  while another app was in front. The launcher re-checks with `getChangedPackages` on every return.
- **Unsplash blocks scripted downloads** with a bot check. The wallpapers come from Wikimedia
  Commons Featured Pictures instead.

## Supported TVs

Android TV and Google TV: TCL, Xiaomi/Redmi, Hisense, Sony, OnePlus, Realme, Nvidia Shield,
Chromecast with Google TV and most others. Not Samsung (Tizen), LG (webOS) or Fire TV.
Tested on: TCL Google TV (Android 11), Chromecast with Google TV 4K (Android 14). Add yours to
`examples/`.

## Credits

Inspired by [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). Wallpaper photos are
from Wikimedia Commons under their own licences, see
[launcher/WALLPAPER-CREDITS.md](launcher/WALLPAPER-CREDITS.md). Code is MIT, see [LICENSE](LICENSE).
