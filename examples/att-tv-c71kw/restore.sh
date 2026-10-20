#!/usr/bin/env bash
# Undo everything on the AT&T TV / DIRECTV box: re-enable the debloat list and setupwraith, make DIRECTV
# the home screen again, turn off the launcher's Home-button service, put the Glance screensaver back
# (as it was) and uninstall the launcher.
# Usage: examples/att-tv-c71kw/restore.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
PY="${PY:-tools/venv/bin/python}"

STOCK_LAUNCHER=com.att.tv.openvideo/com.att.ngctv.dfw.navigation.MainNavigationActivity \
  tools/restore.sh examples/att-tv-c71kw/debloat.txt
printf '%s\n' \
  "settings delete secure enabled_accessibility_services" \
  "settings put secure accessibility_enabled 0" \
  "settings put secure screensaver_components com.glance.tv.directv/com.glance.tv.screensaver.ui.GlanceDayDream" \
  "pm uninstall com.mihir.tvhome" \
  "input keyevent KEYCODE_HOME" \
  | "$PY" -I tools/tv.py --batch
echo "Back to stock. Press Home on the remote."
