#!/usr/bin/env bash
# Free RAM right now: force-stop every app that has a launcher tile (Netflix, YouTube, ...) and clear cached
# background processes. Keeps the launcher, Settings, Play Store and the TV's own org.droidtv.* apps.
# An on-device version isn't possible: Android only lets shell/system callers force-stop, and this firmware
# refuses killBackgroundProcesses from third-party launchers.
# Usage: tools/free-memory.sh [package ...]   (extra packages to keep running)
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-tools/venv/bin/python}"
tv() { "$PY" -I tools/tv.py "$@"; }
free() { tv 'dumpsys meminfo | grep "Free RAM"' | sed 's/^ *//' | cut -c1-24; }

KEEP=" com.mihir.tvhome com.android.tv.settings com.android.vending io.github.jqssun.airplay $* "
echo "before: $(free)"
tv "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER" \
  | awk -F/ 'NF > 1 {gsub(/^ +/, "", $1); print $1}' | sort -u \
  | while read -r pkg; do
      case "$KEEP" in *" $pkg "*) continue ;; esac
      case "$pkg" in org.droidtv.*|com.mediatek.*) continue ;; esac
      echo "am force-stop $pkg"
    done > "${TMPDIR:-/tmp}/free-memory.$$"
{ cat "${TMPDIR:-/tmp}/free-memory.$$"; echo "am kill-all"; } | tv --batch | grep '^\$' | sed 's/^\$ //'
rm -f "${TMPDIR:-/tmp}/free-memory.$$"
sleep 3
echo "after:  $(free)"
