#!/usr/bin/env python3
"""Fail release builds if bundled arm64 ELF LOAD segments require 4 KB pages."""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path

MIN_ALIGNMENT = 16 * 1024


def load_alignments(elf: bytes) -> list[int]:
    if len(elf) < 64 or elf[:4] != b"\x7fELF" or elf[4] != 2 or elf[5] != 1:
        raise ValueError("expected a little-endian ELF64 library")
    phoff = struct.unpack_from("<Q", elf, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", elf, 54)
    if phentsize < 56 or phnum == 0 or phoff + phentsize * phnum > len(elf):
        raise ValueError("invalid ELF program header table")
    aligns = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        if struct.unpack_from("<I", elf, offset)[0] == 1:  # PT_LOAD
            aligns.append(struct.unpack_from("<Q", elf, offset + 48)[0])
    if not aligns:
        raise ValueError("ELF has no LOAD segments")
    return aligns


def check_archive(path: Path) -> tuple[int, list[str]]:
    issues = []
    checked = 0
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            parts = name.split("/")
            if len(parts) < 3 or parts[-2] != "arm64-v8a" or not name.endswith(".so"):
                continue
            checked += 1
            try:
                aligns = load_alignments(archive.read(name))
            except ValueError as error:
                issues.append(f"{path.name}:{name}: {error}")
                continue
            if any(align < MIN_ALIGNMENT for align in aligns):
                issues.append(f"{path.name}:{name}: LOAD alignment {', '.join(hex(v) for v in aligns)}; requires at least 0x4000")
    return checked, issues


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("archives", type=Path, nargs="+")
    args = parser.parse_args()
    total = 0
    issues = []
    for path in args.archives:
        count, archive_issues = check_archive(path)
        total += count
        issues.extend(archive_issues)
    if total == 0:
        issues.append("No arm64 native libraries were checked")
    for issue in issues:
        print(issue, file=sys.stderr)
    if issues:
        return 1
    print(f"16 KB ELF alignment verified for {total} arm64 libraries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
