"""Answerer interface + a dependency-free extractive answerer.

On device this is the MoE (ADR 0001) prompted with the retrieved passages,
emitting `[n]` citations, accelerated by speculative decoding (ADR 0005). The
prompt builder here is exactly what the device answerer uses; retrieved text is
inserted as DATA with special tokens neutralised (ADR 0002) so a passage can
never act as an instruction.

`ExtractiveAnswerer` lets the whole pipeline run and produce cited answers with
no model, so retrieval + citation verification are testable end to end. It
selects the passages most relevant to the query and emits them as cited
sentences - a genuine (if un-synthesised) grounded answer.
"""

from __future__ import annotations

import re
from typing import Protocol

from reader import Hit

_TOK = re.compile(r"[a-z0-9]+")
_STOP = frozenset(
    "the a an of to in on at for and or but is are was were be do i you can where what how why "
    "when which who my me find good a couple some".split()
)

SYSTEM_PROMPT = (
    # Kept short: these tokens are re-prefilled on every query, which dominates
    # on-device latency. Keeps quote-first grounding + honest abstention.
    "You are Fireplace, an offline research assistant. Answer only from the numbered sources, "
    "using their own wording where possible - never invent facts. Begin with a one-sentence answer, "
    "then explain it in two or three short paragraphs using the specific names, numbers and dates "
    "from the sources. Cite every fact in square brackets like [1]. If the sources don't cover "
    "something, say so plainly instead of guessing. Don't restate the question or write your own 'Sources' list."
)


def build_prompt(question: str, hits: list[Hit]) -> str:
    """The grounded prompt. Passages are fenced and special-token sequences are
    neutralised so passage text cannot become a control token."""
    lines = [SYSTEM_PROMPT, "", "Sources:"]
    for i, h in enumerate(hits, 1):
        lines.append(f"[{i}] ({h.title}) {neutralize(h.text)}")
    lines += ["", f"Question: {question}", "", "Answer (with [n] citations):"]
    return "\n".join(lines)


_CONTROLish = re.compile(r"<\|.*?\|>|</?(?:s|think|tool_call|im_start|im_end)>", re.IGNORECASE)


def neutralize(text: str) -> str:
    """Defang control-token-looking sequences in retrieved text (ADR 0002)."""
    return _CONTROLish.sub(lambda m: m.group(0).replace("<", "< ").replace(">", " >"), text)


class Answerer(Protocol):
    def answer(self, question: str, hits: list[Hit]) -> str: ...


class ExtractiveAnswerer:
    """Model-free grounded answerer: emit the most on-topic sentence from each of
    the top passages, each cited. Deterministic; proves the grounding path."""

    def __init__(self, max_sentences: int = 4):
        self.max_sentences = max_sentences

    def answer(self, question: str, hits: list[Hit]) -> str:
        if not hits:
            return "I don't have a source for that in the installed packs."
        q = set(_TOK.findall(question.lower())) - _STOP
        out = []
        for i, h in enumerate(hits, 1):
            if len(out) >= self.max_sentences:
                break
            sent = _best_sentence(h.text, q)
            # Only cite a source that actually overlaps the question - don't pad
            # the answer with irrelevant passages the retriever happened to return.
            if not (q & set(_TOK.findall(sent.lower()))):
                continue
            out.append(f"{sent.rstrip(' .!?')} [{i}].")
        if not out:  # nothing overlapped; fall back to the top hit
            top = _best_sentence(hits[0].text, q).rstrip(" .!?")
            out.append(f"{top} [1].")
        return " ".join(out)


def _best_sentence(text: str, q: set[str]) -> str:
    sents = [s.strip() for s in re.split(r"(?<=[.!?])\s+", text) if s.strip()]
    if not sents:
        return text.strip()
    # Most query-overlap, tie-broken toward the longer (more informative)
    # sentence - so we quote the body, not the bare title-prefix sentence.
    return max(sents, key=lambda s: (len(q & set(_TOK.findall(s.lower()))), len(s)))
