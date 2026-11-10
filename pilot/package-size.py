"""Compare two Yarn package archives offline without extracting their contents."""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import tarfile

BUDGET_BYTES = 256 * 1024
PACKAGE_NAME = "react-native-receipt-scanner"


class StrictTarInfo(tarfile.TarInfo):
    """Do not let TarFile.next silently accept invalid headers as EOF."""

    @classmethod
    def fromtarfile(cls, archive):
        try:
            return super().fromtarfile(archive)
        except tarfile.EOFHeaderError:
            # Require both zero end blocks and reject hidden trailing payload.
            if archive.fileobj.read(512) != bytes(512):
                raise tarfile.ReadError("Missing tar end blocks") from None
            for block in iter(lambda: archive.fileobj.read(1024 * 1024), b""):
                if any(block):
                    raise tarfile.ReadError("Nonzero data after tar end blocks")
            # Reading through gzip EOF also verifies CRC and the size trailer.
            raise
        except tarfile.HeaderError as error:
            raise tarfile.ReadError(str(error)) from None


def inspect_archive(filename):
    archive = Path(filename).resolve(strict=True)
    digest = hashlib.sha256()
    with archive.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    files = {}
    manifest = None
    with tarfile.open(archive, "r:gz", tarinfo=StrictTarInfo) as package:
        for member in package:
            parts = PurePosixPath(member.name).parts
            if (
                not parts
                or parts[0] != "package"
                or ".." in parts
                or member.name.startswith("/")
            ):
                raise ValueError(f"Invalid archive member: {member.name}")
            if member.name != "/".join(parts):
                raise ValueError(f"Noncanonical archive member: {member.name}")
            if member.isdir():
                continue
            if not member.isfile() or member.name in files:
                raise ValueError(f"Unsupported or duplicate member: {member.name}")
            stream = package.extractfile(member)
            if stream is None:
                raise ValueError(f"Unreadable member: {member.name}")
            # Read actual bytes so a truncated payload cannot count as evidence.
            with stream:
                size = 0
                chunks = []
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    size += len(block)
                    if member.name == "package/package.json":
                        chunks.append(block)
                if size != member.size:
                    raise ValueError(f"Truncated member: {member.name}")
                if member.name == "package/package.json":
                    manifest = json.loads(b"".join(chunks))
            files[member.name] = size
    if not isinstance(manifest, dict) or manifest.get("name") != PACKAGE_NAME:
        raise ValueError("Archive must contain the receipt scanner package manifest")
    return {
        "archive": str(archive),
        "sha256": digest.hexdigest(),
        "compressedBytes": archive.stat().st_size,
        "unpackedBytes": sum(files.values()),
        "files": dict(sorted(files.items())),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", help="Baseline yarn pack .tgz")
    parser.add_argument("candidate", help="Candidate yarn pack .tgz")
    args = parser.parse_args()
    try:
        baseline = inspect_archive(args.baseline)
        candidate = inspect_archive(args.candidate)
        growth = {
            key: candidate[key] - baseline[key]
            for key in ("unpackedBytes", "compressedBytes")
        }
        passed = growth["unpackedBytes"] <= BUDGET_BYTES
        print(json.dumps({
            "schemaVersion": 1,
            "baseline": baseline,
            "candidate": candidate,
            "growth": growth,
            "budgetBytes": BUDGET_BYTES,
            "passed": passed,
        }, indent=2))
        return 0 if passed else 1
    except (OSError, ValueError, tarfile.TarError, EOFError) as error:
        print(f"Package size measurement failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
