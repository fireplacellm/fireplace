"""Tests for the .fpack verify-before-use contract (ADR 0004)."""

import os
import tempfile
import unittest
import zipfile

from pack_format import PackError, create_pack, verify_pack


class PackFormatTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.path = os.path.join(self.tmp, "t.fpack")

    def _make(self, files, **kw):
        return create_pack(self.path, "wikipedia", "2026.10.21", files, **kw)

    def test_roundtrip_verifies(self):
        self._make([("data/index.sqlite", b"hello"), ("data/sources.jsonl", b'{"a":1}')])
        m = verify_pack(self.path)
        self.assertEqual(m["id"], "wikipedia")
        self.assertEqual(len(m["files"]), 2)

    def test_deterministic_bytes(self):
        a = os.path.join(self.tmp, "a.fpack")
        b = os.path.join(self.tmp, "b.fpack")
        files = [("y.bin", b"2"), ("x.bin", b"1")]  # unsorted input
        create_pack(a, "p", "1", files)
        create_pack(b, "p", "1", list(reversed(files)))
        self.assertEqual(open(a, "rb").read(), open(b, "rb").read())

    def test_tampered_content_fails(self):
        self._make([("data/x", b"original")])
        # Flip one content file's bytes, leaving the manifest's hash stale.
        self._rewrite_member("data/x", b"tampered!")
        with self.assertRaises(PackError):
            verify_pack(self.path)

    def test_stowaway_file_fails(self):
        self._make([("data/x", b"ok")])
        self._add_member("data/evil", b"not in manifest")
        with self.assertRaises(PackError):
            verify_pack(self.path)

    def test_missing_declared_file_fails(self):
        self._make([("data/x", b"ok"), ("data/y", b"ok2")])
        self._drop_member("data/y")
        with self.assertRaises(PackError):
            verify_pack(self.path)

    def test_unsafe_paths_rejected_on_create(self):
        for bad in ["../escape", "/abs", "a/../../b", "c:\\x"]:
            with self.assertRaises(PackError):
                create_pack(self.path, "p", "1", [(bad, b"x")])

    def test_signature_hook_can_reject(self):
        self._make([("data/x", b"ok")])
        with self.assertRaises(PackError):
            verify_pack(self.path, verify_signature=lambda _b: False)
        # and accept
        verify_pack(self.path, verify_signature=lambda _b: True)

    # --- helpers that rewrite the zip to simulate tampering ---
    def _members(self):
        with zipfile.ZipFile(self.path) as zf:
            return [(n, zf.read(n)) for n in zf.namelist()]

    def _save(self, members):
        with zipfile.ZipFile(self.path, "w") as zf:
            for n, d in members:
                zf.writestr(n, d)

    def _rewrite_member(self, name, data):
        self._save([(n, data if n == name else d) for n, d in self._members()])

    def _add_member(self, name, data):
        self._save(self._members() + [(name, data)])

    def _drop_member(self, name):
        self._save([(n, d) for n, d in self._members() if n != name])


if __name__ == "__main__":
    unittest.main()
