"""Build the Wikipedia pack from a Wikimedia CirrusSearch dump (ADR 0002).

CirrusSearch dumps are gzipped JSONL in index/content line pairs:
    {"index":{"_id":"12"}}
    {"title":"Foo","text":"...","namespace":0,...}
We keep namespace-0 articles, drop redirects/disambiguation, and feed them to
build_pack. Pure stdlib so it runs anywhere; on a real dump you pass --limit /
--min-chars and optionally an embedder.

Source + license + dump date are recorded in corpus/sources.json.
"""

from __future__ import annotations

import argparse
import gzip
import json
import sys
from collections.abc import Iterator

from build_pack import Document, build_pack


def iter_cirrus(path: str, *, limit: int | None = None, min_chars: int = 200) -> Iterator[Document]:
    opener = gzip.open if path.endswith(".gz") else open
    n = 0
    with opener(path, "rt", encoding="utf-8") as f:
        pending_id: str | None = None
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            if "index" in obj:  # index line → next line is the doc
                pending_id = str(obj["index"].get("_id", ""))
                continue
            # content line
            if obj.get("namespace", 0) != 0:
                pending_id = None
                continue
            title = obj.get("title", "")
            text = obj.get("text", "") or ""
            if obj.get("redirect") or not title or len(text) < min_chars:
                pending_id = None
                continue
            if "may refer to" in text[:120].lower():  # disambiguation
                pending_id = None
                continue
            doc_id = pending_id or f"wiki-{n}"
            yield Document(
                doc_id=doc_id,
                title=title,
                text=text,
                url=f"wikipedia:{title.replace(' ', '_')}",
                license="CC BY-SA 4.0",
            )
            pending_id = None
            n += 1
            if limit and n >= limit:
                return


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Build the Wikipedia .fpack")
    p.add_argument("dump", help="CirrusSearch dump (.json.gz) or sample .jsonl")
    p.add_argument("-o", "--out", default="out/wikipedia.fpack")
    p.add_argument("--version", required=True, help="dump date, e.g. 2026-10-20")
    p.add_argument("--limit", type=int, default=None)
    p.add_argument("--min-chars", type=int, default=200)
    args = p.parse_args(argv)

    import os

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    docs = list(iter_cirrus(args.dump, limit=args.limit, min_chars=args.min_chars))
    if not docs:
        print("no articles parsed", file=sys.stderr)
        return 1
    m = build_pack(args.out, "wikipedia", args.version, docs, source=f"cirrus:{args.dump}")
    print(f"built {args.out}: {len(docs)} docs, {len(m['files'])} files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
