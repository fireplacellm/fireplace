# ADR 0001 - Inference engine and primary model

Status: accepted · 2026-10-21

## Decision

Use **llama.cpp** (current upstream, ARM CPU first) as the inference engine, via our own JNI wrapper. Run a **Qwen3.6-35B-A3B-class mixture-of-experts model at ~2-bit**, with experts streamed from flash so resident RAM stays ~8 GB. Pair it with a **~1B Qwen3 draft model** (same tokenizer family) for speculative decoding (see ADR 0005).

## Why

- A small dense model caps raw answer quality; a large mixture-of-experts model streamed at low bit-width is the credible way to approach the >50% bar. Quality is the bar, and a 35B-A3B MoE at ~2-bit streamed from flash is how we get it within 12 GB.
- A MoE with <1B active per token is exactly the architecture Vitalik's post points at ("~100B params, most on disk, <1B activated").
- llama.cpp is the mature, MIT-licensed on-device engine; it already has MoE expert streaming, aarch64 quant kernels, and flash/mmap loading. We write our own thin JNI layer.

## Memory & storage budget (12 GB Pixel)

| Item | Resident RAM | Disk |
|---|---|---|
| MoE weights (~2-bit, streamed) | ~5-8 GB working set | ~12 GB |
| KV cache + draft model + app | ~1-2 GB | ~1 GB |
| Wikipedia index | (mmap) | ~20-28 GB |
| Places + packs | (mmap) | ~4-6 GB |
| **Total** | **≤ ~8-9 GB** | **< 50 GB** |

Wikipedia may be trimmed to the top-N% by pageviews to buy disk headroom and speed, measured against eval impact.

## Engine hardening (from the llama.cpp line-by-line read)

Our JNI layer must, because sideloaded model files are untrusted:
- Validate GGUF metadata we depend on **before** `llama_model_load` (reject n_head=0, n_expert_used>n_expert, zero SSM dims, etc.).
- Wrap every native entry point; a `GGML_ABORT`/C++ exception must not cross into the JVM and kill the app. Install our own abort handling.
- Never restore a KV/session file from an untrusted source without range-checking `pos` (upstream reads it unchecked - `llama-kv-cache.cpp` whole-cache path).
- Pin a known-good llama.cpp revision and track the upstream GGUF/loader security fixes.
- Emit UTF-8 correctly across JNI (avoid the modified-UTF-8 / emoji bug common in naive JNI string handling).

## Alternatives considered

- **4B dense model:** much easier and faster, but caps quality ~30-40%. Rejected for the primary path; may ship as an optional "fast/low-RAM" model.
- **LiteRT-LM / MediaPipe:** good NPU story but a narrower model set and heavier framework; llama.cpp gives us MoE streaming and GGUF portability now.
- **MLC / custom runtime:** more work than the 10-day window allows.

## Consequences

- Hard dependency on a 12 GB device for development and submission.
- Speed is the risk; ADR 0005 (speculative decoding) and KV reuse are the mitigations.
