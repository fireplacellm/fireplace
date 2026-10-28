"""Cross-encoder reranker (int8 ms-marco-MiniLM-L6), an on-device-grade reranker
evaluated in our retrieval experiments. Runs on CPU via onnxruntime; reuses our
BERT WordPiece. Scores (query, passage) pairs jointly and reorders candidates."""
from __future__ import annotations
import os, sys
import numpy as np
import onnxruntime as ort

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "pipeline"))
from onnx_embedder import WordPiece

HERE = os.path.dirname(os.path.abspath(__file__))
RR = os.path.join(HERE, "..", "models", "reranker")


class CrossEncoderReranker:
    def __init__(self, max_len: int = 256):
        self.sess = ort.InferenceSession(os.path.join(RR, "model_qint8_arm64.onnx"),
                                         providers=["CPUExecutionProvider"])
        self.wp = WordPiece(os.path.join(RR, "vocab.txt"))
        self.max_len = max_len

    def _pair(self, q: str, p: str):
        qt, pt = [], []
        for tok in self.wp._basic(q):
            qt.extend(self.wp._wordpiece(tok))
        for tok in self.wp._basic(p):
            pt.extend(self.wp._wordpiece(tok))
        ids = [self.wp.cls] + qt + [self.wp.sep] + pt + [self.wp.sep]
        typ = [0] * (len(qt) + 2) + [1] * (len(pt) + 1)
        ids, typ = ids[: self.max_len], typ[: self.max_len]
        return ids, typ

    def score(self, query: str, passages: list[str]) -> np.ndarray:
        enc = [self._pair(query, p) for p in passages]
        L = max(len(ids) for ids, _ in enc)
        ids = np.zeros((len(enc), L), np.int64)
        typ = np.zeros((len(enc), L), np.int64)
        mask = np.zeros((len(enc), L), np.int64)
        for i, (a, b) in enumerate(enc):
            ids[i, : len(a)] = a; typ[i, : len(b)] = b; mask[i, : len(a)] = 1
        out = self.sess.run(["logits"], {"input_ids": ids, "attention_mask": mask, "token_type_ids": typ})[0]
        return out.reshape(-1)

    def rerank(self, query: str, candidates: list[tuple[str, str]], k: int):
        """candidates: [(doc_id, text)]; returns top-k doc_ids by cross-encoder score."""
        if not candidates:
            return []
        s = self.score(query, [t for _, t in candidates])
        order = np.argsort(-s)
        return [candidates[i][0] for i in order[:k]]
