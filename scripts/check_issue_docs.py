#!/usr/bin/env python3
"""Verify the generated issue backlog is internally consistent.

Why
----
The backlog is ~170 generated files plus a CSV and a design spec. A silent drift (a renamed
issue, a dangling dependency, a broken down-link) would quietly rot the workflow. This checker
is the guard: it fails loudly the moment the CSV, the issue/tracker files, and the generator
disagree, so it can run in CI after `gen_issue_docs.py`.

What
----
Loads the embedded issue table from `gen_issue_docs.py` (the single source of truth), then asserts:
  1. CSV parity     - one `<id>-<slug>.md` and one `<id>-<slug>-tracker.md` per CSV row; the CSV
                      id set equals the generator id set; ids are unique.
  2. Dependencies   - every `Depends On` id is a real issue id.
  3. Sections       - each issue/tracker file contains its required `##` headings.
  4. Links          - every relative markdown link in an issue/tracker file resolves on disk.
  5. Counts         - reports epics / issues / files and checks the expected totals.

Result
------
Prints a PASS/FAIL report and returns exit code 0 (all good) or 1 (any failure) for CI.
Changed: 2026-07-18 - created.

Input
-----
No CLI args; reads the CSV and the docs/issues files. Output: report on stdout, exit code.
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS_DIR))
import gen_issue_docs as gen  # noqa: E402  (path set above)

try:
    sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
except Exception:  # pragma: no cover
    pass

EXPECTED_EPICS = 13
EXPECTED_ISSUES = 85

ISSUE_SECTIONS = [
    "## Description",
    "## Tracker",
    "## Acceptance Criteria",
    "## Skill Rules (load before coding)",
    "## Guiding Principles",
    "## Workflow Rules — Before Starting",
    "## Workflow Rules — While Solving",
    "## Workflow Rules — Definition of Done",
    "## Verification",
    "## Files Changed",
    "## Sub-tasks",
]
TRACKER_SECTIONS = ["## Status Summary", "## Phase Checklist", "## Verification Log"]

LINK_RE = re.compile(r"\]\(([^)]+)\)")


def check() -> int:
    """Run every consistency check and print a report.

    Why: one callable so CI can invoke the whole suite. What: executes the five check groups,
    accumulating failures. Result: returns 0 if clean, 1 otherwise. Changed: 2026-07-18 - created.
    Input: none. Output: exit code (int).
    """
    failures: list[str] = []

    gen_ids = [i.id for i in gen.ISSUES]
    gen_id_set = set(gen_ids)

    # --- 1. Uniqueness -----------------------------------------------------
    if len(gen_ids) != len(gen_id_set):
        failures.append("duplicate issue ids in the generator table")

    # --- 2. CSV parity -----------------------------------------------------
    if not gen.CSV_PATH.exists():
        failures.append(f"missing CSV: {gen.CSV_PATH}")
        csv_ids: set[str] = set()
    else:
        with gen.CSV_PATH.open(encoding="utf-8", newline="") as fh:
            rows = list(csv.DictReader(fh))
        csv_ids = {r["Issue ID"] for r in rows}
        if len(rows) != EXPECTED_ISSUES:
            failures.append(f"CSV has {len(rows)} rows, expected {EXPECTED_ISSUES}")
        if csv_ids != gen_id_set:
            failures.append(f"CSV ids != generator ids (symmetric diff: {sorted(csv_ids ^ gen_id_set)})")
        # every Depends On id is real
        for r in rows:
            for dep in (d for d in r["Depends On"].split(";") if d):
                if dep not in gen_id_set:
                    failures.append(f"issue {r['Issue ID']} depends on unknown id {dep}")

    # --- 3. Files exist + sections + links --------------------------------
    file_count = 0
    for issue in gen.ISSUES:
        slug = gen.slugify(issue.title)
        issue_path = gen.ISSUES_DIR / f"{issue.id}-{slug}.md"
        tracker_path = gen.ISSUES_DIR / f"{issue.id}-{slug}-tracker.md"

        for path, required in ((issue_path, ISSUE_SECTIONS), (tracker_path, TRACKER_SECTIONS)):
            if not path.exists():
                failures.append(f"missing file: {path.name}")
                continue
            file_count += 1
            text = path.read_text(encoding="utf-8")
            for section in required:
                if section not in text:
                    failures.append(f"{path.name}: missing section '{section}'")
            # relative links must resolve
            for target in LINK_RE.findall(text):
                if target.startswith(("http://", "https://", "#", "mailto:")):
                    continue
                rel = target.split("#", 1)[0]
                if not rel:
                    continue
                resolved = (gen.ISSUES_DIR / rel).resolve()
                if not resolved.exists():
                    failures.append(f"{path.name}: broken link -> {target}")

        # every issue depends only on real ids (generator side)
        for dep in issue.deps:
            if dep not in gen_id_set:
                failures.append(f"issue {issue.id} depends on unknown id {dep}")

    # --- 4. Design spec present -------------------------------------------
    design = gen.SPECS_DIR / "2026-07-17-ai-personal-cfo-design.md"
    if not design.exists():
        failures.append(f"missing design spec: {design.name}")

    # --- 4b. Every referenced skill file exists ---------------------------
    # The Skill Rules table renders each path as a code-span, not a markdown link, so the link
    # check above does not cover it. Verify each SKILL_REGISTRY path resolves (expanding ~ for
    # global skills; ROOT-joining relative project-skill paths).
    for name, (path, _when) in gen.SKILL_REGISTRY.items():
        resolved = Path(path).expanduser()
        if not resolved.is_absolute():
            resolved = gen.ROOT / path
        if not resolved.exists():
            failures.append(f"skill '{name}' path does not resolve: {path}")

    # --- 5. Counts ---------------------------------------------------------
    epic_count = len(gen.EPICS)
    expected_files = EXPECTED_ISSUES * 2

    print("AI Personal CFO - issue backlog check")
    print(f"  Epics:        {epic_count} (expected {EXPECTED_EPICS})")
    print(f"  Issues (gen): {len(gen_ids)} (expected {EXPECTED_ISSUES})")
    print(f"  CSV rows:     {len(csv_ids)}")
    print(f"  Files found:  {file_count} (expected {expected_files})")
    if epic_count != EXPECTED_EPICS:
        failures.append(f"epic count {epic_count} != {EXPECTED_EPICS}")
    if len(gen_ids) != EXPECTED_ISSUES:
        failures.append(f"issue count {len(gen_ids)} != {EXPECTED_ISSUES}")
    if file_count != expected_files:
        failures.append(f"file count {file_count} != {expected_files}")

    print()
    if failures:
        print(f"FAIL - {len(failures)} problem(s):")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("PASS - CSV parity, dependencies, sections, links, and counts all consistent.")
    return 0


if __name__ == "__main__":
    raise SystemExit(check())
