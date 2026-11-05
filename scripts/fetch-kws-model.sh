#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
kws_cache="$repo_dir/artifacts/wake/models"
kws_archive="$kws_cache/russian-streaming-int8.tar.bz2"
kws_name=sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16
kws_sha=6ba68a01ff3c5445aaf2d61e9b97b026f1149dcc9049d11af3f44f55176341d8
mkdir -p "$kws_cache"
if [[ ! -f "$kws_archive" ]]; then
    curl -fL --retry 2 "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$kws_name.tar.bz2" -o "$kws_archive.part"
    mv "$kws_archive.part" "$kws_archive"
fi
printf '%s  %s\n' "$kws_sha" "$kws_archive" | sha256sum -c -
kws_unpack="$(mktemp -d /tmp/androsaur-kws.XXXXXX)"
trap 'rm -rf -- "$kws_unpack"' EXIT
tar -xjf "$kws_archive" -C "$kws_unpack"
mkdir -p "$repo_dir/androidApp/src/main/assets/kws/ru"
for kws_file in encoder.int8.onnx decoder.onnx joiner.int8.onnx tokens.txt; do
    cp "$kws_unpack/$kws_name/$kws_file" "$repo_dir/androidApp/src/main/assets/kws/ru/$kws_file"
done
printf 'Russian KWS assets ready\n'
