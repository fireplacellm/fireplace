"""Reranker interface + a dependency-free lexical fallback.

On device this is a small cross-encoder (ADR 0002). The fallback scores by
query/passage token overlap (Jaccard), which is a reasonable reorder when no
model is loaded and keeps the pipeline runnable in CI.
"""

from __future__ import annotations

import re
from typing import Protocol

from reader import Hit

_TOK = re.compile(r"[a-z0-9]+")


def _toks(s: str) -> set[str]:
    return set(_TOK.findall(s.lower()))


class Reranker(Protocol):
    def rerank(self, query: str, hits: list[Hit], top_m: int) -> list[Hit]: ...


class LexicalReranker:
    def rerank(self, query: str, hits: list[Hit], top_m: int) -> list[Hit]:
        q = _toks(query)
        if not q:
            return hits[:top_m]

        def overlap(h: Hit) -> float:
            p = _toks(h.text)
            inter = len(q & p)
            union = len(q | p) or 1
            return inter / union

        return sorted(hits, key=overlap, reverse=True)[:top_m]
