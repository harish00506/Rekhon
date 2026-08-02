# ADR-0009 — Split lines are a child table that moves no money; the remainder rule is largest-remainder, not HALF_EVEN

- **Status:** accepted
- **Date:** 2026-08-02
- **Deciders:** Harish G (solo), implementing issue 3.3
- **SRS refs:** §5.3 (FR-TXN-004, FR-TXN-001), §20.1 (table inventory), §20.2 (`transactions` DDL),
  §20.3 (DB-001, DB-002, DB-003, DB-004), MNY-001, P-03

## Context

FR-TXN-004 is a MUST:

> Split transactions MUST allow N lines with independent categories; lines MUST sum exactly to the
> parent amount (validated, no rounding drift).

Issue 3.2 had just answered a structurally similar question for transfers and answered it the *other*
way — [ADR-0008](0008-transfers-as-linked-legs.md) chose two linked sibling rows in `transactions`
over a `transfers` parent table. Choosing a parent/child table here needs the difference spelled out,
or the two decisions look arbitrary side by side.

**§20.1 lists `transaction_splits`** in the Transactions domain. As with `transfers`, §20.2's
abridged DDL never defines its columns, so the shape is this issue's to choose.

Two other facts shaped the decision.

**A split line does not move money.** A transfer's two legs are both real balance-affecting rows —
that is precisely why they belong in `transactions`. A split line is not: the parent holds the whole
amount and every balance already sums it. The lines only say what that one amount was *about*.

**The generated issue doc was wrong about the arithmetic.** It said "the remainder is distributed via
HALF_EVEN" and told the implementer to use `Money.splitExact`. Neither exists. The real API is
`Money.split` / `Money.allocate`, and the algorithm is **largest-remainder (Hamilton)**; HALF_EVEN
appears only in `Money.percentOf`. **The SRS itself never says HALF_EVEN here** — it says "no
rounding drift", which is a different and stronger requirement.

## Decision

**1. Split lines live in their own table, `transaction_splits`, keyed by `transaction_id`.**

Columns: `id`, `profile_id`, `transaction_id`, `amount_minor`, `category_id?`, `note?`, and the
standard `created_at` / `updated_at` / `deleted_at`. Schema v6 → v7, purely additive.

**2. The parent is an ordinary transaction and the lines are weightless.** The parent carries the
amount, moves the balance, and carries **no `category_id`** — the lines carry those. No balance query
changes at all.

**3. Lines are signed like their parent**, so "the lines sum to the parent" is one comparison of two
signed `Money` values rather than a rule about magnitudes plus a direction.

**4. The exact-sum rule is enforced by refusal, never by adjustment.** `SplitDraft.validated()`
rejects a draft whose lines do not sum to the parent exactly — no tolerance, because paise are
integers.

**5. "Split evenly" is `Money.split`, and largest-remainder is the correct rule.** ₹1,000 across
three lines becomes 333.34 / 333.33 / 333.33. The issue doc's HALF_EVEN instruction is corrected at
source in `scripts/gen_issue_docs.py`.

**6. `profile_id` is denormalised onto every line**, duplicating its parent's.

## Consequences

**Good.**

- **No balance code was written or changed for this feature.** DB-001's derivation and ADR-0007's
  "the balance is derived, the column is a cache" are untouched, and a split moves an account by its
  parent amount exactly once — observed on the emulator, and pinned by
  `a split moves the balance once, not once per line`.
- **Deleting a parent takes its lines** in the same database transaction (DB-004), so no line can
  outlive the amount it attributes.
- **Budgets (4.4) and Need/Want/Invest (4.3) inherit the right shape**: they will sum split *lines*
  where a transaction is split and the parent where it is not, rather than being stuck with one
  category per purchase.
- **`profile_id` on the line keeps the demo wipe a single-table, profile-scoped delete** (ADR-0006).
  `DemoDao.countRowsFor` counts the new table, so a future table that forgets this reddens the
  residue test rather than shipping quietly.

**Bad, and accepted.**

- **A second table to read.** The recent list needs each transaction's lines, which is a `@Relation`
  and therefore a second query under the hood. It replaced a `combine` of two `Flow`s, which was
  worse in a way worth recording: `combine` calls `yield()` internally, and
  `UnconfinedTestDispatcher` refuses it — every repository test that read the list died on the
  dispatcher rather than on anything about the data.
- **`@Relation` cannot carry a `WHERE`**, so soft-deleted lines arrive from the DAO and are filtered
  in the repository's mapper. One filter, at one mapping site, but it is a rule a reader must know.
- **No foreign key on `transaction_id`.** No table in this schema declares one; adding it here alone
  would make this the only place a delete order is enforced by SQLite, and DB-002 soft-deletes
  parents anyway, so the constraint would never fire on the case that matters.
- **A line's amount is unsigned in the UI and signed in the store.** The user types "600" and the
  ViewModel applies the parent's sign at save. Mixing the two conventions is exactly how the running
  remainder first read as double the amount during implementation.

**Why this differs from ADR-0008, in one line:** transfers are two rows that both move money, so a
parent row would have held no fact; splits are N rows that move no money, so a child table costs
nothing and keeps them out of every balance.

## Alternatives considered

**N child rows in `transactions`, linked by a `split_id`** — the transfer shape. Rejected because the
parent and its lines would both be in `SUM(amount_minor)`, so every split would double-count. Fixing
that means excluding one of them from every balance query in the app — the account list, the
single-account read, net worth as-of, and the nightly integrity job — for a feature that is not about
balances at all.

**Lines replacing the parent** (no parent row; the lines *are* the transaction). Sums correctly and
needs no exclusion, but it destroys the concept of "one purchase": the list, the merchant, the
receipt (issue 3.8) and any future duplicate-guard all key off a single transaction, and there would
no longer be one.

**HALF_EVEN on each line**, as the issue doc instructed. Rejected because it cannot meet the
requirement: rounding each of three ₹333.333… shares half-to-even gives 333.33 three times, which is
₹999.99 — the exact "rounding drift" FR-TXN-004 forbids. HALF_EVEN rounds *one* value well; only a
remainder-distribution rule can make N parts sum to a whole.

**Storing a percentage per line** instead of an amount. Tempting for "split this evenly" and hopeless
for everything else: percentages of a paise-denominated amount reintroduce rounding at every read,
and the user usually knows the amounts, not the ratios.
