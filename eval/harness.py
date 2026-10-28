"""Fireplace evaluation harness.

Two question sets:
  * dev.jsonl      -- we tune on this.
  * heldout.jsonl  -- we NEVER tune on this. It is sealed: heldout.seal holds
                      the SHA-256 of heldout.jsonl at seal time, committed to
                      git. The harness REFUSES to score the held-out set unless
                      the file still matches the seal, so a reader can prove our
                      held-out numbers were not produced by quietly editing the
                      questions after seeing the answers. See docs/eval-protocol.md.

The model and the judge are injected (Protocols below), so this harness runs in
CI with stubs and on-device with the real system.
"""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import os
import time
from collections.abc import Iterable
from typing import Protocol

HERE = os.path.dirname(os.path.abspath(__file__))


@dataclasses.dataclass(frozen=True)
class Question:
    id: str
    prompt: str
    category: str = "general"


@dataclasses.dataclass
class Answer:
    text: str
    citations: list[str] = dataclasses.field(default_factory=list)
    latency_ms: float = 0.0
    tokens_per_sec: float = 0.0


@dataclasses.dataclass
class Grade:
    question_id: str
    score: float  # 0..1, fraction of the frontier+web baseline
    notes: str = ""


class SystemUnderTest(Protocol):
    def answer(self, prompt: str) -> Answer: ...


class Judge(Protocol):
    """Grades an answer against the baseline. In production this is a blind,
    position-swapped frontier-model judge; see docs/eval-protocol.md."""

    def grade(self, q: Question, a: Answer) -> Grade: ...


def load_questions(path: str) -> list[Question]:
    out: list[Question] = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            d = json.loads(line)
            out.append(Question(id=d["id"], prompt=d["prompt"], category=d.get("category", "general")))
    return out


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


class SealError(Exception):
    pass


def check_seal(questions_path: str, seal_path: str) -> None:
    """Raise unless questions_path matches the committed seal."""
    if not os.path.exists(seal_path):
        raise SealError(f"no seal at {seal_path}; refusing to score an unsealed held-out set")
    with open(seal_path, encoding="utf-8") as f:
        want = f.read().strip().split()[0]
    got = sha256_file(questions_path)
    if got != want:
        raise SealError(
            f"held-out set does not match its seal (got {got[:12]}..., sealed {want[:12]}...). "
            "The held-out questions were modified after sealing; results are void."
        )


def write_seal(questions_path: str, seal_path: str) -> str:
    digest = sha256_file(questions_path)
    with open(seal_path, "w", encoding="utf-8") as f:
        f.write(digest + "\n")
    return digest


def run(sut: SystemUnderTest, judge: Judge, questions: Iterable[Question]) -> list[Grade]:
    grades: list[Grade] = []
    for q in questions:
        t0 = time.monotonic()
        a = sut.answer(q.prompt)
        if a.latency_ms == 0.0:
            a.latency_ms = (time.monotonic() - t0) * 1000.0
        grades.append(judge.grade(q, a))
    return grades


def summarize(grades: list[Grade], answers_by_id: dict[str, Answer] | None = None) -> dict:
    n = len(grades) or 1
    mean = sum(g.score for g in grades) / n
    by_cat: dict[str, list[float]] = {}
    report = {
        "n": len(grades),
        "mean_score": round(mean, 4),
        "pass_bar_0.5": mean >= 0.5,
    }
    if answers_by_id:
        lat = sorted(a.latency_ms for a in answers_by_id.values())
        if lat:
            report["latency_ms_p50"] = round(lat[len(lat) // 2], 1)
            report["latency_ms_p90"] = round(lat[min(len(lat) - 1, int(len(lat) * 0.9))], 1)
    return report


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Fireplace eval harness")
    p.add_argument("--set", choices=["dev", "heldout"], default="dev")
    p.add_argument("--seal", action="store_true", help="(re)write the held-out seal; use only when intentionally changing the held-out set")
    args = p.parse_args(argv)

    qpath = os.path.join(HERE, "questions", f"{args.set}.jsonl")
    sealpath = os.path.join(HERE, "questions", "heldout.seal")

    if args.seal:
        digest = write_seal(os.path.join(HERE, "questions", "heldout.jsonl"), sealpath)
        print(f"sealed held-out set: {digest}")
        return 0

    if args.set == "heldout":
        check_seal(qpath, sealpath)

    print(f"loaded {len(load_questions(qpath))} questions from {args.set}")
    print("plug in a SystemUnderTest + Judge to score (see eval/test_harness.py).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
