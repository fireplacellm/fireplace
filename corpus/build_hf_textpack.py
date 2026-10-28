"""Build a .fpack from any Hugging Face text dataset (Wikivoyage travel guides,
arXiv abstracts, …). Streams the dataset, chunks each record into passages, builds
FTS5 + optional MiniLM dense, and packages. Generalises build_hf_wikipedia.py.

Examples (venv, from corpus/):
  # Travel - Wikivoyage geotagged guides (CC BY-SA)
  python build_hf_textpack.py --dataset yuiseki/wikivoyage-geotagged --config 20260901.en \
      --split train --title-field title --text-field text --limit 30000 \
      --id travel --title "Travel & destinations" --out ../out/travel.fpack --embed

  # Research - arXiv ML abstracts (CC0 metadata)
  python build_hf_textpack.py --dataset CShorten/ML-ArXiv-Papers --split train \
      --title-field title --text-field abstract --limit 20000 \
      --id arxiv --title "AI/ML research papers" --out ../out/arxiv.fpack --embed
"""
from __future__ import annotations
import argparse, json, os, sqlite3, sys, tempfile
from build_pack import Document, chunk_document
from pack_format import create_pack_from_files


def build(a) -> dict:
    from datasets import load_dataset
    ds = load_dataset(a.dataset, a.config, split=a.split, streaming=True) if a.config \
         else load_dataset(a.dataset, split=a.split, streaming=True)
    work = tempfile.mkdtemp()
    db = os.path.join(work, "index.sqlite"); srcp = os.path.join(work, "sources.jsonl")
    con = sqlite3.connect(db)
    con.execute("PRAGMA journal_mode=DELETE;"); con.execute("PRAGMA synchronous=OFF;")
    con.execute("CREATE TABLE docs(doc_id TEXT PRIMARY KEY, title TEXT, url TEXT, license TEXT)")
    con.execute("CREATE VIRTUAL TABLE passages USING fts5(passage_id UNINDEXED, doc_id UNINDEXED, title, body, tokenize='porter unicode61')")
    kept = passages = 0
    with open(srcp, "w", encoding="utf-8") as sf:
        for row in ds:
            title = str(row.get(a.title_field) or "").strip()
            text = str(row.get(a.text_field) or "").strip()
            if not title or len(text) < a.min_chars:
                continue
            doc_id = f"d{kept}"
            url = str(row.get(a.url_field) or "") if a.url_field else ""
            con.execute("INSERT OR IGNORE INTO docs VALUES(?,?,?,?)", (doc_id, title, url, a.license))
            sf.write(json.dumps({"doc_id": doc_id, "title": title, "url": url, "license": a.license}, sort_keys=True) + "\n")
            rows = [(p.passage_id, p.doc_id, p.title, p.text) for p in chunk_document(Document(doc_id, title, text))]
            if a.cap_per_doc:
                rows = rows[: a.cap_per_doc]   # lead sections are the most useful; keeps the pack small + dense-eligible
            con.executemany("INSERT INTO passages(passage_id,doc_id,title,body) VALUES(?,?,?,?)", rows)
            passages += len(rows); kept += 1
            if kept % 2000 == 0:
                con.commit(); print(f"  {kept} docs, {passages} passages", flush=True)
            if a.limit and kept >= a.limit:
                break
    con.execute("INSERT INTO passages(passages) VALUES('optimize')"); con.commit(); con.close()
    print(f"built {kept} docs, {passages} passages")

    files = [("data/index.sqlite", db), ("data/sources.jsonl", srcp)]
    if a.embed:
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "pipeline"))
        import numpy as np
        from onnx_embedder import default_embedder
        emb = default_embedder()
        c = sqlite3.connect(db); rows = c.execute("SELECT passage_id, body FROM passages").fetchall(); c.close()
        ids = [r[0] for r in rows]; texts = [r[1] for r in rows]
        i8 = os.path.join(work, "dense.i8")
        with open(i8, "wb") as f:
            for i in range(0, len(texts), 256):
                v = emb.embed_batch(texts[i : i + 256])
                f.write(np.clip(np.round(v * 127.0), -127, 127).astype(np.int8).tobytes())
                if i and i % 10240 == 0: print(f"  embedded {i}/{len(texts)}", flush=True)
        idf = os.path.join(work, "dense_ids.txt"); open(idf, "w").write("\n".join(ids))
        files += [("data/dense.i8", i8), ("data/dense_ids.txt", idf)]
        print(f"embedded {len(texts)} passages")

    return create_pack_from_files(a.out, a.id, f"hf:{a.dataset}", files,
                                  source=f"hf:{a.dataset}", title=a.title, description=a.description or a.title)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--dataset", required=True); p.add_argument("--config", default="")
    p.add_argument("--split", default="train")
    p.add_argument("--title-field", required=True); p.add_argument("--text-field", required=True)
    p.add_argument("--url-field", default=""); p.add_argument("--license", default="CC BY-SA 4.0")
    p.add_argument("--limit", type=int, default=0); p.add_argument("--min-chars", type=int, default=200)
    p.add_argument("--cap-per-doc", type=int, default=0, help="max passages per document (keeps pack small + under the 120k dense cap)")
    p.add_argument("--id", required=True); p.add_argument("--title", required=True)
    p.add_argument("--description", default=""); p.add_argument("--out", required=True)
    p.add_argument("--embed", action="store_true")
    a = p.parse_args()
    build(a); print(f"wrote {a.out}: {os.path.getsize(a.out)//(1024*1024)} MB")


if __name__ == "__main__":
    raise SystemExit(main())
