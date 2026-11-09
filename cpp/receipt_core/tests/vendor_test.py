"""Exercise the offline source snapshot verifier with isolated synthetic copies."""

import hashlib
import json
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CONFIG = runpy.run_path(str(ROOT / "vendor.py"))


class VendorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="receipt-core-vendor-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in CONFIG["FILES"]:
            destination = self.root / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, destination)
        # Offline integrity fixture: this synthetic SHA makes no upstream claim.
        manifest = dict(schemaVersion=1, abiVersion=1,
                        sourceRepository=CONFIG["REPOSITORY"],
                        sourceDirectory=CONFIG["DIRECTORY"], sourceCommit="0" * 40,
                        files={name: hashlib.sha256((self.root / name).read_bytes()).hexdigest()
                               for name in CONFIG["FILES"]})
        (self.root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    def invoke(self, *args):
        return subprocess.run([sys.executable, str(self.root / "vendor.py"), *args],
                              capture_output=True, text=True, check=False)

    def test_offline_snapshot(self):
        self.assertEqual(self.invoke("--check").returncode, 0)

    def test_empty_pin_is_invalid(self):
        result = self.invoke("--pin", "")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("sourceCommit must be a full immutable Git SHA", result.stderr)

    def test_unlisted_file_is_invalid(self):
        (self.root / "extra.cpp").write_text("// Unrecorded source.\n", encoding="utf-8")
        result = self.invoke("--check")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("snapshot file inventory mismatch", result.stderr)

    def test_corrupted_source_is_invalid(self):
        with (self.root / "receipt_core.cpp").open("a", encoding="utf-8") as source:
            source.write("\n// Corrupted source.\n")
        result = self.invoke("--check")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("checksum mismatch", result.stderr)


if __name__ == "__main__":
    unittest.main()
