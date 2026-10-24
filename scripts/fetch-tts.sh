#!/bin/sh
# Downloads what the Piper speech engine needs and puts it where Gradle and the APK expect it (all too big for git):
#  - the sherpa-onnx Android library (AAR) into androidApp/libs,
#  - four Russian Piper voices (fp32: on the Redmi 12 the int8 ones are slower than real time, fp32 is about 0.6) into androidApp/src/main/assets/tts/voices/<name>/{model.onnx,tokens.txt},
#  - the espeak-ng phoneme data, shared by all voices, into androidApp/src/main/assets/tts/espeak-ng-data.
# Hashes are checked. Run it once after cloning.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

fetch() { # url sha256 file
  curl -fsSL -o "$3" "$1"
  echo "$2  $3" | sha256sum -c - >/dev/null || { echo "hash mismatch: $1" >&2; exit 1; }
}

AAR="$ROOT/androidApp/libs/sherpa-onnx-1.13.8.aar"
if [ ! -f "$AAR" ]; then
  mkdir -p "$(dirname "$AAR")"
  # Not 2.x: version 2.0.0 dropped the built-in espeak phonemizer, which the Piper voices need.
  fetch "$BASE/v1.13.8/sherpa-onnx-1.13.8.aar" 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96 "$AAR"
  echo "saved: $AAR"
fi

TTS="$ROOT/androidApp/src/main/assets/tts"
mkdir -p "$TTS/voices"
voice() { # name sha256
  [ -f "$TTS/voices/$1/model.onnx" ] && return 0
  fetch "$BASE/tts-models/vits-piper-ru_RU-$1-medium.tar.bz2" "$2" "$WORK/$1.tar.bz2"
  tar -xjf "$WORK/$1.tar.bz2" -C "$WORK"
  SRC="$WORK/vits-piper-ru_RU-$1-medium"
  mkdir -p "$TTS/voices/$1"
  cp "$SRC/ru_RU-$1-medium.onnx" "$TTS/voices/$1/model.onnx"
  cp "$SRC/tokens.txt" "$SRC/MODEL_CARD" "$TTS/voices/$1/"
  [ -d "$TTS/espeak-ng-data" ] || cp -r "$SRC/espeak-ng-data" "$TTS/espeak-ng-data"
  echo "saved voice: $1"
}
voice denis  efa4c18e0b5e32b81d1b6df36b9d312831e5d545200e27848ef926a4cd930300
voice dmitri c86d0803737de13d441923ff3b3f309482fab8d7af3ec85949942809eb9a3660
voice irina  1fc0f54e5e084fe287c07909f2f6e0ba6d857864cf800e3ab80286a4e8233008
voice ruslan 0690b1cad01f86e8db9ba988af24898bdc1af774e23cb2e46b9c730269b6fd83
