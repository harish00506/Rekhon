# ADR-0006 — Demo mode is a second profile in the same database, wiped by hard delete

- **Status:** accepted
- **Date:** 2026-07-28
- **Deciders:** Harish G (solo), implementing issue 2.4
- **Refs:** SRS §5.1 **FR-ONB-004**, CLAUDE.md §3 (DB-003), `core/database/.../Entities.kt`
  (the soft-delete invariant), ADR-0004 (the `budget` / `recurring_rule` schema)

## Context

FR-ONB-004 (SRS §5.1, p.12, priority **SHOULD**):

> A demo mode with realistic sample data MUST be available **without creating a profile**.

Issue 2.4's acceptance criteria sharpen that into two properties the implementation has to
guarantee rather than claim:

1. the sample data is loaded into an **isolated, clearly-marked** place, and
2. leaving the demo **wipes it with no residue in the real profile**.

Two decisions follow, and both cut against a rule this codebase otherwise holds firmly.

> **Note on the issue file's citation.** `docs/issues/2.4-demo-mode-with-sample-data.md` cites
> "SRS §5.1, §33 (demo)". §33 in v1.7 is the *Growth Optimizer Suite (AI-GRW)* and has nothing to do
> with demo mode; the only demo requirement in the SRS is FR-ONB-004. The generator
> (`scripts/gen_issue_docs.py`) was corrected rather than the generated file.

## Decision 1 — isolation by profile id, not by a second database

Demo rows are written under a second profile id, `demo`, alongside the user's `local` profile in the
same encrypted database.

**Why this and not a second SQLCipher file.** Every table in this schema already carries `profile_id`
and every query is already scoped by it (issue 1.6's stated invariant), so isolation costs nothing
and is enforced by queries that already exist. A second database file would need its own
Keystore-wrapped passphrase, its own Room instance, a DI swap on a flag, and its own migration story
— a substantial amount of security-critical surface for a SHOULD-priority feature, and every line of
it a new place for a key-handling mistake.

**What makes the isolation real rather than nominal:** exactly one class knows the demo profile id
(`DemoModeRepository.DEMO_PROFILE_ID`), it is a `const` that cannot be configured, and the switch
between the two profiles is expressed in exactly one place — `DemoModeRepository.activeProfileId`.
Readers take that flow and never decide for themselves, so a screen cannot end up asking the wrong
profile. `DemoModeRepositoryTest` asserts a real profile's rows are byte-identical before and after a
full demo round trip.

**Consequence to accept:** demo and real rows share tables, so any future query that forgets its
`profile_id` predicate would span them. That predicate is already mandatory (issue 1.6) and
review-checked; this decision raises the cost of forgetting it from "wrong profile's data" to "the
user's data mixed with fabricated data", which is worse. `DemoDao.countRowsFor` exists partly so the
boundary stays assertable as tables are added.

## Decision 2 — the wipe is a hard `DELETE`

`DemoDao` is the only DAO in the app that deletes rows outright. Everywhere else, deletion sets
`deleted_at_utc_millis` and the row stays.

**Why the exception.** The soft-delete invariant exists for two reasons, and neither applies here:
recoverability (an accidental delete of the user's own data must be undoable) and sync (a future
device needs to see the tombstone). Demo rows are fabricated by the app; nobody can want them back,
and there is nothing to synchronise. Against that, the acceptance criterion is explicit that exiting
leaves **no residue** — and a tombstone is residue. A soft-deleted demo transaction would still be a
row in the user's encrypted database describing money that never existed.

**How the exception is kept narrow:**

- every query in `DemoDao` takes `profileId` as a parameter — there is no "delete everything" — so
  the worst a bug can do is erase the profile it was handed, and the only id ever handed to it is
  the demo one;
- `audit_log` is untouched: it has no `profile_id` and is append-only by design (SEC-002), and
  security events are not the demo's to erase;
- `DemoDao.countRowsFor` deliberately does **not** filter `deleted_at_utc_millis`, so a soft delete
  would leave the count non-zero and fail the residue test. That gate was verified to fail by
  swapping one `DELETE` for an `UPDATE … SET deleted_at` before being trusted.

**DB-003 is not violated.** DB-003 forbids *destructive migrations* — losing user data to a schema
change. This deletes fabricated rows on explicit user action, and needs no schema change at all:
adding a DAO adds queries over existing tables, so `CfoDatabase.VERSION` stays at 3.

## Decision 3 — the demo writes no profile settings

Entering the demo sets exactly one setting: `demo_mode_active`. It does not set the time zone, the
currency, the display name, or `onboarding_completed_at_utc_millis`.

This is FR-ONB-004's "without creating a profile" taken literally, and it is what makes leaving the
demo return the user to first-run onboarding rather than to an empty dashboard belonging to a profile
they never made. `MainViewModel` therefore reads **both** flags to decide the start destination: a
demo user has no completed onboarding, so the onboarding flag alone would send them back to the
welcome screen with their sample data still loaded.

## Alternatives rejected

| Alternative | Why not |
|---|---|
| Separate encrypted DB file | A second Keystore key, Room instance, DI swap and migration story — security-critical surface out of proportion to a SHOULD feature. |
| In-memory demo, never persisted | Zero residue by construction, but every repository grows a demo branch forever, and the demo would stop exercising the real read/write paths — proving nothing about the app. |
| Soft-delete the demo rows | Fails the acceptance criterion: a tombstone is residue, and the rows would accumulate across repeated demos. |
| A `is_demo` column on every table | A schema change to all six tables plus a migration, to express something `profile_id` already expresses. |

## Consequences

- The demo exercises the **real** storage path, so it is worth something as a smoke test of the app
  as it grows: issues 2.5, 3.6 and 4.1 will render demo accounts, transactions and categories with
  no further work here.
- Every reader added later inherits demo-awareness for free by calling the no-argument
  `observeLatestEnvelopes()` (or its equivalent) rather than naming a profile.
- A future household mode (issue 13.1) introduces generated profile ids; `demo` must stay reserved,
  and the wipe must never be handed a user's id. Both are guarded by the `const`.
