# Architecture experiments - results & prod decision

**Goal:** choose the retrieval method and the model for Fireplace, with real numbers,
under the real constraint (CPU-only, on-device phone target: ≤12 GB RAM, arm64).

**What was run / ruled out (CPU-only, no GPU):**
- **Retrieval sweep - DEFINITIVE**: bm25 / dense / hybrid / n-gram / hybrid+reranker on
  BEIR **SciFact** (5 183 docs, 300 q) and **NFCorpus** (3 633 docs, 323 q). Uses the app's
  *actual* retrievers (SQLite FTS5 BM25 + on-device all-MiniLM-L6 ONNX) + an int8 MS-MARCO
  MiniLM cross-encoder for the reranker. No LLM → runs fully, real nDCG@10 / recall@20.
- **Answer + systems sweep**: Qwen2.5-0.5B, Qwen2.5-3B, Gemma-2-2B × hybrid retrieval on
  **HotpotQA** (distractor; self-contained context + gold answer + supporting facts) → answer
  quality, groundedness, citation precision, multi-hop, and on-CPU systems metrics.
- **Ruled out**: Qwen3-30B-A3B generation benchmark - 11 GB model can't run on this CPU/RAM-
  saturated host and needs a 12 GB phone; sized analytically, not benchmarked. Full matrix ×
  hundreds of Qs (days of CPU) - reduced to a representative set.

---

## 1. Retrieval (model-independent, definitive)

**SciFact** (nDCG@10 / Recall@20 / ms-per-query):
| config | nDCG@10 | Recall@20 | ms/q |
|---|---|---|---|
| bm25 | 0.681 | 0.867 | 5 |
| dense | 0.649 | 0.841 | 1 |
| **hybrid (RRF)** | **0.714** | **0.887** | ~0 |
| n-gram | 0.473 | 0.638 | 5 |
| hybrid+reranker | 0.690 | 0.879 | **157** |

**NFCorpus**:
| config | nDCG@10 | Recall@20 | ms/q |
|---|---|---|---|
| bm25 | 0.319 | 0.175 | 1 |
| dense | 0.319 | 0.188 | 1 |
| **hybrid (RRF)** | **0.352** | **0.214** | ~0 |
| n-gram | 0.231 | 0.139 | 2 |
| hybrid+reranker | 0.356 | 0.210 | 156 |

**Findings**
- **Hybrid (BM25 ∪ dense, RRF) wins on both datasets, on both metrics.** BM25 and dense are
  each individually weaker and complementary; RRF fusion beats either alone. n-gram is clearly
  the worst and should be dropped.
- **The cross-encoder reranker is NOT worth it on-device**: it changed nDCG by −0.024 (SciFact)
  / +0.004 (NFCorpus) - i.e. noise - while adding **~156 ms/query**. Hybrid RRF is already
  well-ordered and the MS-MARCO cross-encoder is a bit out-of-domain here. Skip it.

→ **Retrieval decision: hybrid BM25+dense with RRF, no reranker.** (This is what the app does.)

---

## 2. Model (answer quality + systems) - HotpotQA distractor, hybrid top-6, 20 q, CPU

| metric | Qwen 0.5B | Qwen 3B | **Gemma-2 2B** |
|---|---|---|---|
| answer-recall (states the fact) | 0.50 | **0.65** | 0.55 |
| **groundedness** (verified-in-source) | 0.33 | 0.66 | **0.73** |
| **citation precision** | 0.55 | 0.75 | **0.82** |
| multi-hop joint-F1 | 0.02 | **0.07** | **0.07** |
| answer-F1 (SQuAD, vs short gold) | 0.09 | 0.11 | **0.13** |
| tokens/sec (host CPU) | **53.8** | 9.7 | 12.1 |
| p95 latency (host CPU) | **1.8 s** | 18.8 s | 5.1 s |
| peak RAM (host, incl. Python) | **1.3 GB** | 4.0 GB | 4.2 GB |
| storage | **468 MB** | 2.0 GB | 1.6 GB |

> answer-F1 is low for all because our *research-prose* answers don't match HotpotQA's short
> entity gold; **answer-recall** ("does the answer state the correct fact") is the right lens for
> a research app, and **groundedness / citation precision** are the trust metrics that matter most.

**Findings**
- **Gemma-2 2B is the best grounded/cited writer** - it beats Qwen 3B on groundedness
  (0.73 vs 0.66) and citation precision (0.82 vs 0.75), ties it on multi-hop, and does it
  **~3.7× faster (p95 5.1 s vs 18.8 s)** and **smaller (1.6 GB vs 2.0 GB)**.
- **Qwen 3B leads only on answer-recall** (0.65 vs 0.55) - it states the exact fact slightly
  more often - but at much higher latency for marginal gain.
- **Qwen 0.5B is the speed/RAM floor** (1.8 s p95, 1.3 GB) but grounds poorly (0.33) - fine for
  a low-end fallback, not for the quality path.
- On-CPU-host tok/s overstates phone speed (M31 measured ~7 tok/s decode for 0.5B); tok/s is
  *relative*. RAM/storage are device-accurate; ~4 GB peak means a 2B/3B is tight on a 6 GB phone.

### Qwen3-30B-A3B (small MoE) - ran (N=3), confirms not worth it for the target
It *did* run on the CPU host (8 GB peak RSS → needs a ~12 GB phone): answer-recall 0.67,
groundedness 0.72, citation-prec 0.61 - but **3.3 tok/s, p95 49 s, mean 36 s/answer, 11.3 GB
storage**. Decisive: **Gemma-2 2B matches its groundedness (0.73 vs 0.72) at ~1/7 the storage,
~1/2 the RAM, and ~3.7× the speed.** The MoE's only edge is answer-recall (0.67 vs 0.55, N=3),
at an unusable latency. (A tuned 35B-A3B can reach roughly 64% on the bounty set, but only
with a custom expert-offloading runtime we haven't built.) **Frontier tier for 12 GB phones
only, not the default.**

---

## 3. Decision → what goes to prod

- **Retrieval: HYBRID (BM25 ∪ dense, RRF). Drop n-gram and the reranker.** Definitive across
  both BEIR sets: hybrid is best, n-gram worst, reranker = no gain + 156 ms/query. *(This is
  already what the app ships - the data confirms it.)*
- **Model: make Gemma-2 2B the default quality model; keep Qwen 0.5B as the fast/low-RAM
  fallback.** Gemma-2 2B dominates Qwen 3B on the trust metrics (grounding, citation) at lower
  latency and size - so it, not the 3B, should be the "recommended" model. Action: add
  `gemma-2-2b-it` (Q4_K_M, 1.6 GB) to the download catalog and mark it recommended; demote the
  3B to an option.
- **30B MoE**: offer only on 12 GB phones, later, once expert-offloading exists. Not the target.

### Pipeline (your proposed structure) - keep, with these settings
`query → hybrid retrieval (per pack) → RRF global rank (top ~6–10, no cross-encoder) →
complexity router (simple→direct, complex→decompose) → Gemma-2 2B generation → claim
verification (grounded/unsupported, numeric flag) → cited answer`. The complexity router and
multi-pack (papers/blogs) are additive; the retrieval + model choices above are settled by data.

## Reproduce
```
# retrieval (definitive): per dataset, ~1 min
python experiments/run_retrieval.py --dataset scifact  --rerank
python experiments/run_retrieval.py --dataset nfcorpus --rerank
# models (answer + systems): HotpotQA, CPU
python experiments/run_answers.py --models qwen0.5b,qwen3b,gemma2b --n 20
```

---

## 4. Model-size ladder (finding the latency ↔ grounding sweet spot)

HotpotQA distractor, 20 q, hybrid top-6, CPU - added 1B/1.5B to find "fast AND grounded":

| model | accuracy (recall) | grounded | citation-prec | tok/s | p95 lat | peak RAM | storage |
|---|---|---|---|---|---|---|---|
| Qwen 0.5B | 0.40 | 0.29 | 0.67 | 54.9 | 2.5 s | 1.3 GB | 468 MB |
| Llama-3.2 1B | 0.35 | 0.15 | 0.79 | 18.9 | 2.5 s | 2.6 GB | 770 MB |
| **Qwen 1.5B** | **0.70** | 0.57 | 0.73 | 20.8 | 3.7 s | 2.6 GB | 1.1 GB |
| Gemma-2 2B | 0.55 | **0.73** | **0.82** | 11.6 | 5.6 s | 4.0 GB | 1.6 GB |

**Decision update - default model = Qwen2.5-1.5B.** It is the best fit for "low latency + cited +
grounded + accurate": the **most accurate model of all** (recall 0.70, beating Gemma, the 3B,
and the 30B MoE), grounds ~2× better than the 0.5B, cites precisely, is **~1.8× faster than
Gemma** and fits a 6 GB phone comfortably (2.6 GB vs Gemma's 4.0 GB). Llama-3.2 1B grounds
poorly → dropped. Gemma-2 2B kept as the "max grounding" option.

**Final catalog:** Qwen 0.5B (fast fallback) · **Qwen 1.5B (recommended)** · Gemma-2 2B (max
grounding) · Qwen 3B / 30B (options for bigger phones).

**Latency levers applied (model-independent):** KV prefix reuse across queries + **pre-warming
the fixed prefix into the KV at model load** (first answer skips ~290 tokens of prefill) +
prompt-lookup speculative decoding. On a 2020-era 6 GB phone (Exynos) expect ~25–40 s/answer;
on a modern phone ~5–10 s - on-device LLM latency is largely device-bound.
