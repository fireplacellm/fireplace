#!/usr/bin/env python3
"""Run the whole Fireplace pipeline on a tiny built-in corpus and print cited,
verified answers. No model, no network - proves the retrieval/grounding/
citation brain end to end. On device the ExtractiveAnswerer is replaced by the
MoE; everything else is identical.

    python scripts/demo.py
"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "pipeline"))
sys.path.insert(0, os.path.join(ROOT, "corpus"))

import tempfile

from answerer import ExtractiveAnswerer
from build_pack import Document, build_pack
from embedder import HashingEmbedder
from orchestrator import Orchestrator
from reader import PackReader
from retriever import HybridRetriever

DOCS = [
    Document("eth-pos", "Ethereum proof of stake",
             "Ethereum moved from proof of work to proof of stake in the Merge. Proof of stake "
             "selects validators by staked ether rather than computation, cutting energy use by "
             "over 99 percent.", "wikipedia:Proof_of_stake", "CC BY-SA"),
    Document("lisbon", "Vegan food in Lisbon",
             "Lisbon has a thriving vegan scene. The neighbourhoods of Príncipe Real and Santos are "
             "known for vegan restaurants, and vegan buffets are common in the city centre.",
             "wikipedia:Lisbon", "CC BY-SA"),
    Document("snakebite", "Snakebite first aid",
             "For a snakebite keep the person calm and still and keep the limb below heart level. Do "
             "not apply a tourniquet, do not cut the wound, and do not suck out venom. Get medical "
             "help quickly.", "wikibooks:First_Aid", "CC BY-SA"),
]

QUESTIONS = [
    "Why did Ethereum switch to proof of stake?",
    "Where in Lisbon can I find vegan food?",
    "What should I do for a snakebite?",
]


def main() -> int:
    tmp = tempfile.mkdtemp()
    pack = os.path.join(tmp, "demo.fpack")
    build_pack(pack, "demo", "0", DOCS, embedder=HashingEmbedder())
    reader = PackReader(pack)  # verifies the pack before use
    orch = Orchestrator(HybridRetriever([reader], embedder=HashingEmbedder()), ExtractiveAnswerer())

    for q in QUESTIONS:
        res = orch.ask(q)
        print(f"\nQ: {q}")
        print(f"A: {res.answer}")
        srcs = ", ".join(f"[{s['n']}] {s['title']}" for s in res.sources())
        print(f"   sources: {srcs}")
        print(f"   citations verified: {res.citations.supported}/{res.citations.total}"
              f"  pass_rate={res.citations.pass_rate:.2f}  uncited={res.citations.uncited}")
    reader.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
