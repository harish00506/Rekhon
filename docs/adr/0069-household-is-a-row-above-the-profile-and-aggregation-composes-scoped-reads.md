<!--
  Why:  CLAUDE.md §5 — any decision or deviation from the SRS needs an ADR, and issue 13.1's AC3
        asks for one explicitly.
  What: issue 13.1 — how household → profiles is modelled, why aggregation is forbidden from
        reaching across profiles in SQL, what the flag holds back, and the residual risk the
        fourteen id-keyed queries carry into multi-profile life.
  Result: a reader can see why the scoping rule is a test over the DAO rather than a convention,
          why the aggregation views are specified but not built, and what must be true before the
          flag is allowed to go on.
  Changelog: 2026-10-03 — Created.
-->

# ADR-0069 — A household is a row above the profile, and aggregation composes scoped reads

**Status:** Accepted · **Date:** 2026-10-03 · **Issue:** 13.1 · **SRS:** §27, §33 · **Rules:** P-01, P-02, P-03, P-08, ARC-005, DB-003

## Context

Household mode is an Epic 13 *design-for* item: v1 ships the foundation, not the feature. Before
designing anything, the existing scoping was measured rather than assumed:

| Measurement | Count |
|---|---|
| Entities carrying a `profile_id` column | **33** |
| `@Query` functions that take a `profileId` parameter | **95** |
| …of those, that actually filter on it | **95** (all) |
| `@Query` functions touching a scoped table with **no** profile filter | **15** |
| …deliberately device-wide | **1** (`SmsDraftDao.deleteAllPending`) |
| …keyed by a row id instead | **14** |

So scoping is in good shape today, and the reason is worth stating precisely: **today there is
exactly one profile per device.** Every one of those 95 filters is correct, and the 14 id-keyed
queries are *also* correct — but only because the id their caller holds came from a read that was
itself profile-filtered. That is a transitive argument, and household mode is exactly the change
that breaks the premise it rests on.

The word "household" appeared nowhere in the code — only in prose.

## Decision

### 1 · A household is one row above the profile, not a column inside it

Schema **30** adds a `household` table and a `profile.household_id` column. One household has many
profiles; a profile belongs to exactly one household. The alternative — a `household_member` join
table — was rejected: it buys many-to-many, nothing in §27 or §33 asks for a profile in two
households, and a join table would make "which household is this profile in?" a join rather than a
column read on the row the app already has loaded.

`household` itself carries **no** `profile_id`, so it is not a profile-scoped table and it holds no
money. It holds a display name and a creation timestamp. The migration is additive (DB-003): it
creates the table, inserts one household dated as its oldest profile, and adds the column with that
household as its SQL default, so an upgrading user and a fresh install land in the same shape and
no row is ever null.

### 2 · Strict scoping is a test over the DAO, not a convention in a doc

"No cross-leak" is a property of 100+ SQL strings, which is exactly the kind of rule that holds on
the day it is written and erodes quietly afterwards. `ProfileScopingTest` reads `Daos.kt` and
requires every `@Query` on a profile-scoped table to be one of three things:

- **profile-filtered** — it constrains `profile_id`;
- **id-keyed** — it constrains a row id the caller holds;
- **explicitly device-wide** — it carries a `// DEVICE-WIDE:` marker and a reason of real length in
  its own doc comment.

The third category is the point. `SmsDraftDao.deleteAllPending` genuinely must cross profiles — SMS
consent is granted for the device, so a revocation scoped to whichever profile happened to be on
screen would leave the other's drafts on disk. That reasoning was already written in the DAO as
prose; this ADR makes it a **marker the test requires**, so the count of device-wide queries is
pinned at one and adding a second is a deliberate act somebody has to write a reason for.

### 3 · Aggregation composes scoped reads; cross-profile SQL stays banned

The tempting implementation of a household net-worth view is one query without the `profile_id`
clause. That is rejected outright, and the scoping test is what enforces the ban.

Household figures are instead computed as **N scoped reads summed in Kotlin**: for each member
profile, run the existing per-profile engine, then add the results as `Money`. This costs a loop and
buys three things — every number keeps the provenance the per-profile engine gave it (P-02), the
arithmetic stays in the deterministic layer rather than in SQL (P-03, P-08), and a bug in the
aggregate can never *leak* one profile's rows into another's view, because no statement ever selects
across profiles.

**The views, specified:**

| View | Shows | Composed from |
|---|---|---|
| Household net worth | Sum of each member's net worth, with the per-member split always visible | `NetWorthEngine` per profile |
| Household cash flow | Combined inflow/outflow for a month, per-member attributed | `CashFlowEngine` per profile |
| Shared obligations | Bills and EMIs due, labelled with the member who owns them | `RecurringEngine` per profile |

Every view is **additive and attributed** — there is no household figure that cannot be broken back
down into the member rows that produced it. A household total with no attribution would violate P-02
and would also be the first place a privacy complaint lands.

### 4 · The flag holds back the views, not the foundation

`HouseholdMode.isEnabled` is `false` in v1. The foundation — the schema, the scoping test, the
marker convention — ships unconditionally, because it is all either inert data or a build-time
check. What the flag holds back is the aggregation entry point, which returns a disabled error
rather than a wrong number.

### 5 · The fourteen id-keyed queries are the residual risk, and this is the precondition

An id-keyed query is safe when the id came from a scoped read. Once a device holds two profiles, any
code path that obtains an id from somewhere else — a notification payload, a widget, a deep link, a
restored backup — can hand an id from profile A to a query serving profile B, and no SQL in this
repository would stop it.

**Before `HouseholdMode.isEnabled` is allowed to become `true`, every id-keyed query must also
constrain `profile_id`.** Demo mode is a second precondition: its profile currently
defaults into the same household as the real one, which is harmless while the flag is off and the
demo is wiped on exit, but would list demo data as a household member the day it is not. That is a mechanical change to 14 queries plus their callers, deliberately
not done now: making it today would add a parameter to 14 functions to defend against a
configuration no user can reach, and the change is far safer written beside the feature that needs
it, with tests that can actually create a second profile.

## Consequences

- Adding a query that crosses profiles fails `:core:database`'s tests, with the three categories and
  the marker convention named in the failure message.
- Adding a database version remains the five-step checklist in `Migrations.kt`; schema 30 follows it.
- The 14 id-keyed queries are recorded here as a precondition rather than a TODO in code, so turning
  the flag on starts by reading this ADR.
- A fourth instance of the repository's recurring staleness bug was found and fixed (see below).

## The gate could be skipped, for the fourth time

`ProfileScopingTest` reads Kotlin **source** at runtime, and most of what it checks lives in
**comments**. A comment-only edit compiles to byte-identical classes, so Gradle found nothing changed
on the task's declared inputs and replayed the previous **pass**: deleting the `// DEVICE-WIDE:`
marker left `:core:database:testDebugUnitTest` UP-TO-DATE and the build green in 1s, while the same
edit under `--rerun-tasks` failed two assertions. The gate was right; only its scheduling was wrong.

This is the same bug issue 7.2 fixed for one data file, issue 11.5 fixed for the rest, and issue
11.7 extended to `docs/security/` — each time for *data* a test reads. A test that reads *source* was
still exposed. `configureOwnSourceAsTestInput()` declares each module's own `src/main` as an input of
its test tasks, so an edit to a comment the tests check can no longer be invisible to Gradle.

## Alternatives considered

- **A `household_member` join table** — rejected, §1: many-to-many nobody asked for.
- **One cross-profile SQL view per aggregate** — rejected, §3: faster, and it puts money arithmetic
  in SQL while removing the structural guarantee against leaks.
- **Scoping enforced by a custom lint rule rather than a test** — considered seriously, since the
  repo already publishes six detectors. Rejected because the rule is about the *text of SQL strings
  and the comments above them* in one known file, which a test reads directly and clearly; a lint
  detector would need UAST plumbing to reach the same strings and would report at a weaker moment.
- **Adding `profile_id` to all 14 id-keyed queries now** — rejected, §5: unreachable defence today,
  and safer written with the feature that makes it reachable.
