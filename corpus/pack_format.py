"""Fireplace knowledge-pack (.fpack) format: create, and verify-before-use.

A pack is a deterministic zip (sorted entries, fixed timestamps) holding a
manifest plus content files. The security-critical property, implemented and
tested here, is that EVERY file is hash-checked against the signed manifest
before anything is installed or parsed -- see ADR 0004. Signature
verification is a pluggable hook (Ed25519 in the app); the hash manifest is
the part enforced in pure stdlib so CI needs no dependencies.
"""

from __future__ import annotations

import dataclasses
import hashlib
import io
import json
import posixpath
import zipfile
from collections.abc import Iterable

FORMAT_VERSION = 1
MANIFEST_NAME = "manifest.json"
_FIXED_TS = (1980, 1, 1, 0, 0, 0)  # deterministic zip timestamp
_CHUNK = 1 << 20


class PackError(Exception):
    """Raised when a pack fails verification. Callers must treat a pack that
    raises as untrusted and refuse to install or load it."""


@dataclasses.dataclass(frozen=True)
class FileEntry:
    path: str
    sha256: str
    bytes: int


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _sign_payload(pack_id: str, version: str, entries: list["FileEntry"]) -> bytes:
    """The stable bytes that a signature covers - id + version + sorted file
    hashes. Built identically on the device so no JSON canonicalization is needed."""
    lines = [pack_id, version]
    for e in sorted(entries, key=lambda x: x.path):
        lines.append(f"{e.path}:{e.sha256}:{e.bytes}")
    return "\n".join(lines).encode()


def _sign(pack_id: str, version: str, entries: list["FileEntry"]):
    """Return (sig_hex, pub_hex) if a signing seed is available, else None.
    Seed comes from $FIREPLACE_SIGNING_SEED (hex) or corpus/.signing_seed."""
    import os
    seed_hex = os.environ.get("FIREPLACE_SIGNING_SEED")
    if not seed_hex:
        path = os.path.join(os.path.dirname(__file__), ".signing_seed")
        if os.path.exists(path):
            seed_hex = open(path).read().strip()
    if not seed_hex:
        return None
    try:
        import ed25519  # local, pure-stdlib
    except Exception:
        import sys
        sys.path.insert(0, os.path.dirname(__file__))
        import ed25519
    seed = bytes.fromhex(seed_hex)
    payload = _sign_payload(pack_id, version, entries)
    return ed25519.sign(payload, seed).hex(), ed25519.publickey(seed).hex()


def _safe_path(path: str) -> str:
    """Reject anything that could escape the pack root on extraction."""
    if not path or path.startswith("/") or ":" in path:
        raise PackError(f"unsafe path in pack: {path!r}")
    norm = posixpath.normpath(path)
    if norm.startswith("..") or norm != path or "\\" in path:
        raise PackError(f"unsafe path in pack: {path!r}")
    return norm


def create_pack(
    out_path: str,
    pack_id: str,
    version: str,
    files: Iterable[tuple[str, bytes]],
    *,
    source: str = "",
    personal: bool = False,
    title: str = "",
    description: str = "",
) -> dict:
    """Write a deterministic .fpack. `files` is (arcname, content) pairs.

    Returns the manifest dict that was embedded.
    """
    entries: list[FileEntry] = []
    materialized: list[tuple[str, bytes]] = []
    for arcname, content in sorted(files, key=lambda kv: kv[0]):
        safe = _safe_path(arcname)
        entries.append(FileEntry(safe, _sha256_bytes(content), len(content)))
        materialized.append((safe, content))

    manifest = {
        "format_version": FORMAT_VERSION,
        "id": pack_id,
        "version": version,
        "source": source,
        "personal": personal,
        "title": title,
        "description": description,
        "files": [dataclasses.asdict(e) for e in entries],
    }
    # Optional Ed25519 publisher signature over a stable payload (id + version +
    # sorted file hashes). The device verifies it against the embedded publisher
    # key, so a pack from an untrusted channel can't be swapped for another.
    sig = _sign(pack_id, version, entries)
    if sig is not None:
        manifest["signature"], manifest["pubkey"] = sig
    manifest_bytes = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()

    with zipfile.ZipFile(out_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        # manifest first so a reader can verify before streaming large files
        _write_deterministic(zf, MANIFEST_NAME, manifest_bytes)
        for arcname, content in materialized:
            _write_deterministic(zf, arcname, content)
    return manifest


def _write_deterministic(zf: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, date_time=_FIXED_TS)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    zf.writestr(info, data)


def _sha256_and_size(path: str) -> tuple[str, int]:
    h = hashlib.sha256()
    size = 0
    with open(path, "rb") as f:
        while chunk := f.read(_CHUNK):
            h.update(chunk)
            size += len(chunk)
    return h.hexdigest(), size


def create_pack_from_files(
    out_path: str,
    pack_id: str,
    version: str,
    files: Iterable[tuple[str, str]],
    *,
    source: str = "",
    personal: bool = False,
    title: str = "",
    description: str = "",
) -> dict:
    """Like create_pack but streams large files from disk (chunked hash + streamed
    zip), so building a multi-GB pack does not load everything into memory.
    `files` is (arcname, local_path) pairs."""
    items = sorted(files, key=lambda kv: kv[0])
    entries: list[FileEntry] = []
    for arc, path in items:
        safe = _safe_path(arc)
        sha, nbytes = _sha256_and_size(path)
        entries.append(FileEntry(safe, sha, nbytes))

    manifest = {
        "format_version": FORMAT_VERSION,
        "id": pack_id,
        "version": version,
        "source": source,
        "personal": personal,
        "files": [dataclasses.asdict(e) for e in entries],
    }
    if title:
        manifest["title"] = title
    if description:
        manifest["description"] = description
    # Optional Ed25519 publisher signature (same as create_pack) so streamed packs are
    # authenticated too, not just hash-checked.
    sig = _sign(pack_id, version, entries)
    if sig is not None:
        manifest["signature"], manifest["pubkey"] = sig
    manifest_bytes = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()

    with zipfile.ZipFile(out_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        _write_deterministic(zf, MANIFEST_NAME, manifest_bytes)
        for arc, path in items:
            info = zipfile.ZipInfo(_safe_path(arc), date_time=_FIXED_TS)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            # force_zip64: streamed entries whose size isn't known up front need
            # ZIP64 when they exceed 4 GiB (large FTS indexes do).
            with open(path, "rb") as src, zf.open(info, "w", force_zip64=True) as dst:
                while chunk := src.read(_CHUNK):
                    dst.write(chunk)
    return manifest


def read_manifest(fpack_path: str) -> dict:
    with zipfile.ZipFile(fpack_path, "r") as zf:
        with zf.open(MANIFEST_NAME) as f:
            manifest = json.loads(f.read())
    if manifest.get("format_version") != FORMAT_VERSION:
        raise PackError(f"unsupported format_version: {manifest.get('format_version')}")
    return manifest


def verify_pack(fpack_path: str, *, verify_signature=None) -> dict:
    """Verify a pack end to end. Raises PackError on ANY mismatch.

    This is called before install AND before load (ADR 0004). `verify_signature`
    is an optional callable(manifest_bytes) -> bool for the app's Ed25519 check;
    if provided and it returns False, verification fails.
    """
    with zipfile.ZipFile(fpack_path, "r") as zf:
        names = set(zf.namelist())
        if MANIFEST_NAME not in names:
            raise PackError("pack has no manifest")
        manifest_bytes = zf.read(MANIFEST_NAME)
        manifest = json.loads(manifest_bytes)
        if manifest.get("format_version") != FORMAT_VERSION:
            raise PackError(f"unsupported format_version: {manifest.get('format_version')}")

        if verify_signature is not None and not verify_signature(manifest_bytes):
            raise PackError("manifest signature verification failed")

        declared = {e["path"]: e for e in manifest["files"]}
        for path in declared:
            _safe_path(path)

        # Every content file in the archive must be declared (no stowaways)...
        content_names = names - {MANIFEST_NAME}
        undeclared = content_names - set(declared)
        if undeclared:
            raise PackError(f"undeclared files in pack: {sorted(undeclared)}")
        # ...and every declared file must be present and hash-match.
        for path, entry in declared.items():
            if path not in names:
                raise PackError(f"declared file missing from pack: {path}")
            h = hashlib.sha256()
            size = 0
            with zf.open(path) as f:
                while chunk := f.read(_CHUNK):
                    h.update(chunk)
                    size += len(chunk)
            if size != entry["bytes"]:
                raise PackError(f"size mismatch for {path}: {size} != {entry['bytes']}")
            if h.hexdigest() != entry["sha256"]:
                raise PackError(f"sha256 mismatch for {path}")
    return manifest


if __name__ == "__main__":
    import sys

    if len(sys.argv) >= 3 and sys.argv[1] == "verify":
        m = verify_pack(sys.argv[2])
        print(f"OK: {m['id']} v{m['version']} ({len(m['files'])} files verified)")
    else:
        print("usage: python -m corpus.pack_format verify <file.fpack>")
