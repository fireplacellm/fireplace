"""Build a small *starter* .fpack by subsetting a full Wikipedia pack, so the app
can ship a working knowledge base inside the APK (Research works out of the box,
offline, before any download).

Strategy: breadth beats depth for an arbitrary research question - the topic has
to be *present* at all - so we keep as many distinct, substantial articles as fit,
capping passages per article (the lead sections answer most questions) and
dropping list/timeline/disambiguation pages. The int8 dense vectors are sliced
across from the source pack (aligned via dense_ids.txt) so semantic search still
works; staying under the app's dense cap means dense retrieval stays on.

Usage (in the venv):
  python corpus/make_starter_pack.py --src out/wikipedia-clean.fpack \
      --out out/starter.fpack --max-passages 55000 --cap-per-doc 7
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import sqlite3
import tempfile
import zipfile

from pack_format import create_pack_from_files
from starter_topics import CORE_TOPICS

# Chronology / list / index pages dominate a size ranking yet almost never answer
# a research question - drop them so encyclopedic concept articles come through.
_SKIP_PREFIXES = (
    "list of", "timeline of", "index of", "glossary of", "outline of",
    "deaths in", "births in", "chronology of",
)
_SKIP_SUBSTR = ("disambiguation", "discography", "filmography")
_MONTHS = ("january", "february", "march", "april", "may", "june", "july",
           "august", "september", "october", "november", "december")
# Pure dates/years/decades/centuries, and "<year> in <topic>" chronologies.
_NOISE_RE = re.compile(
    r"^(\d{3,4}(\s*(bc|ad|bce|ce))?|\d{4}s|\d{1,2}(st|nd|rd|th)\s+century"
    r"|\d{4}\s+in\s+.+|(mon|tues|wednes|thurs|fri|satur|sun)day)$",
    re.IGNORECASE,
)


def _keep_title(title: str) -> bool:
    t = (title or "").strip().lower()
    if not t:
        return False
    if any(t.startswith(p) for p in _SKIP_PREFIXES):
        return False
    if any(s in t for s in _SKIP_SUBSTR):
        return False
    if _NOISE_RE.match(t):
        return False
    # "April 15", "15 April" style date pages.
    parts = t.split()
    if parts and (parts[0] in _MONTHS or (len(parts) == 2 and parts[1] in _MONTHS)):
        return False
    return True


def build(src: str, out: str, max_passages: int, cap_per_doc: int, vec_dim: int) -> dict:
    work = tempfile.mkdtemp()
    with zipfile.ZipFile(src) as z:
        z.extractall(work)
    data = os.path.join(work, "data")
    src_db = os.path.join(data, "index.sqlite")
    src_dense = os.path.join(data, "dense.i8")
    src_ids = os.path.join(data, "dense_ids.txt")

    con = sqlite3.connect(src_db)
    cur = con.cursor()

    counts = dict(cur.execute("SELECT doc_id, COUNT(*) FROM passages GROUP BY doc_id"))
    titles = {d: t for d, t in cur.execute("SELECT doc_id, title FROM docs")}
    by_title_lower: dict[str, str] = {}
    for d, t in titles.items():
        by_title_lower.setdefault((t or "").strip().lower(), d)

    # 1) Force-include the curated canonical topics (guarantees the obvious research
    #    questions are answerable); give them a higher passage cap for richer answers.
    core_cap = max(cap_per_doc, 12)
    chosen: dict[str, int] = {}  # doc_id -> cap for that doc
    core_hits = 0
    for topic in CORE_TOPICS:
        did = by_title_lower.get(topic.strip().lower())
        if did and did not in chosen:
            chosen[did] = core_cap
            core_hits += 1
    print(f"core topics matched: {core_hits}/{len(CORE_TOPICS)}")

    # 2) Fill the remaining budget with a seeded-random sample of substantial concept
    #    articles (4–40 passages) for long-tail breadth - random beats size-ranking,
    #    which just surfaces chronology/list pages.
    pool = [d for d, c in counts.items()
            if d not in chosen and 4 <= c <= 40 and _keep_title(titles.get(d, ""))]
    random.Random(1337).shuffle(pool)

    total = sum(min(counts[d], chosen[d]) for d in chosen)
    for did in pool:
        if total >= max_passages:
            break
        chosen[did] = cap_per_doc
        total += min(counts[did], cap_per_doc)
    chosen_set = set(chosen)
    print(f"selected {len(chosen_set)} articles, ~{total} passages "
          f"({core_hits} core + {len(chosen_set) - core_hits} fill)")

    # Pick the kept passages (first `cap_per_doc` of each doc, by passage_id order).
    keep_passages: list[tuple] = []  # (passage_id, doc_id, title, body)
    per: dict[str, int] = {}
    for pid, did, title, body in cur.execute(
        "SELECT passage_id, doc_id, title, body FROM passages ORDER BY doc_id, passage_id"
    ):
        if did not in chosen_set:
            continue
        if per.get(did, 0) >= cap_per_doc:
            continue
        per[did] = per.get(did, 0) + 1
        keep_passages.append((pid, did, title, body))
    keep_pids = {p[0] for p in keep_passages}
    print(f"kept {len(keep_passages)} passages")

    # --- new sqlite (same schema as the builder) ---
    out_work = tempfile.mkdtemp()
    db_path = os.path.join(out_work, "index.sqlite")
    con2 = sqlite3.connect(db_path)
    con2.execute("PRAGMA journal_mode=DELETE;")
    con2.execute("PRAGMA synchronous=OFF;")
    con2.execute("CREATE TABLE docs(doc_id TEXT PRIMARY KEY, title TEXT, url TEXT, license TEXT)")
    con2.execute(
        "CREATE VIRTUAL TABLE passages USING fts5("
        "passage_id UNINDEXED, doc_id UNINDEXED, title, body, tokenize='porter unicode61')"
    )
    docrows = cur.execute(
        f"SELECT doc_id, title, url, license FROM docs WHERE doc_id IN ({','.join('?' * len(chosen_set))})",
        list(chosen_set),
    ).fetchall()
    con2.executemany("INSERT INTO docs VALUES(?,?,?,?)", docrows)
    con2.executemany(
        "INSERT INTO passages(passage_id, doc_id, title, body) VALUES(?,?,?,?)", keep_passages
    )
    con2.execute("INSERT INTO passages(passages) VALUES('optimize')")
    con2.commit()
    con2.close()
    con.close()

    # --- sources.jsonl ---
    src_path = os.path.join(out_work, "sources.jsonl")
    with open(src_path, "w", encoding="utf-8") as f:
        for doc_id, title, url, lic in docrows:
            f.write(json.dumps({"doc_id": doc_id, "title": title, "url": url, "license": lic}, sort_keys=True) + "\n")

    files = [("data/index.sqlite", db_path), ("data/sources.jsonl", src_path)]

    # --- slice the dense vectors for the kept passages, preserving alignment ---
    if os.path.exists(src_dense) and os.path.exists(src_ids):
        row_of = {}
        with open(src_ids) as f:
            for i, line in enumerate(f):
                row_of[line.strip()] = i
        new_i8 = os.path.join(out_work, "dense.i8")
        new_ids = os.path.join(out_work, "dense_ids.txt")
        written = 0
        with open(src_dense, "rb") as sf, open(new_i8, "wb") as of, open(new_ids, "w") as idf:
            for pid, _, _, _ in keep_passages:
                r = row_of.get(pid)
                if r is None:
                    continue
                sf.seek(r * vec_dim)
                of.write(sf.read(vec_dim))
                idf.write(pid + "\n")
                written += 1
        files += [("data/dense.i8", new_i8), ("data/dense_ids.txt", new_ids)]
        print(f"sliced {written} dense vectors ({vec_dim}d int8)")

    m = create_pack_from_files(
        out, "wikipedia", "starter-20231101-en", files,
        source="subset:wikipedia-clean",
    )
    return m


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Build a small starter .fpack from a full one")
    p.add_argument("--src", default="out/wikipedia-clean.fpack")
    p.add_argument("--out", default="out/starter.fpack")
    p.add_argument("--max-passages", type=int, default=55000)
    p.add_argument("--cap-per-doc", type=int, default=7)
    p.add_argument("--vec-dim", type=int, default=384)
    args = p.parse_args(argv)
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    m = build(args.src, args.out, args.max_passages, args.cap_per_doc, args.vec_dim)
    size = os.path.getsize(args.out)
    print(f"wrote {args.out}: {len(m['files'])} files, {size // (1024 * 1024)} MB, signed={'signature' in m}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
