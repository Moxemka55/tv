#!/usr/bin/env bash
# Undo everything: re-enable every package in the debloat list, bring back the stock launcher (and
# Google TV's setup app) and make the stock launcher the default home again.
# Usage: tools/restore.sh <debloat list>
#   STOCK_LAUNCHER=com.google.android.tvlauncher/.MainActivity tools/restore.sh list.txt   (older Android TV)
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: tools/restore.sh <debloat list>}"
PY="${PY:-tools/venv/bin/python}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx/.home.HomeActivity}"
{
  grep -vE '^\s*(#|$)' "$LIST" | awk '{print "pm enable " $1}'
  echo "pm enable com.google.android.tungsten.setupwraith"
  echo "pm enable ${STOCK_LAUNCHER%%/*}"
  # Disabling a package makes Android clear the settings that pointed at it, and enabling it again doesn't
  # bring them back: without these, voice search and the stock launcher's notifications stay off.
  if grep -q '^com.google.android.katniss' "$LIST"; then
    echo "settings put secure assistant com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService"
    echo "settings put secure voice_interaction_service com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService"
    echo "settings put secure voice_recognition_service com.google.android.katniss/com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService"
  fi
  [ "${STOCK_LAUNCHER%%/*}" = com.google.android.apps.tv.launcherx ] && echo "settings put secure enabled_notification_listeners com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.coreservices.notificationlistener.TvNotificationListenerService"
  echo "cmd package set-home-activity --user 0 $STOCK_LAUNCHER"
  echo "input keyevent KEYCODE_HOME"
} | "$PY" -I tools/tv.py --batch
echo "Restored. Press Home on the remote."
