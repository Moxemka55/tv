# Restore: Xiaomi Mi TV "rango"

Everything here was disabled, not uninstalled, so it all comes back with `pm enable`.

## Before any command

TV on, same Wi-Fi, Developer options → USB debugging on. Then, from the repo folder:

```bash
tv() { tools/venv/bin/python -I tools/tv.py "$@"; }
tools/find-tv.sh     # if the TV's IP changed
tv --probe
```

## Partial undo

```bash
tv 'pm enable <package.name>'                     # one app
tv 'pm list packages -d' | sort                   # what's disabled now
tv 'cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity'   # new launcher missing at Home
```

## Full restore (back to PatchWall)

Order matters: bring the old home screens back before removing the new launcher.

```bash
# 1. Old home screens, the Home-key handler, setup app
tv 'pm enable com.google.android.tvlauncher'
tv 'pm enable com.mitv.tvhome.atv'
tv 'pm enable com.xiaomi.android.tvsetup.partnercustomizer'
tv 'pm enable com.google.android.tungsten.setupwraith'
tv 'cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity'

# 2. The debloat list
tools/restore.sh examples/xiaomi-rango/debloat.txt

# 3. Anything else you disabled by hand, e.g.
tv 'pm enable com.xiaomi.mitv.updateservice'

# 4. Background limits back to normal (harmless if never set)
for p in com.netflix.ninja com.amazon.amazonvideo.livingroom in.startv.hotstar com.google.android.youtube.tv; do
  tv "cmd appops set $p RUN_ANY_IN_BACKGROUND allow"
done

# 5. Remove the new launcher
tv 'pm uninstall com.mihir.tvhome'

# 6. Should print nothing, or only apps you disabled yourself
tv 'pm list packages -d'
```

Then cold-boot the TV: PatchWall should open and the remote's Home button should work.

Last resort: Settings → Device Preferences → Reset (factory reset) re-enables everything,
but wipes all apps and logins.
