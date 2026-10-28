# Building & running Fireplace

## Reference pipeline (no device, no model) - works today

The whole retrieval/grounding/citation pipeline runs on any machine with Python 3.11+ and no dependencies:

```bash
# end-to-end demo: builds a pack, answers questions, verifies citations
python scripts/demo.py

# full test suite
python -m unittest discover -s eval     -p 'test_*.py'
python -m unittest discover -s corpus   -p 'test_*.py'
python -m unittest discover -s pipeline -p 'test_*.py'

# offline guarantee (also run in CI)
bash scripts/verify_offline.sh
```

## Validate with a REAL model on the host (pre-device "simulator")

Build llama.cpp for the host and run the whole pipeline with a small real model:

```bash
# 1. build llama.cpp (Metal on macOS) - produces .../build/bin/llama-completion
git clone --depth 1 https://github.com/ggml-org/llama.cpp engine/src/main/cpp/llama.cpp
cmake -S engine/src/main/cpp/llama.cpp -B engine/src/main/cpp/llama.cpp/build \
    -DCMAKE_BUILD_TYPE=Release -DGGML_METAL=ON -DLLAMA_CURL=OFF
cmake --build engine/src/main/cpp/llama.cpp/build --target llama-completion -j4

# 2. get a small GGUF (validation only; the device uses the big MoE)
curl -L -o models/qwen2.5-3b-instruct-q4_k_m.gguf \
  https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf

# 3. run the full pipeline with the real model
FIREPLACE_MODEL=models/qwen2.5-3b-instruct-q4_k_m.gguf python scripts/run_host.py
```

This validates retrieval → real-LLM grounding → citation verification before any
device. On device the identical grounded prompt runs through the JNI engine with
the big MoE; the Answerer interface is unchanged.

## Building a knowledge pack from a real dump

```bash
# Wikipedia from a Wikimedia CirrusSearch dump (.json.gz):
python corpus/build_wikipedia.py path/to/enwiki-cirrussearch.json.gz \
    --version 2026-10-20 --out out/wikipedia.fpack --limit 200000
# verify it:
python -m corpus.pack_format verify out/wikipedia.fpack
```

Source URLs, licenses and pinned dump dates: [`corpus/sources.json`](../corpus/sources.json).

## The Android app (needs a device + toolchain) - Phase 0 onward

Prerequisites: a **12 GB Pixel-class / GrapheneOS** device, Android Studio with NDK, and a pinned `llama.cpp` checkout at `engine/src/main/cpp/llama.cpp` (revision in [`engine/llama-revision.txt`](../engine/llama-revision.txt)).

```bash
./gradlew :app:assembleRelease          # build the APK
bash scripts/verify_offline.sh app/build/outputs/apk/release/app-release.apk
adb install -r app-release.apk          # or install on the device directly
```

Then download the model + packs (links + SHA-256 in `corpus/sources.json`) to the device's Downloads and import them in-app - no `adb push` required (phone-only install).

> The Android/engine layers are currently scaffolding (module graph, hardened JNI bridge, offline manifest). The inference loop, retrieval wiring and Compose UI are implemented in Phases 1-2 on device - see [roadmap.md](roadmap.md). The Python reference pipeline defines the exact behaviour they implement.
