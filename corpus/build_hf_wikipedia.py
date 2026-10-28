"""Build a real Wikipedia .fpack from the Hugging Face `wikimedia/wikipedia`
dataset (cleaned article text, CC BY-SA). Streams the dataset (no full download),
filters stubs/disambiguation, chunks to passages, builds a SQLite FTS5 index
incrementally, and packages a signed .fpack - scaled so a 150k-article pack does
not blow up memory.

Built from primary public sources by our own pipeline (Wikimedia dataset →
passages → FTS5); the HF dataset is the primary source and nothing is reused.

Run inside the venv:  python corpus/build_hf_wikipedia.py --limit 150000
"""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import tempfile

from build_pack import Document, chunk_document
from pack_format import create_pack_from_files

DISAMBIG = ("may refer to", "may refer to:")


def keep(title: str, text: str, min_chars: int) -> bool:
    if not title or len(text) < min_chars:
        return False
    head = text[:200].lower()
    if any(d in head for d in DISAMBIG):
        return False
    if title.endswith("(disambiguation)"):
        return False
    return True


def build(out_path: str, limit: int, min_chars: int, config: str, embed: bool = False) -> dict:
    from datasets import load_dataset

    work = tempfile.mkdtemp()
    db_path = os.path.join(work, "index.sqlite")
    src_path = os.path.join(work, "sources.jsonl")

    con = sqlite3.connect(db_path)
    con.execute("PRAGMA journal_mode=DELETE;")
    con.execute("PRAGMA synchronous=OFF;")  # bulk load; file is finalized before packing
    con.execute("CREATE TABLE docs(doc_id TEXT PRIMARY KEY, title TEXT, url TEXT, license TEXT)")
    con.execute(
        "CREATE VIRTUAL TABLE passages USING fts5("
        "passage_id UNINDEXED, doc_id UNINDEXED, title, body, tokenize='porter unicode61')"
    )

    ds = load_dataset("wikimedia/wikipedia", config, split="train", streaming=True)
    kept = 0
    passages_total = 0
    with open(src_path, "w", encoding="utf-8") as src_f:
        for row in ds:
            title = row.get("title") or ""
            text = row.get("text") or ""
            if not keep(title, text, min_chars):
                continue
            doc_id = f"w{row.get('id', kept)}"
            url = row.get("url") or f"wikipedia:{title.replace(' ', '_')}"
            con.execute("INSERT OR IGNORE INTO docs VALUES(?,?,?,?)", (doc_id, title, url, "CC BY-SA 4.0"))
            src_f.write(json.dumps({"doc_id": doc_id, "title": title, "url": url, "license": "CC BY-SA 4.0"}, sort_keys=True) + "\n")
            doc = Document(doc_id, title, text)
            rows = [(p.passage_id, p.doc_id, p.title, p.text) for p in chunk_document(doc)]
            con.executemany("INSERT INTO passages(passage_id, doc_id, title, body) VALUES(?,?,?,?)", rows)
            passages_total += len(rows)
            kept += 1
            if kept % 2000 == 0:
                con.commit()
                print(f"  {kept} articles, {passages_total} passages", flush=True)
            if kept >= limit:
                break
    con.commit()
    con.execute("INSERT INTO passages(passages) VALUES('optimize')")  # compact FTS index
    con.commit()
    con.close()

    print(f"built index: {kept} articles, {passages_total} passages", flush=True)

    files = [("data/index.sqlite", db_path), ("data/sources.jsonl", src_path)]

    if embed:
        import sys as _sys
        _sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "pipeline"))
        import numpy as np
        from onnx_embedder import default_embedder

        emb = default_embedder()
        con2 = sqlite3.connect(db_path)
        rows = con2.execute("SELECT passage_id, body FROM passages").fetchall()
        con2.close()
        ids = [r[0] for r in rows]
        texts = [r[1] for r in rows]
        print(f"embedding {len(texts)} passages…", flush=True)
        i8_path = os.path.join(work, "dense.i8")
        with open(i8_path, "wb") as f:
            for i in range(0, len(texts), 256):
                vecs = emb.embed_batch(texts[i : i + 256])
                f.write(np.clip(np.round(vecs * 127.0), -127, 127).astype(np.int8).tobytes())
                if i % 10240 == 0 and i:
                    print(f"  embedded {i}/{len(texts)}", flush=True)
        ids_path = os.path.join(work, "dense_ids.txt")
        with open(ids_path, "w", encoding="utf-8") as f:
            f.write("\n".join(ids))
        files += [("data/dense.i8", i8_path), ("data/dense_ids.txt", ids_path)]

    m = create_pack_from_files(
        out_path, "wikipedia", "hf-20231101-en-slice", files,
        source=f"hf:wikimedia/wikipedia:{config}:top{limit}",
    )
    return m


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Build Wikipedia .fpack from HF wikimedia/wikipedia")
    p.add_argument("-o", "--out", default="out/wikipedia.fpack")
    p.add_argument("--limit", type=int, default=150000)
    p.add_argument("--min-chars", type=int, default=800)
    p.add_argument("--config", default="20231101.en")
    p.add_argument("--embed", action="store_true", help="also build the dense (ONNX) index")
    args = p.parse_args(argv)
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    m = build(args.out, args.limit, args.min_chars, args.config, embed=args.embed)
    size = os.path.getsize(args.out)
    print(f"wrote {args.out}: {len(m['files'])} files, {size // (1024*1024)} MB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
