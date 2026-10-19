# Xiaomi Mi TV (MiTV-AXFR1 / "rango")

Android TV 11 with Xiaomi PatchWall, Amlogic (droidlogic) chipset, **1 GB RAM**.
Debloated with the scripts in this repo in October 2026.

## What's different from the TCL example

### 1. PatchWall sits on top of Google's launcher
Only two apps register as HOME:

```
com.google.android.tvlauncher/.MainActivity      priority=2
com.android.tv.settings/.system.FallbackHome     priority=-1000
```

PatchWall (`com.mitv.tvhome.atv`) is **not** a HOME app. It's opened on top by Xiaomi's
remote-key handler (see 2). So `resolve-activity` reports Google's launcher even though
PatchWall is what you see.

A third-party launcher has HOME priority 0. It can't beat Google's launcher (priority 2),
so `com.google.android.tvlauncher` must be disabled. The launcher install script needs:

```bash
STOCK_LAUNCHER=com.google.android.tvlauncher launcher/build-and-install.sh
```

The default value, `com.google.android.apps.tv.launcherx`, is the Google TV launcher and
isn't on this set. `com.google.android.tungsten.setupwraith` is present but not a HOME app
here, so it doesn't hijack Home.

### 2. The remote's Home button is a "global key", not KEYCODE_HOME
`logcat` when pressing Home on the remote:

```
WindowManager: interceptKeyTi keyCode=192 down=true ...
GlobalKey: receive ACTION_GLOBAL_BUTTON keyCode = 192
GlobalKey: receiving KeyEvent.KEYCODE_BUTTON_2 start tvhome
```

The receiver is `com.xiaomi.android.tvsetup.partnercustomizer`. It starts PatchWall directly.
The standard `input keyevent KEYCODE_HOME` works fine; only the remote's button goes
through Xiaomi.

With PatchWall disabled, **the remote's Home button does nothing**. Back still returns to
whatever launcher is set as HOME. Options:

| Option | Home button | Cost |
|---|---|---|
| Live with it, use **Back** | dead | none (what this household chose) |
| Button Mapper (flar2) remapping key 192 → Home | works | see 3 |
| Leave PatchWall enabled | works, opens PatchWall | PatchWall's RAM/ads |

The Netflix / Prime / Hotstar remote buttons likely go through the same receiver; they were
not used here, so this wasn't checked.

### 3. Button Mapper costs a CPU core on this set
Button Mapper did remap key 192 correctly, but `mediaserver` sat at ~97% CPU and
`media.extractor` at ~38%. `dumpsys media.extractor` showed an Ogg file inside Button
Mapper's own APK being decoded constantly (`flar2.homebutton ... base.apk ... active`),
which looks like a keep-alive audio loop. Force-stopping Button Mapper:

| | Button Mapper running | stopped |
|---|---|---|
| CPU idle (of 400%) | 206% | 344% |
| mediaserver | 97% | gone |
| kswapd0 | 26% | gone |

On a 1 GB set that isn't worth it.

### 4. Projectivy vs this repo's launcher
Projectivy Launcher was tried first (it works once `tvlauncher` is disabled) but used
~120 MB RSS and ~40% CPU at boot. It also launched itself at boot even after another
launcher was set as HOME, so it had to be disabled. This repo's launcher:

```
com.mihir.tvhome    TOTAL PSS: ~31 MB   TOTAL RSS: ~68 MB
```

## Results (5 minutes after a cold boot)

| | Midway (Projectivy + Button Mapper) | Final (this launcher, no remapper) |
|---|---|---|
| CPU idle (of 400%) | 206% | 345% |
| kswapd0 (memory thrashing) | 26–34% CPU | not in top list |
| MemAvailable | 190–220 MB | 230–280 MB |

No baseline was taken on stock PatchWall before the first batch, so these aren't
before/after-everything numbers. `android.hardware.audio.service-droidlogic` sits at ~20%
CPU in every reading, stock or not.

## Order that worked

1. `tools/disable.sh examples/xiaomi-rango/debloat.txt`
2. `cp examples/xiaomi-rango/config.xml launcher/res/values/config.xml`
3. `./setup.sh` (full), then
   `STOCK_LAUNCHER=com.google.android.tvlauncher launcher/build-and-install.sh`
4. `tv 'pm disable-user --user 0 com.mitv.tvhome.atv'` (PatchWall)
5. Cold boot, check it opens the new launcher, then test HDMI.

Undo: see [RESTORE.md](RESTORE.md).

## Tips for this set
- `tools/tv.py` worked fine on macOS (arm64); `find-tv.sh` found it on port 5555 as soon as
  USB debugging was on.
- In zsh, `PY="tools/venv/bin/python -I tools/tv.py"; $PY ...` fails (no word splitting).
  Use a function: `tv() { tools/venv/bin/python -I tools/tv.py "$@"; }`
- Streaming apps can be kept from running in the background:
  `tv 'cmd appops set <package> RUN_ANY_IN_BACKGROUND ignore'`
- Netflix is a system app here; "uninstall" from Settings only disables it.
