# Roadmap (to Oct 31 2026)

Phase gates are measured on the real 12 GB Pixel. Each phase ends with a go/no-go.

- **Phase 0 - device + skeleton (Day 1).** 12 GB Pixel in hand; llama.cpp building for arm64; chosen MoE runs at all; INTERNET stripped + CI gate green. **Gate:** model loads and emits tokens on device. *(Blocked on the device.)*
- **Phase 1 - inference spine (Days 2-4).** Experts streaming from flash; KV + system-prompt prefix reuse (never wiped between turns); plain chat answers. Measure tok/s + TTFT on device. Speculative-decoding spike + go/no-go (ADR 0005). **Gate:** usable single-turn latency.
- **Phase 2 - retrieval + corpus (Days 4-7).** Wikipedia + places + packs built from public sources; hybrid search + reranker; plan→retrieve→answer→cite; citation verifier; retrieved text tokenized as data. **Gate:** cited answers beat the no-retrieval baseline on dev.
- **Phase 3 - eval + tune (Days 7-9).** dev set to ~60 Qs; sealed held-out; blind baseline + judge; fix worst categories without touching held-out. **Gate:** honest dev mean_score and a held-out read.
- **Phase 4 - ship (Days 9-10).** Signed APK; reproducible build + phone-only pack install; README/build steps a stranger follows in minutes; X/Farcaster demo (include queries a 1B model fails); poidh claim.
- **Phase 5+ - post-bounty.** Data pods (Strava/health/device as signed personal `.fpack`s); more packs; speed work; low-RAM model tier.

## Status

| Piece | State |
|---|---|
| Repo, ADRs, README, build docs | done |
| Offline gate + CI | done, verified (passes clean, fails on injected INTERNET) |
| `.fpack` format + verify-before-use | implemented + tested (7 tests) |
| Eval harness + sealed held-out | implemented + tested (5 tests) |
| Corpus: generic pack builder | implemented + tested (3 tests) |
| Corpus: Wikipedia (CirrusSearch) builder | implemented + tested (3 tests) |
| **Retrieval/grounding/citation pipeline** | **implemented + tested end-to-end (7 tests), runnable via `scripts/demo.py`** |
| &nbsp;&nbsp;- hybrid retriever (BM25+dense, RRF) | done (reference) |
| &nbsp;&nbsp;- reranker | lexical fallback done; cross-encoder = device |
| &nbsp;&nbsp;- grounded prompt + control-token neutralise | done |
| &nbsp;&nbsp;- citation verifier | done (lexical; model NLI = device) |
| **Host validation with a REAL model** | **done** - llama.cpp built (Metal), Qwen2.5-0.5B/3B answering through the real pipeline; correct grounded, cited, verified answers at ~1-3 s (`scripts/run_host.py`) |
| **Android engine cross-compile** | **done** - llama.cpp + hardened JNI build to arm64 `.so` via NDK r27c |
| **JNI decode loop** | **done** - real greedy generate (tokenize → prefill → sample → detokenize), `load_mode=MMAP`, cancel-aware |
| **APK builds** | **done** - `:app:assembleDebug` → 17 MB APK, native libs inside, **no INTERNET permission** (verified on the built APK) |
| **Emulator run** | **done** - arm64 AVD (android-35); APK installs, loads a real GGUF via JNI, retrieves → grounds → generates a cited answer on-device (screenshot: `docs/emulator-run.png`). Validates FUNCTION with a small model, not big-MoE quality/speed. |
| **Production Compose chat UI** | **done** - streaming answer, source list, citation %, loading/error/empty states; emulator-tested (`docs/emulator-production.png`) |
| **FTS5 Kotlin retrieval (ported pipeline)** | **done** - `rag/Pack.kt` (verify-before-use + bundled-SQLite FTS5 BM25), `rag/Rag.kt` (rerank, grounding, control-token neutralize, citation verify w/ grouping). Emulator-verified: correct top source + 100% citation check |
| **Streaming generation (JNI callback)** | **done** - tokens stream to the UI; Stop works; emulator-verified |
| **Big MoE downloaded + validated (host)** | **done** - Qwen3-30B-A3B UD-Q2_K_XL (11 GB, Apache-2.0) runs through the pipeline on Mac: correct, fully-cited answers (100% citation pass), 3–20 s. `/no_think` disables Qwen3 reasoning for phone latency (ADR 0001). Staged in `models/` to push to the Pixel. |
| **Real Wikipedia corpus (host)** | **done** - built from HF `wikimedia/wikipedia` (Simple English full: 146k articles, 1.1M passages, 223 MB `.fpack`, verified). `corpus/build_hf_wikipedia.py` streams + builds FTS5. Retrieval 2–22 ms over 1.1M passages. |
| **Data pods (ADR 0006) - UNIQUE FEATURE** | **built + tested** - import health/fitness (Health Connect, on-device) or any CSV/JSON export → private `.fpack` built on device → same retrieve/ground/cite pipeline. Emulator-verified: fitness pod loads + is queryable ("How far did I run this week" → runs retrieved, cited, 83% verified). Host w/ big MoE: *correctly sums 23.3 km with per-session citations*. `pods/PodBuilder.kt`, `FileImporter.kt`, `HealthImporter.kt` + Import UI. Offline gate still passes (health perms ≠ network). |
| **Dense retrieval (ADR 0002)** | **built + tested on-device** - all-MiniLM-L6-v2 ONNX + WordPiece (Kotlin port 1:1 w/ Python), int8 passage vectors in packs, BM25∪dense RRF fusion. Emulator-verified w/ Kotlin/Python PARITY: paraphrase "a green plant that turns sunlight into food" (no keyword) → top-3 **Photosynthesis**. Removed the lexical rerank (it undid dense). Dense signal proven (sim 0.84 vs 0.02). |
| &nbsp;&nbsp;Cross-encoder reranker | not built - RRF fusion is the ranking for now; cross-encoder is the future upgrade. |
| &nbsp;&nbsp;Full-corpus dense index | **offline build** - real embed rate ~107 passages/sec → 146k-article corpus (1.1M passages) ≈ 3 h (overnight/CI). Proven on a 134-article/2,290-vector pack. |
| Production-grade app polish | **not this round** - source viewer (tap citation), small-pod retrieve-all, settings screen remain. |
| Speculative decoding | greedy baseline works; draft+verify next (measurable only on device) |
| Big-MoE quality/speed ON DEVICE, full-English corpus | **pending device** - emulator can't run the 11 GB MoE at usable speed; full-English (vs Simple) corpus is a device-time build within the 50 GB budget. |

Emulator caveat: the emulated CPU makes even the 3B impractically slow; it validates FUNCTION (UI, FTS5 retrieval, streaming, citations) with a small model, not big-MoE quality/speed.

## Critical path / risks

1. **12 GB Pixel in hand** - blocks Phase 0 and submission. #1 risk.
2. **Speed** - speculative decoding is the bet; KV-reuse fallback de-risks it.
3. **10-day window** - Vitalik-confirm is a long shot; the realistic target is the creator's-pick for the most honest, reproducible, genuinely-offline entry.
