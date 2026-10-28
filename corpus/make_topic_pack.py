"""Build a FOCUSED topical .fpack (first-aid, crypto, …) as a slice of a full
Wikipedia pack - select only the articles matching a curated topic list, keep the
aligned int8 dense vectors, repackage. Fully open (CC BY-SA), no new downloads.

Usage (venv, from corpus/):
  python make_topic_pack.py --topics first_aid --id first-aid \
      --title "First aid & emergencies" --out ../out/first-aid.fpack
"""
from __future__ import annotations
import argparse, json, os, sqlite3, tempfile, zipfile
from pack_format import create_pack_from_files
import pack_topics

TOPIC_SETS = {"first_aid": pack_topics.FIRST_AID, "crypto": pack_topics.CRYPTO}


def build(src, out, topics, pack_id, title, description, cap, vec_dim):
    work = tempfile.mkdtemp()
    with zipfile.ZipFile(src) as z:
        z.extractall(work)
    data = os.path.join(work, "data")
    con = sqlite3.connect(os.path.join(data, "index.sqlite")); cur = con.cursor()
    titles = {d: t for d, t in cur.execute("SELECT doc_id, title FROM docs")}

    # Match each topic to a doc: exact (lower) first, else substring.
    want = [t.strip().lower() for t in topics]
    exact = {}
    for d, t in titles.items():
        exact.setdefault((t or "").strip().lower(), d)
    # Exact title match only - a substring fallback mismatches badly (e.g. "Cardano"
    # → "Gerolamo Cardano", "Solana" → "Javier Solana"). A topic absent from the
    # source slice is simply skipped rather than pulling in an off-topic article.
    chosen: set[str] = {exact[w] for w in want if w in exact}
    print(f"matched {len(chosen)}/{len(topics)} topics (exact)")

    keep = []          # (passage_id, doc_id, title, body)
    per: dict[str, int] = {}
    for pid, did, t, body in cur.execute(
        "SELECT passage_id, doc_id, title, body FROM passages ORDER BY doc_id, passage_id"
    ):
        if did not in chosen or per.get(did, 0) >= cap:
            continue
        per[did] = per.get(did, 0) + 1
        keep.append((pid, did, t, body))
    print(f"kept {len(keep)} passages")

    ow = tempfile.mkdtemp()
    db = os.path.join(ow, "index.sqlite")
    c2 = sqlite3.connect(db)
    c2.execute("PRAGMA journal_mode=DELETE;"); c2.execute("PRAGMA synchronous=OFF;")
    c2.execute("CREATE TABLE docs(doc_id TEXT PRIMARY KEY, title TEXT, url TEXT, license TEXT)")
    c2.execute("CREATE VIRTUAL TABLE passages USING fts5(passage_id UNINDEXED, doc_id UNINDEXED, title, body, tokenize='porter unicode61')")
    docrows = cur.execute(
        f"SELECT doc_id,title,url,license FROM docs WHERE doc_id IN ({','.join('?'*len(chosen))})", list(chosen)
    ).fetchall()
    c2.executemany("INSERT INTO docs VALUES(?,?,?,?)", docrows)
    c2.executemany("INSERT INTO passages(passage_id,doc_id,title,body) VALUES(?,?,?,?)", keep)
    c2.execute("INSERT INTO passages(passages) VALUES('optimize')"); c2.commit(); c2.close()
    con.close()

    src_jsonl = os.path.join(ow, "sources.jsonl")
    with open(src_jsonl, "w") as f:
        for d, t, u, lic in docrows:
            f.write(json.dumps({"doc_id": d, "title": t, "url": u, "license": lic}, sort_keys=True) + "\n")
    files = [("data/index.sqlite", db), ("data/sources.jsonl", src_jsonl)]

    di8, dids = os.path.join(data, "dense.i8"), os.path.join(data, "dense_ids.txt")
    if os.path.exists(di8) and os.path.exists(dids):
        row = {l.strip(): i for i, l in enumerate(open(dids))}
        ni8, nid = os.path.join(ow, "dense.i8"), os.path.join(ow, "dense_ids.txt")
        n = 0
        with open(di8, "rb") as sf, open(ni8, "wb") as of, open(nid, "w") as idf:
            for pid, *_ in keep:
                r = row.get(pid)
                if r is None: continue
                sf.seek(r * vec_dim); of.write(sf.read(vec_dim)); idf.write(pid + "\n"); n += 1
        files += [("data/dense.i8", ni8), ("data/dense_ids.txt", nid)]
        print(f"sliced {n} dense vectors")

    m = create_pack_from_files(out, pack_id, "topic-wikipedia-slice", files,
                               source="subset:wikipedia-clean", title=title, description=description)
    return m


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--src", default="../out/wikipedia-clean.fpack")
    p.add_argument("--topics", required=True, help="a key in TOPIC_SETS (first_aid|crypto)")
    p.add_argument("--id", required=True)
    p.add_argument("--title", required=True)
    p.add_argument("--description", default="")
    p.add_argument("--out", required=True)
    p.add_argument("--cap", type=int, default=15)
    p.add_argument("--vec-dim", type=int, default=384)
    a = p.parse_args()
    m = build(a.src, a.out, TOPIC_SETS[a.topics], a.id, a.title,
              a.description or a.title, a.cap, a.vec_dim)
    print(f"wrote {a.out}: {os.path.getsize(a.out)//1024} KB, signed={'signature' in m}")


if __name__ == "__main__":
    raise SystemExit(main())
