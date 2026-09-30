#!/usr/bin/env python3
"""Validate every Android XML resource parses.

Why this exists: ParseSources.java only checks JAVA syntax, so a malformed resource XML
reached CI and failed the build at :app:mergeDebugResources. The specific defect was a "--"
used as a dash inside an XML comment, which is illegal XML but invisible to every offline
check the project had. This closes that gap by actually parsing each file the way AAPT does.

Run from the IRIS-Android directory:  python tests/check-xml.py
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOTS = [Path("app/src/main/res"), Path("app/src/main")]

def xml_files():
    seen = set()
    for root in ROOTS:
        if not root.exists():
            continue
        # Only the manifest from app/src/main itself; res/ is walked fully.
        pattern = "**/*.xml" if root.name == "res" else "AndroidManifest.xml"
        for path in root.glob(pattern):
            if path.is_file() and path not in seen:
                seen.add(path)
                yield path

def main():
    failures = []
    count = 0
    for path in sorted(xml_files()):
        count += 1
        try:
            ET.parse(path)
        except ET.ParseError as error:
            failures.append(f"{path}: {error}")

    if failures:
        print(f"XML validation FAILED for {len(failures)} of {count} file(s):")
        for line in failures:
            print(f"  {line}")
        return 1
    print(f"Passed XML validation for {count} resource files")
    return 0

if __name__ == "__main__":
    sys.exit(main())
