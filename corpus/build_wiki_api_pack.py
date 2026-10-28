"""Build a focused topical .fpack by fetching curated articles straight from the
Wikipedia API (full current text, redirects resolved) - far better coverage for a
specific topic than slicing a partial dump. Content is CC BY-SA 4.0. Builds FTS5 +
MiniLM dense + a signed/plain .fpack with our pipeline.

Usage (venv, from corpus/):
  python build_wiki_api_pack.py --topics first_aid --id first-aid \
      --title "First aid & emergencies" --out ../out/first-aid.fpack --embed
"""
from __future__ import annotations
import argparse, json, os, sqlite3, sys, tempfile, time, urllib.error, urllib.parse, urllib.request

from build_pack import Document, chunk_document
from pack_format import create_pack_from_files
import pack_topics

TOPIC_SETS = {"first_aid": pack_topics.FIRST_AID, "crypto": pack_topics.CRYPTO}
API = "https://en.wikipedia.org/w/api.php"
UA = "FireplaceOfflineResearch/1.0 (knowledge-pack builder; contact: local)"


def _get(url: str) -> dict:
    """GET with patient retry on 429/5xx - Wikipedia rate-limits bulk fetching, so
    wait it out (long, capped backoff) rather than failing the whole build."""
    waits = [10, 20, 40, 60, 60, 90, 120]
    for attempt in range(len(waits) + 1):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            return json.loads(urllib.request.urlopen(req, timeout=40).read())
        except urllib.error.HTTPError as e:
            if e.code in (429, 503) and attempt < len(waits):
                time.sleep(waits[attempt]); continue
            raise
        except Exception:
            if attempt < len(waits):
                time.sleep(waits[attempt]); continue
            raise
    return {}


def fetch_extracts(titles: list[str]) -> dict[str, str]:
    """Plain-text article extracts, ONE title per request - reliable (the batched
    extracts API truncates + paginates). Polite pacing + backoff."""
    out: dict[str, str] = {}
    for n, t in enumerate(titles):
        params = {
            "action": "query", "prop": "extracts", "explaintext": "1",
            "redirects": "1", "format": "json", "formatversion": "2", "titles": t,
        }
        data = _get(API + "?" + urllib.parse.urlencode(params))
        pages = data.get("query", {}).get("pages", [])
        if pages and "missing" not in pages[0]:
            txt = (pages[0].get("extract") or "").strip()
            if len(txt) > 200:
                out[pages[0]["title"]] = txt
        if (n + 1) % 20 == 0:
            print(f"  fetched {len(out)}/{n+1}", flush=True)
        time.sleep(1.5)
    return out


def build(out_path, topics, pack_id, title, description, embed, min_chars):
    arts = fetch_extracts(topics)
    print(f"fetched {len(arts)}/{len(topics)} articles")
    work = tempfile.mkdtemp()
    db_path = os.path.join(work, "index.sqlite")
    src_path = os.path.join(work, "sources.jsonl")
    con = sqlite3.connect(db_path)
    con.execute("PRAGMA journal_mode=DELETE;"); con.execute("PRAGMA synchronous=OFF;")
    con.execute("CREATE TABLE docs(doc_id TEXT PRIMARY KEY, title TEXT, url TEXT, license TEXT)")
    con.execute("CREATE VIRTUAL TABLE passages USING fts5(passage_id UNINDEXED, doc_id UNINDEXED, title, body, tokenize='porter unicode61')")
    total = 0
    with open(src_path, "w", encoding="utf-8") as sf:
        for t, text in sorted(arts.items()):
            if len(text) < min_chars:
                continue
            doc_id = "w_" + t.lower().replace(" ", "_").replace("/", "_")[:48]
            url = "https://en.wikipedia.org/wiki/" + urllib.parse.quote(t.replace(" ", "_"))
            con.execute("INSERT OR IGNORE INTO docs VALUES(?,?,?,?)", (doc_id, t, url, "CC BY-SA 4.0"))
            sf.write(json.dumps({"doc_id": doc_id, "title": t, "url": url, "license": "CC BY-SA 4.0"}, sort_keys=True) + "\n")
            rows = [(p.passage_id, p.doc_id, p.title, p.text) for p in chunk_document(Document(doc_id, t, text))]
            con.executemany("INSERT INTO passages(passage_id,doc_id,title,body) VALUES(?,?,?,?)", rows)
            total += len(rows)
    con.execute("INSERT INTO passages(passages) VALUES('optimize')"); con.commit(); con.close()
    print(f"built {total} passages from {len(arts)} articles")

    files = [("data/index.sqlite", db_path), ("data/sources.jsonl", src_path)]
    if embed:
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "pipeline"))
        import numpy as np
        from onnx_embedder import default_embedder
        emb = default_embedder()
        c = sqlite3.connect(db_path); rows = c.execute("SELECT passage_id, body FROM passages").fetchall(); c.close()
        ids = [r[0] for r in rows]; texts = [r[1] for r in rows]
        i8 = os.path.join(work, "dense.i8")
        with open(i8, "wb") as f:
            for i in range(0, len(texts), 128):
                v = emb.embed_batch(texts[i : i + 128])
                f.write(np.clip(np.round(v * 127.0), -127, 127).astype(np.int8).tobytes())
        idf = os.path.join(work, "dense_ids.txt"); open(idf, "w").write("\n".join(ids))
        files += [("data/dense.i8", i8), ("data/dense_ids.txt", idf)]
        print(f"embedded {len(texts)} passages")

    m = create_pack_from_files(out_path, pack_id, "wiki-api", files,
                               source="wikipedia-api", title=title, description=description)
    return m


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--topics", required=True)
    p.add_argument("--id", required=True)
    p.add_argument("--title", required=True)
    p.add_argument("--description", default="")
    p.add_argument("--out", required=True)
    p.add_argument("--embed", action="store_true")
    p.add_argument("--min-chars", type=int, default=300)
    a = p.parse_args()
    m = build(a.out, TOPIC_SETS[a.topics], a.id, a.title, a.description or a.title, a.embed, a.min_chars)
    print(f"wrote {a.out}: {os.path.getsize(a.out)//1024} KB")


if __name__ == "__main__":
    raise SystemExit(main())
