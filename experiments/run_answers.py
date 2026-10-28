"""Answer-quality + systems sweep (the model-dependent half).

For each question we retrieve context with our HYBRID retriever (the proven-best
config), feed the SAME context to each model, and measure answer + grounding +
citation + multi-hop quality, plus on-CPU systems metrics. Self-contained on
HotpotQA (distractor): every question ships its own 10 paragraphs (2 gold + 8
distractor) + gold answer + supporting facts, so context is identical per model
and multi-hop is testable.

CPU-only via llama-cpp-python. Run:
  python experiments/run_answers.py --models qwen0.5b,qwen3b --n 30
"""
from __future__ import annotations
import argparse, os, re, sys, time, collections
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "pipeline"))
from onnx_embedder import OnnxEmbedder
from answerer import SYSTEM_PROMPT
from citations import verify, Hit
from run_retrieval import Bm25, Dense, rrf, _terms

HERE = os.path.dirname(os.path.abspath(__file__))
EMB = os.path.join(HERE, "..", "models", "embedder")
MODELS = {
    "qwen0.5b": "../models/qwen2.5-0.5b-instruct-q4_k_m.gguf",
    "qwen1.5b": "../models/qwen2.5-1.5b-instruct.gguf",
    "qwen3b":   "../models/qwen2.5-3b-instruct-q4_k_m.gguf",
    "qwen30b":  "../models/qwen3-30b-a3b-ud-q2kxl.gguf",
    "gemma2b":  "../models/gemma-2-2b-it-q4_k_m.gguf",
    "llama1b":  "../models/llama-3.2-1b-instruct.gguf",
}
def family(name):
    if name.startswith("gemma"): return "gemma"
    if name.startswith("llama"): return "llama"
    return "qwen"
STOP = {"qwen": ["<|im_end|>"], "gemma": ["<end_of_turn>"], "llama": ["<|eot_id|>"]}

# ---------- data ----------
def load_hotpot(n: int):
    from datasets import load_dataset
    ds = load_dataset("hotpotqa/hotpot_qa", "distractor", split="validation", trust_remote_code=True)
    out = []
    for ex in ds.select(range(min(n, len(ds)))):
        titles = ex["context"]["title"]
        sents = ex["context"]["sentences"]
        chunks = [(t, t, " ".join(s)) for t, s in zip(titles, sents)]  # (id=title, title, text)
        gold = set(ex["supporting_facts"]["title"])
        out.append({"q": ex["question"], "answer": ex["answer"], "chunks": chunks, "gold_titles": gold})
    return out

# ---------- retrieval over a question's own paragraphs ----------
def hybrid_topk(chunks, query, emb, k=4):
    corpus = {cid: txt for cid, _, txt in chunks}
    bm = Bm25(corpus); dn = Dense(corpus, emb)
    fused = rrf([bm.search(query, k*3), dn.search(query, k*3)], k)
    title = {cid: t for cid, t, _ in chunks}
    return [(cid, title[cid], corpus[cid]) for cid in fused[:k]]

# ---------- metrics (SQuAD-style) ----------
def _norm(s):
    s = s.lower()
    s = re.sub(r"\b(a|an|the)\b", " ", s)
    s = re.sub(r"[^a-z0-9 ]", " ", s)
    return s.split()

def f1(pred, gold):
    p, g = _norm(pred), _norm(gold)
    if not p or not g:
        return float(p == g)
    common = collections.Counter(p) & collections.Counter(g)
    ns = sum(common.values())
    if ns == 0:
        return 0.0
    prec, rec = ns / len(p), ns / len(g)
    return 2 * prec * rec / (prec + rec)

def contains_gold(pred, gold):  # recall: did the answer state the fact at all
    return set(_norm(gold)).issubset(set(_norm(pred))) if _norm(gold) else False

# ---------- model ----------
class LlamaRunner:
    def __init__(self, path, n_ctx=4096, n_threads=None):
        from llama_cpp import Llama
        self.llm = Llama(model_path=path, n_ctx=n_ctx, n_threads=n_threads,
                         n_gpu_layers=0, verbose=False)  # CPU-only (phone-representative)
    def gen(self, prompt, max_tokens=320, stop=None):
        t0 = time.time()
        r = self.llm(prompt, max_tokens=max_tokens, temperature=0.3, top_p=0.9, top_k=40, echo=False, stop=stop or [])
        dt = time.time() - t0
        txt = r["choices"][0]["text"]
        ntok = r["usage"]["completion_tokens"]
        return txt, ntok, dt

EIFFEL_SRC = "[1] (Eiffel Tower) A wrought-iron lattice tower in Paris, France, built for the 1889 World's Fair; at 330 m it was the world's tallest structure until 1930."
EIFFEL_ANS = "The Eiffel Tower is a 330 m wrought-iron lattice tower in Paris, France, built for the 1889 World's Fair; it was the world's tallest structure until 1930 [1]."

def _sources(hits):
    return "\n".join(f"[{i+1}] ({h['title']}) {h['text']}" for i, h in enumerate(hits))

def build_prompt(question, hits, fam="qwen"):
    # Mirrors the app's prompt: grounding system rule + a one-shot example that
    # teaches the "direct answer + inline [n]" shape (small models need it).
    if fam == "gemma":  # no system role; fold into the user turn
        return (
            f"<start_of_turn>user\n{SYSTEM_PROMPT}\n\nSources:\n{EIFFEL_SRC}\n\n"
            "Question: Tell me about the Eiffel Tower.<end_of_turn>\n"
            f"<start_of_turn>model\n{EIFFEL_ANS}<end_of_turn>\n"
            f"<start_of_turn>user\nSources:\n{_sources(hits)}\n\nQuestion: {question}<end_of_turn>\n"
            "<start_of_turn>model\n"
        )
    if fam == "llama":
        H, E = "<|start_header_id|>", "<|end_header_id|>\n\n"
        return (
            f"<|begin_of_text|>{H}system{E}{SYSTEM_PROMPT}<|eot_id|>"
            f"{H}user{E}Sources:\n{EIFFEL_SRC}\n\nQuestion: Tell me about the Eiffel Tower.<|eot_id|>"
            f"{H}assistant{E}{EIFFEL_ANS}<|eot_id|>"
            f"{H}user{E}Sources:\n{_sources(hits)}\n\nQuestion: {question}<|eot_id|>"
            f"{H}assistant{E}"
        )
    return (
        f"<|im_start|>system\n{SYSTEM_PROMPT} /no_think<|im_end|>\n"
        f"<|im_start|>user\nSources:\n{EIFFEL_SRC}\n\nQuestion: Tell me about the Eiffel Tower.<|im_end|>\n"
        f"<|im_start|>assistant\n{EIFFEL_ANS}<|im_end|>\n"
        f"<|im_start|>user\nSources:\n{_sources(hits)}\n\nQuestion: {question}<|im_end|>\n<|im_start|>assistant\n"
    )

CITE = re.compile(r"\[(\d+)\]")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default="qwen0.5b,qwen3b")
    ap.add_argument("--n", type=int, default=30)
    ap.add_argument("--topk", type=int, default=6)
    args = ap.parse_args()

    data = load_hotpot(args.n)
    emb = OnnxEmbedder(os.path.join(EMB, "model.onnx"), os.path.join(EMB, "vocab.txt"))
    # Pre-retrieve once (same context for every model).
    for d in data:
        d["hits"] = hybrid_topk(d["chunks"], d["q"], emb, args.topk)

    import psutil, os as _os
    proc = psutil.Process(_os.getpid()) if _has_psutil() else None

    print(f"HotpotQA distractor · {len(data)} questions · hybrid top-{args.topk} · CPU\n")
    for mname in args.models.split(","):
        path = os.path.join(HERE, MODELS[mname])
        if not os.path.exists(path):
            print(f"{mname}: model file missing, skipping"); continue
        rss0 = proc.memory_info().rss if proc else 0
        run = LlamaRunner(path)
        peak_rss = proc.memory_info().rss if proc else 0
        m = collections.defaultdict(list)
        fam = family(mname)
        for d in data:
            hits = [{"title": t, "text": x} for _, t, x in d["hits"]]
            prompt = build_prompt(d["q"], hits, fam)
            txt, ntok, dt = run.gen(prompt, stop=STOP[fam])
            if proc: peak_rss = max(peak_rss, proc.memory_info().rss)
            # answer quality
            m["f1"].append(f1(txt, d["answer"]))
            m["recall"].append(float(contains_gold(txt, d["answer"])))
            # groundedness via our verifier
            vhits = [Hit(passage_id=str(i+1), doc_id=hits[i]["title"], title=hits[i]["title"],
                         text=hits[i]["text"], score=1.0) for i in range(len(hits))]
            rep = verify(txt, vhits)
            m["grounded"].append(rep.pass_rate)
            # citation precision + multi-hop support
            cited_idx = {int(c) for c in CITE.findall(txt) if 1 <= int(c) <= len(hits)}
            cited_titles = {hits[i-1]["title"] for i in cited_idx}
            if cited_titles:
                m["cite_prec"].append(len(cited_titles & d["gold_titles"]) / len(cited_titles))
            # multi-hop joint: support-F1 (cited vs gold titles) × answer-F1
            if d["gold_titles"]:
                sp = len(cited_titles & d["gold_titles"]) / len(cited_titles) if cited_titles else 0.0
                sr = len(cited_titles & d["gold_titles"]) / len(d["gold_titles"])
                sf1 = 2*sp*sr/(sp+sr) if (sp+sr) else 0.0
                m["joint"].append(sf1 * m["f1"][-1])
            m["tok_s"].append(ntok / dt if dt > 0 else 0)
            m["lat"].append(dt)
        mb = (peak_rss - 0) / (1024*1024) if proc else float("nan")
        p95 = np.percentile(m["lat"], 95)
        print(f"=== {mname} ===")
        print(f"  answer-F1        {np.mean(m['f1']):.3f}")
        print(f"  answer-recall    {np.mean(m['recall']):.3f}   (states the correct fact)")
        print(f"  groundedness     {np.mean(m['grounded']):.3f}")
        print(f"  citation-prec    {np.mean(m['cite_prec']) if m['cite_prec'] else float('nan'):.3f}")
        print(f"  multihop joint-F1{np.mean(m['joint']) if m['joint'] else float('nan'):.3f}")
        print(f"  tokens/sec       {np.mean(m['tok_s']):.1f}   (host CPU)")
        print(f"  p95 latency      {p95:.1f}s   mean {np.mean(m['lat']):.1f}s")
        print(f"  peak RSS         {mb:.0f} MB" if proc else "  peak RSS n/a")
        print(f"  storage          {os.path.getsize(path)//(1024*1024)} MB (model)\n")
        del run

def _has_psutil():
    try:
        import psutil  # noqa
        return True
    except Exception:
        return False

if __name__ == "__main__":
    main()
