"""Dense embedder interface + a dependency-free fallback.

On device, the embedder is a small ONNX sentence model producing int8 vectors.
Here we define the interface and a deterministic hashing embedder so the dense
path and tests run with no model or network. The hashing embedder is a real
bag-of-words vector (useful as a weak dense signal), not a learned one.
"""

from __future__ import annotations

import hashlib
import math
import re
from typing import Protocol

_TOK = re.compile(r"[a-z0-9]+")


class Embedder(Protocol):
    dim: int

    def embed(self, text: str) -> list[float]: ...


class HashingEmbedder:
    """Hashing bag-of-words → L2-normalised vector. Deterministic, no deps."""

    def __init__(self, dim: int = 256):
        self.dim = dim

    def embed(self, text: str) -> list[float]:
        v = [0.0] * self.dim
        for tok in _TOK.findall(text.lower()):
            h = int.from_bytes(hashlib.blake2b(tok.encode(), digest_size=8).digest(), "big")
            idx = h % self.dim
            sign = 1.0 if (h >> 1) & 1 else -1.0
            v[idx] += sign
        norm = math.sqrt(sum(x * x for x in v)) or 1.0
        return [x / norm for x in v]


def cosine(a: list[float], b: list[float]) -> float:
    return sum(x * y for x, y in zip(a, b))  # inputs are unit vectors
