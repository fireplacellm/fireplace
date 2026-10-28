# Fireplace architecture

## Goal

A fully-offline Android research assistant that is >50% as good as "internet search + a frontier model", within 12 GB RAM and 50 GB storage, at a speed that feels usable on a phone. Quality and honest measurement are the priorities; speed is the axis the existing field is weakest on and the one we most aim to improve.

## System overview

```
          ┌──────────────────────────── Android app (Kotlin/Compose) ───────────────────────────┐
          │                                                                                       │
  user ──▶ │  Chat UI ──▶ Orchestrator ──▶ Retriever ──▶ Reranker ──▶ ContextBudget ──▶ Answerer  │
          │                   │                │             │                             │       │
          │                   │                ▼             ▼                             ▼       │
          │                   │          SQLite FTS5    dense index (ONNX          llama.cpp (JNI) │
          │                   │          (BM25)         embedder, int8)            MoE + draft LM  │
          │                   │                                                        │           │
          │                   └──────────────────────── CitationVerifier ◀────────────┘           │
          │                                                                                        │
          │  Packs (signed .fpack): wikipedia · places · first-aid · travel · crypto · [pods]      │
          └────────────────────────────────────────────────────────────────────────────────────┘
        Network is confined to the first-run setup downloader - enforced in CI (verify_offline.sh)
```

## Request lifecycle

1. **Route.** A cheap heuristic (`Router.looksFactual`, no model call) auto-grounds factual/lookup questions even in casual Chat mode, so a small model never answers a lookup ("who was president in 2010") from memory with confident fake facts. Research mode always grounds.
2. **Retrieve.** Hybrid search over the relevant packs: BM25 (SQLite FTS5) ∪ dense (on-device ONNX embedder → int8 vectors) merged by reciprocal-rank fusion.
3. **Rerank.** A small cross-encoder reorders the top ~24 candidates; we keep the top few within a token budget.
4. **Answer.** The MoE writes an answer grounded in the passages, with numbered `[n]` citations. A **1B draft model + speculative decoding** accelerates generation.
5. **Verify.** Each cited claim is checked against its passage before display; unsupported claims are flagged, not hidden.

## Components

| Layer | Choice | ADR |
|---|---|---|
| Inference engine | llama.cpp (ARM CPU; Vulkan optional), our own JNI | [0001](docs/adr/0001-engine-and-model.md) |
| Primary model | Qwen3.6-35B-A3B class MoE, ~2-bit, experts streamed from flash | [0001](docs/adr/0001-engine-and-model.md) |
| Draft model | ~1B Qwen3 (same tokenizer family) for speculative decoding | [0005](docs/adr/0005-speculative-decoding.md) |
| Retrieval | BM25 (FTS5) + dense (ONNX) + reranker, RRF | [0002](docs/adr/0002-retrieval-and-corpus.md) |
| Corpus | Wikipedia + places + packs, rebuilt from public sources | [0002](docs/adr/0002-retrieval-and-corpus.md) |
| Offline guarantee | INTERNET limited to the first-run downloader; no network in inference/retrieval/import, enforced by a CI gate (verify_offline.sh) | [0003](docs/adr/0003-offline-enforcement.md) |
| Knowledge format | Signed `.fpack`, verified before install AND before load | [0004](docs/adr/0004-pack-format.md) |

## Design principles

The constraints below shape every part of the app:

- **Never wipe the KV cache between turns, and reuse the system-prompt prefix.** Re-reading the whole prompt on every question is the single biggest avoidable speed sink; we keep the cache warm and chunk prefill so it stays cancellable.
- **Verify before use, not after.** Every file's hash is checked against the signed manifest *before* a pack is installed AND *before* it is loaded, so a tampered file never reaches the GGUF/ONNX/SQLite parser. ([0004](docs/adr/0004-pack-format.md))
- **Confine the network to the setup downloader; enforce in CI.** The INTERNET permission exists solely so the first-run downloader can fetch the model and optional pack upgrade. Inference, retrieval and all data import make no network calls, and `scripts/verify_offline.sh` (run in CI) fails the build if any network API appears outside `data/Downloader.kt`. Put the phone in airplane mode after setup and everything still works. ([0003](docs/adr/0003-offline-enforcement.md))
- **Phone-only install, no adb.** Packs and pods import from the device's own storage via the file picker - never a developer-only flow.
- **Don't overfit to the test set, and don't self-grade silently.** We keep a sealed held-out set and publish the protocol. ([eval](docs/eval-protocol.md))
- **Guard the model-file parser.** Sideloaded GGUFs can crash a native engine (unvalidated session state, recursion in template parsing, divide-by-zero on crafted hparams). We validate before load and wrap every native call so a bad file fails gracefully instead of taking down the app.
- **Don't inject retrieved text raw into the prompt.** Retrieved passages are tokenized as data with special-token parsing off, so a passage can't become a control token.
- **Don't log prompts/answers.** Fireplace logs no user content.

## Non-goals for the bounty submission

- Personal **data pods** (Strava, health, device data) - the `.fpack` format is designed to support them, but they are a post-Oct 31 product direction, not part of the submission (off-spec for a research/lookup tool).
- Low-RAM (<12 GB) phones. We target the full 12 GB envelope.
- iOS, desktop.
