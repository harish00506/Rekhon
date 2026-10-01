<!--
  Why:  CLAUDE.md §5 — any decision or deviation from the SRS needs an ADR.
  What: issue 11.5 — DPDP alignment: the consent record in the export, its one-way direction, a
        compliance matrix a test checks, and the four obligations code cannot satisfy.
  Result: a reader can see why consents are exported but never imported, why the matrix is
          machine-checkable, and why 29 drift tests had to be re-armed before any of it was true.
  Changelog: 2026-10-01 — Created.
-->

# ADR-0061 — DPDP alignment is a checked document, and the consent record is export-only

**Status:** Accepted · **Date:** 2026-10-01 · **Issue:** 11.5 · **SRS:** §23, §32, DPDP, P-01

## Context

§32 and the DPDP Act 2023 ask for purpose limitation, consent records, and the data-principal
rights — access, correction, erasure, portability — "documented and evidenced". Most of the
machinery already existed: the consent ledger with both timestamps (issue 1.9), the dashboard that
shows them (11.3), the JSON archive (5.4), and the crypto-shredding erase (11.4). Two things did
not: the consent record had never reached a file, and nothing tied the compliance claims to the code.

## Decision

### 1 · The consent record goes in the export

DPDP's right of access is not satisfied by handing a user their *data* while keeping the record of
what they agreed to. "What has this app been allowed to do, and since when?" is a question the data
principal is entitled to an answer to **in a form they can keep**. `CfoArchive` gains
`consents: List<ConsentRecord>`, read from the Proto DataStore ledger rather than a DAO — because
that is where the ledger lives — with one row per declared `ConsentFeature`, **including the ones
nobody has ever answered for**. "Never asked" is a fact about the user's choices, and a record
listing only the answered ones would read as a shorter list of permissions than the app actually has.

It stores `ConsentFeature.id`, not the enum: the archive is a contract with files already on users'
phones, so a Kotlin rename must not change the file format, and a consent *removed* from the enum
must still decode in an archive that has it.

**An unreadable ledger fails the whole export.** The alternative is a file with an empty consent
list, which a reader would take as "this app was granted nothing" — and a document whose purpose is
to be authoritative must fail loudly rather than quietly understate what the app was permitted to do.

### 2 · And it is never imported

A file is not a person. Restoring consents would re-grant one the user has withdrawn since the file
was written, and a hand-edited archive would become a mechanism for granting consents that were
never given. Consent is given on the device, by the person, through the screen that states the
purpose — and nowhere else. `import` reads every other list and deliberately ignores this one;
`ArchiveConsentRecordTest` holds that direction, and a mutation that restores consents from the file
fails it.

This is the first field in the archive that is **asymmetric**, which is a cost: the round-trip
property "export then import gives back what you had" no longer holds for consents. It holds for
every byte of the user's *data*, which is what that property exists to protect.

### 3 · The compliance matrix is machine-checkable

`docs/compliance/dpdp-2023.md` maps each obligation to its implementation and the test that proves
it. A traceability matrix is the easiest document in a repository to make quietly false: it claims
"erasure is implemented in `EraseRepository`, proven by `EraseDeviceTest`", and the day either is
renamed the claim stops being true with nothing anywhere noticing. Compliance documents do not fail
CI, which is exactly why they drift.

So `DpdpComplianceDriftTest` checks it: every `ConsentFeature` must have a row (DPDP §4's purpose
limitation, enforced rather than remembered — a fifth consent added without a declared purpose fails
the build), the document must name no consent that does not exist, every class and test it cites must
still exist, and the four open obligations must still be listed as open. Classes are resolved **by
file name across the repository** rather than by path, because this project moves classes between
modules deliberately and a path-based assertion would fail on a legitimate refactor and teach
everyone to delete it.

### 4 · The four obligations code cannot satisfy are listed as open

Grievance redressal (§13), nomination (§14), breach notification (§8(6)) and children's data (§9)
require a named contact, a process and product decisions — none of which live in this repository.
The matrix states them as **not satisfied**, with what each would need, rather than letting the rows
above imply full compliance. A compliance document that overstates is worse than none, and this one
is tested to keep saying so.

### 5 · AC1's "backups" and the app's structural position

Worth stating because it recurs: the app has no server, no account and no telemetry, so most
obligations are met **structurally** — the processing that would trigger them does not happen. The
only configured network call carries a `PriceKey`, whose character set cannot hold an amount or a
name, and the default binding (`UnconfiguredMarketDataApi`) makes no call at all.

## What had to be fixed first: 29 drift tests that could not fail

The new drift test passed, and then three of five mutations against it **survived**. The cause was
not the test: Gradle held `:core:datastore:testDebugUnitTest` **UP-TO-DATE** when only the document
changed, because a file read at runtime is not a declared task input. `--rerun-tasks` failed all four
assertions, which is how we know the gate was right and only its scheduling was wrong.

**Issue 7.2 found this exact bug and fixed it — for one file.** `configureRulebookAsTestInput`
declared `ai/rules/rules-kb.json` and left the other nine data files undeclared, and it was wired
into the pure-Kotlin convention plugin only, so no Android-library module got it at all. Across the
repository that is **29 drift tests**, each the only thing stopping a typed Kotlin mirror diverging
from the row it claims to come from, and every one of them skippable on exactly the edit it was
written to catch.

`configureCheckedDataAsTestInput` now declares the `ai/` and `docs/compliance/` **directories** —
directories rather than a file list, which is what stops this recurring a third time: a knowledge
base added next year is covered without anyone remembering. It is applied from both convention
plugins. Proven by bumping `calendar-seasonality.json`'s `_meta.version` and watching
`SeasonalityKbDriftTest` fail where it would previously have been skipped.

## Alternatives rejected

| Alternative | Why not |
|---|---|
| Consent record in a separate file from the archive | Two files to keep, two to lose. The right of access is one answer, so it is one document. |
| Import the consent record too, for symmetry | A file would become a consent mechanism. The asymmetry is the safety property. |
| Export only the consents that were answered | Reads as a shorter list of permissions than the app has. "Never asked" is a fact worth recording. |
| An empty consent list when the ledger cannot be read | The quietest possible lie in the one document meant to be authoritative. |
| Render the dates in the profile's zone, as the dashboard does | This is a machine-readable record; UTC epoch millis match every other timestamp in the archive (TIM-001). The screen is where a date becomes a day. |
| A prose compliance document | Prose cannot be checked, and an unchecked compliance claim rots. Every claim here names a file a test asserts exists. |
| Parse every backticked token in the document instead of a literal `CITED` list | The document also names resource ids, lint rules and methods; a parser clever enough to tell them apart is clever enough to stop matching silently. A name added to the matrix but not to the list is simply unchecked — never falsely checked. |
| Declare the individual data files as test inputs | What 7.2 did, and the reason this bug came back. Directories survive a new file. |
| Claim the four open obligations as met | They are not. A matrix that overstates is worse than no matrix. |

## Consequences

- An export is now a complete account of what the app holds **and** what it was allowed to do.
- A consent can never be granted by a file — only by the person, on the device.
- Adding a `ConsentFeature` without a declared purpose fails the build.
- Renaming or deleting any class the matrix cites fails the build, so the document cannot rot.
- **All 29 drift tests in the repository now actually run** when the data they guard changes. Until
  this issue, every one of them could be skipped on exactly the edit it existed to catch.
- An edit anywhere under `ai/` re-runs every module's unit tests. That is the cheaper mistake.
- **Still open:** the four publisher obligations in the matrix's §4, and an append-only consent
  history rather than the latest grant/withdraw pair (a schema change plus a retention decision).
