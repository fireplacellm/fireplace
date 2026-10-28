#!/usr/bin/env bash
# Install Fireplace on a USB-connected Android phone and push the model(s),
# knowledge pack, and embedder. Run from the repo root:  bash scripts/install_device.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.fireplace"
DATA="/sdcard/Android/data/$PKG/files"

# --- which models to push? default: fast 0.5B + rich 3B. Set MODELS to override.
#     add the 30B only on a 12 GB+ phone (it needs ~12 GB RAM and 11 GB storage).
MODELS="${MODELS:-models/qwen2.5-0.5b-instruct-q4_k_m.gguf models/qwen2.5-3b-instruct-q4_k_m.gguf}"
PACK="${PACK:-out/wikipedia-clean.fpack}"   # 343 MB, 146k articles

command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools."; exit 1; }
[ -f "$APK" ] || { echo "APK missing. Build it first: ./gradlew :app:assembleDebug"; exit 1; }

echo "== devices =="; adb devices
echo "== install APK =="; adb install -r "$APK"

# Launch once so the app creates its files/ directory, then stop it.
echo "== first launch (creates data dir) =="
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 4; adb shell am force-stop "$PKG"
adb shell mkdir -p "$DATA/packs" "$DATA/embedder"

echo "== push knowledge pack ($PACK) =="
adb push "$ROOT/$PACK" "$DATA/packs/wikipedia.fpack"

echo "== push embedder (dense retrieval for data pods) =="
adb push "$ROOT/models/embedder/model.onnx" "$DATA/embedder/model.onnx"
adb push "$ROOT/models/embedder/vocab.txt"  "$DATA/embedder/vocab.txt"

echo "== push model(s) — this is the big transfer =="
for m in $MODELS; do
  base="$(basename "$m")"
  echo "   -> $base"
  adb push "$ROOT/$m" "$DATA/$base"
done

echo "== launch =="
adb shell am start -n "$PKG/.MainActivity" >/dev/null
echo "Done. On the phone: finish the intro, then ask a question."
echo "Switch models anytime in  Packs ▸ Model  (bigger = richer, smaller = faster)."
