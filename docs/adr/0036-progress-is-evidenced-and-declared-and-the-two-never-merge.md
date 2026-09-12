# ADR-0036 — Goal progress is evidenced *and* declared, and the two never merge

**Status:** Accepted · **Date:** 2026-09-06 · **Issue:** 7.4 — Linked contributions
**Requirements:** §15, FR-GOAL-002, FR-GOAL-004 · **Supersedes nothing; pays ADR-0033's debt**

---

## Context

Issue 7.1 shipped `goal.saved_minor` as a number the user types, and said so in three places — the
entity's own KDoc, [ADR-0033](0033-goals-mint-no-rulebook-row.md) (*"Until 7.4, a user who saves
towards a goal must tell the app"*) and the 0.7.1 changelog. Everything stacked on it since has been
computed from a self-declared figure: 7.2's runway, 7.3's waterfall and its three levers, and
Safe-to-Spend's goal term.

§15 asks for the opposite:

> Progress is transaction-evidenced (FR-GOAL-004); "ghost progress" (manual claims without linked
> funds) is visually distinct.

**FR-GOAL-004 (MUST):** *"Contributions MUST be linkable to real transactions/accounts so progress is
evidence-based, not self-declared."* **FR-GOAL-002 (MUST)** additionally gives every goal *funding
accounts*, which nothing had built.

ADR-0033 deliberately deferred the table: *"a `goal_contribution` table now — builds 7.4's data model
before 7.4 has decided what links to what."* This is that decision.

## Decision

### 1. Two join tables, and neither holds an amount

`goal_contribution(goal_id, transaction_id)` and
`goal_funding_account(goal_id, account_id, linked_from_iso_date)`, at schema **22**. Both follow
`transaction_tags` exactly: denormalised `profile_id`, three stamps, soft delete, and a **unique
index on the pair**.

Neither stores money. A contribution *is* the linked transaction's `amount_minor`, summed at query
time by `GoalDao.observeEvidenced` — [ADR-0009](0009-splits-as-a-child-table.md)'s argument that the
parent holds the money and the child holds only the relationship, and
[ADR-0007](0007-account-balances-derived-not-stored.md)'s that a stored copy drifts the moment the
source is edited. MNY-001 is then satisfied trivially: **no new column holds a monetary value at
all**, so there is nothing here for `CfoMoneyAsFloatingPoint` to catch and nothing to round.

### 2. The two link kinds sum differently, on purpose

| Link | Sum | Why |
|---|---|---|
| explicit transaction | `ABS(amount_minor)` | linking is the user asserting *this movement funded the goal*. A ₹5,000 SIP debit is stored negative and still funds it |
| funding account | **signed** `SUM(amount_minor)` from `linked_from_iso_date` | the claim is about a *pot*, so what counts is how much it grew: an outflow reduces it, and dedicating both sides of an internal transfer nets to zero instead of counting twice |

A movement that is inside a dedicated account **and** explicitly linked to that same goal counts
**once**, via the explicit link — `NOT EXISTS` against the explicit set, scoped per goal. Both halves
are bounded by `booked_on_iso_date <= today`, the same bound every balance query uses: a future-dated
transaction (issue 3.4) has not happened, so it cannot be progress.

### 3. Progress becomes two numbers that never merge

`GoalSpec.savedEvidenced` and `GoalProjection.savedEvidenced` / `savedDeclared`, with
`savedEvidenced + savedDeclared == saved` enforced by an `init` — the same shape of guard `GoalPlan`
puts on its citation. **`goal.saved_minor` is not removed**: §15 keeps manual claims and asks only
that they be shown as distinct, so the row stays exactly as the editor wrote it and the evidenced
half is derived *beside* it.

Both defaults keep every pre-7.4 caller compiling and every golden record valid: an untouched profile
reads as `evidenced = 0, declared = saved`, which is the truth about it.

**The pair is floored together.** A dedicated account that has paid out more than the declared figure
would otherwise produce negative progress. The clamp is applied to the *evidenced* half at
`-declared`, not to the total at zero, so `saved - savedEvidenced` still equals exactly what the user
typed. Flooring the total on its own would have made the declared half come out as a number they
never entered.

### 4. Reversal is a soft delete, because the criterion asks for two things at once

*"Unlinking reverses it; provenance is retained."* A hard `DELETE` does the first and destroys the
second. The row keeps its `created_at`, stops counting, and a re-link **revives** it rather than
minting a second — which the unique index makes a rule rather than a convention.

### 5. `RULE-PAY-FIRST` is consumed, and mirrors nothing

The row has named `AI-GOAL` in `consumed_by` since the rulebook was written and had no reader — the
third such row, after `RULE-HORIZON` (7.1) and `RULE-EMERG-FIRST` (7.3). Its `params_json` is
`{"anchor": "salary_credit_day"}`: **a source name, not a threshold**. So there is nothing to mirror
and nothing to drift. The day itself is the profile's own fact — quick setup (issue 2.3) already
writes an `income` recurring rule whose `next_due_iso_date` carries it — and it reaches the engine as
`GoalPlanInput.contributionAnchorDay`, exactly as ADR-0035 routes `emergencyGateMonths`.

`RulebookDriftTest` asserts the row is enabled, still addressed to `AI-GOAL`, at the cited version,
and **still anchored on the salary day**: if `anchor` were changed to `month_end` the engine would go
on citing a rule whose advice had inverted while every arithmetic test stayed green.

### 6. No rulebook row minted

`rules-kb.json` `_meta.version` stays **1.15.0** and the six typed mirrors are untouched — 7.1's and
7.3's precedent, and on the merits: summing linked movements is arithmetic, and the salary day is a
fact rather than a threshold. `RulebookDriftTest` asserts the **absence** of the three keys somebody
would reach for (`contribution_lookback_months`, `min_contribution_minor`,
`ghost_progress_tolerance_pct`), so a future threshold has to arrive deliberately.

### 7. A route, not another line on the goal card

`CfoRoute.GoalDetail(goalId)`, modelled on `Holdings(accountId)`. The goal card already carries three
competing "monthly" figures — 7.1's required, the user's planned, 7.3's allocated — and the 7.3
session recorded what adding a second measurement did to the sentences already there. The card gains
exactly one new line, the ghost note; everything else lives on the new screen.

### 8. The archive gap, found by building on top of it

`goal` was **missing from `CfoArchive`** since 7.1, so every export taken between 7.1 and 7.4
silently dropped the user's goals — and `rowCount()` had never counted eight of its lists, so the
"rows imported" figure the user is asked to check was under-reported. `DemoDao.countRowsFor` likewise
omitted `goal`, `investment_holding` and `investment_lot`, and the demo wipe never deleted any of
them. All fixed here, because shipping two more tables into an archive that dropped their parent
would have compounded it.

`CfoArchive.VERSION` stays **1**: the three new lists default to empty and `Json` is configured with
`ignoreUnknownKeys`, so neither direction breaks. The gate that actually refuses an archive this
build cannot restore is `schemaVersion`, which moves 21 → 22 on its own.

## Consequences

- A goal's progress is now the sum of movements in the user's own ledger, and the part that is not
  is labelled as such and clearable in one tap.
- **Safe-to-Spend moves.** `requiredMonthly` falls as `saved` rises, and `RULE-STS` subtracts the
  total — so linking a contribution raises the headline figure. That is correct and was previously
  impossible.
- The editor now loads the **declared** half. Loading the total, as it did before this issue, would
  have folded the evidenced half into `saved_minor` on every edit and doubled it on the next read —
  a defect created by this change in code that did not change, which is the 7.3 session's §2b.2
  pattern for the second time.
- `AI-GOAL` moves to engine version **1.1**: every existing figure is computed identically, but the
  input can now say more.

## Alternatives considered

| Option | Why not |
|---|---|
| An `amount_minor` on the link | drifts the instant the transaction is edited; two representations of one movement is ADR-0009's double-count |
| Replace `saved_minor` outright | §15 explicitly *keeps* ghost progress and asks only that it be distinct; deleting it would discard what every existing user has typed |
| `ABS` for funding accounts too | a withdrawal would then *increase* progress, and dedicating both legs of a transfer would double it |
| Signed amounts for explicit links | a SIP debit is stored negative; linking it would *reduce* the goal it funds |
| Dedupe across all goals | a movement may legitimately evidence two goals; those are separate claims. The exclusion is per goal |
| Count the funding account's whole balance | that is an opening balance the goal never received, and it would jump the instant the account was linked |
| No `linked_from_iso_date` | dedicating an account with two years of history would credit the goal with all of it, silently |
| Link from the transaction detail sheet | `:feature:goals` and `:feature:transactions` would have to know about each other (ARC-001). A route is the sanctioned way |
| A fourth section on the goal card | the 7.3 contradiction, deliberately repeated |
| Paparazzi screenshots for the new screen | `:feature:goals` uses Robolectric Compose tests, as `:feature:accounts` and `:feature:budgets` do (ADR-0033's last row) |

## References

- SRS §15, §29.2; FR-GOAL-002, FR-GOAL-004; `RULE-PAY-FIRST` v1.0
- [ADR-0033](0033-goals-mint-no-rulebook-row.md) — deferred this table, and named the three debts it pays
- [ADR-0035](0035-the-waterfall-substitutes-an-observed-p50-and-mirrors-nothing.md) — the citation-not-a-mirror rule this follows
- [ADR-0009](0009-splits-as-a-child-table.md), [ADR-0007](0007-account-balances-derived-not-stored.md) — derive, never store
- [ADR-0008](0008-transfers-as-linked-legs.md) — why the picker offers a transfer once
- [ADR-0023](0023-archive-replaces-and-carries-no-blobs.md) — the archive this repairs
- `domain/engines/goals/ENGINE.md` — the contract and the version log
