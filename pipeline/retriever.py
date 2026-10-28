"""Hybrid retriever: BM25 ∪ dense → RRF → rerank → token budget (ADR 0002)."""

from __future__ import annotations

import dataclasses

from embedder import Embedder, cosine
from fusion import reciprocal_rank_fusion
from reader import Hit, PackReader
from reranker import LexicalReranker, Reranker


@dataclasses.dataclass
class RetrievalConfig:
    bm25_k: int = 20
    dense_k: int = 20
    rerank_m: int = 6
    rerank_pool: int = 18  # fused candidates the reranker reorders (precision pass)
    budget_chars: int = 2400  # ~ token budget for the answerer's context


class HybridRetriever:
    def __init__(
        self,
        readers: list[PackReader],
        *,
        embedder: Embedder | None = None,
        reranker: Reranker | None = None,
        config: RetrievalConfig | None = None,
    ):
        self.readers = readers
        self.embedder = embedder
        self.reranker = reranker or LexicalReranker()
        self.cfg = config or RetrievalConfig()

    def retrieve(self, query: str) -> list[Hit]:
        by_id: dict[str, Hit] = {}
        bm25_ranked: list[str] = []
        dense_ranked: list[str] = []
        title_ranked: list[str] = []
        force_include: list[str] = []  # all passages of a matched small pod

        # Title-planning pre-pass (no model): seed the lead passage of any article the
        # query names outright, so an exact-entity question can't miss its own page.
        for h in self._title_plan(query):
            by_id.setdefault(h.passage_id, h)
            title_ranked.append(h.passage_id)

        # Embed the query once (int8) for dense search over stored passage vectors.
        q_i8 = None
        if self.embedder is not None:
            import numpy as np

            qv = np.asarray(self.embedder.embed(query), dtype=np.float32)
            q_i8 = np.clip(np.round(qv * 127.0), -127, 127).astype(np.int8)

        for r in self.readers:
            matched = False
            for h in r.search_bm25(query, self.cfg.bm25_k):
                by_id.setdefault(h.passage_id, h)
                bm25_ranked.append(h.passage_id)
                matched = True
            if q_i8 is not None and r.has_dense:
                for h in r.search_dense(q_i8, self.cfg.dense_k):
                    by_id.setdefault(h.passage_id, h)
                    dense_ranked.append(h.passage_id)
                    matched = True
            # A personal pod that matched: pull in ALL its records (so "summarize"
            # over your own data sees every record, not just a keyword match).
            if matched and r.personal and r.is_small:
                for h in r.all_hits():
                    by_id.setdefault(h.passage_id, h)
                    if h.passage_id not in force_include:
                        force_include.append(h.passage_id)

        # A named-article lead outranks all; dense outranks lexical (dense measured much
        # stronger on entity/temporal questions like "who was president in 2010").
        ranked_lists: list[list[str]] = []
        weights: list[float] = []
        if title_ranked:
            ranked_lists.append(title_ranked)
            weights.append(1.3)
        ranked_lists.append(bm25_ranked)
        weights.append(0.6)
        if dense_ranked:
            ranked_lists.append(dense_ranked)
            weights.append(1.0)

        fused = reciprocal_rank_fusion(ranked_lists, weights=weights)
        fused_order = [pid for pid, _ in fused]
        fset = set(fused_order)
        finc = set(force_include)
        # Pod records first - matched ones in relevance order, then the rest of the
        # pod - then the web/knowledge hits. So a pod query isn't crowded out.
        pod_matched = [pid for pid in fused_order if pid in finc]
        pod_rest = [pid for pid in force_include if pid not in fset]
        web = [pid for pid in fused_order if pid not in finc]
        # Precision pass: the (cross-encoder, or lexical-fallback) reranker reorders the
        # web hits so the most on-topic passage leads. No longer dead code.
        web_hits = self.reranker.rerank(
            query, [by_id[p] for p in web if p in by_id], self.cfg.rerank_pool
        )
        hits = [by_id[p] for p in (pod_matched + pod_rest) if p in by_id] + web_hits
        return self._apply_budget(hits[: max(self.cfg.rerank_m, len(force_include))])

    def _title_plan(self, query: str) -> list[Hit]:
        words = ["".join(c for c in w if c.isalnum()) for w in query.lower().split()]
        words = [w for w in words if len(w) >= 2]
        if len(words) < 2:
            return []
        out: dict[str, Hit] = {}
        probes = 0
        for n in (4, 3, 2):
            i = 0
            while i + n <= len(words) and probes < 12:
                probes += 1
                phrase = " ".join(words[i : i + n])
                for r in self.readers:
                    h = r.lead_by_title(phrase)
                    if h:
                        out.setdefault(h.passage_id, h)
                i += 1
        return list(out.values())

    def _apply_budget(self, hits: list[Hit]) -> list[Hit]:
        out, used = [], 0
        for h in hits:
            if used + len(h.text) > self.cfg.budget_chars and out:
                break
            out.append(h)
            used += len(h.text)
        return out
