"""Tests for the eval harness, including the held-out seal integrity check."""

import os
import tempfile
import unittest

import harness
from harness import Answer, Grade, Question, SealError


class StubSUT:
    def answer(self, prompt: str) -> Answer:
        return Answer(text=f"answer to: {prompt}", citations=["wikipedia:1"], tokens_per_sec=12.0)


class StructuralJudge:
    """Deterministic stand-in for the real frontier judge: rewards a non-empty,
    cited answer. Lets us test the harness end to end without a model."""

    def grade(self, q: Question, a: Answer) -> Grade:
        score = 0.0
        if a.text.strip():
            score += 0.5
        if a.citations:
            score += 0.5
        return Grade(question_id=q.id, score=score)


HERE = os.path.dirname(os.path.abspath(__file__))


class HarnessTest(unittest.TestCase):
    def test_dev_and_heldout_load(self):
        for name in ("dev", "heldout"):
            qs = harness.load_questions(os.path.join(HERE, "questions", f"{name}.jsonl"))
            self.assertGreater(len(qs), 0)
            self.assertTrue(all(q.id and q.prompt for q in qs))

    def test_run_and_summarize(self):
        qs = harness.load_questions(os.path.join(HERE, "questions", "dev.jsonl"))
        grades = harness.run(StubSUT(), StructuralJudge(), qs)
        rep = harness.summarize(grades)
        self.assertEqual(rep["n"], len(qs))
        self.assertEqual(rep["mean_score"], 1.0)  # stub answers are non-empty + cited
        self.assertTrue(rep["pass_bar_0.5"])

    def test_heldout_matches_its_seal(self):
        # The committed seal must match the committed held-out file. If this
        # fails in CI, either the questions or the seal was changed without the
        # other - exactly what the seal is meant to catch.
        qpath = os.path.join(HERE, "questions", "heldout.jsonl")
        sealpath = os.path.join(HERE, "questions", "heldout.seal")
        harness.check_seal(qpath, sealpath)  # raises on mismatch

    def test_seal_detects_tampering(self):
        tmp = tempfile.mkdtemp()
        q = os.path.join(tmp, "heldout.jsonl")
        s = os.path.join(tmp, "heldout.seal")
        with open(q, "w") as f:
            f.write('{"id":"x","prompt":"a"}\n')
        harness.write_seal(q, s)
        harness.check_seal(q, s)  # ok
        with open(q, "a") as f:
            f.write('{"id":"y","prompt":"sneaked in after sealing"}\n')
        with self.assertRaises(SealError):
            harness.check_seal(q, s)

    def test_missing_seal_refuses(self):
        tmp = tempfile.mkdtemp()
        q = os.path.join(tmp, "heldout.jsonl")
        open(q, "w").write("{}\n")
        with self.assertRaises(SealError):
            harness.check_seal(q, os.path.join(tmp, "nope.seal"))


if __name__ == "__main__":
    unittest.main()
