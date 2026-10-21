#!/usr/bin/env bash
# Snapshot the TV before changing anything: every package (all / disabled / user-installed), all settings,
# system properties, the current Home app and a screenshot, saved under backup/<model>-<timestamp>/ (git-ignored).
# Usage: tools/backup.sh
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-tools/venv/bin/python}"
tv() { "$PY" -I tools/tv.py "$@"; }

model=$(tv 'getprop ro.product.model' | tr -c 'A-Za-z0-9\n' '-' | sed 's/-*$//')
B="backup/${model:-tv}-$(date +%Y%m%d-%H%M)"
mkdir -p "$B"
printf '%s\n' \
  'pm list packages -f' 'pm list packages -d' 'pm list packages -3' \
  'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' \
  'settings list global' 'settings list secure' 'settings list system' 'getprop' \
  'dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity"' > "$B/cmds.txt"
tv --batch < "$B/cmds.txt" > "$B/state.txt"
tv --shot "$B/00-before.png"
echo "$B" > .last_backup
echo "Backed up to $B ($(grep -c '' "$B/state.txt") lines of state). Screenshots can show account photos: keep backup/ private."
