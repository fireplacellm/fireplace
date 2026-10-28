"""Build a Fireplace knowledge pack from a set of source documents.

This is the real pack builder used by every corpus (`build_wikipedia.py` etc.
feed documents into it). It chunks documents into passages, builds a SQLite
FTS5 index and a source-document store, and emits a verified-before-use
`.fpack` (ADR 0004). It is pure stdlib so it runs anywhere and in CI.

Dense vectors are produced by a pluggable embedder (see retrieval/embedder.py);
when none is supplied the pack is BM25-only and the dense index is omitted.
"""

from __future__ import annotations

import dataclasses
import json
import os
import re
import sqlite3
import tempfile
from collections.abc import Iterable, Sequence

from pack_format import create_pack

PASSAGE_WORDS = 220  # ~250-token passages; matches the retrieval budget


@dataclasses.dataclass(frozen=True)
class Document:
    doc_id: str
    title: str
    text: str
    url: str = ""          # citation target the UI can open (offline reference id)
    license: str = ""


@dataclasses.dataclass(frozen=True)
class Passage:
    passage_id: str
    doc_id: str
    title: str
    text: str


_WS = re.compile(r"\s+")

# Minimum real-content words a paragraph must have to become a passage. Shorter
# ones are section headers / navbox lines, and BM25 length-normalization would
# actually rank those stubs ABOVE the article body, poisoning retrieval.
MIN_PASSAGE_WORDS = 12

# Wikipedia end-matter and navigation sections carry no answer content.
_BOILERPLATE_HEADINGS = frozenset(
    "references see also external links further reading notes bibliography "
    "citations sources footnotes gallery contents navigation".split()
)
_SENT_END = re.compile(r"[.!?]")
_ALPHA = re.compile(r"[a-zA-Z]")


def _norm(text: str) -> str:
    return _WS.sub(" ", text).strip()


def _is_low_quality(body: str) -> bool:
    """Drop section-header and navbox stubs so retrieval sees only prose that can
    actually answer a question. Mirrored in the Kotlin reader's parity tests."""
    b = body.strip()
    low = b.lower().rstrip(":. ")
    if low in _BOILERPLATE_HEADINGS:
        return True
    words = [w for w in b.split(" ") if _ALPHA.search(w)]
    if len(words) < MIN_PASSAGE_WORDS:
        return True
    # A paragraph of real prose has at least one sentence terminator; a line with
    # none and few words is a heading, caption or navbox list.
    if not _SENT_END.search(b) and len(words) < 25:
        return True
    return False


def chunk_document(doc: Document, passage_words: int = PASSAGE_WORDS) -> list[Passage]:
    """Split a document into title-prefixed passages on paragraph then word
    boundaries. Title prefixing measurably helps BM25 recall for entity queries.
    Low-information paragraphs (headings, navboxes, reference lists) are dropped."""
    passages: list[Passage] = []
    para_idx = 0
    for para in re.split(r"\n\s*\n", doc.text):
        para = _norm(para)
        if not para or _is_low_quality(para):
            continue
        words = para.split(" ")
        for i in range(0, len(words), passage_words):
            body = " ".join(words[i : i + passage_words])
            pid = f"{doc.doc_id}#{para_idx}"
            # Prefix the title so a passage is self-describing in retrieval.
            text = f"{doc.title}. {body}" if doc.title else body
            passages.append(Passage(pid, doc.doc_id, doc.title, text))
            para_idx += 1
    return passages


def build_sqlite(db_path: str, docs: Sequence[Document], passages: Sequence[Passage]) -> None:
    con = sqlite3.connect(db_path)
    try:
        con.execute("PRAGMA journal_mode=DELETE;")  # deterministic single-file DB
        con.execute(
            "CREATE TABLE docs(doc_id TEXT PRIMARY KEY, title TEXT, url TEXT, license TEXT)"
        )
        con.executemany(
            "INSERT INTO docs VALUES(?,?,?,?)",
            [(d.doc_id, d.title, d.url, d.license) for d in docs],
        )
        # External-content-free FTS5 so the pack is self-contained.
        con.execute(
            "CREATE VIRTUAL TABLE passages USING fts5("
            "passage_id UNINDEXED, doc_id UNINDEXED, title, body, tokenize='porter unicode61')"
        )
        con.executemany(
            "INSERT INTO passages(passage_id, doc_id, title, body) VALUES(?,?,?,?)",
            [(p.passage_id, p.doc_id, p.title, p.text) for p in passages],
        )
        con.commit()
    finally:
        con.close()


def build_pack(
    out_path: str,
    pack_id: str,
    version: str,
    docs: Iterable[Document],
    *,
    source: str = "",
    embedder=None,
    personal: bool = False,
    title: str = "",
    description: str = "",
) -> dict:
    """Build `<out_path>` from `docs`. Returns the embedded manifest."""
    docs = list(docs)
    passages: list[Passage] = []
    for d in docs:
        passages.extend(chunk_document(d))

    work = tempfile.mkdtemp()
    db_path = os.path.join(work, "index.sqlite")
    build_sqlite(db_path, docs, passages)

    files: list[tuple[str, bytes]] = [("data/index.sqlite", open(db_path, "rb").read())]

    # sources.jsonl: one row per doc, used to resolve/open citations offline.
    sources_jsonl = "\n".join(
        json.dumps(
            {"doc_id": d.doc_id, "title": d.title, "url": d.url, "license": d.license},
            sort_keys=True,
        )
        for d in docs
    ).encode()
    files.append(("data/sources.jsonl", sources_jsonl))

    if embedder is not None:
        # dense.i8: N×384 int8 passage vectors (L2-normalized float → round(v*127)),
        # row order == dense_ids.txt. The device embeds the query with the same
        # model and scores by int8 dot product. int8 keeps it ~384 B/passage.
        import numpy as np

        texts = [p.text for p in passages]
        vecs = _embed_all(embedder, texts)  # (N, dim) float32, normalized
        i8 = np.clip(np.round(vecs * 127.0), -127, 127).astype(np.int8)
        files.append(("data/dense.i8", i8.tobytes()))
        files.append(("data/dense_ids.txt", "\n".join(p.passage_id for p in passages).encode()))

    return create_pack(out_path, pack_id, version, files, source=source,
                       personal=personal, title=title, description=description)


def _embed_all(embedder, texts, batch: int = 64):
    import numpy as np

    if hasattr(embedder, "embed_batch"):
        out = []
        for i in range(0, len(texts), batch):
            out.append(embedder.embed_batch(texts[i : i + batch]))
        return np.vstack(out) if out else np.zeros((0, getattr(embedder, "dim", 384)), np.float32)
    return np.array([embedder.embed(t) for t in texts], dtype=np.float32)


if __name__ == "__main__":
    import sys

    print("build_pack is a library; see build_wikipedia.py / tests for usage.", file=sys.stderr)
