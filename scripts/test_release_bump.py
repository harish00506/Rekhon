#!/usr/bin/env python3
"""The version arithmetic the release train performs (issue 12.5; §26).

Why:  `next_version` encodes the project's own rule — a new epic is a minor bump that resets the
      patch — and getting it wrong is precisely what happened before: Epic 11's issues continued the
      `0.10.x` series instead of starting `0.11.0`. The arithmetic is three lines, which is exactly
      the kind of code people decline to test and then get wrong once.
What: each bump kind, and the refusal of anything else.
Result: the rule is pinned where it is implemented, not only where it is written down.

Changelog: 2026-10-03 — Created for issue 12.5.
"""
from __future__ import annotations

import unittest

from release import next_version, next_version_code


class NextVersionTest(unittest.TestCase):
    def test_a_patch_bump_increments_the_patch(self):
        self.assertEqual("0.12.5", next_version("0.12.4", "patch"))

    def test_a_minor_bump_resets_the_patch(self):
        # The rule Epic 11 broke: a new epic starts at `.0`, it does not continue the old series.
        self.assertEqual("0.13.0", next_version("0.12.4", "minor"))

    def test_a_major_bump_resets_minor_and_patch(self):
        self.assertEqual("1.0.0", next_version("0.12.4", "major"))

    def test_whitespace_around_the_current_version_is_tolerated(self):
        # VERSION is a one-line file and ends with a newline.
        self.assertEqual("0.12.5", next_version(" 0.12.4\n", "patch"))

    def test_an_unknown_bump_is_refused(self):
        with self.assertRaises(ValueError):
            next_version("0.12.4", "epic")


class NextVersionCodeTest(unittest.TestCase):
    LEDGER = "| Version | versionCode |\n|---|---|\n| 0.12.3 | 61 |\n| 0.12.4 | 62 |\n"

    def test_it_is_one_above_the_highest_recorded(self):
        self.assertEqual(63, next_version_code(self.LEDGER))

    def test_the_highest_wins_even_when_the_ledger_is_out_of_order(self):
        # Ten historical releases share a code with the one before them, so "the last row" and "the
        # highest" are genuinely different numbers in this repository's ledger.
        out_of_order = "| 0.3.10 | 18 |\n| 0.3.11 | 18 |\n| 0.4.0 | 18 |\n| 0.4.1 | 19 |\n"
        self.assertEqual(20, next_version_code(out_of_order))

    def test_an_empty_ledger_starts_at_one(self):
        self.assertEqual(1, next_version_code("| Version | versionCode |\n|---|---|\n"))


if __name__ == "__main__":
    unittest.main()
