"""Test the CirrusSearch parser on a tiny synthetic dump."""

import json
import os
import tempfile
import unittest

from build_wikipedia import iter_cirrus
from pack_format import verify_pack


def _write_cirrus(path, rows):
    with open(path, "w", encoding="utf-8") as f:
        for i, row in enumerate(rows):
            f.write(json.dumps({"index": {"_id": str(i)}}) + "\n")
            f.write(json.dumps(row) + "\n")


class BuildWikipediaTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.dump = os.path.join(self.tmp, "sample.jsonl")
        _write_cirrus(
            self.dump,
            [
                {"title": "Lisbon", "text": "Lisbon is the capital of Portugal. " * 20, "namespace": 0},
                {"title": "Talk:Lisbon", "text": "chatter " * 50, "namespace": 1},  # dropped: ns
                {"title": "Foo", "text": "short", "namespace": 0},  # dropped: too short
                {"title": "Bar", "text": "x", "namespace": 0, "redirect": [{"title": "Baz"}]},  # dropped
                {"title": "Mercury", "text": "Mercury may refer to: the planet, the element. " * 10, "namespace": 0},  # disambig
                {"title": "Ethereum", "text": "Ethereum is a blockchain. " * 30, "namespace": 0},
            ],
        )

    def test_filters(self):
        docs = list(iter_cirrus(self.dump))
        titles = {d.title for d in docs}
        self.assertEqual(titles, {"Lisbon", "Ethereum"})
        self.assertTrue(all(d.license == "CC BY-SA 4.0" for d in docs))
        self.assertTrue(all(d.url.startswith("wikipedia:") for d in docs))

    def test_limit(self):
        self.assertEqual(len(list(iter_cirrus(self.dump, limit=1))), 1)

    def test_builds_verifiable_pack(self):
        from build_pack import build_pack

        out = os.path.join(self.tmp, "wikipedia.fpack")
        docs = list(iter_cirrus(self.dump))
        build_pack(out, "wikipedia", "2026-10-20", docs)
        m = verify_pack(out)  # raises if the built pack is inconsistent
        self.assertEqual(m["id"], "wikipedia")

    def test_low_quality_passages_dropped(self):
        """Section headings, reference lists and navbox stubs must not become
        passages - BM25 length-normalization would rank those above the body."""
        from build_pack import Document, chunk_document

        doc = Document(
            "d1", "Photosynthesis",
            "Photosynthesis is how plants and some microorganisms make carbohydrates "
            "using sunlight, water and carbon dioxide to build sugars and release oxygen.\n\n"
            "References\n\n"
            "Cell biology Cellular respiration Metabolism\n\n"
            "Oxygen is produced as a result of photosynthesis and released into the air "
            "where it becomes available for animals and other organisms to breathe.",
        )
        bodies = [p.text.lower() for p in chunk_document(doc)]
        self.assertEqual(len(bodies), 2)  # two prose paragraphs; stubs dropped
        self.assertTrue(all("carbohydrates" in b or "oxygen is produced" in b for b in bodies))
        self.assertFalse(any(b.strip() == "photosynthesis. references" for b in bodies))

    def test_pack_carries_title_and_description(self):
        from build_pack import Document, build_pack
        from pack_format import read_manifest

        out = os.path.join(self.tmp, "titled.fpack")
        build_pack(out, "general", "1", [Document("d1", "T", "A real paragraph of prose with enough words to survive the quality filter.")],
                   title="General Knowledge", description="Everyday research")
        m = read_manifest(out)
        self.assertEqual(m["title"], "General Knowledge")
        self.assertEqual(m["description"], "Everyday research")


if __name__ == "__main__":
    unittest.main()
