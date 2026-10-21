#!/usr/bin/env bash
# Put this Philips Google TV back exactly as it was: re-enables every package we disabled, the stock
# Google TV launcher and the setup app, and makes the stock launcher the default home again.
# The custom launcher stays installed but unused; remove it with: tools/venv/bin/python -I tools/tv.py 'pm uninstall com.mihir.tvhome'
# Full pre-change state (packages, settings, screenshot) is in backup/philips-ta1-20261021-1140/.
cd "$(dirname "$0")" && WINDOW_ANIM=1.0 TRANSITION_ANIM=1.0 ANIMATOR_ANIM=0.6 exec tools/restore.sh examples/philips-google-tv-ta1/debloat.txt "$@"
