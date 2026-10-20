#!/usr/bin/env bash
# Undo everything: re-enable every package in the debloat list, bring back the stock launcher (and
# Google TV's setup app), make the stock launcher the default home again, give the Assistant button
# back to Google Assistant and turn off the launcher's remote-button service.
# Usage: tools/restore.sh <debloat list>
#   STOCK_LAUNCHER=com.google.android.tvlauncher/.MainActivity tools/restore.sh list.txt   (older Android TV)
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: tools/restore.sh <debloat list>}"
PY="${PY:-tools/venv/bin/python}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx/.home.HomeActivity}"
{
  grep -vE '^\s*(#|$)' "$LIST" | awk '{print "pm enable " $1}'
  echo "pm enable com.google.android.tungsten.setupwraith"
  echo "pm enable ${STOCK_LAUNCHER%%/*}"
  echo "cmd package set-home-activity --user 0 $STOCK_LAUNCHER"
  echo "cmd role add-role-holder --user 0 android.app.role.HOME ${STOCK_LAUNCHER%%/*}"
  echo "cmd role add-role-holder --user 0 android.app.role.ASSISTANT com.google.android.katniss"
  # Turn off the launcher's ButtonService (remote buttons, Home takeover), keeping any other services.
  echo 's=$(settings get secure enabled_accessibility_services | sed "s#com.mihir.tvhome/com.mihir.tvhome.ButtonService##; s#::*#:#g; s#^:##; s#:\$##"); case "$s" in ""|null) settings delete secure enabled_accessibility_services ;; *) settings put secure enabled_accessibility_services "$s" ;; esac'
  echo "input keyevent KEYCODE_HOME"
} | "$PY" -I tools/tv.py --batch
echo "Restored. Press Home on the remote."
