#!/usr/bin/env bash
# Build the launcher APK with the bare SDK tools (no Gradle) and install it on the TV as the home screen.
#   launcher/build-and-install.sh            build + install
#   launcher/build-and-install.sh --build    build only
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
# Google TV stock launcher; older Android TV sets use com.google.android.tvlauncher.
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx}"
B=build

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex"
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  -A assets --min-sdk-version 30 --target-sdk-version 30 "$B/res.zip"
javac -nowarn -Xlint:-options --release 11 -classpath "$JAR" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
"$BT/d8" --min-api 30 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
if command -v zip >/dev/null; then (cd "$B/dex" && zip -q -u ../unsigned.apk classes.dex)
else python3 -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED).write(sys.argv[2], "classes.dex")' \
  "$B/unsigned.apk" "$B/dex/classes.dex"; fi  # no zip on minimal Linux installs
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out "$B/tvhome.apk" "$B/aligned.apk"
echo "built $B/tvhome.apk ($(wc -c < "$B/tvhome.apk") bytes)"
[ "${1:-}" = "--build" ] && exit 0

PY=../tools/venv/bin/python
SETUP_APP=com.google.android.tungsten.setupwraith
# What config.xml asks for, parsed so spacing doesn't matter: take_over_home, whether button_apps
# maps any keys, and whether it maps the Assistant button.
read -r TAKEOVER MAPPED ASSIST_MAPPED < <("$PY" -I -c '
import sys, xml.etree.ElementTree as ET
r = ET.parse(sys.argv[1]).getroot()
takeover = (r.findtext("bool[@name=\"take_over_home\"]") or "").strip() == "true"
items = [(i.text or "").strip() for i in r.findall("string-array[@name=\"button_apps\"]/item")]
keys = [i.split("=", 1)[0].strip() for i in items if "=" in i]
print(int(takeover), int(bool(keys)), int("KEYCODE_ASSIST" in keys))' res/values/config.xml)
# take_over_home: Android won't make this the real home (a Chromecast always picks Google TV's home),
# so ButtonService stands in for it. If config.xml maps remote buttons or takes over Home, turn on
# ButtonService, keeping any accessibility services already on.
BUTTONS=""
{ [ "$MAPPED" = 1 ] || [ "$TAKEOVER" = 1 ]; } && BUTTONS='s=$(settings get secure enabled_accessibility_services); case "$s" in *com.mihir.tvhome*) ;; null|"") settings put secure enabled_accessibility_services com.mihir.tvhome/com.mihir.tvhome.ButtonService ;; *) settings put secure enabled_accessibility_services "$s:com.mihir.tvhome/com.mihir.tvhome.ButtonService" ;; esac; settings put secure accessibility_enabled 1'
# The Assistant button only goes to the Assistant role holder, so take the role if it's mapped.
ASSIST=""
[ "$ASSIST_MAPPED" = 1 ] && ASSIST="cmd role add-role-holder --user 0 android.app.role.ASSISTANT com.mihir.tvhome"
# Google TV has two safe combinations, never the stock launcher off with the setup app on: then the
# setup app's RecoveryActivity becomes home and boot-loops the TV at its next restart. The order of
# the two commands keeps it out of that combination even halfway through.
#   take_over_home: both on (a Chromecast needs the setup app for Cast), even after an earlier install.
#   otherwise: both off, so this is the real home. Disabling the setup app can delete a Wi-Fi network
#   it saved during setup from a phone: re-add Wi-Fi in Settings first.
if [ "$TAKEOVER" = 1 ]; then
  PKG1="pm enable --user 0 $STOCK_LAUNCHER"; PKG2="pm enable --user 0 $SETUP_APP"
  SHOW="am start -n com.mihir.tvhome/.MainActivity"
else
  PKG1="pm disable-user --user 0 $SETUP_APP"; PKG2="pm disable-user --user 0 $STOCK_LAUNCHER"
  SHOW="input keyevent KEYCODE_HOME"
fi
"$PY" -I ../tools/tv.py --push-b64 "$B/tvhome.apk" /data/local/tmp/tvhome.apk
printf '%s\n' \
  "pm install -r /data/local/tmp/tvhome.apk" \
  "rm -f /data/local/tmp/tvhome.apk" \
  "pm grant com.mihir.tvhome android.permission.ACCESS_FINE_LOCATION" \
  "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity" \
  "cmd role add-role-holder --user 0 android.app.role.HOME com.mihir.tvhome" \
  "$PKG1" \
  "$PKG2" \
  "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity" \
  "$BUTTONS" \
  "$ASSIST" \
  "am force-stop com.android.tv.settings" \
  "$SHOW" \
  | "$PY" -I ../tools/tv.py --batch
echo "Installed. Press Home on the remote."
