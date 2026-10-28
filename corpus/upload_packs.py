"""Upload the hosted knowledge pack + embedder to a Hugging Face dataset so the app
can offer the full Wikipedia corpus as an in-app upgrade download. The files are
range-request friendly on HF's CDN (the app's Downloader resumes/streams them).

The pack should already be SIGNED (run sign_pack.py first) - the app verifies the
Ed25519 publisher signature on download, same as the bundled packs.

Prereqs:
  pip install huggingface_hub
  huggingface-cli login        # a token with WRITE scope on the target repo

Usage (from corpus/):
  python upload_packs.py --repo <user>/fireplace-packs

Then set Catalog.HOST in the app to:
  https://huggingface.co/datasets/<user>/fireplace-packs/resolve/main
"""
from __future__ import annotations
import argparse, os, sys

# (local path, path inside the HF repo) - matches the app's Catalog URLs.
FILES = [
    ("../out/wikipedia-clean.fpack", "wikipedia.fpack"),
    ("../models/embedder/model.onnx", "embedder/model.onnx"),
    ("../models/embedder/vocab.txt", "embedder/vocab.txt"),
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="HF dataset repo, e.g. you/fireplace-packs")
    ap.add_argument("--private", action="store_true", help="create the repo private (default public)")
    a = ap.parse_args()

    from huggingface_hub import HfApi, create_repo
    api = HfApi()
    try:
        who = api.whoami().get("name")
    except Exception as e:
        print(f"Not logged in / invalid token: {e}\nRun: huggingface-cli login"); return 1
    print(f"logged in as {who}; target dataset {a.repo}")

    create_repo(a.repo, repo_type="dataset", private=a.private, exist_ok=True)
    for local, remote in FILES:
        if not os.path.exists(local):
            print(f"  MISSING {local} - skipped"); continue
        mb = os.path.getsize(local) // (1024 * 1024)
        print(f"  uploading {local} -> {remote} ({mb} MB)…", flush=True)
        api.upload_file(path_or_fileobj=local, path_in_repo=remote, repo_id=a.repo, repo_type="dataset")
    base = f"https://huggingface.co/datasets/{a.repo}/resolve/main"
    print(f"\nDone. Set Catalog.HOST to:\n  {base}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
