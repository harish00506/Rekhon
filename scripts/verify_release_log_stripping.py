#!/usr/bin/env python3
"""Proves the release APK contains no chatty log call (issue 11.6; §21.6, SEC-007).

Why:  §21.6 bans PII and amounts from logs, and `app/proguard-rules.pro` strips `Log.v/d/i/w` and
      `println` from the release build so there is no release log surface to get wrong. That is a
      claim about a binary, and a claim about a binary that nobody checks is the exact shape of
      defect this project keeps finding — a rule file can be edited, a build type can be flipped,
      and the APK would still ship while every test stayed green.

      So this reads the shipped DEX and fails if a stripped method is referenced at all.

What: parses each `classes*.dex` in the APK, resolves its `method_id` table, and reports any
      reference to the methods the release is supposed to have removed.
Result: exit 0 when the strip held; exit 1 naming each surviving method.

Changelog: 2026-10-02 — Created for issue 11.6.

**Why a parser rather than `dexdump`:** `dexdump` lives under a versioned `build-tools` path that CI
would have to pin and that differs per machine. This needs only the standard library. It was
validated against `dexdump -d` on this app's own release APK: both agree that `v`, `d`, `i`, `w` and
`isLoggable` are absent and that `e` (128 call sites) and `wtf` (9) are present — see the issue
tracker's verification log.

**What "referenced" means.** R8 emits a `method_id` only for a method some instruction actually
refers to, so an absent id means no call site survived. The converse is the conservative direction:
if an id is present this fails, even in the unlikely case that nothing calls it. A false alarm costs
a reader five minutes; a missed one ships the thing §21.6 forbids.
"""
from __future__ import annotations

import struct
import sys
import zipfile
from pathlib import Path

#: Methods the release build must not reference, as {class descriptor: {method names}}.
#:
#: `Log.e` and `Log.wtf` are deliberately absent from this set: an error path that cannot say
#: anything is a release nobody can diagnose, and the `CfoPiiInLogs` lint rule already blocks PII in
#: their arguments at compile time. The levels listed here are the ones that carry chatter.
STRIPPED = {
    "Landroid/util/Log;": {"v", "d", "i", "w", "isLoggable"},
    "Ljava/io/PrintStream;": {"println"},
}


def read_u32(data: bytes, offset: int) -> int:
    """Reads a little-endian uint32.

    Why:    DEX is little-endian regardless of host, so this never uses the native byte order.
    Result: the integer at [offset, offset+4).
    Input:  data — the DEX bytes; offset — a byte offset. Output: int.
    Changelog: 2026-10-02 — Created for issue 11.6.
    """
    return struct.unpack_from("<I", data, offset)[0]


def read_uleb128(data: bytes, offset: int) -> tuple[int, int]:
    """Reads a ULEB128 integer, as DEX uses for string lengths.

    Why:    string_data_item begins with a ULEB128 UTF-16 length; the bytes follow it.
    Result: (value, offset just past the encoded integer).
    Input:  data; offset. Output: a (value, next_offset) tuple.
    Changelog: 2026-10-02 — Created for issue 11.6.
    """
    value = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7


def dex_strings(data: bytes) -> list[str]:
    """Every string in a DEX's string pool, in index order.

    Why:    class descriptors and method names are string-pool entries, so the pool has to be
            resolved before any `method_id` means anything.
    Result: the decoded strings. MUTF-8 is decoded as UTF-8 with replacement — this only ever
            compares against ASCII identifiers, so a lossy decode of an exotic string is harmless
            and is better than aborting the check over one.
    Input:  data — the whole DEX. Output: a list of str.
    Changelog: 2026-10-02 — Created for issue 11.6.
    """
    count = read_u32(data, 0x38)
    ids_off = read_u32(data, 0x3C)
    strings = []
    for index in range(count):
        data_off = read_u32(data, ids_off + index * 4)
        _, start = read_uleb128(data, data_off)
        end = data.index(b"\x00", start)
        strings.append(data[start:end].decode("utf-8", "replace"))
    return strings


def referenced_methods(data: bytes) -> set[tuple[str, str]]:
    """Every (class descriptor, method name) a DEX refers to.

    Why:    the whole check. A `method_id` exists only for a method some instruction references, so
            this set is what the APK can actually call.
    Result: the pairs, e.g. `("Landroid/util/Log;", "e")`.
    Input:  data — the whole DEX. Output: a set of (str, str).
    Changelog: 2026-10-02 — Created for issue 11.6.

    The type_id table maps a type index to a string index; a method_id is
    `(class_idx: u16, proto_idx: u16, name_idx: u32)`. The prototype is irrelevant here — an
    overload is as forbidden as the method it overloads.
    """
    strings = dex_strings(data)
    type_count = read_u32(data, 0x40)
    type_ids_off = read_u32(data, 0x44)
    types = [strings[read_u32(data, type_ids_off + i * 4)] for i in range(type_count)]

    method_count = read_u32(data, 0x58)
    method_ids_off = read_u32(data, 0x5C)
    found = set()
    for index in range(method_count):
        entry = method_ids_off + index * 8
        class_idx, _proto_idx, name_idx = struct.unpack_from("<HHI", data, entry)
        found.add((types[class_idx], strings[name_idx]))
    return found


def survivors(apk: Path) -> list[str]:
    """The stripped methods an APK still references.

    Why:    the verdict, kept separate from how it is printed so a test can assert on it.
    Result: a sorted list of `"class->method"`, empty when the strip held.
    Input:  apk — the path to the APK. Output: a list of str.
    Changelog: 2026-10-02 — Created for issue 11.6.
    """
    found: set[tuple[str, str]] = set()
    with zipfile.ZipFile(apk) as archive:
        dex_names = [n for n in archive.namelist() if n.startswith("classes") and n.endswith(".dex")]
        if not dex_names:
            raise SystemExit(f"{apk} contains no classes.dex — not an APK?")
        for name in dex_names:
            found |= referenced_methods(archive.read(name))
    return sorted(
        f"{owner}->{method}"
        for owner, method in found
        if method in STRIPPED.get(owner, ())
    )


def main(argv: list[str]) -> int:
    """Checks one APK and reports.

    Result: 0 when clean, 1 when a stripped method survived, 2 on bad usage.
    Input:  argv — `[apk_path]`. Output: the process exit code.
    Changelog: 2026-10-02 — Created for issue 11.6.
    """
    if len(argv) != 1:
        print("usage: verify_release_log_stripping.py <release.apk>", file=sys.stderr)
        return 2
    apk = Path(argv[0])
    if not apk.is_file():
        print(f"error: {apk} does not exist — assemble the release build first", file=sys.stderr)
        return 2
    remaining = survivors(apk)
    if remaining:
        print(f"error: the release build still references {len(remaining)} stripped method(s):")
        for entry in remaining:
            print(f"  {entry}")
        print("\nThe release is supposed to have no chatty log surface at all (§21.6).")
        print("Check app/proguard-rules.pro and that `isMinifyEnabled = true` for release.")
        return 1
    print(f"OK: {apk.name} references none of the stripped log methods (§21.6, SEC-007).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
