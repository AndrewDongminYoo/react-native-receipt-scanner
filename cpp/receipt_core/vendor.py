"""Pin or verify a source-only snapshot without fetching during a build."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent
FILES = (
    "CMakeLists.txt",
    "LICENSE",
    "README.md",
    "receipt_core.cpp",
    "receipt_core.h",
    "tests/c_header_test.c",
    "tests/core_test.cpp",
    "tests/corpus_driver.cpp",
    "tests/corpus_runner.js",
    "tests/vendor_test.py",
    "vendor.py",
)
REPOSITORY = "https://github.com/AndrewDongminYoo/react-native-receipt-scanner"
DIRECTORY = "cpp/receipt_core"


def upstream_matches(revision):
    for name in FILES:
        expected = subprocess.check_output(
            ["git", "-C", str(ROOT), "show", f"{revision}:{DIRECTORY}/{name}"]
        )
        if expected != (ROOT / name).read_bytes():
            raise ValueError(f"{name} differs from pinned source commit")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--pin")
    mode.add_argument("--check", action="store_true")
    parser.add_argument("--upstream-check", action="store_true")
    args = parser.parse_args()
    manifest_path = ROOT / "manifest.json"
    actual = {entry.relative_to(ROOT).as_posix() for entry in ROOT.rglob("*")
              if entry.is_file() or entry.is_symlink()}
    if actual - {"manifest.json"} != set(FILES):
        raise ValueError("snapshot file inventory mismatch")
    hashes = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in FILES}
    if args.pin is not None:
        if not re.fullmatch(r"[0-9a-f]{40}", args.pin):
            raise ValueError("sourceCommit must be a full immutable Git SHA")
        upstream_matches(args.pin)
        manifest = dict(schemaVersion=1, abiVersion=1, sourceRepository=REPOSITORY,
                        sourceDirectory=DIRECTORY, sourceCommit=args.pin, files=hashes)
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    else:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        expected = dict(schemaVersion=1, abiVersion=1, sourceRepository=REPOSITORY,
                        sourceDirectory=DIRECTORY, sourceCommit=manifest.get("sourceCommit"), files=hashes)
        if not re.fullmatch(r"[0-9a-f]{40}", manifest.get("sourceCommit") or ""):
            raise ValueError("sourceCommit must be a full immutable Git SHA")
        if manifest != expected:
            raise ValueError("source manifest metadata or checksum mismatch")
        if args.upstream_check:
            upstream_matches(manifest["sourceCommit"])
    print(f"Receipt core ABI v1 verified at {manifest['sourceCommit']}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"receipt_core: {error}") from error
