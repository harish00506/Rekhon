<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 8.3 — the backup restore drill as a release gate (§21.5, §34.4 DRL-001); closes Epic 8.
  Result: a reader can see why parity is read from SQLite rather than the archive, how the gate is
          wired and what it cannot yet enforce, and why DRL-001's in-app drill waits.
  Changelog: 2026-09-19 — Created.
-->

# 2026-09-19 — The backup restore drill (issue 8.3, ADR-0041)

**Branch:** `feature/8-3-backup-restore-drill` off `dev` (`e4e89f5`) · **VERSION** 0.8.2 → **0.8.3** ·
**versionCode** 35 → 36 · **Schema** 22, unchanged · **No app code changed**: tests, build, CI, docs

---

## 1 · Decisions this session

### 1a · Parity is read from SQLite, not from the archive

Comparing an export taken before the backup with one taken after the restore can't catch a table the
archive forgets, because it's missing from both. That is how `goal` went missing from every export
for three issues. `ProfileSnapshot` reads every table from `sqlite_master`, scopes rows by
`profile_id` (`profile` by `id`), and renders each row with typed values, sorted. A table it can't
scope is `unscoped` and fails the drill.

### 1b · The fixture must fill every table, and the drill checks that

`DrillFixture` puts one row with every nullable column set into all 22 profile-scoped tables. It is
issue 5.4's seed plus the five tables that seed never reached: holdings, lots, goals, contributions
and funding accounts. The drill asserts that no table is empty, so a future table fails until it is
seeded here.

### 1c · Two drills, one gate

- JVM (`BackupRestoreDrillTest`, in `unitTests`, runs on every PR).
- Device (`BackupRestoreDrillDeviceTest`, via `./gradlew restoreDrill`), the release gate: encrypted
  database seeded, backup at the shipping cost, database file **and key** deleted, fresh database,
  restore, compare.
- The CI `restore-drill` job runs `restoreDrill` on an emulator for PRs into `stage` and `main`.

The gate is documented as a recurring step in `00-issue-workflow.md` ("Release gate"), in `CLAUDE.md`
§7's promote rule and in `/pre-merge` item 11.

### 1d · Watched it go red, twice

With `insertGoals` removed from the archive restore, the JVM drill failed on "row counts per table …
goal", and the device drill failed on the same assertion. `restoreDrill` exited 1. The mutation was
reverted both times, and `git diff` confirmed it.

### 1e · DRL-001's in-app drill is deferred (ADR-0041)

The twice-yearly user prompt needs `backups_log`, an insight feed (Epic 9), and a backup the app can
re-open. None of these exist. The ADR names all three as triggers.

### 1f · What cannot be verified from this machine

The CI job has never run. Nothing has been pushed, and there are no GitHub credentials here. It
blocks only once branch protection lists it as a required check. The YAML was parsed with SnakeYAML,
and the task it runs is green locally.

---

## 2 · Flow changed this session

**No runtime call path changed.** `FLOW.md` is untouched on purpose: the drill is a test and a build
task, and it drives §2.06's existing backup → restore path exactly as the app does.

---

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `data/repository/src/sharedTest/.../DrillFixture.kt` | **New.** One row, every nullable column, in all 22 profile-scoped tables |
| `data/repository/src/sharedTest/.../ProfileSnapshot.kt` | **New.** `ProfileSnapshot` — every table from `sqlite_master`, rows typed and sorted |
| `data/repository/src/test/.../BackupRestoreDrillTest.kt` | **New.** JVM drill: fixture fills everything; parity; a lost table and a changed paisa are caught |
| `data/repository/src/androidTest/.../BackupRestoreDrillDeviceTest.kt` | **New.** The release-gate drill on SQLCipher |
| `data/repository/build.gradle.kts` | `sharedTest` source dir on both test source sets |
| `build.gradle.kts` (root) | `restoreDrill` task |
| `.github/workflows/ci.yml` | `restore-drill` job (emulator) for PRs/pushes to `stage`/`main` |
| `docs/issues/00-issue-workflow.md`, `CLAUDE.md` §7, `.claude/commands/pre-merge.md` | The release-gate step |
| `docs/adr/0041-…`, `DECISIONS.md` | The decision; approach row; the emulator-runner action row |

---

## 4 · Quiz

**Outcome: not yet taken.** The answers in italics are the author's. Record the developer's own pass
here, fails included.

1. Why can't the drill compare two archives? *(A table the archive forgets is missing from both
   sides.)*
2. A migration adds a table `envelope` with `profile_id`. What happens to the drill, and what fixes
   it? *(`emptyTables` contains `envelope` and the drill fails; seed it in `DrillFixture`, then fix
   the archive if parity then fails.)*
3. The CI job is green on a PR into `main`, but the PR merges while the job is red. How? *(The job
   isn't a required check in branch protection.)*
4. Why is DRL-001's user-facing drill not built? *(It needs `backups_log`, an insight feed and a
   backup the app can re-open.)*
