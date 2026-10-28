"""Add (or refresh) the Ed25519 publisher signature on an existing .fpack, WITHOUT
rebuilding it. The signature covers the same stable payload as pack_format._sign
(id + version + sorted "path:sha256:bytes"), which is already recorded in the pack's
manifest - so we never need to re-read the content files, just rewrite manifest.json.

The signing key comes from $FIREPLACE_SIGNING_SEED (hex) or corpus/.signing_seed, and
must match the publisher key pinned in the app (rag/Signatures.kt).

Usage (from corpus/):  python sign_pack.py ../app/src/main/assets/starter/*.fpack
"""
from __future__ import annotations
import json, os, sys, zipfile

import pack_format as pf


def resign(path: str) -> bool:
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if pf.MANIFEST_NAME not in names:
            print(f"  {os.path.basename(path)}: no manifest - skipped"); return False
        manifest = json.loads(z.read(pf.MANIFEST_NAME))
        members = [(n, z.read(n)) for n in names if n != pf.MANIFEST_NAME]

    entries = [pf.FileEntry(f["path"], f["sha256"], int(f["bytes"])) for f in manifest["files"]]
    sig = pf._sign(manifest["id"], manifest["version"], entries)
    if sig is None:
        print("  no signing seed ($FIREPLACE_SIGNING_SEED or corpus/.signing_seed) - cannot sign")
        return False
    manifest["signature"], manifest["pubkey"] = sig
    manifest_bytes = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()

    tmp = path + ".signing"
    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED) as z:
        pf._write_deterministic(z, pf.MANIFEST_NAME, manifest_bytes)   # manifest first
        for name, data in members:
            pf._write_deterministic(z, name, data)
    os.replace(tmp, path)
    print(f"  signed {os.path.basename(path)}  id={manifest['id']}  pubkey={sig[1][:16]}…")
    return True


def main(argv: list[str]) -> int:
    paths = [p for p in argv if p.endswith(".fpack")]
    if not paths:
        print("usage: python sign_pack.py <pack.fpack> [more.fpack ...]"); return 2
    ok = 0
    for p in paths:
        try:
            if resign(p): ok += 1
        except Exception as e:
            print(f"  {os.path.basename(p)}: FAILED - {e}")
    print(f"signed {ok}/{len(paths)} packs")
    return 0 if ok == len(paths) else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
