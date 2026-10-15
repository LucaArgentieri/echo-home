#!/bin/sh
# Regenerates app/src/main/assets/voice/ (word clips for instant answers) with the Piper voice.
# Needs the TTS model (scripts/fetch-models.sh), a JDK (eval "$(mise env)") and macOS arm64
# (the sherpa-onnx JVM jars below are the macOS build; use the -linux-x64 one on Linux).
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="${TMPDIR:-/tmp}/echohome-voice-clips"
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8
NATIVE="${SHERPA_NATIVE:-sherpa-onnx-native-lib-osx-aarch64-1.13.8.jar}"
mkdir -p "$TMP/classes"
for j in sherpa-onnx-jvm-1.13.8.jar "$NATIVE"; do
  [ -f "$TMP/$j" ] || curl -fsSL -o "$TMP/$j" "$BASE/$j"
done
CP="$TMP/sherpa-onnx-jvm-1.13.8.jar:$TMP/$NATIVE"
javac -cp "$CP" -d "$TMP/classes" "$ROOT/scripts/voice-clips/GenClips.java"
java -cp "$CP:$TMP/classes" GenClips "$ROOT/app/src/main/assets/tts" "$ROOT/scripts/voice-clips/words.txt" "$ROOT/app/src/main/assets/voice"
