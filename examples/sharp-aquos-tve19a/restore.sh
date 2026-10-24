#!/usr/bin/env bash
# Restore this Sharp AQUOS TV to how it was: re-enables everything in debloat.txt, the stock
# launcher and the setup app, and makes the stock launcher the default home again.
# Packages that were already disabled before the debloat (Freeview apps, TalkBack, Play Games,
# YouTube Music, screensaver) are left as they were; see backup/pre-change/inventory.txt.
# The launcher itself stays installed but unused; remove with: tools/venv/bin/python -I tools/tv.py 'pm uninstall com.mihir.tvhome'
set -euo pipefail
cd "$(dirname "$0")/../.."
STOCK_LAUNCHER=com.google.android.tvlauncher/.MainActivity tools/restore.sh examples/sharp-aquos-tve19a/debloat.txt
