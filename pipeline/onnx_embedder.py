"""On-device-parity sentence embedder: BERT WordPiece tokenizer + all-MiniLM-L6-v2
ONNX (mean-pooled, L2-normalized, 384-dim). The SAME model and tokenization run
on Android (onnxruntime-android + a Kotlin port of this WordPiece), so dense
vectors built here match what the device computes.
"""

from __future__ import annotations

import os
import re

import numpy as np
import onnxruntime as ort

DIM = 384
_PUNCT = re.compile(r"([^\w\s]|_)", re.UNICODE)


class WordPiece:
    """Uncased BERT WordPiece, enough for MiniLM. Ported 1:1 to Kotlin."""

    def __init__(self, vocab_path: str):
        self.vocab: dict[str, int] = {}
        with open(vocab_path, encoding="utf-8") as f:
            for i, line in enumerate(f):
                self.vocab[line.rstrip("\n")] = i
        self.unk = self.vocab["[UNK]"]
        self.cls = self.vocab["[CLS]"]
        self.sep = self.vocab["[SEP]"]

    def _basic(self, text: str) -> list[str]:
        text = text.lower().strip()
        text = _PUNCT.sub(r" \1 ", text)
        return text.split()

    def _wordpiece(self, token: str) -> list[int]:
        if len(token) > 100:
            return [self.unk]
        out, start = [], 0
        chars = token
        while start < len(chars):
            end = len(chars)
            cur = None
            while start < end:
                sub = chars[start:end]
                if start > 0:
                    sub = "##" + sub
                if sub in self.vocab:
                    cur = self.vocab[sub]
                    break
                end -= 1
            if cur is None:
                return [self.unk]
            out.append(cur)
            start = end
        return out

    def encode(self, text: str, max_len: int = 256) -> list[int]:
        ids = [self.cls]
        for tok in self._basic(text):
            ids.extend(self._wordpiece(tok))
            if len(ids) >= max_len - 1:
                break
        ids = ids[: max_len - 1] + [self.sep]
        return ids


class OnnxEmbedder:
    dim = DIM

    def __init__(self, model_path: str, vocab_path: str):
        self.tok = WordPiece(vocab_path)
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 4
        self.sess = ort.InferenceSession(model_path, opts, providers=["CPUExecutionProvider"])
        self.inputs = {i.name for i in self.sess.get_inputs()}
        self.out_name = self.sess.get_outputs()[0].name

    def embed(self, text: str) -> list[float]:
        return self.embed_batch([text])[0].tolist()

    def embed_batch(self, texts: list[str]) -> np.ndarray:
        enc = [self.tok.encode(t) for t in texts]
        maxlen = max(len(e) for e in enc)
        ids = np.zeros((len(enc), maxlen), dtype=np.int64)
        mask = np.zeros((len(enc), maxlen), dtype=np.int64)
        for i, e in enumerate(enc):
            ids[i, : len(e)] = e
            mask[i, : len(e)] = 1
        feed = {"input_ids": ids, "attention_mask": mask}
        if "token_type_ids" in self.inputs:
            feed["token_type_ids"] = np.zeros_like(ids)
        out = self.sess.run([self.out_name], feed)[0]  # [B, T, 384]
        # masked mean pool
        m = mask[:, :, None].astype(np.float32)
        summed = (out * m).sum(axis=1)
        counts = np.clip(m.sum(axis=1), 1e-9, None)
        vecs = summed / counts
        # L2 normalize
        norms = np.clip(np.linalg.norm(vecs, axis=1, keepdims=True), 1e-9, None)
        return (vecs / norms).astype(np.float32)


def default_embedder() -> OnnxEmbedder:
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    return OnnxEmbedder(
        os.path.join(here, "models/embedder/model.onnx"),
        os.path.join(here, "models/embedder/vocab.txt"),
    )


if __name__ == "__main__":
    e = default_embedder()
    import numpy as _np
    a = _np.array(e.embed("the capital of Australia is Canberra"))
    b = _np.array(e.embed("what is the capital city of Australia"))
    c = _np.array(e.embed("photosynthesis in plants"))
    print("inputs:", e.inputs, "| out:", e.out_name)
    print(f"sim(capital-australia, q) = {float(a @ b):.3f}  (expect high)")
    print(f"sim(capital-australia, photosynthesis) = {float(a @ c):.3f}  (expect low)")
