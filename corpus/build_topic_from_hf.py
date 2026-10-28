"""Build focused topical packs (first-aid, crypto) by streaming the FULL HF
`wikimedia/wikipedia` dump and keeping only the curated topic articles - full,
current coverage (DeFi, Solana, …) with no API rate limit. CC BY-SA 4.0.

Builds BOTH packs in ONE streaming pass. Run (venv, from corpus/):
  python build_topic_from_hf.py --config 20231101.en --max-rows 3000000 --embed
"""
from __future__ import annotations
import argparse, json, os, sqlite3, sys, tempfile
from build_pack import Document, chunk_document
from pack_format import create_pack_from_files
import pack_topics

PACKS = [
    ("first-aid", "First aid & emergencies",
     "First-aid and emergency response - grounded in Wikipedia medical articles (CC BY-SA).",
     pack_topics.FIRST_AID),
    ("crypto", "Crypto & blockchain",
     "Cryptocurrency and blockchain - Bitcoin, Ethereum, DeFi and the concepts behind them (CC BY-SA).",
     pack_topics.CRYPTO),
]


def collect(config: str, max_rows: int):
    from datasets import load_dataset
    # title(lower) -> pack_id, so one pass fills every pack
    want: dict[str, str] = {}
    for pid, _, _, topics in PACKS:
        for t in topics:
            want[t.strip().lower()] = pid
    found: dict[str, dict] = {pid: {} for pid, *_ in PACKS}   # pid -> {title: text}
    ds = load_dataset("wikimedia/wikipedia", config, split="train", streaming=True)
    scanned = 0
    for row in ds:
        scanned += 1
        pid = want.get((row.get("title") or "").strip().lower())
        if pid is not None:
            found[pid][row["title"]] = row.get("text") or ""
        if scanned % 200000 == 0:
            got = sum(len(v) for v in found.values())
            print(f"  scanned {scanned}, matched {got}/{len(want)}", flush=True)
            if got >= len(want):
                break
        if max_rows and scanned >= max_rows:
            break
    return found


def build_one(pid, title, desc, arts: dict, embed, min_chars):
    work = tempfile.mkdtemp()
    db = os.path.join(work, "index.sqlite"); srcp = os.path.join(work, "sources.jsonl")
    con = sqlite3.connect(db)
    con.execute("PRAGMA journal_mode=DELETE;"); con.execute("PRAGMA synchronous=OFF;")
    con.execute("CREATE TABLE docs(doc_id TEXT PRIMARY KEY, title TEXT, url TEXT, license TEXT)")
    con.execute("CREATE VIRTUAL TABLE passages USING fts5(passage_id UNINDEXED, doc_id UNINDEXED, title, body, tokenize='porter unicode61')")
    total = 0
    with open(srcp, "w", encoding="utf-8") as sf:
        for t, text in sorted(arts.items()):
            if len(text) < min_chars:
                continue
            doc_id = "w_" + t.lower().replace(" ", "_").replace("/", "_")[:48]
            url = "https://en.wikipedia.org/wiki/" + t.replace(" ", "_")
            con.execute("INSERT OR IGNORE INTO docs VALUES(?,?,?,?)", (doc_id, t, url, "CC BY-SA 4.0"))
            sf.write(json.dumps({"doc_id": doc_id, "title": t, "url": url, "license": "CC BY-SA 4.0"}, sort_keys=True) + "\n")
            rows = [(p.passage_id, p.doc_id, p.title, p.text) for p in chunk_document(Document(doc_id, t, text))]
            con.executemany("INSERT INTO passages(passage_id,doc_id,title,body) VALUES(?,?,?,?)", rows)
            total += len(rows)
    con.execute("INSERT INTO passages(passages) VALUES('optimize')"); con.commit(); con.close()
    files = [("data/index.sqlite", db), ("data/sources.jsonl", srcp)]
    if embed:
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "pipeline"))
        import numpy as np
        from onnx_embedder import default_embedder
        emb = default_embedder()
        c = sqlite3.connect(db); rr = c.execute("SELECT passage_id, body FROM passages").fetchall(); c.close()
        ids = [r[0] for r in rr]; texts = [r[1] for r in rr]
        i8 = os.path.join(work, "dense.i8")
        with open(i8, "wb") as f:
            for i in range(0, len(texts), 128):
                v = emb.embed_batch(texts[i : i + 128])
                f.write(np.clip(np.round(v * 127.0), -127, 127).astype(np.int8).tobytes())
        idf = os.path.join(work, "dense_ids.txt"); open(idf, "w").write("\n".join(ids))
        files += [("data/dense.i8", i8), ("data/dense_ids.txt", idf)]
    out = os.path.join(os.path.dirname(__file__), "..", "out", pid + ".fpack")
    create_pack_from_files(out, pid, "hf-wikipedia-topics", files, source="hf:wikimedia/wikipedia",
                           title=title, description=desc)
    print(f"{pid}: {len(arts)} articles, {total} passages → {out} ({os.path.getsize(out)//1024} KB)")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--config", default="20231101.en")
    p.add_argument("--max-rows", type=int, default=3_000_000)
    p.add_argument("--min-chars", type=int, default=300)
    p.add_argument("--embed", action="store_true")
    a = p.parse_args()
    found = collect(a.config, a.max_rows)
    for pid, title, desc, _ in PACKS:
        build_one(pid, title, desc, found[pid], a.embed, a.min_chars)


if __name__ == "__main__":
    raise SystemExit(main())
