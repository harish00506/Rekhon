#!/usr/bin/env python3
"""The release train's consistency rules, tested without touching the repository (issue 12.5; §26).

Why:  `VERSION`, `versionCode` and `CHANGELOG.md` are bumped **by hand** on every issue, and nothing
      has ever checked that the three agree. They have already drifted once: the workflow says
      "starting a new Epic → minor bump +0.1.0", and Epic 11's seven issues shipped as `0.10.8` to
      `0.10.14` under the Epic 10 heading. Nobody noticed for seven issues, because noticing was
      somebody's job rather than the build's.

      Two of the failures this prevents are expensive in different ways. A `versionCode` that does not
      increase is rejected by Play **after** a release is cut. A version with no changelog entry ships
      with no release notes, and the gap is only visible to the person who looks for it later.
What: the SemVer parse, the epic-heading rule, the changelog entry, and `versionCode` monotonicity.
Result: the drift that already happened cannot happen silently again.

Changelog: 2026-10-03 — Created for issue 12.5.

Run with `python3 -m unittest discover -s scripts -p 'test_*.py'`, which `scriptTests` does.
"""
from __future__ import annotations

import unittest

from verify_release_metadata import (
    check_changelog_entry,
    check_epic_heading,
    check_issue_epic,
    check_version_code,
    parse_semver,
)


class ParseSemverTest(unittest.TestCase):
    """VERSION has to be a version before anything else can be said about it."""

    def test_a_plain_release_parses(self):
        self.assertEqual((0, 12, 5), parse_semver("0.12.5"))

    def test_surrounding_whitespace_is_tolerated(self):
        # The file is written by a script and read by a human; a trailing newline is normal.
        self.assertEqual((1, 0, 0), parse_semver(" 1.0.0\n"))

    def test_a_two_part_version_is_rejected(self):
        # "0.12" would make the epic check meaningless and Play reject the name.
        with self.assertRaises(ValueError):
            parse_semver("0.12")

    def test_a_non_numeric_part_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_semver("0.12.x")

    def test_a_four_part_version_is_rejected(self):
        # Pinned because the tuple unpacking below would reject it anyway: the regex is belt and
        # braces, and this records that the redundancy is deliberate rather than accidental.
        with self.assertRaises(ValueError):
            parse_semver("0.1.2.3")

    def test_an_empty_version_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_semver("")


class EpicHeadingTest(unittest.TestCase):
    """The rule that was actually broken: an epic's issues live under that epic's minor."""

    CHANGELOG = """# Changelog

## [0.12.0] — Epic 12: Quality, Testing & Release

### [0.12.5] — Issue 12.5: the release train  (2026-10-03)
- something

## [0.11.0] — Epic 11: Privacy

### [0.11.1] — Issue 11.1: keys  (2026-09-28)
- something
"""

    def test_an_entry_under_its_own_epic_heading_passes(self):
        self.assertEqual([], check_epic_heading(self.CHANGELOG, "0.12.5"))

    def test_a_drifted_entry_is_consistent_by_minor_which_is_why_this_check_is_not_enough(self):
        # Worth pinning rather than deleting: `0.10.11` under the `0.10.0` heading agrees by minor, so
        # this check passes it. The drift is that the *issue* is 11.4 — caught by `check_issue_epic`,
        # which exists because running the first implementation against the real CHANGELOG showed this
        # one calling the known-drifted releases consistent.
        drifted = """## [0.10.0] — Epic 10: Advisor

### [0.10.11] — Issue 11.4: erase everything  (2026-10-01)
- something
"""
        self.assertEqual([], check_epic_heading(drifted, "0.10.11"))

    def test_a_minor_that_does_not_match_its_heading_is_reported(self):
        wrong = """## [0.12.0] — Epic 12: Quality

### [0.11.9] — Issue 11.9: something  (2026-10-03)
- something
"""
        problems = check_epic_heading(wrong, "0.11.9")
        self.assertEqual(1, len(problems))
        self.assertIn("0.12.0", problems[0])

    def test_an_entry_with_no_epic_heading_above_it_is_reported(self):
        orphan = "# Changelog\n\n### [0.12.5] — Issue 12.5  (2026-10-03)\n- something\n"
        problems = check_epic_heading(orphan, "0.12.5")
        self.assertEqual(1, len(problems))
        self.assertIn("no epic heading", problems[0])


class IssueEpicTest(unittest.TestCase):
    """The rule that catches the drift that actually happened."""

    def test_an_issue_whose_epic_matches_the_minor_passes(self):
        text = "### [0.12.5] — Issue 12.5: the release train  (2026-10-03)\n"
        self.assertEqual([], check_issue_epic(text, "0.12.5"))

    def test_an_issue_from_a_later_epic_than_its_minor_is_reported(self):
        # The real case: Epic 11's issues shipped as 0.10.8-0.10.14 for seven releases.
        text = "### [0.10.11] — Issue 11.4: erase everything  (2026-10-01)\n"
        problems = check_issue_epic(text, "0.10.11")
        self.assertEqual(1, len(problems))
        self.assertIn("Issue 11.4", problems[0])
        self.assertIn("minor is 10", problems[0])

    def test_an_entry_naming_no_issue_is_skipped_rather_than_flagged(self):
        # An epic heading, a hotfix or a hand-written note need not cite an issue, and inventing a
        # rule for them would mean editing seventy historical entries to satisfy a checker.
        text = "### [0.12.5] — a hotfix for the thing  (2026-10-03)\n"
        self.assertEqual([], check_issue_epic(text, "0.12.5"))

    def test_a_version_with_no_entry_at_all_is_skipped_here(self):
        # Reported by `check_changelog_entry`; saying it twice would be noise.
        self.assertEqual([], check_issue_epic("### [0.12.4] — Issue 12.4: x\n", "0.12.5"))


class ChangelogEntryTest(unittest.TestCase):
    """A release with no notes is a release nobody can read."""

    def test_an_entry_that_exists_passes(self):
        text = "### [0.12.5] — Issue 12.5: the release train  (2026-10-03)\n- something\n"
        self.assertEqual([], check_changelog_entry(text, "0.12.5"))

    def test_a_missing_entry_is_reported(self):
        problems = check_changelog_entry("### [0.12.4] — Issue 12.4\n", "0.12.5")
        self.assertEqual(1, len(problems))
        self.assertIn("0.12.5", problems[0])

    def test_a_duplicated_entry_is_reported(self):
        # Two entries for one version means a copy-paste that will confuse anyone reading back.
        text = "### [0.12.5] — a\n### [0.12.5] — b\n"
        problems = check_changelog_entry(text, "0.12.5")
        self.assertEqual(1, len(problems))
        self.assertIn("twice", problems[0])

    def test_a_version_that_is_only_a_prefix_of_another_does_not_count(self):
        # `0.12.5` must not be satisfied by an entry for `0.12.50`.
        problems = check_changelog_entry("### [0.12.50] — a\n", "0.12.5")
        self.assertEqual(1, len(problems))


class VersionCodeTest(unittest.TestCase):
    """A versionCode that does not increase is rejected by Play after the release is cut."""

    LEDGER = """| Version | versionCode |
|---|---|
| 0.12.3 | 61 |
| 0.12.4 | 62 |
"""

    def test_a_new_version_with_a_higher_code_passes(self):
        self.assertEqual([], check_version_code(self.LEDGER, "0.12.5", 63))

    def test_a_code_that_did_not_move_is_reported(self):
        problems = check_version_code(self.LEDGER, "0.12.5", 62)
        self.assertEqual(1, len(problems))
        self.assertIn("62", problems[0])

    def test_a_code_that_went_backwards_is_reported(self):
        problems = check_version_code(self.LEDGER, "0.12.5", 60)
        self.assertEqual(1, len(problems))

    def test_a_version_already_in_the_ledger_must_keep_its_code(self):
        # Re-releasing 0.12.4 with a different code would make the ledger a lie.
        self.assertEqual([], check_version_code(self.LEDGER, "0.12.4", 62))
        problems = check_version_code(self.LEDGER, "0.12.4", 70)
        self.assertEqual(1, len(problems))
        self.assertIn("already", problems[0])

    def test_an_empty_ledger_accepts_any_positive_code(self):
        self.assertEqual([], check_version_code("| Version | versionCode |\n|---|---|\n", "0.1.0", 1))

    def test_a_non_positive_code_is_rejected_and_says_why(self):
        # The message is pinned, not just the count. A mutation removing the explicit guard still
        # rejects 0 — the monotonic check catches it anyway — so without this assertion the guard is
        # unkillable dead weight. It earns its place by saying "must be positive" instead of
        # "does not increase", which is a different fix for the reader.
        problems = check_version_code(self.LEDGER, "0.12.5", 0)
        self.assertEqual(1, len(problems))
        self.assertIn("must be positive", problems[0])


if __name__ == "__main__":
    unittest.main()
