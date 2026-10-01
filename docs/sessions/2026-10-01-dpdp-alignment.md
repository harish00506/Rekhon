<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 11.5 — DPDP alignment, and the 29 drift tests it found could not fail.
  Result: a reader can see why the consent record is export-only, why the compliance matrix is
          machine-checked, and why none of that was true until the build was fixed.
  Changelog: 2026-10-01 — Created.
-->

# 2026-10-01 — What you were allowed to do, in a file you keep (issue 11.5, ADR-0061)

**Branch:** `feature/11-5-dpdp-act-2023-alignment` off `dev` (`d017bb4`)
**Versions:**
- **VERSION** 0.10.11 → **0.10.12**
- **versionCode** 55 → 56
- **Schema** 29 → **29 (unchanged — the consent record lives in DataStore, not Room)**

---

## 1 · Decisions this session

The full argument for each is in [ADR-0061](../adr/0061-dpdp-alignment-is-a-checked-document-and-the-consent-record-is-export-only.md).

- **The consent record ships in the export.** DPDP's right of access is not satisfied by handing a
  user their *data* while keeping the record of what they agreed to. One row per declared
  `ConsentFeature`, **including the ones nobody has ever answered for** — "never asked" is a fact
  about the user's choices, and a record listing only the answered ones would read as a shorter list
  of permissions than the app actually has.
- **Stored by stable `id`, not the enum.** The archive is a contract with files already on users'
  phones: a Kotlin rename must not change the file format, and a consent *removed* from the enum must
  still decode in an archive that has it.
- **An unreadable ledger fails the whole export.** The alternative is a file with an empty consent
  list, which reads as "this app was granted nothing" — the quietest possible lie in the one document
  meant to be authoritative.
- **And it is never imported.** A file is not a person. Restoring would re-grant a consent the user
  has since withdrawn, and a hand-edited archive would become a mechanism for granting consents that
  were never given. This is the first asymmetric field in the archive, and the cost is real: the
  round-trip property no longer holds for consents. It holds for every byte of the user's *data*,
  which is what that property exists to protect.
- **The compliance matrix is machine-checkable.** A traceability matrix is the easiest document in a
  repository to make quietly false — it claims "erasure is in `EraseRepository`, proven by
  `EraseDeviceTest`" and the day either is renamed the claim stops being true with nothing noticing.
  Compliance documents do not fail CI, which is exactly why they drift.
- **Classes are resolved by file name across the repository, not by path**, because this project
  moves classes between modules deliberately (11.4 moved three sets of constants) and a path-based
  assertion would fail on a legitimate refactor and teach everyone to delete it.
- **A literal `CITED` list rather than parsing every backticked token.** The document also names
  resource ids, lint rules and methods; a parser clever enough to tell them apart is clever enough to
  stop matching silently. A name added to the matrix but not the list is *unchecked*, never falsely
  checked.
- **Four obligations are listed as not met.** Grievance redressal, nomination, breach notification and
  children's data need a person and a process. The document says so, with what each would need, and a
  test keeps them listed. A compliance document that overstates is worse than none.
- **Deferred** (ADR-0061): the four publisher obligations, and an append-only consent *history*
  rather than the latest grant/withdraw pair — a schema change plus a retention decision.

**What this found.**

1. **Twenty-nine drift tests in this project could not fail.** Three of five mutations against the
   new compliance test survived; `--rerun-tasks` then failed all four of its assertions. The test was
   right and its *scheduling* was wrong: a file read at runtime is not a declared task input, so
   Gradle held the test task **UP-TO-DATE** on exactly the edit the test exists to catch.

   **Issue 7.2 found this exact bug and fixed it — for one file.** `rules-kb.json` was declared, the
   other nine data files were not, and the call was wired into the pure-Kotlin convention plugin only,
   so no Android-library module had it at all. Every rulebook and knowledge-base drift test in the
   repository was skippable. `configureCheckedDataAsTestInput` now declares the `ai/` and
   `docs/compliance/` **directories** — directories rather than a file list, which is what stops this
   returning a third time. Proven independently by bumping `calendar-seasonality.json`'s
   `_meta.version` and watching `SeasonalityKbDriftTest` fail where the task was previously skipped.
2. **Four fakes that could not stand in for a store.** `observeAll()` returned `emptyFlow()` in the
   backup tests because nothing had ever read it; the export's `.first()` then threw
   `NoSuchElementException`. A fake that never emits is not a stand-in for a store, it is a hang
   waiting for a caller.
3. **The device proved the decision that mattered.** Withdraw a consent, then import the file that
   says `granted: true`: `cfo_settings.pb` came back **byte-identical** and the dashboard still read
   "Withdrawn". That is the assertion a unit test can only approximate.
4. **Three detekt `LongMethod` failures on `export`, fixed properly rather than trimmed** — the ledger
   read became its own documented function, `flatMap` replaced a `when`, and the field moved into a
   chained `withConsentRecord` beside the existing `withAdvisor`.

## 2 · Flow changed this session

One path extended and one direction deliberately absent — `FLOW.md` §2.24:

```
ArchiveRepository.export()
├─ consentRecord()                    ConsentStore.observeAll() — not a DAO read
│  ├─ Err ⇒ the whole export fails    a file reading "granted nothing" would be the quiet lie
│  └─ one row per ConsentFeature, including never-answered
└─ withAdvisor(...).withConsentRecord(record)

ArchiveRepository.import(json)
└─ archive.consents                   **read and ignored, on purpose** (ADR-0061)
```

`ai/` and `docs/compliance/` are now declared test inputs from **both** convention plugins, so a
change to any checked data file re-runs every drift test.

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `docs/compliance/dpdp-2023.md` (new) | the obligation-by-obligation matrix, and the four open items |
| `core/datastore/src/test/DpdpComplianceDriftTest.kt` (new) | 5 tests that make the matrix fail the build when it stops being true |
| `data/repository/Archive.kt` | `CfoArchive.consents` and the `ConsentRecord` type |
| `data/repository/ArchiveRepository.kt` | reads the ledger on export and may fail on it; `withConsentRecord`; the import ignores it |
| `data/repository/RepositoryFactory.kt` | `archive(..., consents)` |
| `data/repository/src/test/ArchiveConsentRecordTest.kt` (new) | 7 tests, 7 mutations, including the import refusal |
| `data/repository/src/{test,androidTest}/BackupRestore*.kt` | four fakes emit from `observeAll()` instead of returning an empty flow |
| `app/di/RepositoryModule.kt` | passes the ledger to the archive |
| `build-logic/ProjectExtensions.kt` | `configureCheckedDataAsTestInput` replaces 7.2's rulebook-only input |
| `build-logic/Cfo{Kotlin,Android}LibraryConventionPlugin.kt` | both apply it; the Android one never had it |
| `docs/adr/0061-…`, `DECISIONS.md`, `FLOW.md` §2.24, `CHANGELOG.md`, `docs/memory.md`, `VERSION` | the records |
