# ADR-0008 — A transfer is two linked legs, not a parent row; `type` is derived, never supplied

- **Status:** accepted
- **Date:** 2026-08-02
- **Deciders:** Harish G (solo), implementing issue 3.2
- **SRS refs:** §5.3 (FR-TXN-003, FR-TXN-001, FR-TXN-009), §20.1 (table inventory), §20.2
  (`transactions` DDL), §20.3 (DB-001, DB-002, DB-003, DB-004), MNY-001, P-03

## Context

FR-TXN-003 is a MUST:

> Transfers MUST be a single logical record affecting two accounts atomically; deleting one side
> deletes both.

Two things in the SRS's own schema bear on how to build that, and the codebase matched neither
before this issue.

**§20.1 lists a `transfers` table** in the Transactions domain, alongside `transactions`,
`transaction_splits` and the rest. It is named in the inventory but §20.2's abridged DDL never
defines its columns — the only transfer-related column it does define is `transactions.transfer_id
FK NULL`.

**§20.2 gives `transactions` a `type` column** the app did not have:

```
transactions(id PK, profile_id FK, account_id FK, type TEXT CHECK(type IN
  ('expense','income','transfer_out','transfer_in','adjustment')),
  ... transfer_id FK NULL, ...)
```

Issue 3.1 deliberately shipped without `type`. Its `Transaction` doc comment said so explicitly:

> There is no separate `type` field for expense/income — the sign *is* the type, and storing both
> would let the two disagree.

That was defensible while every row belonged to one account and every direction was a sign. It stops
being defensible here, because FR-TXN-003 requires a transfer leg to be **excluded from income and
expense totals**, and the sign cannot tell a `transfer_out` from an ordinary `expense` — both are
negative.

A third fact constrains the implementation: SQLite's `ALTER TABLE … ADD COLUMN` **cannot add a
`CHECK` constraint**, and DB-003 forbids the destructive table-rebuild that would be needed to get
one. So §20.2's `CHECK(type IN (…))` is unenforceable at the schema level on any database that
already exists.

## Decision

**1. A transfer is two rows in `transactions` sharing a `transfer_id`. There is no `transfers`
table.**

The outgoing leg is negative and typed `transfer_out`; the incoming leg is positive and typed
`transfer_in`. Both are written inside one `withTransaction` (DB-004), share one `transfer_id`, one
`occurred_at_utc_millis` and one `booked_on_iso_date`, and carry no category.

**2. `transactions.type` is adopted from §20.2 — all five values.**

`expense` and `income` from the sign, `transfer_out`/`transfer_in` from which leg is being written,
`adjustment` for issue 2.7's FR-ACC-006 balance corrections.

**3. No caller ever supplies a `type`.** It is derived inside `:data:repository`, at the single
mapping site for each write path. `TransactionType.matches(amount)` states the sign rule once, and
`TransferTest.every row every write path produces has a type that agrees with its sign` walks every
path and asserts it.

**4. The 5 → 6 migration backfills `type` for existing rows** rather than leaving them on the SQL
default: `source = 'reconciliation'` → `adjustment`, otherwise the sign decides.

## Consequences

**Good.**

- "Money the user actually spent or received" is `type IN ('expense','income')` — a filter that
  cannot be got wrong the way a `transfer_id IS NULL AND amount < 0` convention can. Nothing computes
  a spend total yet; issues 4.4, 5.1 and 5.2 will, and they inherit a correct one.
- Each account's balance stays a plain `SUM` over its own rows, so DB-001's derivation and
  ADR-0007's "the balance is derived, the column is a cache" survive untouched. **Issue 3.2 adds no
  balance-writing code at all.**
- Deleting either leg is one `UPDATE … WHERE transfer_id = ?`, so there is no window in which one leg
  is gone and the other is not — FR-TXN-003's second clause is structural rather than sequenced.
- A transfer between an asset and a liability (bank → credit-card payment) correctly leaves net worth
  unchanged while moving both subtotals, which is what P-02 shows the user.

**Bad, and accepted.**

- **Direction is now stored twice** — in `type` and in the amount's sign — and they can in principle
  disagree. This is the real cost of adopting §20.2's column, and issue 3.1's doc comment named it in
  advance. The mitigation is that no caller supplies a type, the rule lives in one function, and a
  test walks every write path; the mitigation is *not* discipline.
- **§20.2's `CHECK` constraint does not exist on upgraded databases** and so the invariant lives in
  the test suite. A row written by a future direct-SQL path — an import, a restore, a migration —
  could violate it and nothing at the storage layer would object.
- **A transfer's legs are only as atomic as the enclosing `withTransaction`.** Anything that later
  writes a leg outside one re-opens the half-transfer failure mode.

**Deviations from the SRS this records.**

- **No `transfers` table** (§20.1). A parent row would carry no fact the legs do not already hold:
  the accounts, the amount, the date and the note all live on the legs, and the parent's only content
  would be its own id. It can be added later without touching the legs if a transfer ever gains a
  fact of its own (a fee, an FX rate, a status).
- **`transactions.source` carries `reconciliation` and `demo`**, which §20.2's
  `CHECK(source IN ('manual','ocr','sms','import','recurring'))` does not list. Both predate this
  issue — 2.7 and 2.4 respectively — and both are load-bearing: omitting `demo` from the parsing enum
  in issue 3.1 made every sample transaction invisible. Recorded here rather than quietly diverging.
- **Cross-currency transfers are refused**, not converted. §20.1 reserves an `fx_rates` table and no
  issue has built it; inventing a rate would be the app producing an unverified number (P-03).

## Alternatives considered

**`transfer_id` alone, with no `type` column.** A row would be a transfer iff `transfer_id != null`,
and the sign would keep its job as the only record of direction. This was the recommendation, because
it removes the drift hazard entirely rather than mitigating it. Rejected in favour of matching §20.2,
which also makes the spend filter a single readable predicate.

**A `transfers` parent table, per §20.1.** Rejected as above: no facts to hold. It also costs a join
on the busiest read path in the app.

**One row with two account columns** (`from_account_id`, `to_account_id`). Genuinely "a single
record", and tempting. Rejected because every balance query in the app is
`SUM(amount_minor) WHERE account_id = ?`; a second account column would make each of them a `UNION`
or a `CASE`, and DB-001's derivation — the thing ADR-0007 built the accounts feature on — would have
to be rewritten for a feature that is not about balances.
