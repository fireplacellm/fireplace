# ADR 0005 - Speculative decoding for speed

Status: proposed (primary innovation; has a fallback) · 2026-10-21

## Decision

Accelerate generation with **speculative decoding**: a small ~1B draft model proposes several tokens, and the 35B-A3B MoE verifies them in a single batched forward pass, accepting the longest correct prefix. Draft and target share a tokenizer family (Qwen3) so proposals are directly verifiable.

## Why

- Speed is the weakest axis for on-device research and a stated bounty requirement ("speeds that feel usable"). Multi-minute answers are common for large on-device models. A faster *and* high-quality answer is the clearest way to stand out.
- Speculative decoding preserves the target model's output distribution - you get the big model's quality at closer to the small model's speed when drafts are accepted. On a streamed MoE, the verify pass touches experts anyway; batching verification amortizes that.
- It is exactly the "n-gram / extreme-MoE" direction Vitalik's post gestures at. A cheap n-gram/draft proposer over the retrieved context (which is highly predictable text) should have a high acceptance rate.
- Working on-device speculative decoding is rare to nonexistent in practice, so landing it is a genuine differentiator.

## Risks & fallback

Speculative decoding on a flash-streamed MoE is the hardest piece and the one most likely to slip in 10 days. Gating:

- **Milestone check (Phase 1):** measure draft acceptance rate and end-to-end tokens/sec on the real device. If net speedup < ~1.3× or it destabilizes streaming, we fall back.
- **Fallback:** a tuned router (instant / fast / deep) + strict KV-prefix reuse + never wiping KV between turns. This alone already delivers strong latency - avoiding the common failure of reprocessing the full context every turn - and is low-risk.

## Measurement

Reported in the eval harness alongside quality: time-to-first-token, tokens/sec, draft acceptance rate, and total answer latency at the p50/p90 of the question set, all on the real 12 GB device. No synthetic or desktop numbers in headline claims.
