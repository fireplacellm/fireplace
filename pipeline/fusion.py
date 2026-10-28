"""Reciprocal-rank fusion of several ranked lists (ADR 0002)."""

from __future__ import annotations

from collections.abc import Sequence


def reciprocal_rank_fusion(
    ranked_lists: Sequence[Sequence[str]],
    *,
    k: int = 60,
    weights: Sequence[float] | None = None,
    lead_boost: float = 0.004,
) -> list[tuple[str, float]]:
    """Weighted RRF: score(id) = sum over lists of wᵢ/(k + rank), plus a small lead-
    passage boost. A higher weight on the dense list lets a semantic match win on
    questions where the answer article shares no keyword with the query; the lead boost
    favours an article's intro passage (#0/#1), where "who/what is X" is answered."""
    scores: dict[str, float] = {}
    for i, ranked in enumerate(ranked_lists):
        w = 1.0 if weights is None else weights[i]
        for rank, item in enumerate(ranked):
            scores[item] = scores.get(item, 0.0) + w / (k + rank)
    for item in list(scores):
        if item.endswith("#0") or item.endswith("#1"):
            scores[item] += lead_boost
    return sorted(scores.items(), key=lambda kv: kv[1], reverse=True)
