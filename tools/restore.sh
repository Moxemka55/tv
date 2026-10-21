#!/usr/bin/env bash
# Undo everything: re-enable every package in the debloat list, bring back the stock launcher (and
# Google TV's setup app) and make the stock launcher the default home again.
# Usage: tools/restore.sh <debloat list> [--dry-run]     (--dry-run prints the commands and sends nothing)
#   STOCK_LAUNCHER=com.google.android.tvlauncher/.MainActivity tools/restore.sh list.txt   (older Android TV)
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: tools/restore.sh <debloat list> [--dry-run]}"
PY="${PY:-tools/venv/bin/python}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx/.home.HomeActivity}"
cmds=$({
  grep -vE '^\s*(#|$)' "$LIST" | awk '{print "pm enable " $1}'
  # Packages uninstalled for user 0 (never removed from the system image) come back with install-existing.
  [ -f "$(dirname "$LIST")/uninstalled.txt" ] && grep -vE '^\s*(#|$)' "$(dirname "$LIST")/uninstalled.txt" | awk '{print "cmd package install-existing " $1}'
  # Animation scales: the stock values recorded in the backup, or 1.0 if none.
  echo "settings put global window_animation_scale ${WINDOW_ANIM:-1.0}"
  echo "settings put global transition_animation_scale ${TRANSITION_ANIM:-1.0}"
  echo "settings put global animator_duration_scale ${ANIMATOR_ANIM:-1.0}"
  echo "settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop"   # the stock screensaver
  echo "settings delete secure enabled_accessibility_services"   # the launcher's power-menu service
  echo "settings put secure accessibility_enabled 0"
  echo "settings delete global activity_manager_constants"   # undo the cached-process cap, if set
  echo "pm enable com.google.android.tungsten.setupwraith"
  echo "pm enable ${STOCK_LAUNCHER%%/*}"
  echo "cmd package set-home-activity --user 0 $STOCK_LAUNCHER"
  echo "input keyevent KEYCODE_HOME"
})
if [ "${2:-}" = "--dry-run" ]; then printf '%s\n' "$cmds"; echo "(dry run: nothing was sent to the TV)"; exit 0; fi
printf '%s\n' "$cmds" | "$PY" -I tools/tv.py --batch
echo "Restored. Press Home on the remote."
