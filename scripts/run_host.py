#!/usr/bin/env python3
"""Run the FULL Fireplace pipeline on the host with a REAL model (pre-device
validation). Builds a small pack, retrieves, answers with llama.cpp, verifies
citations. Falls back to the model-free ExtractiveAnswerer if no model/binary
is present, so it always runs.

    python scripts/run_host.py
"""

import os
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "pipeline"))
sys.path.insert(0, os.path.join(ROOT, "corpus"))

from answerer import ExtractiveAnswerer  # noqa: E402
from build_pack import build_pack  # noqa: E402
from embedder import HashingEmbedder  # noqa: E402
from llm_answerer import LlamaCppAnswerer  # noqa: E402
from orchestrator import Orchestrator  # noqa: E402
from reader import PackReader  # noqa: E402
from retriever import HybridRetriever  # noqa: E402

# Reuse the demo corpus.
sys.path.insert(0, os.path.join(ROOT, "scripts"))
from demo import DOCS, QUESTIONS  # noqa: E402


def main() -> int:
    tmp = tempfile.mkdtemp()
    pack = os.path.join(tmp, "host.fpack")
    build_pack(pack, "host", "0", DOCS, embedder=HashingEmbedder())
    reader = PackReader(pack)
    retr = HybridRetriever([reader], embedder=HashingEmbedder())

    llm = LlamaCppAnswerer()
    if llm.available():
        print(f"ANSWERER: real model - {os.path.basename(llm.model)}\n")
        answerer = llm
    else:
        print("ANSWERER: model-free ExtractiveAnswerer (no GGUF/binary found)\n")
        answerer = ExtractiveAnswerer()

    orch = Orchestrator(retr, answerer)
    for q in QUESTIONS:
        t0 = time.monotonic()
        res = orch.ask(q)
        dt = (time.monotonic() - t0) * 1000
        print(f"Q: {q}")
        print(f"A: {res.answer}")
        srcs = ", ".join(f"[{s['n']}] {s['title']}" for s in res.sources())
        print(f"   sources: {srcs}")
        print(f"   citations: {res.citations.supported}/{res.citations.total} supported, "
              f"pass_rate={res.citations.pass_rate:.2f}, uncited={res.citations.uncited}")
        print(f"   latency: {dt:.0f} ms\n")
    reader.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
