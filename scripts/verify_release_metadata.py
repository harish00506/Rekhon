#!/usr/bin/env python3
"""Checks that VERSION, versionCode and CHANGELOG.md agree (issue 12.5; §26, SemVer).

Why:  these three are bumped **by hand** on every issue, and until this script nothing checked that
      they agree. They have already drifted: `docs/issues/00-issue-workflow.md` says "starting a new
      Epic → minor bump +0.1.0", and Epic 11's seven issues shipped as `0.10.8` to `0.10.14` under
      the Epic 10 heading. Seven releases, nobody noticed — because noticing was a person's job
      rather than the build's, which is the failure mode this repository has now found seven times.

      The two concrete costs: a `versionCode` that does not increase is rejected by Play **after** a
      release is cut, and a version with no changelog entry ships with no release notes.
What: SemVer parse · the entry exists exactly once · it sits under its own epic's heading ·
      `versionCode` is positive, monotonic and stable for a version already released.
Result: exit 0 when the release metadata is coherent; 1 listing every problem.

Changelog: 2026-10-03 — Created for issue 12.5.

**It reports every problem, not the first.** Someone fixing a release bump wants the whole list in one
run, not a game of whack-a-mole across four files.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

#: `### [0.12.5] — …` — one released issue.
ENTRY = re.compile(r"^### \[(\d+\.\d+\.\d+)\]", re.M)

#: `### [0.12.5] — Issue 12.5: …` — the issue id an entry claims to deliver.
ENTRY_ISSUE = re.compile(r"^### \[(\d+\.\d+\.\d+)\][^\n]*?Issue (\d+)\.(\d+)", re.M)

#: `## [0.12.0] — Epic 12: …` — the epic a run of entries belongs to.
EPIC = re.compile(r"^## \[(\d+\.\d+\.\d+)\]", re.M)

#: `| 0.12.4 | 62 |` — one row of the release ledger.
LEDGER_ROW = re.compile(r"^\|\s*(\d+\.\d+\.\d+)\s*\|\s*(\d+)\s*\|", re.M)

#: `versionCode = 62` in the app's build file.
VERSION_CODE = re.compile(r"versionCode\s*=\s*(\d+)")


def parse_semver(raw: str) -> tuple[int, int, int]:
    """Parses `major.minor.patch`.

    Why:    everything else here is stated in terms of the minor, so an unparseable version has to
            stop the run rather than be guessed at.
    Result: the three numbers. Input: [raw] — the file's contents, whitespace tolerated.
    Output: a (major, minor, patch) tuple.
    Changelog: 2026-10-03 — Created for issue 12.5.

    Raises [ValueError] on anything that is not three dot-separated integers.
    """
    text = raw.strip()
    if not re.fullmatch(r"\d+\.\d+\.\d+", text):
        raise ValueError(f"VERSION must be major.minor.patch; got {text!r}")
    major, minor, patch = (int(part) for part in text.split("."))
    return major, minor, patch


def check_changelog_entry(changelog: str, version: str) -> list[str]:
    """Checks the version has exactly one changelog entry.

    Why:    a release with no notes is a release nobody can read back, and two entries for one
            version is a copy-paste that misleads whoever reads it later.
    Result: a list of problems, empty when the entry is present once.
    Input:  [changelog] — the file's contents; [version] — the version under release.
    Output: a list of str.
    Changelog: 2026-10-03 — Created for issue 12.5.
    """
    found = [match for match in ENTRY.findall(changelog) if match == version]
    if not found:
        return [f"CHANGELOG.md has no `### [{version}]` entry — the release would ship with no notes"]
    if len(found) > 1:
        return [f"CHANGELOG.md lists `### [{version}]` twice ({len(found)} times)"]
    return []


def check_epic_heading(changelog: str, version: str) -> list[str]:
    """Checks the entry sits under the epic heading whose minor it shares.

    Why:    **this is the rule that was actually broken.** The workflow says an epic starts with a
            minor bump, so an issue of Epic N belongs under the `## [0.N.0] — Epic N` heading. Epic
            11's issues sat under the Epic 10 heading for seven releases. Comparing the minor of the
            entry with the minor of the nearest heading above it is what would have caught that on
            the first one.
    Result: a list of problems, empty when the entry is under a heading of the same minor.
    Input:  [changelog]; [version]. Output: a list of str.
    Changelog: 2026-10-03 — Created for issue 12.5.
    """
    entry = next((m for m in ENTRY.finditer(changelog) if m.group(1) == version), None)
    if entry is None:
        # Reported by `check_changelog_entry`; saying it twice would be noise.
        return []
    headings = [m for m in EPIC.finditer(changelog) if m.start() < entry.start()]
    if not headings:
        return [f"`{version}` has no epic heading above it in CHANGELOG.md"]
    heading = headings[-1].group(1)
    if heading.split(".")[1] != version.split(".")[1]:
        return [
            f"`{version}` sits under the `{heading}` epic heading. An epic starts with a minor bump "
            f"(`00-issue-workflow.md`, Versioning), so an issue of that epic belongs under a heading "
            f"with the same minor. This is the drift that put Epic 11's releases under Epic 10."
        ]
    return []


def check_issue_epic(changelog: str, version: str) -> list[str]:
    """Checks the entry's issue number belongs to the epic its minor names.

    Why:  **this is the check that catches the drift that actually happened, and the first version of
          this script did not.** Comparing the entry's minor with the heading's minor passes happily
          for `### [0.10.11] — Issue 11.4`, because both say 10. The thing that was wrong is that an
          issue of **Epic 11** shipped under minor **10**: the workflow says an epic starts with a
          minor bump, so issue `N.x` belongs at version `0.N.y`.

          Found by running the first implementation against the repository's own history and seeing
          it call the known-drifted releases "consistent".
    Result: a list of problems, empty when the issue's epic matches the version's minor.
    Input:  [changelog]; [version]. Output: a list of str.
    Changelog: 2026-10-03 — Created for issue 12.5.

    An entry whose title names no issue is skipped rather than flagged: an epic heading, a hotfix or a
    hand-written note is not required to cite one, and inventing a rule for them would mean editing
    seventy historical entries to satisfy a checker.
    """
    entry = next((m for m in ENTRY_ISSUE.finditer(changelog) if m.group(1) == version), None)
    if entry is None:
        return []
    epic = entry.group(2)
    minor = version.split(".")[1]
    if epic != minor:
        return [
            f"`{version}` delivers Issue {epic}.{entry.group(3)}, but its minor is {minor}. An epic "
            f"starts with a minor bump (`00-issue-workflow.md`, Versioning), so Epic {epic}'s issues "
            f"belong at `0.{epic}.y`. This is exactly the drift that put Epic 11's seven releases in "
            f"the 0.10.x series."
        ]
    return []


def check_version_code(ledger: str, version: str, version_code: int) -> list[str]:
    """Checks `versionCode` is positive, monotonic, and stable for an already-released version.

    Why:    Play rejects an upload whose `versionCode` did not increase, and it does so **after** a
            release has been cut and tagged. The ledger makes the history checkable without asking
            git, which a shallow CI clone cannot answer.
    Result: a list of problems, empty when the code is acceptable.
    Input:  [ledger] — `docs/releases.md`'s contents; [version]; [version_code] — from the build file.
    Output: a list of str.
    Changelog: 2026-10-03 — Created for issue 12.5.
    """
    if version_code < 1:
        return [f"versionCode must be positive; got {version_code}"]
    rows = {ver: int(code) for ver, code in LEDGER_ROW.findall(ledger)}
    if version in rows:
        if rows[version] != version_code:
            return [
                f"`{version}` is already in docs/releases.md with versionCode {rows[version]}, but "
                f"the build says {version_code}. A released version's code cannot change."
            ]
        return []
    highest = max(rows.values(), default=0)
    if version_code <= highest:
        return [
            f"versionCode {version_code} does not increase — docs/releases.md already records "
            f"{highest}. Play rejects an upload whose code did not move, after the release is cut."
        ]
    return []


def main(argv: list[str]) -> int:
    """Checks the repository's release metadata.

    Result: 0 when coherent, 1 listing every problem, 2 on a missing file.
    Input:  argv — optionally the repository root. Output: the exit code.
    Changelog: 2026-10-03 — Created for issue 12.5.
    """
    root = Path(argv[0]) if argv else Path(".")
    try:
        version_raw = (root / "VERSION").read_text(encoding="utf-8")
        changelog = (root / "CHANGELOG.md").read_text(encoding="utf-8")
        build_file = (root / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        ledger = (root / "docs" / "releases.md").read_text(encoding="utf-8")
    except OSError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    try:
        parse_semver(version_raw)
    except ValueError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    version = version_raw.strip()

    match = VERSION_CODE.search(build_file)
    if match is None:
        print("error: no `versionCode = <n>` in app/build.gradle.kts", file=sys.stderr)
        return 1
    version_code = int(match.group(1))

    problems = (
        check_changelog_entry(changelog, version)
        + check_epic_heading(changelog, version)
        + check_issue_epic(changelog, version)
        + check_version_code(ledger, version, version_code)
    )
    if problems:
        print(f"error: release metadata for {version} (versionCode {version_code}) is inconsistent:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"OK: {version} / versionCode {version_code} — VERSION, CHANGELOG and the ledger agree (§26).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
