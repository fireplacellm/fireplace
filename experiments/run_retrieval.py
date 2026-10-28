"""Retrieval architecture sweep - the model-independent half of the experiments.

Evaluates our ACTUAL production retrievers (SQLite FTS5 BM25 + the on-device
all-MiniLM-L6-v2 ONNX embedder) on labelled BEIR datasets, so the nDCG@10 /
recall@20 numbers reflect what the app really does on a phone - just measured on
a corpus with relevance judgments.

Configs: bm25 | dense | hybrid(RRF) | ngram  (reranker added separately if a
cross-encoder is available). CPU-only, no LLM.

Run (in the venv):  python experiments/run_retrieval.py --dataset scifact --limit-corpus 0
"""
from __future__ import annotations
import argparse, math, os, re, sqlite3, sys, time
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "pipeline"))
from onnx_embedder import OnnxEmbedder  # our on-device embedder

HERE = os.path.dirname(os.path.abspath(__file__))
EMB = os.path.join(HERE, "..", "models", "embedder")


# ---------------- data ----------------
def load_beir(name: str, limit_corpus: int = 0):
    from datasets import load_dataset
    corpus = {}
    for r in load_dataset(f"BeIR/{name}", "corpus", split="corpus"):
        corpus[str(r["_id"])] = ((r.get("title") or "") + ". " + (r.get("text") or "")).strip()
        if limit_corpus and len(corpus) >= limit_corpus:
            break
    queries = {str(r["_id"]): r["text"] for r in load_dataset(f"BeIR/{name}", "queries", split="queries")}
    # qrels: a separate repo BeIR/<name>-qrels, split test (or train for some)
    qrels: dict[str, dict[str, int]] = {}
    qd = load_dataset(f"BeIR/{name}-qrels")
    split = "test" if "test" in qd else list(qd.keys())[0]
    for r in qd[split]:
        qid, did, s = str(r["query-id"]), str(r["corpus-id"]), int(r["score"])
        if s > 0:
            qrels.setdefault(qid, {})[did] = s
    # keep only queries that have judgments AND whose judged docs are in corpus
    queries = {q: t for q, t in queries.items() if q in qrels}
    return corpus, queries, qrels


# ---------------- retrievers ----------------
_WORD = re.compile(r"[a-z0-9]+")

def _terms(q: str):
    return [t for t in _WORD.findall(q.lower()) if len(t) > 1]

class Bm25:
    """SQLite FTS5 - the app's exact lexical retriever (title+body, porter)."""
    def __init__(self, corpus: dict[str, str]):
        self.con = sqlite3.connect(":memory:")
        self.con.execute("CREATE VIRTUAL TABLE p USING fts5(did UNINDEXED, body, tokenize='porter unicode61')")
        self.con.executemany("INSERT INTO p(did, body) VALUES(?,?)", corpus.items())
    def search(self, q: str, k: int):
        terms = _terms(q)
        if not terms:
            return []
        match = " OR ".join(f'"{t}"' for t in terms)  # defuse FTS operators
        try:
            rows = self.con.execute(
                "SELECT did, bm25(p) s FROM p WHERE p MATCH ? ORDER BY s LIMIT ?", (match, k)
            ).fetchall()
        except sqlite3.OperationalError:
            return []
        return [did for did, _ in rows]

class Dense:
    """Brute-force cosine over MiniLM embeddings - the app's dense retriever."""
    def __init__(self, corpus: dict[str, str], emb: OnnxEmbedder):
        self.ids = list(corpus.keys())
        texts = [corpus[i] for i in self.ids]
        mat = []
        for i in range(0, len(texts), 64):
            mat.append(emb.embed_batch(texts[i:i+64]))
        self.mat = np.vstack(mat).astype(np.float32)       # (N,384) L2-normalized
        self.emb = emb
    def search(self, q: str, k: int):
        v = self.emb.embed_batch([q])[0].astype(np.float32)
        scores = self.mat @ v
        idx = np.argpartition(-scores, min(k, len(scores)-1))[:k]
        idx = idx[np.argsort(-scores[idx])]
        return [self.ids[i] for i in idx]

class Ngram:
    """Word-bigram overlap - a pure n-gram lexical baseline."""
    def __init__(self, corpus: dict[str, str]):
        self.ids = list(corpus.keys())
        self.grams = [self._g(corpus[i]) for i in self.ids]
    @staticmethod
    def _g(t: str):
        w = _terms(t)
        return set(zip(w, w[1:])) | set(w)
    def search(self, q: str, k: int):
        qg = self._g(q)
        scored = sorted(((len(qg & g), i) for i, g in enumerate(self.grams)), reverse=True)
        return [self.ids[i] for s, i in scored[:k] if s > 0]

def rrf(lists, k: int, c: int = 60):
    score: dict[str, float] = {}
    for lst in lists:
        for rank, did in enumerate(lst):
            score[did] = score.get(did, 0.0) + 1.0 / (c + rank)
    return [d for d, _ in sorted(score.items(), key=lambda x: -x[1])][:k]


# ---------------- metrics ----------------
def ndcg_at_k(ranked, rel: dict[str, int], k=10):
    dcg = sum((rel.get(d, 0)) / math.log2(i + 2) for i, d in enumerate(ranked[:k]))
    ideal = sorted(rel.values(), reverse=True)[:k]
    idcg = sum(r / math.log2(i + 2) for i, r in enumerate(ideal))
    return dcg / idcg if idcg > 0 else 0.0

def recall_at_k(ranked, rel: dict[str, int], k=20):
    relevant = {d for d, s in rel.items() if s > 0}
    if not relevant:
        return 0.0
    return len(relevant & set(ranked[:k])) / len(relevant)


# ---------------- driver ----------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", default="scifact")
    ap.add_argument("--limit-corpus", type=int, default=0)
    ap.add_argument("--max-queries", type=int, default=0)
    ap.add_argument("--topk", type=int, default=50)
    ap.add_argument("--rerank", action="store_true", help="add a cross-encoder hybrid+rerank config")
    args = ap.parse_args()

    t0 = time.time()
    corpus, queries, qrels = load_beir(args.dataset, args.limit_corpus)
    print(f"[{args.dataset}] corpus={len(corpus)} queries={len(queries)} (loaded {time.time()-t0:.0f}s)")

    print("building indexes…", flush=True)
    bm25 = Bm25(corpus)
    ngram = Ngram(corpus)
    emb = OnnxEmbedder(os.path.join(EMB, "model.onnx"), os.path.join(EMB, "vocab.txt"))
    te = time.time(); dense = Dense(corpus, emb); print(f"  dense embedded {len(corpus)} docs in {time.time()-te:.0f}s")

    qids = list(queries.keys())
    if args.max_queries:
        qids = qids[:args.max_queries]

    reranker = None
    configs = ["bm25", "dense", "hybrid", "ngram"]
    if args.rerank:
        from xreranker import CrossEncoderReranker
        reranker = CrossEncoderReranker()
        configs.append("hybrid_rr")
    agg = {c: {"ndcg": [], "recall": [], "ms": []} for c in configs}
    K = args.topk
    for qid in qids:
        q = queries[qid]; rel = qrels[qid]
        runs = {}
        for c in configs:
            t = time.time()
            if c == "bm25":   r = bm25.search(q, K)
            elif c == "dense": r = dense.search(q, K)
            elif c == "ngram": r = ngram.search(q, K)
            elif c == "hybrid": r = rrf([runs["bm25"], runs["dense"]], K)
            else:  # hybrid_rr: cross-encoder rerank the fused top-30 candidates
                cand = runs["hybrid"][:30]
                r = reranker.rerank(q, [(d, corpus[d]) for d in cand], K)
            runs[c] = r
            agg[c]["ms"].append((time.time()-t)*1000)
            agg[c]["ndcg"].append(ndcg_at_k(r, rel, 10))
            agg[c]["recall"].append(recall_at_k(r, rel, 20))

    print(f"\n=== {args.dataset}: retrieval results ({len(qids)} queries) ===")
    print(f"{'config':<10}{'nDCG@10':>10}{'Recall@20':>12}{'ms/query':>10}")
    for c in configs:
        a = agg[c]
        print(f"{c:<10}{np.mean(a['ndcg']):>10.4f}{np.mean(a['recall']):>12.4f}{np.mean(a['ms']):>10.1f}")

if __name__ == "__main__":
    main()
