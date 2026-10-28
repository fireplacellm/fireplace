"""Minimal, dependency-free Ed25519 (RFC 8032) for signing knowledge packs.

This is the standard reference construction over the field 2**255-19 and the
twisted-Edwards curve. It is used ONLY at pack-BUILD time to sign a pack's
manifest with Fireplace's publisher key; the device verifies with the platform's
own Ed25519 (API 33+) against the embedded public key. Kept pure-stdlib so the
corpus pipeline and CI need no third-party crypto dependency.
"""

from __future__ import annotations

import hashlib

_p = 2 ** 255 - 19
_L = 2 ** 252 + 27742317777372353535851937790883648493
_d = (-121665 * pow(121666, _p - 2, _p)) % _p
_I = pow(2, (_p - 1) // 4, _p)


def _H(m: bytes) -> bytes:
    return hashlib.sha512(m).digest()


def _inv(x: int) -> int:
    return pow(x, _p - 2, _p)


def _xrecover(y: int) -> int:
    xx = (y * y - 1) * _inv(_d * y * y + 1)
    x = pow(xx, (_p + 3) // 8, _p)
    if (x * x - xx) % _p != 0:
        x = (x * _I) % _p
    if x % 2 != 0:
        x = _p - x
    return x


_By = (4 * _inv(5)) % _p
_Bx = _xrecover(_By)
_B = (_Bx % _p, _By % _p, 1, (_Bx * _By) % _p)  # extended coords (X, Y, Z, T)


def _edwards_add(P, Q):
    (x1, y1, z1, t1) = P
    (x2, y2, z2, t2) = Q
    a = ((y1 - x1) * (y2 - x2)) % _p
    b = ((y1 + x1) * (y2 + x2)) % _p
    c = (t1 * 2 * _d * t2) % _p
    dd = (z1 * 2 * z2) % _p
    e = b - a
    f = dd - c
    g = dd + c
    h = b + a
    return ((e * f) % _p, (g * h) % _p, (f * g) % _p, (e * h) % _p)


def _scalarmult(P, e):
    Q = (0, 1, 1, 0)
    while e > 0:
        if e & 1:
            Q = _edwards_add(Q, P)
        P = _edwards_add(P, P)
        e >>= 1
    return Q


def _encodeint(y: int) -> bytes:
    return y.to_bytes(32, "little")


def _encodepoint(P) -> bytes:
    (x, y, z, _t) = P
    zi = _inv(z)
    x = (x * zi) % _p
    y = (y * zi) % _p
    bits = y | ((x & 1) << 255)
    return bits.to_bytes(32, "little")


def publickey(seed: bytes) -> bytes:
    """32-byte public key from a 32-byte seed (the private key)."""
    h = _H(seed)
    a = 2 ** 254 + sum(1 << i for i in range(3, 254) if (h[i // 8] >> (i % 8)) & 1)
    return _encodepoint(_scalarmult(_B, a))


def sign(msg: bytes, seed: bytes) -> bytes:
    """64-byte Ed25519 signature of msg under the 32-byte seed."""
    h = _H(seed)
    a = 2 ** 254 + sum(1 << i for i in range(3, 254) if (h[i // 8] >> (i % 8)) & 1)
    pub = _encodepoint(_scalarmult(_B, a))
    r = int.from_bytes(_H(h[32:64] + msg), "little") % _L
    R = _scalarmult(_B, r)
    Renc = _encodepoint(R)
    k = int.from_bytes(_H(Renc + pub + msg), "little") % _L
    s = (r + k * a) % _L
    return Renc + _encodeint(s)


def new_seed() -> bytes:
    import os
    return os.urandom(32)


def _decodepoint(s: bytes):
    y = int.from_bytes(s, "little") & ((1 << 255) - 1)
    x = _xrecover(y)
    if (x & 1) != ((s[31] >> 7) & 1):
        x = _p - x
    P = (x, y, 1, (x * y) % _p)
    return P


def verify(sig: bytes, msg: bytes, pub: bytes) -> bool:
    """Verify a 64-byte signature of msg under the 32-byte public key."""
    if len(sig) != 64 or len(pub) != 32:
        return False
    try:
        R = _decodepoint(sig[:32])
        A = _decodepoint(pub)
    except Exception:
        return False
    s = int.from_bytes(sig[32:], "little")
    k = int.from_bytes(_H(sig[:32] + pub + msg), "little") % _L
    # Check [s]B == R + [k]A
    left = _scalarmult(_B, s)
    right = _edwards_add(R, _scalarmult(A, k))
    return _encodepoint(left) == _encodepoint(right)
