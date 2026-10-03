#!/usr/bin/env python3
"""Bumps the version in the four places that must agree (issue 12.5; §26, SemVer).

Why:  `VERSION`, `app/build.gradle.kts`'s `versionCode`, `CHANGELOG.md` and `docs/releases.md` are
      four files that have to move together, and doing it by hand has already failed twice in this
      repository's history — seven releases numbered into the wrong epic, and ten with a
      `versionCode` that did not increase. A script cannot make the decision about *what* a release
      contains, but it can stop the mechanical half going wrong.
What: computes the next version for a `patch`, `minor` (a new epic) or `major` bump, writes it to
      `VERSION`, increments `versionCode`, appends the ledger row, and tells you what to write by hand.
Result: the four files agree by construction, and `verifyReleaseMetadata` confirms it.

Changelog: 2026-10-03 — Created for issue 12.5.

**It does not write the changelog entry, and it does not tag.** The entry is the one part that has to
be written by a person — it is the release notes, and a generated line saying "issue 12.5 shipped"
would be worse than none. Tagging is left to the promotion, where `stage`/`main` are protected and a
human is already in the loop; the command is printed rather than run (`00-issue-workflow.md` §7).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

VERSION_CODE = re.compile(r"(versionCode\s*=\s*)(\d+)")
LEDGER_ROW = re.compile(r"^\|\s*(\d+\.\d+\.\d+)\s*\|\s*(\d+)\s*\|", re.M)


def next_version(current: str, bump: str) -> str:
    """The version after this one.

    Why:    the project's own rule, in one place: a new epic is a minor bump and resets the patch
            (`00-issue-workflow.md`, Versioning), finishing an issue is a patch bump. Encoding it here
            is what stops the next epic starting in the previous epic's series, as Epic 11's did.
    Result: the new version string. Input: [current] — e.g. `0.12.4`; [bump] — patch, minor or major.
    Output: [str].
    Changelog: 2026-10-03 — Created for issue 12.5.
    """
    major, minor, patch = (int(part) for part in current.strip().split("."))
    if bump == "patch":
        return f"{major}.{minor}.{patch + 1}"
    if bump == "minor":
        return f"{major}.{minor + 1}.0"
    if bump == "major":
        return f"{major + 1}.0.0"
    raise ValueError(f"bump must be patch, minor or major; got {bump!r}")


def next_version_code(ledger: str) -> int:
    """One above the highest code the ledger records.

    Why:    the number is not a judgement call, and leaving it to a person is how ten releases ended
            up sharing a code with the one before them.
    Result: the next code. Input: [ledger] — `docs/releases.md`'s contents. Output: [int].
    Changelog: 2026-10-03 — Created for issue 12.5.
    """
    codes = [int(code) for _, code in LEDGER_ROW.findall(ledger)]
    return max(codes, default=0) + 1


def main(argv: list[str]) -> int:
    """Performs the bump.

    Result: 0 on success, 2 on bad usage. Input: argv — `[patch|minor|major]`, optionally a root.
    Output: the exit code.
    Changelog: 2026-10-03 — Created for issue 12.5.
    """
    if not 1 <= len(argv) <= 2 or argv[0] not in {"patch", "minor", "major"}:
        print("usage: release.py <patch|minor|major> [repo-root]", file=sys.stderr)
        return 2
    bump = argv[0]
    root = Path(argv[1]) if len(argv) == 2 else Path(".")

    version_file = root / "VERSION"
    build_file = root / "app" / "build.gradle.kts"
    ledger_file = root / "docs" / "releases.md"

    current = version_file.read_text(encoding="utf-8").strip()
    new_version = next_version(current, bump)
    ledger = ledger_file.read_text(encoding="utf-8")
    new_code = next_version_code(ledger)

    version_file.write_text(new_version + "\n", encoding="utf-8")
    build_file.write_text(
        VERSION_CODE.sub(lambda m: f"{m.group(1)}{new_code}", build_file.read_text(encoding="utf-8"), count=1),
        encoding="utf-8",
    )
    ledger_file.write_text(ledger.rstrip("\n") + f"\n| {new_version} | {new_code} |  |\n", encoding="utf-8")

    heading = f"0.{new_version.split('.')[1]}.0"
    print(f"{current} → {new_version}, versionCode {new_code}")
    print()
    steps = []
    if bump == "minor":
        steps.append(f"This is a new epic — add the `## [{heading}] — Epic N: <name>` heading first.")
    steps.append(
        f"Add `### [{new_version}] — Issue <id>: <title>  (YYYY-MM-DD)` to CHANGELOG.md, under the "
        f"`## [{heading}] — Epic …` heading. The notes are the one part a person has to write; a "
        "generated line would be worse than none."
    )
    steps.append("./gradlew verifyReleaseMetadata     # confirms the four files agree")
    steps.append(
        f"On promotion, tag it:  git tag -a v{new_version} -m 'Release {new_version}'  — not done "
        "here, because `stage`/`main` are protected and a human is already in the loop for the "
        "promotion (00-issue-workflow.md §7)."
    )
    print("Still to do by hand — deliberately:")
    for index, step in enumerate(steps, start=1):
        print(f"  {index}. {step}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
