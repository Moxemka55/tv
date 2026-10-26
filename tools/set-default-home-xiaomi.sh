#!/usr/bin/env bash
# Make tv-home the default Home on a Xiaomi Mi TV (tested target: MiTv-AFMU0).
# Safe only: sets the home activity and disables the Xiaomi stock home
# (PatchWall) and, if present, the Google TV launcher. It does NOT disable
# any core Android package. Undo with: pm enable com.mitv.tvhome.atv
# and re-picking Home in Settings, or tools/restore.sh with your debloat list.
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-tools/venv/bin/python}"
printf '%s\n' \
  "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity" \
  "pm disable-user --user 0 com.mitv.tvhome.atv" \
  "pm disable-user --user 0 com.google.android.apps.tv.launcherx" \
  "am force-stop com.android.tv.settings" \
  "input keyevent KEYCODE_HOME" \
  | "$PY" -I tools/tv.py --batch
echo "Done. Press Home on the Xiaomi remote — tv-home should open."
