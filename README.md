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

## What's here

| Path | What it is |
|---|---|
| [PROMPT.md](PROMPT.md) | The prompt for Claude Code / any coding agent |
| [launcher/](launcher/) | The home screen. 4.7 MB including the wallpapers; plain Android, no network, no background services |
| [launcher/res/values/config.xml](launcher/res/values/config.xml) | Dock apps, hidden apps, Live TV app, grid columns |
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
- **Edit the dock on the TV**: hold OK on a tile to move it left or right, add it to or remove it from the dock,
  hide it, or open its app info. No rebuild needed. `config.xml` still sets the default dock.
- **Recently used first**: the grid under the dock is sorted by when you last opened each app.
- **Back to resume**: Back on the home screen returns to the app you were last watching, where you left off.
  A small hint above the dock says which app.
- **Quick settings**: the Settings pill opens a side panel (Picture, Sound, Network, Inputs, All settings, and the
  TV's "screen off, keep sound" mode where `config.xml` names it). Only rows this TV supports are shown.
- **Time-of-day wallpapers**: morning, day and evening photos (tags in `res/values/captions.xml`), with a slow
  pan-and-zoom.
- **Apple TV-style focus**: the focused tile leans the way you moved and settles, with a light sweep across it.
- **Nature screensaver** (`NatureDream`): the same photos with the place name and a clock. Turn it on with
  `adb shell settings put secure screensaver_components com.mihir.tvhome/.NatureDream`. Google TV hides the
  screensaver picker once its ambient mode (`dreamx`) is disabled.

Recently used and Back-to-resume need usage access. The install script grants it with
`appops set com.mihir.tvhome GET_USAGE_STATS allow`; without it the grid is A to Z and Back does nothing.

Change the default dock: edit `config.xml`, then run `launcher/build-and-install.sh`.

### Performance

Measured on a 2 GB Kodak Google TV (Realtek RTD2851A), the same build before and after this round of changes:

| | Before | After |
|---|---|---|
| Cold start (`Displayed` in logcat) | 730 ms | 455 ms |
| Frame time while moving around, median / 90th (`dumpsys gfxinfo`) | 57 / 69 ms | 19 / 22 ms |
| Janky frames | 31.6% | 4.8% |
| Frames drawn in 10 s of idle | 487 | 10 |
| Memory (PSS) | 52 MB | 36 MB |

Nearly all of it is about not redrawing a 1080p screen for nothing on a weak TV GPU:
- **Stepped drift:** the wallpaper drift is applied once a second (it moves under a pixel per second anyway),
  not at 60 fps.
- **Scrim baked in:** the scrim is painted into each photo when it's decoded, instead of being a second
  full-screen layer.
- **Fewer background layers:** the root view has no background, and the window's black background is dropped
  once the first photo is up.
- **Smaller glow:** the glow view covers only the bottom 70% of the screen, and its drawable is recoloured in
  place rather than reallocated every animation frame.
- **Off the main thread:** apps (banners, colours, usage) load in the background. Banner colours are cached
  per app version, and returning home re-reads only usage, rebuilding tiles only when the order changed.

## Gotchas we hit

- **macOS blocks the `adb` binary** on the local network ("No route to host"). `tools/tv.py` uses
  the `adb-shell` Python package instead.
- **Google TV hijacks the Home button.** With the stock launcher disabled, the setup app
  (`com.google.android.tungsten.setupwraith`) has a higher HOME priority than any third-party
  launcher. The install script disables it; `restore.sh` re-enables it.
- **Home opened the launcher behind the current app**, because of a stale home task from switching
  launchers. Force-stopping the launcher and `com.android.tv.settings` and pressing Home fixes it.
  The install script does this.
- **Chained `pm` commands** (`pm disable a; pm disable b`) can silently stop after the first. The
  scripts run one per shell call.
- **Apps installed from the Play Store didn't appear**, because the package broadcast was missed
  while another app was in front. The launcher re-checks with `getChangedPackages` on every return.
- **The remote's Settings button stops working** once the stock launcher is disabled. SystemUI sends it to
  `launcherx/.dashboard.DashboardHandler`, and only a system app may answer. Things that don't fix it on
  Android 14 without root:
  - The key never reaches apps.
  - Re-enabling the stock launcher makes it take over Home again: system launchers outrank both the preferred
    activity and the HOME role.
  - `pm disable <component>` is refused for other apps' components.
  - Accessibility services only see the key after the system has acted on it.

  The Settings pill's Quick settings panel is the replacement.
- **Disabling a package clears the settings that point at it**: disabling `katniss` empties `assistant`,
  `voice_interaction_service` and `voice_recognition_service`, and disabling `launcherx` empties
  `enabled_notification_listeners`. Re-enabling the apps doesn't refill them, so `restore.sh` now does.
- **Settings → Privacy → Usage & diagnostics spins forever** with `setupwraith` disabled: that app serves the
  page (`com.google.android.settings.usage`). Enable it briefly to change the setting, then disable it again.
- **Apps installed with `adb install` (incremental) are only verified, never compiled ahead of time**, even with
  `cmd package compile -m speed`. Use `adb install --no-incremental`. `speed-profile` only helps apps that
  already have a usage profile.
- **Unsplash blocks scripted downloads** with a bot check. The wallpapers come from Wikimedia
  Commons Featured Pictures instead.

## Supported TVs

Android TV and Google TV: TCL, Xiaomi/Redmi, Hisense, Sony, OnePlus, Realme, Nvidia Shield,
Chromecast with Google TV and most others. Not Samsung (Tizen), LG (webOS) or Fire TV.
Tested on: TCL Google TV (Android 11), Kodak Google TV by SPPL (Android 14). Add yours to `examples/`.

## Credits

Inspired by [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). Wallpaper photos are
from Wikimedia Commons under their own licences, see
[launcher/WALLPAPER-CREDITS.md](launcher/WALLPAPER-CREDITS.md). Code is MIT, see [LICENSE](LICENSE).
