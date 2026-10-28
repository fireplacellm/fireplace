# ADR 0002 - Retrieval and corpus

Status: accepted · 2026-10-21

## Decision

Hybrid retrieval: **BM25 (SQLite FTS5) ∪ dense (on-device ONNX embedder → int8 vectors), merged by reciprocal-rank fusion, then a small cross-encoder reranker.** Corpus = **Wikipedia + a places pack + targeted reference packs** (first-aid, travel, crypto), each **rebuilt by us from the original public sources**.

## Why

- Retrieval quality is the second-biggest quality lever after the model. A hybrid + reranker design is the strongest approach; weaker retrieval (e.g. a single OR-query BM25 with no ranking, or an FTS index with a stemming bug) demonstrably drops the relevant passage. We adopt a hybrid + rerank + RRF approach and implement it ourselves.
- The eval question distribution is travel-, places-, first-aid- and crypto-heavy, so those packs are high-value. Places questions especially reward a structured places DB answered in code.

## Corpus sources (rebuilt, not lifted)

| Pack | Source | License |
|---|---|---|
| wikipedia | Wikimedia CirrusSearch / HTML dumps | CC BY-SA |
| places | Overture Maps + OpenStreetMap | ODbL |
| travel | Wikivoyage + Wikidata country facts | CC BY-SA / CC0 |
| first-aid | Wikibooks First Aid, MedlinePlus | CC BY-SA / public |
| crypto | EIPs, ERCs, consensus specs, NIST PQC | public / CC0 |

We build these from the primary dumps with our own pipeline (`corpus/`); we reuse **no** prebuilt third-party dataset. Exact dump dates and SHA-256s are pinned in `corpus/sources.json`.

## Retrieval safety

- Retrieved passage text is passed to the model **as data**, tokenized with special-token parsing off, so a passage containing `<|...|>`/`</think>`/`[INST]` cannot become a control token.
- Passages carry a stable source id so citations resolve to a real document the UI can open.

## Pipeline

```
question ─▶ (optional) query expansion ─▶ BM25 top-k ┐
                                          dense top-k � ─▶ RRF ─▶ rerank top-m ─▶ budget(~N tokens) ─▶ answerer
```

## Alternatives considered

- **BM25 only:** simplest, but misses paraphrased queries. Rejected.
- **Dense only:** needs a bigger embedder and more RAM; BM25 is cheap and strong for named entities. Hybrid wins.
- **Kiwix/ZIM corpus:** convenient but a coarser retrieval unit; passage-level FTS retrieves better.

## Open questions

- Which on-device embedder (size vs quality vs RAM). Candidates: a ~20-30M ONNX sentence embedder. Decide in Phase 2 against eval recall.
- Reranker: distil our own small cross-encoder vs use a public small one. Must be our own artifact.
