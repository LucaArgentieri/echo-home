#!/usr/bin/env bash
# Downloads the sherpa-onnx AAR and all models into app/ (not in git).
# Idempotent: skips files already present, verifies SHA-256 from scripts/models.sha256.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download
ASSETS="$ROOT/app/src/main/assets"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

have() { for f in "$@"; do [ -f "$f" ] || return 1; done; }

# fetch_tar <url> <dest-dir> <file>...  : extract the tarball, copy the named files
fetch_tar() {
  local url=$1 dest=$2; shift 2
  mkdir -p "$dest"
  local f missing=0
  for f in "$@"; do [ -f "$dest/$f" ] || missing=1; done
  [ "$missing" = 0 ] && return 0
  echo "download $(basename "$url")"
  local dir="$TMP/$(basename "$url" .tar.bz2)"
  mkdir -p "$dir"
  curl -fsSL "$url" | tar -xj -C "$dir"
  for f in "$@"; do
    cp "$(find "$dir" -name "$f" -type f | head -1)" "$dest/$f"
  done
}

# AAR
if ! have "$ROOT/app/libs/sherpa-onnx-1.13.8.aar"; then
  echo "download sherpa-onnx-1.13.8.aar"
  mkdir -p "$ROOT/app/libs"
  curl -fsSL -o "$ROOT/app/libs/sherpa-onnx-1.13.8.aar" "$BASE/v1.13.8/sherpa-onnx-1.13.8.aar"
fi

# Whisper base (int8)
fetch_tar "$BASE/asr-models/sherpa-onnx-whisper-base.tar.bz2" "$ASSETS/whisper-base" \
  base-encoder.int8.onnx base-decoder.int8.onnx base-tokens.txt

# Silero VAD
if ! have "$ASSETS/silero-vad/silero_vad_v5.onnx"; then
  echo "download silero_vad_v5.onnx"
  mkdir -p "$ASSETS/silero-vad"
  curl -fsSL -o "$ASSETS/silero-vad/silero_vad_v5.onnx" "$BASE/asr-models/silero_vad_v5.onnx"
fi

# KWS: the non-mobile model (the -mobile one fails with a Reshape error)
fetch_tar "$BASE/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2" "$ASSETS/kws" \
  encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx \
  decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx \
  joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx tokens.txt

# TTS: Piper paola + Italian-only espeak-ng-data
if ! have "$ASSETS/tts/it_IT-paola-medium.onnx" "$ASSETS/tts/tokens.txt" || [ ! -d "$ASSETS/tts/espeak-ng-data" ]; then
  echo "download vits-piper-it_IT-paola-medium-int8.tar.bz2"
  mkdir -p "$ASSETS/tts" "$TMP/tts"
  curl -fsSL "$BASE/tts-models/vits-piper-it_IT-paola-medium-int8.tar.bz2" | tar -xj -C "$TMP/tts"
  src="$(dirname "$(find "$TMP/tts" -name it_IT-paola-medium.onnx -type f | head -1)")"
  cp "$src/it_IT-paola-medium.onnx" "$src/tokens.txt" "$ASSETS/tts/"
  mkdir -p "$ASSETS/tts/espeak-ng-data"
  for e in intonations phondata phondata-manifest phonindex phontab it_dict lang voices; do
    cp -R "$src/espeak-ng-data/$e" "$ASSETS/tts/espeak-ng-data/"
  done
fi

cd "$ROOT"
shasum -a 256 -c scripts/models.sha256 --quiet && echo "ok: all files present and verified"
