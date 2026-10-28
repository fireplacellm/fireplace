#!/usr/bin/env bash
#
# Fetch Fireplace's bundled build assets into the app so a source build produces a
# working, fully offline APK. These assets (the starter Wikipedia pack with its dense
# vectors, the MiniLM embedder, and the cross-encoder reranker) are large binaries, so
# they are attached to the GitHub Release rather than committed to git.
#
# Most people do NOT need this: just install the prebuilt APK from the Release. Run this
# only to build the app from source.
#
# Usage:
#   scripts/fetch-assets.sh
#   FIREPLACE_REPO=you/fireplace FIREPLACE_ASSETS_TAG=assets-v1 scripts/fetch-assets.sh
#
set -euo pipefail

REPO="${FIREPLACE_REPO:-fireplacellm/fireplace}"   # override with FIREPLACE_REPO=you/repo
TAG="${FIREPLACE_ASSETS_TAG:-assets-v1}"        # the Release tag holding the asset bundle
BUNDLE="fireplace-assets.zip"
URL="https://github.com/${REPO}/releases/download/${TAG}/${BUNDLE}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${ROOT}/app/src/main/assets/starter"

if [ "${REPO}" = "OWNER/fireplace" ]; then
  echo "Set FIREPLACE_REPO to your GitHub owner/repo first, e.g.:"
  echo "  FIREPLACE_REPO=you/fireplace scripts/fetch-assets.sh"
  exit 1
fi

echo "Downloading ${URL}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fL "$URL" -o "$tmp/${BUNDLE}"

mkdir -p "$DEST"
unzip -o "$tmp/${BUNDLE}" -d "$DEST"

echo "Assets installed under: $DEST"
echo "Now build:  JAVA_HOME=\$(/usr/libexec/java_home -v 21) ./gradlew :app:assembleRelease"
