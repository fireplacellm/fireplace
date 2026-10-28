#!/usr/bin/env bash
# Enforce Fireplace's offline guarantee: network is used ONLY to download the
# model + knowledge pack at first-run setup (data/Downloader.kt). Inference and
# ALL data import must make no network calls — so after setup the app works in
# airplane mode. This gate fails if any network API appears outside the one
# allowed downloader file, or if a broad network-state permission is declared.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail=0

# 1) Permissions: INTERNET is allowed (setup download). These broader ones are
#    not needed and would widen the surface, so they stay forbidden.
FORBIDDEN=(
  "android.permission.ACCESS_NETWORK_STATE"
  "android.permission.ACCESS_WIFI_STATE"
  "android.permission.CHANGE_NETWORK_STATE"
  "android.permission.CHANGE_WIFI_STATE"
)
while IFS= read -r -d '' m; do
  for perm in "${FORBIDDEN[@]}"; do
    grep -qF "$perm" "$m" && { echo "FAIL: $m declares $perm"; fail=1; }
  done
done < <(find "$REPO_ROOT" -name AndroidManifest.xml -not -path '*/build/*' -print0)

# 2) Network APIs may appear ONLY in the setup downloader. Scan our own source
#    (not the vendored llama.cpp). Anything that opens a connection elsewhere
#    would break "offline during use".
ALLOWED="app/src/main/java/com/fireplace/data/Downloader.kt"
NET_RE='HttpURLConnection|java\.net\.URL|java\.net\.Socket|okhttp|retrofit|DownloadManager|openConnection'
hits="$(grep -rnE "$NET_RE" \
  "$REPO_ROOT/app/src/main/java" "$REPO_ROOT/engine/src/main/java" "$REPO_ROOT/engine/src/main/cpp/fireplace_jni.cpp" \
  2>/dev/null | grep -v "/$ALLOWED:" || true)"
if [[ -n "$hits" ]]; then
  echo "FAIL: network API used outside $ALLOWED:"
  echo "$hits"
  fail=1
fi

if [[ $fail -eq 0 ]]; then
  echo "OK: network is confined to the setup downloader — inference and data import are fully offline."
fi
exit $fail
