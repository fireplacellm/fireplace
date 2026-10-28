# 🔥 Fireplace

**A research assistant that runs entirely on your phone. No internet, no servers, no account - and every answer cites exactly where it came from.**

Fireplace answers real research questions - explanation, comparison, synthesis, not just fact lookup - fully offline on Android, grounding every answer in an on-device knowledge base (Wikipedia, first aid, crypto, travel, and arXiv). It is built for the moment you are actually offline - travelling, in the field, in a blackout - where cloud AI is useless but a good grounded answer still matters.

Built for **[poidh bounty #31](https://poidh.xyz/mainnet/bounty/31)**, inspired by [Vitalik Buterin's post](https://x.com/VitalikButerin). The bar: an offline phone app that is **">50% as good as internet + frontier AI"** within a 12 GB-RAM, 50 GB-storage budget. Independent community bounty.

**▶️ [60-second demo](docs/demo/DEMO.md)** - ask a question → instant cited answer → sources with the exact text → model catalogue → Deep Research, all offline.

---

## Install (2 minutes)

1. Download the signed release APK from the **[latest release](../../releases/latest)** (`app-release.apk`, arm64, Android 12+). No Play Store, no Play Services.
2. Copy it to an Android phone and open it to install (allow "install from this source" if prompted).
3. Open the app. On first run it **auto-downloads the best model for your phone** (about 0.5 GB on a budget phone, about 2 GB on a flagship) over Wi-Fi, the only time it ever touches the network.
4. Ask a question, then put the phone in **airplane mode** and everything still works.

Five knowledge packs (about 155 MB) ship **inside the APK**, so research works the instant the model finishes downloading, with no pack hosting required.

---

## What makes it different

Fireplace is not a chatbot with a local model bolted on. It is a grounded, cited, verified research pipeline:

- **Grounded + cited + verified.** Every answer is built from retrieved passages, cites them inline `[n]`, and a verification pass marks each claim's support. The sources - with the **exact text** each claim came from - are shown under every answer.
- **Hybrid retrieval.** BM25 (SQLite FTS5) ∪ int8 dense vectors (all-MiniLM-L6-v2 via ONNX) fused with reciprocal-rank fusion, with per-document diversity - tuned with a real experiment harness (nDCG@10 / recall@20 on BEIR, answer-F1 / groundedness on HotpotQA).
- **Post-hoc citation + grounding.** Small models often write correct source-based prose without citing; Fireplace re-attaches `[n]` to each sentence from its best-matching source and flags any claim the sources don't support - so an answer reads as rigorous *and* stays honest.
- **Instant, streamed answers.** A grounded, cited extractive answer appears in ~2 s while the model composes the fuller answer behind it, then streams in - perceived latency collapses even on a weak phone.
- **Deep Research mode.** A toggle that plans the question into sections, researches each from your packs into one numbered source pool, drafts each section **over the real sources** (surfacing explicit gaps), and assembles a structured, cited answer.
- **Domain agents.** General Research, First Aid, Crypto, Travel, Research Papers - each scoped to its pack so a focused query isn't drowned out by the big Wikipedia pack.
- **On-device model catalogue.** Pick, download (with resume + ETA), switch, and delete models; each tagged with how well it fits *your* device's RAM. The app auto-sizes the right model per device and can never dead-end with "no model".
- **Signed knowledge packs.** Every pack carries an Ed25519 publisher signature (+ per-file SHA-256) verified on load, so a pack from an untrusted channel can't be swapped for another.

---

## Meets the bounty's hard constraints

| Constraint | Requirement | Fireplace |
|---|---|---|
| **RAM** | ≤ 12 GB | Model sized to the device (0.5 B ≈ 0.5 GB on 5 GB phones, up to a 30 B-A3B MoE that streams experts in ~8 GB on a 12 GB phone) |
| **Storage** | ≤ 50 GB | APK about 242 MB (5 packs bundled) plus a 0.5 to 2 GB model; the optional full-corpus upgrade is about 0.3 GB |
| **Network during use** | **Zero** | INTERNET is used **only** by the first-run setup downloader; inference/retrieval/import make no network calls - enforced by [`scripts/verify_offline.sh`](scripts/verify_offline.sh) in CI, provable in airplane mode |
| **Play Services** | Not required | None - no GMS/Firebase in the APK (androidx only) |
| **Platform** | Android, arm64 | `arm64-v8a`, `minSdk 31`, `targetSdk 35` |

---

## How it works

For each question Fireplace runs: **query → agent-scoped hybrid retrieval → instant extractive preview → grounded prompt → streamed model synthesis (llama.cpp via JNI) → post-hoc citation attribution → claim verification → cited answer + sources**. Deep Research swaps the single synthesis for an outline → per-section → assembly pipeline over a shared source pool.

- **Inference**: [llama.cpp](https://github.com/ggml-org/llama.cpp) through a thin JNI bridge (GGUF, Qwen/Gemma chat templates), with KV prefix caching, prompt-lookup speculative decoding, and device-aware context sizing. The model is pinned in RAM when it fits, so decode stays compute-bound.
- **Retrieval**: bundled `.fpack` packs = a signed zip of an FTS5 index + int8 dense vectors + sources.
- **Privacy**: no account, no telemetry, no analytics. Nothing leaves the device. Chats are stored in a local SQLite DB; optional biometric app-lock.

See **[ARCHITECTURE.md](ARCHITECTURE.md)** and the **[ADRs](docs/adr/)** for the design and the decisions behind it.

---

## Build from source

Requires Android SDK (API 35), NDK 27, and **JDK 21**.

```bash
# 1. Fetch the bundled assets (starter Wikipedia pack + embedder + reranker). These are
#    large binaries, attached to the GitHub Release instead of committed to git.
FIREPLACE_REPO=fireplacellm/fireplace bash scripts/fetch-assets.sh

# 2. Build (signed if keystore.properties is present, otherwise debug-signed).
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:assembleRelease

# Prove the offline guarantee (also runs in CI).
bash scripts/verify_offline.sh
```

The native engine (llama.cpp) is built `-O3`/Release by CMake as part of the Gradle build. Knowledge packs are built and signed under [`corpus/`](corpus/) from the public sources in [`corpus/sources.json`](corpus/sources.json); `scripts/package-assets.sh` rebuilds the release asset bundle. Installing the prebuilt APK from the release is the fastest path; building from source is for verification.

---

## Repository layout

| Path | What |
|---|---|
| `app/` | Android app (Jetpack Compose UI, ViewModel, RAG pipeline, model catalogue) |
| `engine/` | JNI bridge + vendored llama.cpp |
| `corpus/` | Pack builders, Ed25519 signing (`sign_pack.py`), full-pack upload |
| `pipeline/` | Python mirror of the on-device RAG pipeline (reference + tests) |
| `experiments/` | Retrieval + answer-quality experiment harness (BEIR / HotpotQA) |
| `docs/` | ARCHITECTURE, ADRs, attribution, build notes |

---

## Models and data

All weights and corpora are open and downloaded from public sources; full licenses are in [docs/attribution.md](docs/attribution.md) and pack provenance in [docs/pack-sources.md](docs/pack-sources.md).

**Language models** (chosen in the in-app catalogue, downloaded from Hugging Face on first run):

| Model | Quant | Size | Use |
|---|---|---|---|
| Qwen2.5-0.5B-Instruct | Q4_K_M | ~0.5 GB | default on budget phones |
| Qwen2.5-1.5B-Instruct | Q4_K_M | ~1.0 GB | balanced |
| Gemma-2-2B-it | Q4_K_M | ~1.6 GB | alternative family |
| Qwen2.5-3B-Instruct | Q4_K_M | ~2.0 GB | default on flagships |
| Qwen3-30B-A3B | Q2_K_XL | ~11 GB | MoE, experts streamed from flash |

**Retrieval models** (bundled in the APK): `all-MiniLM-L6-v2` (384-dim sentence embedder, ONNX, int8) and a MiniLM cross-encoder reranker (ONNX, int8).

**Knowledge packs** (`.fpack` = a signed zip of an FTS5 index + int8 dense vectors + sources): Wikipedia (Simple English), first aid, crypto, travel, and arXiv abstracts, built from the public sources in [corpus/sources.json](corpus/sources.json).

---

## License & attribution

**MIT**, see [LICENSE](LICENSE). Fireplace builds on open components (llama.cpp, public GGUF models, and CC-licensed corpora), each credited in [docs/attribution.md](docs/attribution.md). All application code is original to this project.
