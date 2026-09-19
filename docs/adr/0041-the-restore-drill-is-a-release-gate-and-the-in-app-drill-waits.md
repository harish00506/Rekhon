# ADR-0041 — The restore drill is a release gate that reads the schema itself; DRL-001's in-app drill waits

- **Status:** accepted
- **Date:** 2026-09-19
- **Deciders:** Harish G (solo)
- **SRS refs:** §21.5 ("backups proven restorable"), §34.4 DRL-001, SEC-005; issue 8.3. Builds on
  ADR-0039 (the backup format) and ADR-0040 (restore through the archive import)

## Context

Two requirements share the word "drill" and describe different things.

- **Issue 8.3 / §21.5:** an *automated* drill in the instrumented tests that seals a backup from a
  seeded database, restores it onto a clean instance, asserts row parity, and blocks a release when
  it fails. It must be documented as a recurring release-gate step.
- **DRL-001 (§34.4):** a *user-facing* prompt, twice a year: decrypt the user's latest backup in a
  sandbox, check row counts and checksums, confirm the passphrase still works, log the result in
  `backups_log`, and raise a critical insight when it fails. It is to be placed in `:sync:backup`.

DRL-001 depends on things that don't exist yet. There is no `backups_log` table: the schema has
`audit_log`, not `backups_log`. There's no insight system to raise into (the AI orchestrator, AI-ORCH,
is Epic 9). And the app never learns where a backup was saved, because the file picker returns a
one-time grant, so it has no "latest backup" to re-open six months later.

Two design questions for the automated drill:

1. **What does "row parity" compare?** Comparing the archive exported before the backup with one
   exported after the restore is circular. A table the archive forgets is missing from both sides,
   so they match. That is exactly how `goal` went missing from every export between 7.1 and 7.4.
2. **What makes a failure block a release?** CI has never had an emulator, and required checks are
   repository settings, not code.

## Decision

**1. Parity is read from SQLite, not from the archive.** `ProfileSnapshot` lists every table in
`sqlite_master`, scopes each by `profile_id` (`profile` by `id`), and renders every row column by
column with typed values, sorted. `audit_log` and SQLite/Room bookkeeping are excluded, with reasons.
Any other table it cannot scope is reported as `unscoped`, and the drill fails on it. A table added
by a future migration is therefore in the drill the day it lands.

**2. The fixture must fill every table, and the drill checks that.** `DrillFixture` (`sharedTest`,
shared by both drills) puts one row with every nullable column set into all 22 profile-scoped tables.
The drill asserts that no table is empty. A new table fails until someone seeds it. That is
deliberate: an unseeded table passes against an archive that forgets it.

**3. Two drills, one gate.**
- `BackupRestoreDrillTest` (JVM, Robolectric, two in-memory databases) runs in `unitTests`, so CI
  runs it on every PR.
- `BackupRestoreDrillDeviceTest` (instrumented) seeds the **encrypted** database, seals at the
  shipping Argon2id cost, deletes the database file and its wrapped key, opens a fresh one, restores
  and compares. The `restoreDrill` task runs it. It is the release gate: required before
  `dev → stage` and `stage → main` (`00-issue-workflow.md`, `CLAUDE.md` §7, `/pre-merge` item 11).
- CI's new `restore-drill` job runs `restoreDrill` on an emulator
  (`reactivecircus/android-emulator-runner`) for every PR into `stage` or `main`.

**4. Both drills were watched go red.** Removing `insertGoals` from the archive restore failed the
JVM drill and the device drill, both on "row counts per table" and naming `goal`, and made
`restoreDrill` exit 1. A single changed paisa and a deleted table are also asserted to be caught, as
permanent tests.

**5. DRL-001's user-facing drill is deferred, with its triggers written down.** It gets built when all
three of these exist:
- a place to log results (`backups_log`, or `audit_log` extended);
- a place to raise the failure (the insight feed, Epic 9);
- a backup the app can re-open unprompted (the §22 blob store, or a persisted SAF grant).

Until then the user can run the manual version any time, from Settings → Restore, on a backup file.

## Consequences

- **The CI job only blocks once branch protection lists it as required.** That is a GitHub setting
  on `stage` and `main`, and this repository has never been pushed from this machine, so it has not
  been verified. The workflow doc says so.
- **The CI job has never run.** The YAML parses (checked with SnakeYAML) and the task it calls is
  green locally, but no runner has executed it. It is recorded as unproven.
- **A CI emulator costs minutes per release PR.** It is limited to the release path; `dev` relies on
  the JVM drill.
- **Issue 5.4's `ArchiveRepositoryTest` keeps its own fixture, which misses five tables.** Its
  assertions name that fixture's rows, and rewriting it was out of scope. `DrillFixture` is the
  complete one.
- **`:sync:backup` stays a placeholder.** SRS placement for DRL is `:sync:backup`, and the in-app
  drill will live there when its triggers fire; the release-gate drill is a test of
  `:data:repository`, where backup and restore live.

## Alternatives considered

- **Compare the two archives.** Circular, as explained in Context.
- **Hardcode the list of tables to compare.** It is one more list to forget to extend, which is the
  failure `CfoArchive` already had once.
- **Build DRL-001 now on `audit_log`.** That means a new audit event and a notification, but nowhere
  to raise a "critical insight" and no backup the app can re-open. It would be a prompt that can't
  do what the requirement says.
