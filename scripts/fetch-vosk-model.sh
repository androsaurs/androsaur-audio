#!/bin/sh
# Downloads the small Russian Vosk model into the app assets (it is too big for git).
# The official host is very slow at times, so the Hugging Face mirror is used; the hash is checked.
set -eu
NAME=vosk-model-small-ru-0.22.zip
SHA256=961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4
URL=https://huggingface.co/rhasspy/vosk-models/resolve/main/ru/$NAME
DEST="$(dirname "$0")/../androidApp/src/main/assets/$NAME"
mkdir -p "$(dirname "$DEST")"
if [ -f "$DEST" ] && [ "$(sha256sum "$DEST" | cut -d' ' -f1)" = "$SHA256" ]; then echo "already present: $DEST"; exit 0; fi
curl -fL -o "$DEST.part" "$URL"
echo "$SHA256  $DEST.part" | sha256sum -c -
mv "$DEST.part" "$DEST"
echo "saved: $DEST"
