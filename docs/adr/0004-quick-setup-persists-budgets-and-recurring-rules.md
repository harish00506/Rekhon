# ADR-0004 — Quick setup defines `budget` and `recurring_rule` at schema v3

- **Status:** accepted
- **Date:** 2026-07-27
- **Deciders:** Harish G (solo), implementing issue 2.3
- **SRS refs:** §5.1 (FR-ONB-002), §5.5 (FR-BUD-001), §5.3 (FR-TXN-006), §20, DB-003, MNY-001, TIM-002

## Context

FR-ONB-002 says the quick-setup figures are "used to seed budgets and the emergency-fund target".
Issue 2.1 captured them into `cfo_settings.proto`; issue 2.3 is the issue that consumes them.

The problem is that **none of the tables those figures belong in exist**, and none of the issues
that own them is a dependency of 2.3:

| Artifact | Owned by | Status |
|---|---|---|
| `budget` | issue **4.4** (Budgets CRUD + suggestions) | not built; depends on 4.1 → 3.5 → … |
| `recurring_rule` | issue **3.7** (Recurring detection) | not built |
| an account to attach a rule to | issue **2.5** (Accounts CRUD) | not built |
| a `goal` row for the emergency-fund target | issues **7.1 / 7.2** | not built |
| **a `profile` row** | nothing — never written by any issue so far | **missing** |

That last one is the sharpest: every table in this app is scoped by `profile_id`, and issue 2.1
wrote the profile into DataStore *settings* only, because it had no financial data to scope.

Three options were considered, and the user chose the third (see Alternatives).

## Decision

**Persist real rows now.** Schema v2 → v3 adds two tables, and quick setup writes a `profile` row,
three nature-level budget envelopes and up to three recurring rules in **one transaction**.

Four constraints shape the columns, so that 4.4 / 3.7 / 2.5 **extend** rather than rewrite:

1. **Every forward-looking foreign key is nullable.** `budget.category_id` and
   `recurring_rule.account_id` / `.category_id` are `NULL` at first run because neither a category
   nor an account exists. Under DB-003 a nullable column added now is free; a `NOT NULL` one
   guessed now would need a migration to relax.
2. **A budget is per category *or* per nature.** FR-BUD-001 allows "per category or
   category-group", so `budget` carries both `category_id` and `nature` and uses whichever it has.
   Quick setup writes the nature form; issue 4.4's editor writes the category form into the **same
   table**.
3. **No English in a data row.** `recurring_rule.seed_kind` holds `income` / `rent_emi` / `savings`
   and the UI resolves it to a `strings.xml` entry (§21.6). A stored "Rent or EMI" could never be
   translated. `name` stays null, reserved for issue 3.7's merchant-named rules, which genuinely
   are data.
4. **Provenance travels with the row (P-02, AI-ARC-006).** `source`, `rule_id` and `rule_version`
   record that this envelope came from `RULE-50-30-20` v1.0, so the user's drill-down can reproduce
   the figure months later even if the rulebook has since been edited.

**The migration is purely additive** — two `CREATE TABLE IF NOT EXISTS` plus indices, touching no
existing table, column or row. `MigrationSafetyTest` proves that structurally on the JVM;
`MigrationRoundTripTest` gained a 2 → 3 case which has **not been run** (no device on this machine).

**The first profile's id is the constant `"local"`.** Nothing in the app generates ids, and
`UUID.randomUUID()` would break P-08 determinism *and* silently create a second profile on every
re-run, orphaning the first one's data. A constant makes `applySeeds` idempotent by construction.
Issue 13.1 (household mode) is where additional members get generated ids; this one keeps its name,
so existing rows never need re-scoping.

**Ids are derived, not generated** — `<profile>:budget:<nature>:<period>` and
`<profile>:recurring:<kind>`. With `OnConflictStrategy.REPLACE` that is what makes re-running
onboarding update three envelopes instead of adding three more.

**`:data:repository` depends on `:domain:engines:quicksetup`** so the repository can take a
`QuickSetupPlan` and return `BudgetEnvelope` directly. This follows the existing precedent — the
module already depends on `:domain:usecase` — and avoids a duplicate set of mapping types whose
only purpose would be to be identical.

## What each later issue is expected to add

- **Issue 2.5 (accounts):** sets `recurring_rule.account_id` on the seeded rows once the user has
  an account. No schema change needed.
- **Issue 3.7 (recurring detection):** writes rows with `source = 'detected'`, a `name`, and
  cadences beyond `monthly`. Likely adds `day_of_month` / `last_matched_at_utc_millis` — additive.
- **Issue 4.1 / 4.3 (categories):** sets `category_id` on both tables.
- **Issue 4.4 (budgets):** writes per-category budgets into `budget` with `source = 'manual'` and no
  rule citation, and owns the rollover behaviour `rollover_enabled` reserves. May add
  `carried_over_minor` — additive.
- **Issue 7.2 (emergency fund):** the emergency-fund target is currently **computed, not stored** —
  it is `needs × runway`, derivable from the persisted envelope. 7.2 introduces the `goal` table and
  materialises it there.

## Consequences

**Good.** The seeds become real data on day one: the dashboard's spending split is now the user's
own budget rather than three hardcoded constants, and the first `profile` row finally exists, which
unblocks every per-profile query the app will ever run. Both tables are the ones the SRS describes
rather than a temporary shape to be migrated away from.

**Bad.** Issue 2.3 has defined columns that belong to issues 4.4 and 3.7, without their screens to
validate the shape against. If one of those columns turns out wrong, DB-003 makes it a migration
rather than an edit. The nullable foreign keys and the additive-only migration are what keep that
cost bounded, and this record is what makes the guess visible to whoever hits it.

**Neutral.** `budget` and `recurring_rule` join `audit_log` as tables introduced by an issue other
than the one that owns their feature. Requirement traceability (§28) now maps FR-BUD-001 and
FR-TXN-006 partly to issue 2.3.

## Alternatives considered

**Derive on demand, persist nothing.** The plan is deterministic and the seeds are already stored,
so the envelopes could be recomputed whenever needed, with 4.4 and 7.2 materialising rows later.
Rejected by the user in favour of real rows. It was the smallest change and would have avoided
guessing at two schemas, but it leaves the acceptance criterion ("values create the matching
accounts/recurring records") unmet in any literal sense, and leaves the app with no `profile` row
for longer.

**Persist the derived plan into `cfo_settings.proto`.** No migration, and the widget could read it
without recomputing. Rejected: it duplicates state that goes stale the moment a rule threshold is
edited, and a budget is not a setting.

**Wait for 4.4 and 3.7.** Rejected: it inverts the backlog's dependency order — both are several
issues away behind categorisation and transactions — and leaves FR-ONB-002 collecting figures that
nothing uses for that whole time, which is the state issue 2.1 already flagged as unsatisfactory.
