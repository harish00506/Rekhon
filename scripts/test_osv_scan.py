#!/usr/bin/env python3
"""The vulnerability scan's policy, tested without a network (issue 11.6; SEC-007).

Why:  a scanner has one interesting failure mode and it is **passing when it should not**. Reaching
      OSV is plumbing; deciding what blocks a build is the policy, and the ways it can be quietly
      wrong are all here — a HIGH finding that slips through, an allowlist entry that never expires,
      an empty input treated as a clean result, a severity that cannot be parsed and is read as
      harmless.

      So [decide] and its helpers are pure functions over fixture data, and these tests pin the
      decisions rather than the HTTP.
What: severity parsing across the shapes OSV actually uses, the allowlist and its expiry, and the
      block/pass verdict.
Result: a gate whose policy cannot drift unnoticed.

Changelog: 2026-10-02 — Created for issue 11.6.

Run with `python3 -m unittest discover -s scripts -p 'test_*.py'`, which is what CI does. Standard
library only — adding pytest for four dozen assertions would need a DECISIONS.md row it does not earn.
"""
from __future__ import annotations

import datetime as dt
import json
import tempfile
import unittest
from pathlib import Path

from osv_scan import decide, load_allowlist, parse_coordinates, worst_severity


class ParseCoordinatesTest(unittest.TestCase):
    """Reading the generated coordinate list."""

    def test_splits_group_artifact_from_version(self):
        # OSV keys a Maven package by `group:artifact` with the version separate, so sending the
        # whole coordinate as a name would match nothing and the scan would report a clean build.
        self.assertEqual(
            [("androidx.room:room-runtime", "2.6.1")],
            parse_coordinates("androidx.room:room-runtime:2.6.1\n"),
        )

    def test_skips_blanks_and_comments(self):
        self.assertEqual(
            [("a:b", "1")],
            parse_coordinates("\n# a comment\na:b:1\n\n"),
        )

    def test_a_malformed_line_is_skipped_not_fatal(self):
        # The file is generated. One odd entry must not stop the other 277 from being checked.
        self.assertEqual([("a:b", "1")], parse_coordinates("not-a-coordinate\na:b:1\n"))

    def test_duplicates_collapse(self):
        self.assertEqual([("a:b", "1")], parse_coordinates("a:b:1\na:b:1\n"))


class WorstSeverityTest(unittest.TestCase):
    """Reading severity out of the shapes OSV actually returns."""

    def test_reads_the_database_specific_band(self):
        self.assertEqual("CRITICAL", worst_severity({"database_specific": {"severity": "critical"}}))

    def test_reads_a_cvss_vector(self):
        self.assertEqual(
            "HIGH",
            worst_severity({"severity": [{"type": "CVSS_V3", "score": "CVSS:3.1/AV:N/AC:L/HIGH"}]}),
        )

    def test_reads_a_numeric_score_by_band(self):
        # Some sources report a bare number. 9.8 is CRITICAL, 7.5 HIGH, 5.0 MODERATE.
        self.assertEqual("CRITICAL", worst_severity({"severity": [{"score": "9.8"}]}))
        self.assertEqual("HIGH", worst_severity({"severity": [{"score": "7.5"}]}))
        self.assertEqual("MODERATE", worst_severity({"severity": [{"score": "5.0"}]}))
        self.assertEqual("LOW", worst_severity({"severity": [{"score": "2.1"}]}))

    def test_takes_the_worst_of_several(self):
        # A record can carry both a v2 and a v3 score. Taking the first would under-report.
        self.assertEqual(
            "CRITICAL",
            worst_severity({"severity": [{"score": "4.0"}, {"score": "9.9"}]}),
        )

    def test_an_unreadable_record_is_unknown_rather_than_harmless(self):
        # Deliberately not "LOW". UNKNOWN is printed but does not block; calling it LOW would be a
        # claim the data does not support.
        self.assertEqual("UNKNOWN", worst_severity({}))
        self.assertEqual("UNKNOWN", worst_severity({"severity": [{"score": "not-a-number"}]}))


class AllowlistTest(unittest.TestCase):
    """The accepted-findings file, and the expiry that stops it becoming policy."""

    def _write(self, payload: dict) -> Path:
        handle = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
        json.dump(payload, handle)
        handle.close()
        return Path(handle.name)

    def test_a_missing_file_is_an_empty_allowlist(self):
        # Not an error: a repository with nothing accepted is the healthy state.
        self.assertEqual(({}, []), load_allowlist(Path("/nonexistent/allowlist.json"), dt.date(2026, 10, 2)))

    def test_an_in_date_entry_is_live(self):
        path = self._write(
            {"accepted": [{"id": "GHSA-x", "reason": "not reachable from this app", "review_by": "2027-01-01"}]},
        )
        live, expired = load_allowlist(path, dt.date(2026, 10, 2))
        self.assertEqual({"GHSA-x": "not reachable from this app"}, live)
        self.assertEqual([], expired)

    def test_an_entry_past_its_review_date_is_expired_not_live(self):
        # The point of the field. An acceptance has to be renewed deliberately, or a vulnerability
        # somebody once judged tolerable is inherited forever by people who never saw the argument.
        path = self._write(
            {"accepted": [{"id": "GHSA-x", "reason": "no patched release yet", "review_by": "2026-09-01"}]},
        )
        live, expired = load_allowlist(path, dt.date(2026, 10, 2))
        self.assertEqual({}, live)
        self.assertEqual(1, len(expired))
        self.assertIn("GHSA-x", expired[0])

    def test_the_boundary_day_is_still_live(self):
        # review_by is the day it is due, not the day after.
        path = self._write(
            {"accepted": [{"id": "GHSA-x", "reason": "r", "review_by": "2026-10-02"}]},
        )
        live, _ = load_allowlist(path, dt.date(2026, 10, 2))
        self.assertIn("GHSA-x", live)

    def test_an_entry_without_a_review_date_is_rejected_loudly(self):
        # A KeyError here is correct: the field is required, and silently defaulting it to "forever"
        # would reintroduce the exact problem the field exists to prevent.
        path = self._write({"accepted": [{"id": "GHSA-x", "reason": "r"}]})
        with self.assertRaises(KeyError):
            load_allowlist(path, dt.date(2026, 10, 2))


class DecideTest(unittest.TestCase):
    """The verdict: what blocks a build."""

    def _finding(self, severity: str, identifier: str = "GHSA-1") -> dict:
        return {
            "id": identifier,
            "package": "com.example:lib",
            "version": "1.0",
            "severity": severity,
            "summary": "a summary",
        }

    def test_nothing_found_passes(self):
        self.assertEqual(0, decide([], {}, [])[0])

    def test_a_critical_blocks(self):
        code, lines = decide([self._finding("CRITICAL")], {}, [])
        self.assertEqual(1, code)
        self.assertTrue(any("BLOCKING" in line for line in lines))

    def test_a_high_blocks(self):
        self.assertEqual(1, decide([self._finding("HIGH")], {}, [])[0])

    def test_moderate_and_low_do_not_block_but_are_reported(self):
        # The floor is HIGH. Blocking on every MODERATE would make the gate something people turn
        # off, which is worse than a gate that is imperfect — but silence would be worse still.
        for severity in ("MODERATE", "LOW", "UNKNOWN"):
            code, lines = decide([self._finding(severity)], {}, [])
            self.assertEqual(0, code, severity)
            self.assertTrue(any(severity in line for line in lines), severity)

    def test_an_allowlisted_critical_does_not_block_and_shows_its_reason(self):
        code, lines = decide([self._finding("CRITICAL")], {"GHSA-1": "not reachable"}, [])
        self.assertEqual(0, code)
        self.assertTrue(any("not reachable" in line for line in lines))

    def test_an_allowlist_entry_for_a_different_id_does_not_excuse_this_one(self):
        # The mistake worth a test: matching loosely here would let one acceptance silence
        # everything.
        self.assertEqual(1, decide([self._finding("CRITICAL", "GHSA-1")], {"GHSA-2": "r"}, [])[0])

    def test_an_expired_entry_fails_even_with_no_findings_at_all(self):
        # A stale acceptance is itself the failure: it means nobody has looked since the review date,
        # whatever the scan happens to say today.
        code, lines = decide([], {}, ["GHSA-x: review was due 2026-09-01"])
        self.assertEqual(1, code)
        self.assertTrue(any("EXPIRED" in line for line in lines))

    def test_several_findings_are_all_reported_not_just_the_first(self):
        findings = [self._finding("CRITICAL", "GHSA-1"), self._finding("HIGH", "GHSA-2")]
        code, lines = decide(findings, {}, [])
        self.assertEqual(1, code)
        self.assertTrue(any("GHSA-1" in line for line in lines))
        self.assertTrue(any("GHSA-2" in line for line in lines))


if __name__ == "__main__":
    unittest.main()
