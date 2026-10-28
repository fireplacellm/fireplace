"""Open a verified .fpack and expose BM25 retrieval over its passages.

A pack is verified (hashes checked, ADR 0004) before any file is handed to
SQLite. The SQLite DB is opened read-only.
"""

from __future__ import annotations

import dataclasses
import json
import os
import sqlite3
import sys
import tempfile
import zipfile

# Allow `import pack_format` whether run from repo root or the corpus dir.
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "corpus"))
from pack_format import verify_pack  # noqa: E402


SMALL_PACK = 64  # packs with <= this many passages (pods) are retrieved in full


@dataclasses.dataclass(frozen=True)
class Hit:
    passage_id: str
    doc_id: str
    title: str
    text: str
    score: float  # higher = better (we negate bm25, which is lower=better)


class PackReader:
    def __init__(self, fpack_path: str, *, verify_signature=None):
        self._manifest = verify_pack(fpack_path, verify_signature=verify_signature)
        self._dir = tempfile.mkdtemp()
        self._dense = None   # (N, dim) int8 or None
        self._dense_ids: list[str] = []
        with zipfile.ZipFile(fpack_path) as zf:
            names = set(zf.namelist())
            zf.extract("data/index.sqlite", self._dir)
            self._sources = {}
            if "data/sources.jsonl" in names:
                for line in zf.read("data/sources.jsonl").decode().splitlines():
                    if line.strip():
                        d = json.loads(line)
                        self._sources[d["doc_id"]] = d
            if "data/dense.i8" in names and "data/dense_ids.txt" in names:
                import numpy as np

                self._dense_ids = zf.read("data/dense_ids.txt").decode().splitlines()
                raw = np.frombuffer(zf.read("data/dense.i8"), dtype=np.int8)
                if self._dense_ids:
                    self._dense = raw.reshape(len(self._dense_ids), -1)
        db = os.path.join(self._dir, "data/index.sqlite")
        # Read-only; immutable because we just verified every byte.
        self._con = sqlite3.connect(f"file:{db}?mode=ro&immutable=1", uri=True)

    @property
    def has_dense(self) -> bool:
        return self._dense is not None

    @property
    def personal(self) -> bool:
        return bool(self._manifest.get("personal", False))

    @property
    def passage_count(self) -> int:
        if not hasattr(self, "_pcount"):
            self._pcount = self._con.execute("SELECT count(*) FROM passages").fetchone()[0]
        return self._pcount

    @property
    def is_small(self) -> bool:
        return 1 <= self.passage_count <= SMALL_PACK

    def all_hits(self, limit: int = SMALL_PACK) -> list[Hit]:
        rows = self._con.execute(
            "SELECT passage_id, doc_id, title, body FROM passages LIMIT ?", (limit,)
        ).fetchall()
        return [Hit(p, d, t, b, 0.0) for (p, d, t, b) in rows]

    def search_dense(self, query_i8, k: int = 20) -> list[Hit]:
        """Top-k passages by int8 dot product with the query vector."""
        if self._dense is None:
            return []
        import numpy as np

        scores = self._dense.astype(np.int32) @ query_i8.astype(np.int32)
        k = min(k, len(scores))
        top = np.argpartition(-scores, k - 1)[:k]
        top = top[np.argsort(-scores[top])]
        pids = [self._dense_ids[i] for i in top]
        rows = self._fetch_passages(pids)
        return [Hit(p, rows[p][0], rows[p][1], rows[p][2], float(scores[i]))
                for p, i in zip(pids, top) if p in rows]

    def _fetch_passages(self, pids: list[str]) -> dict[str, tuple]:
        if not pids:
            return {}
        qs = ",".join("?" * len(pids))
        out = {}
        for pid, did, title, body in self._con.execute(
            f"SELECT passage_id, doc_id, title, body FROM passages WHERE passage_id IN ({qs})", pids
        ):
            out[pid] = (did, title, body)
        return out

    @property
    def pack_id(self) -> str:
        return self._manifest["id"]

    def source(self, doc_id: str) -> dict:
        return self._sources.get(doc_id, {"doc_id": doc_id, "title": "", "url": ""})

    def search_bm25(self, query: str, k: int = 20) -> list[Hit]:
        # Rare-term CONJUNCTION first, relaxed by dropping the commonest terms, then an
        # OR floor. The old pure-OR query matched any passage with any one common word
        # (116k passages for "president OR united OR states OR 2010"), letting topical-
        # title noise bury the article that actually answers the question.
        terms = _content_terms(query)
        if not terms:
            return []
        ordered = sorted(terms, key=self._term_freq)  # rarest (most discriminative) first
        out: dict[str, Hit] = {}
        keep = len(ordered)
        while keep >= 1 and len(out) < k:
            for h in self._match_query(" AND ".join(f'"{t}"' for t in ordered[:keep]), k):
                out.setdefault(h.passage_id, h)
            keep -= 1
        if len(out) < k:
            for h in self._match_query(" OR ".join(f'"{t}"' for t in ordered), k):
                out.setdefault(h.passage_id, h)
        return list(out.values())[:k]

    def _match_query(self, match: str, k: int) -> list[Hit]:
        if not match.strip():
            return []
        rows = self._con.execute(
            # title weighted 3x body (down from 10x): a title keyword should help, not
            # dominate, so a passage whose title merely repeats a query term can't
            # outrank the article that actually answers the question.
            "SELECT passage_id, doc_id, title, body, bm25(passages, 3.0, 1.0) AS s "
            "FROM passages WHERE passages MATCH ? ORDER BY s LIMIT ?",
            (match, k),
        ).fetchall()
        return [Hit(pid, did, title, body, -s) for (pid, did, title, body, s) in rows]

    def _term_freq(self, term: str) -> int:
        """Document frequency of a term, for ordering the conjunction rarest-first."""
        row = self._con.execute(
            "SELECT count(*) FROM passages WHERE passages MATCH ?", (f'"{term}"',)
        ).fetchone()
        return row[0] if row else 0

    def lead_by_title(self, phrase: str):
        """Lead passage (#0) of the article whose title exactly equals `phrase`
        (case-insensitive), via the FTS title index. The no-model 'title planning'
        pre-pass; None if no such article."""
        p = phrase.strip()
        if len(p) < 3:
            return None
        try:
            rows = self._con.execute(
                "SELECT passage_id, doc_id, title, body FROM passages WHERE passages MATCH ? "
                "ORDER BY length(passage_id), passage_id LIMIT 4",
                (f'title : "{p}"',),
            ).fetchall()
        except Exception:
            return None
        fallback = None
        for pid, did, title, body in rows:
            if title.lower() != p:
                continue
            h = Hit(pid, did, title, body, 0.0)
            if pid.endswith("#0"):
                return h
            if fallback is None:
                fallback = h
        return fallback

    def close(self):
        self._con.close()


_ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
# Dropping stopwords from the OR query is essential: otherwise "how/does/work"
# etc. match unrelated passages and dominate ranking.
_STOP = frozenset(
    "the a an of to in on at for and or but is are was were be been being this that these those "
    "it its as by with from into about over under can could would should may might will shall do "
    "does did has have had you your i we they he she them his her their our not no yes if then than "
    "which who whom whose what when where why how my me find good some couple during".split()
)


def _content_terms(query: str) -> list[str]:
    """Free text → distinct content terms (alphanumeric, non-stopword, >=2 chars)."""
    terms, seen = [], set()
    for raw in query.split():
        t = "".join(c for c in raw if c in _ALNUM).lower()
        if len(t) >= 2 and t not in _STOP and t not in seen:
            seen.add(t)
            terms.append(t)
    return terms


def _fts_or_query(query: str) -> str:
    """Safe FTS5 OR query of quoted terms. Quoting defuses FTS5 operators."""
    return " OR ".join(f'"{t}"' for t in _content_terms(query))
