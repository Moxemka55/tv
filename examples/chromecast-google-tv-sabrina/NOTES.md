# Chromecast with Google TV 4K (sabrina), Android 14

One device, build UTTC.250917.004, October 2026. Debloated with `debloat.txt`; the launcher runs
with `config.xml`. Three things here can leave a Chromecast unable to cast, offline, or
boot-looping, and none of them shows up until later. Read this before touching the home screen.

## The short version

- Keep **both** the Google TV home (`com.google.android.apps.tv.launcherx`) and the setup app
  (`com.google.android.tungsten.setupwraith`) enabled.
  - Setup app off: casting breaks, and its saved Wi-Fi network can vanish.
  - Setup app on, Google TV home off: the Chromecast boot-loops at its next restart.
- So our launcher can't be the real home screen here. With `take_over_home` on, the launcher's
  accessibility service takes over the Home button instead.
- If you disable Assistant (`katniss`), something else has to keep Cast's receiver running. The
  launcher binds it (`HomeApplication.java`).
- Restart the Chromecast once at the end, and check it comes back. Have a computer that it already
  trusts at hand (see "If it boot-loops").

`take_over_home`, `button_apps`, the Cast fix and the matching install-script changes are in the
launcher PR that goes with this folder. The launcher on `main` doesn't do any of it yet.

## Why you can't just disable the Google TV home

Android picks the home screen by intent-filter priority. It only uses the Home role, or a home set
with `cmd package set-home-activity`, when the top candidates tie. Check the order with
`cmd package query-activities --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME`:

| Priority | Home activity |
|---|---|
| 2 | `com.google.android.apps.tv.launcherx/.home.HomeActivity` |
| 1 | `com.google.android.tungsten.setupwraith/.RecoveryActivity` |
| 0 | any other launcher, including this one |

`RecoveryActivity` ships disabled, and setup switches it on as a fallback home. All it does is
re-enable launcherx's home activity and open it. With the launcherx package disabled
(`pm disable-user`) it can't open it, so it closes straight away.

- **On wake** it opened and closed, which revealed our launcher underneath. Everything looked fine
  for a day.
- **At startup** nothing was underneath, so Android reopened it over and over. After the next
  restart, Android restarted itself about once a minute: the colourful Google TV logo each time, a
  black screen in between, Wi-Fi dropping. Unplugging didn't help.

What doesn't work:
- **Disabling just `RecoveryActivity`:** Android 14 refuses. Shell can only change whole packages
  ("Shell cannot change component state").
- **A preferred home or the Home role:** ignored, because the priorities don't tie.

## Why you can't just disable the setup app

- **Cast stops working.** Cast's receiver gets its signing key from the setup app's
  `CastInfoProvider`, so phones end the session at once: "Casting screen to TV has ended".
- **Wi-Fi can disappear.** Disabling the setup app deleted the Wi-Fi network it had saved during
  phone-based setup, and the Chromecast went offline. Re-add Wi-Fi in Settings first, so the
  system owns the network.

Disabling both packages does make the launcher the real home (it survived a restart), but without
Cast.

## What we run instead: `take_over_home`

The Google TV home stays enabled, so Android's startup path is the stock one. The launcher's
accessibility service (`ButtonService`) then:
- catches the Home key, so pressing Home never shows the Google TV home;
- covers launcherx's `HomeActivity` about 0.5 s after Android shows it (1.6 s at startup). It waits
  250 ms after the last window change, because launcherx brings itself to the front again just
  after a Home intent.

It sees which package and class each window change comes from, never window content, and only
covers launcherx's `HomeActivity`. Settings opens other launcherx screens, such as
`AccountVerificationActivity`, and those are left alone. So is an app whose window appears during the
250 ms wait. An app that starts in that window but draws later (YouTube takes 0.3–0.6 s) can still end
up behind the launcher. Casting to a sleeping TV isn't affected: it wakes straight into the Cast
receiver, and the stock home never appears.

Open the launcher with an explicit start (`new Intent(this, MainActivity.class)`), not a full Home
intent (`ACTION_MAIN` + `CATEGORY_HOME`). With a Home intent, Android files the launcher in its home
task and makes it the home process. launcherx then loses its exemption from Android 14's limits on
background activity starts, so the remote's YouTube and Netflix buttons stop opening their apps: the
log says `Background activity launch blocked` and `BAL_BLOCK`. This was seen with cleanyourtv's
launcher.

Tested after a deliberate restart:
- Home from inside Netflix, YouTube and Prime Video.
- Back out of apps and out of Settings.
- The YouTube, Netflix and Assistant buttons.
- No Android restarts for 24 minutes afterwards.

The cost is memory. launcherx's background service uses about 40 MB, and its home process grows to
about 150 MB once it has been shown. Android reclaims that when it needs the memory.

## Cast gets frozen once Assistant is off

Google Cast's receiver (`com.google.android.apps.mediashell`) only runs as a foreground service
while something is bound to its `CastReceiverService`. On stock, Assistant (and maybe the Google TV
home) keeps it running. With `katniss` disabled it became a cached app, and Android 14's freezer
froze it, even with the TV on. Phones then couldn't find the TV, or their connections timed out.
The TV doesn't answer mDNS, the 8009 listen backlog fills up, and the phones' connections sit in
`CLOSE_WAIT`.

- **It hides from testing.** After any restart, the receiver gets about 30 minutes before the
  freeze. A quick cast test after a reboot proves nothing; test with the screen off, 30+ minutes
  later.
- **Check:** `dumpsys activity lru | grep mediashell` should say `FGS` or `vis`, not `cch`.
- **Fix:** the launcher binds `CastReceiverService` with its own action. That makes `mediashell`
  call `startForeground`, and foreground services aren't frozen. It only does that in `onBind`, and
  Android calls `onRebind` instead for an intent that was bound before (after a launcher restart or
  update), so each launcher process binds with a distinct intent (`Intent.setIdentifier`).
- **Don't nudge a frozen receiver:** `am startservice` on it times out, and Android kills it as
  unresponsive (ANR). `cmd activity unfreeze` printed success but didn't thaw it.

## Remote buttons

- **YouTube** (`KEYCODE_BUTTON_3`) belongs to launcherx; `dumpsys window policy` lists who owns each
  global key. **Netflix** (`KEYCODE_BUTTON_4`) belongs to `chromecastservice` and kept working.
- **Assistant** sends `KEYCODE_ASSIST`, which only ever goes to the Assistant role holder.
  Accessibility services never see it. `config.xml` maps it to Prime Video through the Assistant
  role (`AssistActivity`).
- **Test with the physical remote.** Keys sent with `adb shell input keyevent` skip accessibility
  services, so they can't show whether a mapping works.
- **Mapped buttons can't wake the Chromecast**: accessibility services don't get keys while it's
  asleep.

## ADB on this device

- **No classic ADB on port 5555**, only Wireless debugging. That needs a pairing code and TLS, which
  the `adb-shell` package (`tools/tv.py`) can't do, so use Google's platform-tools `adb`:
  `adb pair IP:PORT CODE`, then `adb connect IP:PORT`. Wireless debugging switches itself off at
  every restart, and when Wi-Fi drops.
- **The USB-C port does carry ADB.** Plugged into a laptop, the Chromecast runs from the laptop's
  power. It only connects if it already trusts that computer's ADB key, because you can't accept a
  prompt during a boot loop. Copy `~/.android/adbkey` and `adbkey.pub` from the computer that
  paired over Wi-Fi. USB debugging must have been left on.
- **The logs won't explain a boot loop afterwards.** The log buffers are 256 KiB, about 30 minutes
  of main log, and `dumpsys dropbox` was empty afterwards. Unplugging also clears the logs.
- **Use `</dev/null` in loops.** `adb shell` reads stdin, so a loop of `adb shell` calls stops after
  the first one without it. Run one `pm` command per call.

## If it boot-loops

Plug the Chromecast into the trusted computer by USB-C (HDMI still in the TV) and retry until
Android is up long enough. If the Google TV home is disabled, re-enable it:

```bash
until adb -d shell pm enable --user 0 com.google.android.apps.tv.launcherx 2>/dev/null | grep -q 'new state: enabled'; do sleep 1; done
```

With nothing else, the last resort is a factory reset: hold the button on the back until the light
blinks yellow, then turns white.

## Undo

```bash
tools/restore.sh examples/chromecast-google-tv-sabrina/debloat.txt
adb uninstall --user 0 com.mihir.tvhome    # if you want the launcher gone too
```

With the launcher PR applied, `tools/restore.sh` also hands Home and Assistant back and turns off
`ButtonService`. Without it, turn `ButtonService` off yourself if you'd turned it on. This keeps any
other accessibility services, such as a screen reader:

```bash
adb shell 's=$(settings get secure enabled_accessibility_services | sed "s#com.mihir.tvhome/com.mihir.tvhome.ButtonService##; s#::*#:#g; s#^:##; s#:\$##"); case "$s" in ""|null) settings delete secure enabled_accessibility_services ;; *) settings put secure enabled_accessibility_services "$s" ;; esac'
```

`tools/restore.sh` uses `tools/tv.py`, so on a Chromecast run its commands with `adb shell`, one
per call.
