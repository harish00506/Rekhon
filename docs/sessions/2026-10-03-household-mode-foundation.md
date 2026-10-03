<!--
  Why:  CLAUDE.md §10 — one session file per working session, holding the full reasoning the root
        records only point at.
  What: issue 13.1 — household mode's foundation, the scoping gate, and the three gates that were
        checking less than they claimed.
  Result: a reader can see why aggregation is forbidden from reaching across profiles in SQL, and
          why a comment-only edit could keep a test from running at all.
  Changelog: 2026-10-03 — Created.
-->

# 2026-10-03 — Household mode's foundation (issue 13.1)

Branch `feature/13-1-household-mode` off `dev` (`8309b5d`). Version 0.12.5 → **0.13.0**
(versionCode 64), schema **v29 → v30**. Epic 13 opens.

---

## 1 · Decisions this session

### 1.1 A household is one row above the profile, not a join table

Schema 30 adds `household` plus `profile.household_id`. A `household_member` join table was the
alternative and buys many-to-many — a profile in two households — which nothing in §27 or §33 asks
for, and it would turn "which household is this profile in?" into a join when the app already has
the profile row loaded.

`household` carries no `profile_id` and no money: a display name and a creation date. That is what
lets it sit outside the per-profile invariants honestly, and it is pinned by a test on its exact
column set, so a later `shared_budget_minor` cannot appear in the one table no scoping rule governs.

The migration is additive (DB-003): create the table, insert one household, then add the column with
that household as its SQL default. The order matters — the `INSERT ... SELECT` reads `profile`
before the column exists. The household is dated `MIN(created_at_utc_millis)` over the profiles
rather than "now", because a migration has no injected `Clock` (TIM-001) and the oldest profile's
date is both truthful and deterministic: the same database migrates to the same bytes every time.

### 1.2 Aggregation composes scoped reads; cross-profile SQL stays banned

The obvious household net-worth view is one query without the `profile_id` clause. Rejected. A
household figure is **N per-profile engine runs summed in Kotlin**. The cost is a loop. What it buys:
every number keeps the provenance its per-profile engine gave it (P-02), the arithmetic stays in the
deterministic layer (P-03, P-08), and a bug in the aggregate can produce a wrong total but can never
*leak* one profile's rows into another's view, because no statement ever selects across profiles.

The three views are specified in ADR-0069 §3 and are **additive and attributed** — no household
figure exists that cannot be broken back into the member rows that produced it.

`HouseholdAggregation` refuses an empty household (a caller bug, not a household holding zero — the
reasoning issue 12.2 used to refuse `0/0`), a blank profile id (unattributable, breaking P-02), and
a profile appearing twice (the one mistake here that invents money). With the flag off it refuses
rather than returning `Money.ZERO`, because a screen rendering "₹0" looks like an answer.

### 1.3 Strict scoping became a test over the DAO

"No cross-leak" is a property of 100+ SQL strings — exactly the rule that holds the day it is
written and erodes afterwards. `ProfileScopingTest` requires every `@Query` on a profile-scoped table
to be profile-filtered, id-keyed, or carry a `// DEVICE-WIDE:` marker with a reason.

A custom lint detector was considered seriously — the repo already publishes six. Rejected: the rule
is about the text of SQL strings and the comments above them in one known file, which a test reads
directly; a detector would need UAST plumbing to reach the same strings and would report later.

`SmsDraftDao.deleteAllPending` earns the marker. I first suspected it was a bug and was wrong — the
DAO already explained it: SMS consent is given for the device, so a revocation scoped to whichever
profile happened to be showing would leave the other's drafts on disk. This issue turned that prose
into a marker the test requires, and pinned the count at one.

### 1.4 The 14 id-keyed queries are the flag's precondition, not a TODO

All 95 queries taking a `profileId` filter on it. Fourteen more are keyed by a row id, and are safe
**only because the id came from a scoped read** — a transitive argument whose premise household mode
is precisely the change that breaks. Once two profiles exist, any path that gets an id from
elsewhere (a notification payload, a widget, a deep link, a restored backup) can hand profile A's id
to a query serving profile B, and no SQL here would stop it.

Recorded in ADR-0069 §5 as what must be true before `HouseholdMode.IS_ENABLED` becomes `true`, rather
than fixed now: adding a parameter to 14 functions to defend a state no user can reach is speculative
work, and the change is far safer written beside the feature that makes it reachable, with tests that
can actually create a second profile.

---

## 2 · The gates that were checking less than they claimed

### 2.1 A test that reads source could be skipped — the fourth instance

Deleting the `// DEVICE-WIDE:` marker left `:core:database:testDebugUnitTest` **UP-TO-DATE and the
build green in 1s.** Under `--rerun-tasks` the same edit failed two assertions. The gate was right;
only its scheduling was wrong.

The mechanism: the test reads `Daos.kt` at runtime, which is not a declared task input, and most of
what it checks lives in **comments**, which compile to byte-identical classes. So Gradle saw nothing
change and replayed the previous pass from the build cache.

Issue 7.2 fixed this for one data file, 11.5 for the rest, 11.7 extended it to `docs/security/` —
each time for *data* a test reads. A test reading *source* was still exposed.
`configureOwnSourceAsTestInput()` declares each module's own `src/main` as an input of its test
tasks. **If a test reads a file at runtime, declare the file.**

Measured, not assumed: the i18n catalogue test, which also walks the repo at runtime, was checked
the same way and is correctly scheduled — deleting a string from a feature module fails it, because
resource changes do invalidate the task.

### 2.2 The reason check measured the wrong text

ktlint will not allow an EOL comment directly below a KDoc, so the marker sits *above* the query's
KDoc. The check read "everything after the marker" — which swept up the whole KDoc, so a marker
gutted to `// DEVICE-WIDE: on purpose.` still passed, the prose underneath being long. Found by
mutation M3, which had passed earlier for a different reason and started passing again after the
marker moved. It now reads only the marker's own contiguous `//` block — the only text whoever typed
the marker actually wrote.

### 2.3 The archive's format test listed 14 of 38 keys

Extending the archive meant reading `ArchiveFormatTest`, whose hand-written `TABLES` list had
fourteen names against an envelope with thirty-eight keys: `goals`, `vehicles`, `marketCloses` and
two dozen others were never checked. It now reads the serializer's own descriptor, so a list added to
`CfoArchive` is covered the moment it is declared.

That change alone *weakens* one thing — a derived list cannot notice a field being **removed**,
since the key and the expectation would vanish together. So the key count is pinned too: dropping a
table out of every future backup stays a deliberate edit with a number to change.

### 2.4 The restore drill refused the new table, and was right

`a table no drill can scope to a profile has escaped the backup's reach`. The convenient answer was
to add `household` to the drill's exclusion list. `Archive.kt`'s own class comment is the argument
against it: issue 7.1 added `goal`, nothing added a field to the archive, and **every export taken
between 7.1 and 7.4 silently dropped the user's goals.** A table is exactly the gap holding entities
directly does not cover.

So `household` is in the archive, read *through* `profile.household_id` since it has no `profile_id`
column, written before the profiles on restore, and deliberately **not** in `wipe()` — a per-profile
restore has no business deleting a row another profile may belong to.

---

## 3 · Flow changed this session

```
EXPORT  ArchiveRepository.export()
          └─ archiveDao().<35 reads>
              ├─ .withHousehold(dao, profileId)   household scoped THROUGH profile.household_id
              └─ Json.encodeToString(CfoArchive(...))

IMPORT  ArchiveRepository.import(json)
          └─ withTransaction { wipe(profileId) ; restore(archive) }
                                                   └─ insertHouseholds FIRST, and not in wipe()

ONBOARD QuickSetupRepository.applySeeds(plan, profile)
          └─ withTransaction { householdDao().upsert(default) ; profileDao().upsert(...) }
```

`HouseholdAggregation` has **no runtime caller**: the flag is off and no screen exists, so there is
no path to trace. `FLOW.md` records that rather than drawing a box nothing reaches.

---

## 4 · Code changed this session

| Path | What it does now |
|------|------------------|
| `core/database/.../entity/Entities.kt` | `HouseholdEntity` (name + date, `@Serializable`); `ProfileEntity.householdId`, declared **last** so it matches where `ALTER TABLE ADD COLUMN` puts it |
| `core/database/.../dao/Daos.kt` | `HouseholdDao` (upsert, findById, `members` — profiles, never money); `ArchiveDao.households`/`insertHouseholds`; the `// DEVICE-WIDE:` marker |
| `core/database/.../migration/Migrations.kt` | `MIGRATION_29_30` — table, backfilled household dated as its oldest profile, then the column |
| `core/database/.../CfoDatabase.kt` | `VERSION = 30`; entity + DAO registered |
| `core/database/schemas/.../30.json` | exported schema fixture |
| `core/database/src/test/.../scoping/ProfileScopingTest.kt` | the scoping gate (5 tests) + `markerReason` |
| `core/database/src/test/.../MigrationSafetyTest.kt` | `household` exemption argued; pinned count 8 → 9; its column set pinned |
| `core/database/src/androidTest/.../MigrationRoundTripTest.kt` | the 29 → 30 case |
| `core/common/.../AppError.kt` (+ test) | `FeatureDisabled` |
| `domain/usecase/.../Household.kt` (+ test, + build file) | `HouseholdMode`, `MemberContribution`, `HouseholdTotal`, `HouseholdAggregation` (12 tests) |
| `data/repository/.../ArchiveRepository.kt`, `Archive.kt` | the household in the envelope; `withHousehold` |
| `data/repository/.../QuickSetupRepository.kt` | seeds the household; preserves `householdId` across the REPLACE |
| `data/repository/src/sharedTest/.../ProfileSnapshot.kt`, `DrillFixture.kt` | the drill reaches the household |
| `data/repository/src/test/.../ArchiveFormatTest.kt` | keys derived from the serializer; count pinned |
| `data/repository/src/test/.../QuickSetupRepositoryTest.kt` | 4 household tests |
| `build-logic/convention/.../ProjectExtensions.kt` + both library plugins | `configureOwnSourceAsTestInput()` |
| `docs/adr/0069-*.md` | the ADR |

---

## 5 · Quiz

1. **Why is a household total computed with a loop instead of one SQL query?** Because the loop keeps
   money arithmetic in the deterministic layer and makes a cross-profile leak structurally
   impossible; the query would be faster and would remove the guarantee.
2. **Why can't a comment-only change be invisible to Gradle any more?** Because
   `configureOwnSourceAsTestInput()` declares each module's `src/main` as a test input — a comment
   compiles to identical bytecode, so without it the task is UP-TO-DATE and replays its last pass.
3. **What has to be true before `HouseholdMode.IS_ENABLED` may become `true`?** The 14 id-keyed
   queries must constrain `profile_id`, and demo mode must stop defaulting into the real household.
4. **Why is `household` not in `wipe()`?** It is not profile-scoped; a per-profile restore must not
   delete a row another profile may belong to. `REPLACE` on insert makes it unnecessary anyway.
