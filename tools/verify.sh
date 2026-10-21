#!/usr/bin/env bash
# Check the TV is in the state this repo expects: the launcher is Home, every package in the debloat list is
# still disabled (or uninstalled), nothing is crashing and there is free memory. Run it after a reboot.
# Usage: tools/verify.sh [debloat list]     (default: the Philips TA1 list)
set -uo pipefail
cd "$(dirname "$0")/.."
LIST="${1:-examples/philips-google-tv-ta1/debloat.txt}"
PY="${PY:-tools/venv/bin/python}"
tv() { "$PY" -I tools/tv.py "$@"; }
fail=0
ok()  { echo "PASS  $1"; }
bad() { echo "FAIL  $1"; fail=1; }

home=$(tv 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' | tail -1)
[ "$home" = "com.mihir.tvhome/.MainActivity" ] && ok "the launcher is the default Home" || bad "Home resolves to: $home"

disabled=$(tv 'pm list packages -d' | sed 's/package://')
installed=$(tv 'pm list packages' | sed 's/package://')
reenabled=0
while read -r pkg; do
  echo "$disabled" | grep -qx "$pkg" && continue
  echo "$installed" | grep -qx "$pkg" || continue   # uninstalled for this user
  bad "$pkg is enabled again"; reenabled=1
done < <(grep -vE '^\s*(#|$)' "$LIST" | awk '{print $1}')
[ "$reenabled" = 0 ] && ok "every package in $LIST is disabled or uninstalled"

crashes=$(tv 'logcat -d -b crash | grep -c "FATAL EXCEPTION"' | tr -d '[:space:]')
[ "${crashes:-0}" = 0 ] && ok "no crashes in the crash log" || bad "$crashes crashes logged (tools/tv.py 'logcat -d -b crash')"

# MemAvailable counts memory Android can reclaim, so unlike "Free RAM" it does not read low just because of cache.
avail=$(tv "grep MemAvailable /proc/meminfo" | grep -oE '[0-9]+' | head -1)
[ "${avail:-0}" -ge 600000 ] && ok "available RAM $((avail / 1024)) MB" || bad "available RAM only $((${avail:-0} / 1024)) MB: run tools/free-memory.sh"

echo "info  cached-process cap: $(tv 'settings get global activity_manager_constants')"
exit $fail
