#!/usr/bin/env bash
# Undo everything: reinstall (if removed for the user) and re-enable every package in the given lists, bring
# back the stock launcher (and Google TV's setup app) and make the stock launcher the default home again.
# Usage: tools/restore.sh <debloat list> [uninstall list ...]
#   STOCK_LAUNCHER=com.google.android.tvlauncher/.MainActivity tools/restore.sh list.txt   (older Android TV)
set -euo pipefail
cd "$(dirname "$0")/.."
[ $# -ge 1 ] || { echo "usage: tools/restore.sh <debloat list> [uninstall list ...]"; exit 1; }
PY="${PY:-tools/venv/bin/python}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx/.home.HomeActivity}"
{
  # install-existing brings back a package removed with `pm uninstall -k --user 0`; it's a no-op otherwise.
  grep -hvE '^\s*(#|$)' "$@" | awk '{print "cmd package install-existing " $1; print "pm enable " $1}'
  echo "pm enable com.google.android.tungsten.setupwraith"
  echo "pm enable ${STOCK_LAUNCHER%%/*}"
  echo "cmd package set-home-activity --user 0 $STOCK_LAUNCHER"
  echo "input keyevent KEYCODE_HOME"
} | "$PY" -I tools/tv.py --batch --keep-going
echo "Restored. Press Home on the remote."
