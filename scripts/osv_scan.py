#!/usr/bin/env python3
"""Scans the shipped dependencies against OSV and blocks on new high-severity findings.

Why:  SEC-007 asks for supply-chain scanning. This app's threat model makes it matter more than
      usual rather than less: it is offline-first and holds a complete picture of someone's
      finances, so a compromised dependency is the *only* realistic route to that data off the
      device. There is no server to breach — which concentrates the risk here.

What: reads the resolved coordinates `writeDependencyCoordinates` emits, batches them to OSV's
      public API, and fails on any finding at or above the configured severity floor that is not
      explicitly allowlisted with a reason and a review date.
Result: exit 0 when clean or fully allowlisted; 1 on a blocking finding; 2 when it could not scan.

Changelog: 2026-10-02 — Created for issue 11.6.

**Exit 2 is deliberately not success.** A scanner that passes when it cannot reach the network is
the vacuous gate this project keeps finding — it would go green on every offline machine forever.
This runs in CI, where the network exists; locally it is expected to exit 2 and is not part of the
`unitTests` gate. P-04 is a promise about the *app*, not about a developer tool.

**The allowlist expires.** An entry without a review date becomes permanent, which is how a known
vulnerability quietly becomes policy. `review_by` is required and an entry past it fails the scan.
"""
from __future__ import annotations

import datetime as dt
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

#: OSV's batched query endpoint. Public, unauthenticated, no account needed.
OSV_BATCH_URL = "https://api.osv.dev/v1/querybatch"

#: Full records are fetched one id at a time; the batch response carries ids only.
OSV_VULN_URL = "https://api.osv.dev/v1/vulns/"

#: Coordinates are queried in chunks; OSV accepts up to 1000 per batch.
BATCH_SIZE = 100

#: Severities that block a build, in the CVSS v3 bands OSV reports.
BLOCKING = {"HIGH", "CRITICAL"}

#: Seconds to wait on any single request before giving up and reporting "could not scan".
TIMEOUT_SECONDS = 30


def parse_coordinates(text: str) -> list[tuple[str, str]]:
    """Turns the coordinate file into (package, version) pairs.

    Why:    OSV's Maven ecosystem keys a package by `group:artifact` and takes the version
            separately, so the third colon-separated field has to be split off rather than sent
            whole. A malformed line is skipped rather than fatal: the file is generated, and one odd
            entry must not stop 277 real ones from being checked.
    Result: the pairs, in file order, duplicates removed.
    Input:  text — the file's contents, one `group:artifact:version` per line.
    Output: a list of (package, version) tuples.
    Changelog: 2026-10-02 — Created for issue 11.6.
    """
    seen: list[tuple[str, str]] = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(":")
        if len(parts) != 3:
            print(f"warning: skipping unparseable coordinate {line!r}", file=sys.stderr)
            continue
        pair = (f"{parts[0]}:{parts[1]}", parts[2])
        if pair not in seen:
            seen.append(pair)
    return seen


def worst_severity(vuln: dict) -> str:
    """The highest CVSS band a vulnerability record reports.

    Why:    OSV records carry severity in two shapes — a `database_specific.severity` string, and
            CVSS vectors under `severity`. Neither is always present, so this takes the strongest
            signal available and falls back to `UNKNOWN`.
    Result: one of CRITICAL, HIGH, MODERATE, LOW or UNKNOWN.
    Input:  vuln — one OSV vulnerability record. Output: str.
    Changelog: 2026-10-02 — Created for issue 11.6.

    **`UNKNOWN` does not block.** A record with no severity at all is usually an advisory still being
    triaged, and blocking every build on one would make the gate something people switch off — which
    is worse than the gate being imperfect. It is printed, so it is still seen.
    """
    bands = {"CRITICAL": 4, "HIGH": 3, "MODERATE": 2, "MEDIUM": 2, "LOW": 1}
    best = "UNKNOWN"
    declared = str(vuln.get("database_specific", {}).get("severity", "")).upper()
    if declared in bands:
        best = declared
    for entry in vuln.get("severity", []):
        score = str(entry.get("score", ""))
        # A CVSS v3 vector string, e.g. "CVSS:3.1/AV:N/.../A:H". OSV also reports a bare numeric
        # score for some sources; both are handled, the numeric one by band thresholds.
        for band, _ in sorted(bands.items(), key=lambda kv: -kv[1]):
            if band in score.upper() and bands[band] > bands.get(best, 0):
                best = band
        try:
            numeric = float(score)
        except ValueError:
            continue
        numeric_band = "CRITICAL" if numeric >= 9.0 else "HIGH" if numeric >= 7.0 else "MODERATE" if numeric >= 4.0 else "LOW"
        if bands[numeric_band] > bands.get(best, 0):
            best = numeric_band
    return best


def load_allowlist(path: Path, today: dt.date) -> tuple[dict[str, str], list[str]]:
    """Reads the accepted-findings file and separates the live entries from the expired.

    Why:    some findings genuinely cannot be fixed today — a transitive dependency with no patched
            release, or an advisory that does not apply to how this app uses the library. Those need
            a way to be accepted, and the acceptance needs a **reason** and an **expiry**, because an
            allowlist entry without a review date is how a known vulnerability becomes policy.
    Result: ({id: reason} for entries still in date, [messages] for entries past review).
    Input:  path — the allowlist JSON, which need not exist; today — the date to judge against,
            injected rather than read from the clock so a test is deterministic (P-08).
    Output: a (live, expired) tuple.
    Changelog: 2026-10-02 — Created for issue 11.6.
    """
    if not path.is_file():
        return {}, []
    data = json.loads(path.read_text(encoding="utf-8"))
    live: dict[str, str] = {}
    expired: list[str] = []
    for entry in data.get("accepted", []):
        identifier = entry["id"]
        reason = entry["reason"]
        review_by = dt.date.fromisoformat(entry["review_by"])
        if review_by < today:
            expired.append(f"{identifier}: accepted on {entry.get('accepted_on', '?')}, review was due {review_by}")
        else:
            live[identifier] = reason
    return live, expired


def decide(findings: list[dict], allowed: dict[str, str], expired: list[str]) -> tuple[int, list[str]]:
    """Turns a set of findings into an exit code and the lines to print.

    Why:    the whole policy, in one pure function, so it is testable without a network. Everything
            above it fetches and everything below it prints; this is the part that decides whether a
            build is blocked, and it is the part worth tests.
    Result: (exit code, report lines). Non-zero when a blocking finding is not allowlisted, or when
            any allowlist entry is past its review date — a stale acceptance is itself a failure.
    Input:  findings — [{id, package, version, severity, summary}]; allowed — {id: reason} in date;
            expired — messages for entries past review.
    Output: an (int, list[str]) tuple.
    Changelog: 2026-10-02 — Created for issue 11.6.
    """
    lines: list[str] = []
    blocking = [f for f in findings if f["severity"] in BLOCKING and f["id"] not in allowed]
    accepted = [f for f in findings if f["severity"] in BLOCKING and f["id"] in allowed]
    informational = [f for f in findings if f["severity"] not in BLOCKING]

    for finding in sorted(blocking, key=lambda f: (f["severity"], f["id"])):
        lines.append(f"BLOCKING  {finding['severity']:<8} {finding['id']}  {finding['package']}@{finding['version']}")
        lines.append(f"          {finding['summary']}")
    for finding in sorted(accepted, key=lambda f: f["id"]):
        lines.append(f"accepted  {finding['severity']:<8} {finding['id']}  {finding['package']}@{finding['version']}")
        lines.append(f"          reason: {allowed[finding['id']]}")
    for finding in sorted(informational, key=lambda f: f["id"]):
        lines.append(f"note      {finding['severity']:<8} {finding['id']}  {finding['package']}@{finding['version']}")
    for message in expired:
        lines.append(f"EXPIRED   allowlist entry past its review date — {message}")

    if blocking:
        lines.append("")
        lines.append(f"{len(blocking)} finding(s) at or above HIGH are not allowlisted (SEC-007).")
        lines.append("Upgrade the dependency, or add an entry to config/osv/allowlist.json with a")
        lines.append("reason and a review_by date — an acceptance without an expiry is policy by accident.")
    if expired:
        lines.append("")
        lines.append(f"{len(expired)} allowlist entry/entries are past review. Re-check them or remove them.")
    return (1 if blocking or expired else 0), lines


def query_osv(coordinates: list[tuple[str, str]]) -> list[dict]:
    """Asks OSV about every coordinate and returns the findings.

    Why:    separated from [decide] so the policy is testable without a network, and so a network
            failure is reported as "could not scan" rather than as "nothing found".
    Result: one entry per (vulnerability, package) pair.
    Input:  coordinates — the (package, version) pairs. Output: a list of finding dicts.
    Changelog: 2026-10-02 — Created for issue 11.6.

    Raises [OSError] / [urllib.error.URLError] upward: the caller turns that into exit 2.
    """
    findings: list[dict] = []
    for start in range(0, len(coordinates), BATCH_SIZE):
        chunk = coordinates[start : start + BATCH_SIZE]
        payload = {
            "queries": [
                {"package": {"name": name, "ecosystem": "Maven"}, "version": version}
                for name, version in chunk
            ],
        }
        request = urllib.request.Request(
            OSV_BATCH_URL,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            results = json.load(response).get("results", [])
        for (name, version), result in zip(chunk, results):
            for vuln in result.get("vulns", []):
                record = _fetch_vuln(vuln["id"])
                findings.append(
                    {
                        "id": vuln["id"],
                        "package": name,
                        "version": version,
                        "severity": worst_severity(record),
                        "summary": record.get("summary", "(no summary)").strip(),
                    },
                )
    return findings


def _fetch_vuln(identifier: str) -> dict:
    """One full OSV record, for its severity and summary.

    Why:    the batch endpoint returns ids only, and severity is what the policy turns on.
    Result: the record, or `{}` when it cannot be read — which [worst_severity] maps to `UNKNOWN`,
            so one unreadable advisory degrades to "mentioned but not blocking" rather than hiding
            every other finding behind an exception.
    Input:  identifier — e.g. `GHSA-xxxx`. Output: the record dict.
    Changelog: 2026-10-02 — Created for issue 11.6.
    """
    try:
        with urllib.request.urlopen(OSV_VULN_URL + identifier, timeout=TIMEOUT_SECONDS) as response:
            return json.load(response)
    except (urllib.error.URLError, OSError, json.JSONDecodeError) as error:
        print(f"warning: could not read {identifier}: {error}", file=sys.stderr)
        return {}


def main(argv: list[str]) -> int:
    """Scans and reports.

    Result: 0 clean, 1 blocked, 2 could not scan or bad usage.
    Input:  argv — `[coordinates_file]`, optionally `[allowlist_path]`. Output: the exit code.
    Changelog: 2026-10-02 — Created for issue 11.6.
    """
    if not 1 <= len(argv) <= 2:
        print("usage: osv_scan.py <coordinates.txt> [allowlist.json]", file=sys.stderr)
        return 2
    coordinates_file = Path(argv[0])
    allowlist_file = Path(argv[1]) if len(argv) == 2 else Path("config/osv/allowlist.json")
    if not coordinates_file.is_file():
        print(f"error: {coordinates_file} not found — run ./gradlew writeDependencyCoordinates", file=sys.stderr)
        return 2

    coordinates = parse_coordinates(coordinates_file.read_text(encoding="utf-8"))
    if not coordinates:
        # Nothing to scan is a failure, not a pass: it means the input was empty or unreadable, and
        # a scanner that reports success over zero dependencies checks nothing.
        print("error: no coordinates to scan", file=sys.stderr)
        return 2
    print(f"Scanning {len(coordinates)} dependencies against OSV…")

    try:
        findings = query_osv(coordinates)
    except (urllib.error.URLError, OSError) as error:
        print(f"error: could not reach OSV ({error}). This is NOT a pass — see SEC-007.", file=sys.stderr)
        return 2

    allowed, expired = load_allowlist(allowlist_file, dt.date.today())
    code, lines = decide(findings, allowed, expired)
    for line in lines:
        print(line)
    if code == 0:
        print(f"OK: {len(findings)} finding(s), none blocking (SEC-007).")
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
