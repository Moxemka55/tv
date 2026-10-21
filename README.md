# tv-home

**Make a slow, ad-filled Android TV fast and clean again.** Debloat it over Wi-Fi and give it a
tiny Apple TV-style home screen whose background gradient follows the focused app. You can hand the whole thing to
Claude Code with one prompt, or do it by hand with the scripts here.

![Before: Google TV home with a sponsored hero. After: clean dock over a Lake Louise wallpaper (an earlier version; it now uses a colour gradient)](before-after.png)

On a 2 GB TCL Google TV this disabled 25 bloat apps and cut the home screen's memory use from
192 MB to 83 MB. On a 2.8 GB Philips Google TV (Android 14) the stock launcher process held 263 MB; this one holds
about 135 MB (38 MB proportional), and `tools/free-memory.sh` frees roughly 0.4 GB more on demand.
Everything is reversible: packages are disabled, never uninstalled.

## Do it with Claude Code (easiest)

1. On the TV: Settings → System → About → press OK on **Build** 7 times → back → Developer options
   → turn on **USB debugging**. Keep the TV on, on the same Wi-Fi as your computer.
2. Clone this repo, open Claude Code inside it (so it can reuse the launcher and scripts), and paste:

   ```text
   My Android TV is slow and full of ads. Find it on my Wi-Fi, connect over ADB, and debloat it:
   disable the manufacturer's ads, telemetry and junk (reversibly, never uninstall), asking me first
   about apps and features I might use. Then build and install a fast, minimal Apple TV-style home
   screen with a dock of my favourite apps and a gradient background. Back up before changing
   anything, check every step with screenshots from the TV, and give me a restore script.

   Heads-up from people who've done this: use the adb-shell Python package if macOS blocks adb; on
   Google TV also disable com.google.android.tungsten.setupwraith or it hijacks the Home button; and
   test the Home button from inside an app.
   ```

3. Accept "Allow USB debugging?" on the TV when asked, then answer a few questions: which apps you
   use, which go in the dock, what background you like.

The prompt is short on purpose; Claude works out the details. The gotchas below are there in case it gets stuck.

## Do it by hand (macOS)

```bash
./setup.sh                                                  # Python ADB client, JDK 17, Android SDK
tools/find-tv.sh                                            # finds the TV, saves its IP
tools/backup.sh                                             # snapshot packages + settings first

tools/venv/bin/python -I tools/tv.py --probe                # model, Android version
tools/disable.sh examples/tcl-google-tv-g07/debloat.txt     # or your own list, see examples/
launcher/build-and-install.sh                               # build + install the launcher
```

Undo everything with `tools/restore.sh <the same list>` (add `--dry-run` to read the commands first). After a
reboot, `tools/verify.sh` checks the launcher is still Home and the disabled packages stayed disabled.

## What's here

| Path | What it is |
|---|---|
| [PROMPT.md](PROMPT.md) | The prompt for Claude Code / any coding agent |
| [launcher/](launcher/) | The home screen. about 30 KB; plain Android, no network, no background services |
| [launcher/res/values/config.xml](launcher/res/values/config.xml) | Dock apps, hidden apps, Live TV app, grid columns |
| [tools/tv.py](tools/tv.py) | Pure-Python ADB client: shell, batch, screenshots, chunked APK push |
| [tools/find-tv.sh](tools/find-tv.sh) | Finds Android TVs on your Wi-Fi |
| [tools/type.sh](tools/type.sh) | Type into the TV's on-screen keyboard from your laptop (`tools/type.sh` alone prompts hidden, for passwords) |
| [tools/backup.sh](tools/backup.sh), [tools/verify.sh](tools/verify.sh) | Snapshot the TV before changes; check afterwards that it is still as expected |
| [tools/free-memory.sh](tools/free-memory.sh) | Force-stop every launchable app and clear cached processes (from the Mac, over ADB) |
| [tools/disable.sh](tools/disable.sh), [tools/restore.sh](tools/restore.sh) | Apply / undo a debloat list |
| [examples/](examples/) | Debloat lists per TV model |

## The launcher

- Clock, greeting and date top-left. Frosted pills top-right for **Inputs** (HDMI/AV/Live TV
  dropdown; the remote's Menu key opens it too), **Wi-Fi** (shows the network name) and **Settings**.
- A dock of your favourite apps. The focused app's name appears above it over a glow coloured from
  its banner. Press Down for every other app in a grid.
- The background is a diagonal gradient that eases to the focused app's colour (sampled from its banner),
  plus a tiled film-grain layer that removes banding. Nothing animates while it sits on Home, so it costs
  no CPU there. There are no photos to decode or keep in memory. Frosted pills have vector icons; the network pill
  shows Ethernet when wired.
- **Rearrange:** hold OK on a tile to pick it up (it wiggles), move it with the arrows (it swaps with its
  neighbour, in the dock and grid alike) and press OK to drop it. The order is saved on the TV.
- Focus arrives with a slight tilt in the direction you moved, over the sheen and glow.
- Settings pill: a dropdown of shortcuts plus **Restart** (the TV's own confirmation) and **Shut down** (the
  system power menu, via a tiny accessibility service that listens to no events and can't read the screen).
- **Screensaver** ("Home clock"): a large thin clock on a dim gradient tinted by the last app colour, moving a
  little each minute against burn-in. It is a normal Android dream, so it uses no CPU until the TV is idle.
  `settings put secure screensaver_components com.mihir.tvhome/.ClockDream` selects it.
- Deterministic D-pad focus, remembers the last tile, picks up newly installed apps on return.

Change the dock: edit `config.xml`, then run `launcher/build-and-install.sh`.

## Freeing memory

`tools/free-memory.sh` force-stops every app with a launcher tile (not the launcher, Settings, Play Store or
the TV's own apps) and clears cached processes: on the Philips TA1 that took free RAM from ~0.9 GB to ~1.4 GB.
For a standing cap, `settings put global activity_manager_constants max_cached_processes=6` limits how many
cached apps Android keeps (`tools/restore.sh` removes it). Killing apps automatically when you press Home can't
be done from a launcher: force-stop is shell/system only, and the Philips firmware rejects
`killBackgroundProcesses` from third-party apps ("Invalid packageName").

### Monthly upkeep on a small TV
Storage is the squeeze (the Philips TA1 has 4.9 GB for apps). Play Store auto-update is best switched off
(Play Store → profile → Settings → Auto-update apps → "Don't auto-update apps"), then once a month: update
apps from Manage apps & games, and run `tools/free-memory.sh --trim-caches` (about 0.4 GB back).

## Gotchas we hit

- **macOS blocks the `adb` binary** on the local network ("No route to host"). `tools/tv.py` uses
  the `adb-shell` Python package instead.
- **Google TV hijacks the Home button.** With the stock launcher disabled, the setup app
  (`com.google.android.tungsten.setupwraith`) has a higher HOME priority than any third-party
  launcher. The install script disables it; `restore.sh` re-enables it.
- **Home opened the launcher behind the current app**, because of a stale home task from switching
  launchers. Force-stopping the launcher and `com.android.tv.settings` and pressing Home fixes it.
  The install script does this.
- **Don't "test" restore.sh by backgrounding and killing it.** Killing the wrapper leaves its Python child
  running, so the batch keeps re-enabling your disabled packages.
- **Install failures must stop the script.** `INSTALL_FAILED_VERIFICATION_FAILURE` (Play Protect, first
  install) used to be ignored and the stock launcher got disabled anyway, leaving no home screen. The script
  now retries once and aborts untouched.
- **Chained `pm` commands** (`pm disable a; pm disable b`) can silently stop after the first. The
  scripts run one per shell call.
- **Apps installed from the Play Store didn't appear**, because the package broadcast was missed
  while another app was in front. The launcher re-checks with `getChangedPackages` on every return.

## Supported TVs

Android TV and Google TV: TCL, Xiaomi/Redmi, Hisense, Sony, OnePlus, Realme, Nvidia Shield,
Chromecast with Google TV and most others. Not Samsung (Tizen), LG (webOS) or Fire TV.
Tested on: TCL Google TV (Android 11), Philips Google TV TA1 (Android 14). Add yours to `examples/`.

## Credits

Inspired by [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). Code is MIT, see [LICENSE](LICENSE).
