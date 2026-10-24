#!/usr/bin/env bash
# Build the launcher APK with the bare SDK tools (no Gradle) and install it on the TV as the home screen.
#   launcher/build-and-install.sh                  build, install, smoke-test, make it the default home
#   launcher/build-and-install.sh --build          build only
#   launcher/build-and-install.sh --disable-stock  if Home still opens the stock screen, also disable Google
#                                                  TV's setup app and then, only if needed, the stock launcher
# Nothing is disabled until the new launcher is installed and has started without crashing. If Home still
# doesn't open it after a disable, everything this script disabled is re-enabled.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
# Google TV stock home; older Android TV sets use com.google.android.tvlauncher/.MainActivity.
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx/.home.HomeActivity}"
B=build

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex"
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  -A assets --min-sdk-version 30 --target-sdk-version 34 "$B/res.zip"
javac -nowarn -Xlint:-options --release 11 -classpath "$JAR" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
"$BT/d8" --min-api 30 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out "$B/tvhome.apk" "$B/aligned.apk"
echo "built $B/tvhome.apk ($(wc -c < "$B/tvhome.apk") bytes)"
[ "${1:-}" = "--build" ] && exit 0
DISABLE_STOCK=0
[ "${1:-}" = "--disable-stock" ] && DISABLE_STOCK=1

PY="$(cd ../tools && pwd)/venv/bin/python"
tv() { "$PY" -I ../tools/tv.py "$@"; }
PKG=com.mihir.tvhome
HOME_ACT=$PKG/.MainActivity
SETUP=com.google.android.tungsten.setupwraith

# 1. Install. Play Protect may ask on the TV first; if the install is declined, stop with nothing changed.
tv --push-b64 "$B/tvhome.apk" /data/local/tmp/tvhome.apk
if ! tv "pm install -r /data/local/tmp/tvhome.apk"; then
  tv 'rm -f /data/local/tmp/tvhome.apk' || true
  echo "Install failed or was blocked on the TV. Nothing was disabled; the stock home screen is untouched."
  exit 1
fi
tv 'rm -f /data/local/tmp/tvhome.apk'
tv "pm path $PKG"
# The Wi-Fi name in the top bar needs location on Android 11+; the launcher works without it.
tv "pm grant $PKG android.permission.ACCESS_FINE_LOCATION" || echo "(no location grant; the Wi-Fi pill will just say Wi-Fi)"

# 2. Smoke test: open it directly and make sure it stays up.
tv 'logcat -b crash -c' || true
tv "am start -W -n $HOME_ACT"
sleep 4
crashes=$(tv 'logcat -b crash -d' 2>/dev/null || true)
if ! tv "pidof $PKG" >/dev/null || grep -q "$PKG" <<<"$crashes"; then
  echo "$crashes" | tail -40
  echo "The launcher crashed on start. Nothing was disabled; press Home for the stock home screen."
  exit 1
fi
echo "launcher started and is still running"

# 3. Make it the default home, with the stock launcher left enabled as a fallback.
# Press Home from inside another app (the smoke test left the launcher in front, which would fake a pass),
# and require Android to name it as the default home too.
home_is_ours() {
  tv 'am force-stop com.android.tv.settings' >/dev/null || true
  tv 'am start -a android.settings.SETTINGS' >/dev/null || true
  sleep 2
  tv 'input keyevent KEYCODE_HOME' >/dev/null
  sleep 3
  local top home
  top=$(tv "dumpsys activity activities | grep -E 'ResumedActivity'" 2>/dev/null || true)
  home=$(tv 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' 2>/dev/null || true)
  grep -q "$PKG" <<<"$top" && grep -q "$HOME_ACT" <<<"$home"
}
tv "cmd package set-home-activity --user 0 $HOME_ACT"
if home_is_ours; then
  if grep -q "package:${STOCK_LAUNCHER%%/*}\$" <<<"$(tv 'pm list packages -d' || true)"; then
    echo "Installed. Home opens the new launcher (stock launcher disabled; undo with tools/restore.sh)."
  else
    echo "Installed. Home opens the new launcher; the stock launcher is still enabled as a fallback."
  fi
  exit 0
fi
if [ $DISABLE_STOCK = 0 ]; then
  echo "Installed, but Home still opens something else (on Google TV usually the setup app)."
  echo "Nothing was disabled. To take over Home, rerun with --disable-stock."
  exit 1
fi

# 4. Only with --disable-stock: disable the setup app, then the stock launcher only if still needed,
#    re-checking after each. Roll back if Home never lands on the new launcher.
# Android deletes an app's saved Wi-Fi networks when the app is disabled, and Google TV's setup app saves the
# network you join during first-time setup. Disabling it then drops the TV off Wi-Fi, and ADB with it.
setup_uid=$(tv "pm list packages -U $SETUP" | sed -n 's/.*uid:\([0-9]*\).*/\1/p')
owned=$(tv "dumpsys wifi | grep -cE 'cname=$SETUP|cuid=${setup_uid:-none}( |\$)'; true" | tail -1)
if [ "${owned:-0}" != 0 ]; then
  echo "Google TV's setup app owns a saved Wi-Fi network; disabling it would delete it and drop the TV offline."
  echo "On the TV: Settings → Network & Internet → your network → Forget, then join it again (that re-saves it"
  echo "under Settings). Then rerun with --disable-stock. Nothing was disabled."
  exit 1
fi

disabled=""
rollback() {
  trap - ERR INT TERM HUP
  echo "Home doesn't open the new launcher; re-enabling:$disabled"
  for p in $disabled; do tv "pm enable $p" || true; done
  tv "cmd package set-home-activity --user 0 $STOCK_LAUNCHER" || true
  tv 'input keyevent KEYCODE_HOME' || true
  exit 1
}
trap rollback ERR INT TERM HUP
for p in "$SETUP" "${STOCK_LAUNCHER%%/*}"; do
  tv "pm path $PKG" >/dev/null   # never disable anything unless the new launcher is still installed
  tv "pm disable-user --user 0 $p"
  disabled="$disabled $p"
  tv "cmd package set-home-activity --user 0 $HOME_ACT"
  if home_is_ours; then
    trap - ERR INT TERM HUP
    echo "Installed. Home opens the new launcher. Disabled:$disabled"
    echo "Undo with tools/restore.sh. Reboot the TV once to check it comes back here."
    exit 0
  fi
done
rollback
