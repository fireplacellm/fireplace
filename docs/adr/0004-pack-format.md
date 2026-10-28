# ADR 0004 - Knowledge pack format (`.fpack`)

Status: accepted · 2026-10-21

## Decision

Knowledge ships as **`.fpack`** files: a deterministic archive containing a manifest, a set of content files (SQLite FTS5 DB, dense index, source blobs), and a SHA-256 for every file plus a signature over the manifest. A pack is **verified before it is installed and again before it is loaded** - every file's hash is checked against the signed manifest, and nothing is parsed by SQLite/the embedder until it passes.

## `.fpack` layout

```
<pack>.fpack  (zip, fixed timestamps, sorted entries → reproducible)
├── manifest.json        # id, version, created_at, source provenance, files[]{path, sha256, bytes}, format_version
├── manifest.sig         # signature over manifest.json (Ed25519)
├── data/index.sqlite    # FTS5 passages
├── data/dense.ivf       # int8 dense vectors
└── data/sources.jsonl   # source documents for citation resolution
```

## Verification order (the bug we refuse to repeat)

A naive installer checks a bundle-level hash only *after* unpacking, or loads a model pack after an import has already written files - so a tampered same-name/same-size file can reach the GGUF/ONNX parser before the check catches it, and a remote catalog could serve a replacement pack with any hash. Our rule:

1. Read `manifest.json` + verify `manifest.sig` against the app's embedded public key.
2. For **every** file, stream-hash it and compare to the manifest **before** moving it into place.
3. Only on full success, atomically move the pack into the library.
4. Re-verify file hashes **at load time** too (storage can change under us), before any file is handed to SQLite or the embedder.
5. Sanitize every path in the manifest (no `..`, no absolute, no symlink escape).

There is no remote catalog. The set of trusted pack publisher keys ships in the APK; a user importing a third-party pack is warned and it is sandboxed.

## Why signed + double-checked

Packs contain files parsed by native code (SQLite, the ONNX runtime, potentially GGUF). That is attack surface. Signing + hash-before-parse means a corrupted or tampered pack fails closed instead of feeding malformed input to a native parser.

## Forward-looking: data pods (post-bounty)

The same format carries a user's **personal data pod** - e.g. a Strava or health export built into an `.fpack` on-device - which then becomes just another retrievable, citable source in the same pipeline. Pods are **not** part of the bounty submission (off-spec for a research/lookup tool), but the format is designed so they drop in without a new code path. Personal pods are signed by a device-local key and never leave the phone.
