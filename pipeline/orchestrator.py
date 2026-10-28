"""End-to-end: route → retrieve → answer → verify citations (ARCHITECTURE.md)."""

from __future__ import annotations

import dataclasses

from answerer import Answerer, ExtractiveAnswerer, build_prompt
from citations import CitationReport, verify
from reader import Hit
from retriever import HybridRetriever


@dataclasses.dataclass
class Result:
    question: str
    answer: str
    hits: list[Hit]
    citations: CitationReport
    prompt: str

    def sources(self) -> list[dict]:
        return [{"n": i + 1, "title": h.title, "doc_id": h.doc_id} for i, h in enumerate(self.hits)]


class Orchestrator:
    def __init__(self, retriever: HybridRetriever, answerer: Answerer | None = None):
        self.retriever = retriever
        self.answerer = answerer or ExtractiveAnswerer()

    def ask(self, question: str) -> Result:
        hits = self.retriever.retrieve(question)
        prompt = build_prompt(question, hits)
        answer = self.answerer.answer(question, hits)
        report = verify(answer, hits)
        return Result(question, answer, hits, report, prompt)
