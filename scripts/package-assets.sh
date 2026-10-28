#!/usr/bin/env bash
#
# Package the bundled build assets into fireplace-assets.zip, to upload to the GitHub
# Release that scripts/fetch-assets.sh downloads. Run this from a tree that already has
# the assets in place (the one you build and ship from).
#
# The zip is unpacked directly into app/src/main/assets/starter/, so it must contain:
#   wikipedia.fpack  first-aid.fpack  crypto.fpack  travel.fpack  arxiv.fpack
#   embedder/model.onnx  embedder/vocab.txt
#   reranker/model.onnx  reranker/vocab.txt
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${ROOT}/app/src/main/assets/starter"
OUT="${ROOT}/fireplace-assets.zip"

cd "$SRC"
rm -f "$OUT"
zip -r "$OUT" \
  ./*.fpack \
  embedder/model.onnx embedder/vocab.txt \
  reranker/model.onnx reranker/vocab.txt

echo "Wrote $OUT"
echo "Upload it to the GitHub Release tagged by FIREPLACE_ASSETS_TAG (default assets-v1)."
