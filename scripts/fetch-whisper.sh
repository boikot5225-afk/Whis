#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT_DIR/vendor/whisper.cpp"
TAG="v1.9.4"

if [[ -d "$DEST/.git" ]]; then
  echo "whisper.cpp already present at $DEST"
  exit 0
fi

mkdir -p "$(dirname "$DEST")"
git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/whisper.cpp.git "$DEST"
echo "Fetched whisper.cpp $TAG"
