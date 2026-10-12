#!/usr/bin/env python3
"""Package a verified dist JAR with portable launchers and matching source."""

import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
VERSION = ET.parse(ROOT / "pom.xml").getroot().findtext("{http://maven.apache.org/POM/4.0.0}version")
PREFIX = f"pdf-extractor-standalone-v{VERSION}"
DIST = ROOT / "dist"
JAR = DIST / "pdf-extractor-standalone.jar"


def archive(path, files):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as output:
        for source in sorted(files):
            relative = source.relative_to(ROOT)
            entry = zipfile.ZipInfo(f"{PREFIX}/{relative.as_posix()}")
            entry.compress_type = zipfile.ZIP_DEFLATED
            mode = 0o755 if source.suffix == ".sh" or source.name == "mvnw" else 0o644
            entry.external_attr = (0o100000 | mode) << 16
            output.writestr(entry, source.read_bytes())


def main():
    if not JAR.is_file():
        raise SystemExit("Build and verify dist/pdf-extractor-standalone.jar first")
    portable = DIST / f"{PREFIX}-portable.zip"
    source = DIST / f"{PREFIX}-source.zip"
    archive(portable, [JAR, ROOT / "README.md", *ROOT.glob("scripts/extract-pdf.*")])
    files = [ROOT / name for name in ("pom.xml", "README.md", "mvnw", "mvnw.cmd")]
    files.extend([ROOT / ".github/workflows/verify.yml", ROOT / f"docs/releases/v{VERSION}.md"])
    for directory in ("src", "scripts", ".mvn"):
        files.extend(path for path in (ROOT / directory).rglob("*") if path.is_file())
    archive(source, files)
    checksums = DIST / f"{PREFIX}-sha256.txt"
    checksums.write_text(
        "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
                for path in (JAR, portable, source)),
        encoding="utf-8",
    )
    for path in (JAR, portable, source, checksums):
        print(f"{path.name}: {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
