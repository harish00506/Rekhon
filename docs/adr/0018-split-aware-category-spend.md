# ADR-0018 — Spending is summed from split lines, which moves figures already shipped

- **Status:** accepted
- **Date:** 2026-08-11
- **Deciders:** Harish G (solo), implementing issue 4.4
- **Refs:** [ADR-0009](0009-splits-as-a-child-table.md), issue 4.3, issue 4.4,
  SRS §5.5 (FR-BUD-003), §8.3.1 (AI-CLS-N), P-03, MNY-001

## Context

A split transaction in this app is a parent row plus rows in `transaction_splits` (ADR-0009). The
parent carries the total; each line carries its own amount and its own `category_id`. The parent
usually carries **no** category at all — it is precisely the row the user could not classify with one
label, which is why they split it.

Every aggregation written before issue 4.4 reads the parent and stops there:

- `observeNatureBreakdown` (issue 4.3) — the dashboard's 50/30/20 rings and true spend.
- `observeNatureCandidates` (issue 4.3) — the per-transaction Need/Want/invest/asset/debt decision.

For an unsplit transaction that is correct. For a split one it is wrong in a specific, silent way: a
₹4,000 supermarket run split into ₹3,000 groceries (a Need) and ₹1,000 wine (a Want) reaches
`NatureEngine` as **one** ₹4,000 row with a null category. It falls past §8.3.1's step 5 to the
low-confidence fallback, and the whole ₹4,000 lands in Wants. The user did the work of splitting it
and the app ignored the result.

ADR-0009 stated exactly what both of these were supposed to do, in its consequences:

> **Budgets (4.4) and Need/Want/Invest (4.3) inherit the right shape**: they will sum split *lines*
> where a transaction is split and the parent where it is not, rather than being stuck with one
> category per purchase.

Issue 4.3 did not do this. The shape was available and the query was written against the parent
anyway — so the second half of that sentence is a defect this ADR is fixing, not only a design 4.4
is now implementing. Worth recording: the prediction was right and it still did not happen, because
nothing in 4.3's own tests could tell the difference. A split fixture existed nowhere in
`NatureRepositoryTest` until now.

## Decision

**Every spend aggregation is a `UNION ALL` of two legs: transactions with no live split lines, and
live split lines. A payment is counted exactly once, by its lines where it has them.**

```sql
SELECT ... FROM transactions t
WHERE ... AND NOT EXISTS (
  SELECT 1 FROM transaction_splits s
  WHERE s.transaction_id = t.id AND s.deleted_at_utc_millis IS NULL)
UNION ALL
SELECT ... FROM transaction_splits s JOIN transactions t ON t.id = s.transaction_id
WHERE ... AND s.deleted_at_utc_millis IS NULL
```

The `NOT EXISTS` clause is what makes double-counting impossible: a transaction is in the first leg
only while it has no live lines, and the moment it gains one it leaves. Soft-deleting every line
returns it, with its own total, rather than dropping the payment from the books.

This shape is applied to three queries:

| Query | Issue | What changes |
|---|---|---|
| `observeCategorySpend` | 4.4 | new — per-category totals for the budget screen |
| `observeMonthlyCategorySpend` | 4.4 | new — the 3-month history the suggestion's median reads |
| `observeNatureCandidates` | 4.3 | **rewritten** — one row per split line, not per transaction |

`natureCandidate` — the single-row read behind the transaction detail sheet — is deliberately **not**
split-aware. That sheet is answering "what is this *payment*?", and a payment is one thing to the
user even when it bought two.

## Consequences

### Figures already on screen move

This is the part that needs saying plainly rather than being discovered. Issue 4.3 shipped the
50/30/20 rings and true spend. For any user who has split a transaction, **those numbers change** —
correctly, but without them having done anything. In the fixture above, needs go from ₹0 to ₹3,000
and wants from ₹4,000 to ₹1,000.

This is a bug fix, not a feature, and it is the right direction: the failure it removes ran only one
way, inflating true spend, which is the figure Safe-to-Spend, the health score and the Purchase
Advisor are all calibrated against. Leaving it would have meant budgets and the rings quietly
disagreeing about the same month.

`NatureRepositoryTest` pins both sides of it. The regression was verified the way this repo verifies
every gate — by reintroducing the defect and watching the test fail on exactly the assertion that
should catch it (`expected Money(minor=300000) but was Money(minor=0)`), then restoring it.

### `NatureCandidateRow` no longer means "one transaction"

A row is now one *classifiable amount*: a split line where lines exist, a transaction where they do
not. `id` is the row's own identity and `transactionId` is the parent either way. Any future caller
that assumes one row per transaction will be wrong, which is why both columns are projected and the
KDoc says so.

### It costs a second scan

Two legs read `transactions` where one did. Both are indexed on `profile_id` and
`booked_on_iso_date`, the window is one month for the status and four for the suggestion history, and
the alternative — reading everything into memory and grouping in Kotlin — would put the arithmetic
above the database and be slower besides.

### It does not touch the schema

No migration. The tables and columns are ADR-0009's, unchanged; only the queries over them are new.

## Alternatives considered

**Sum lines in Kotlin after reading both tables.** Rejected: it moves aggregation above the DAO into
a repository, which is arithmetic in a layer that should be plumbing, and it reads far more rows than
it keeps.

**Write the lines' categories up onto the parent.** Rejected outright: a parent has one
`category_id` and a split has many by definition, so the write would have to pick one and discard the
rest — destroying the user's input to make a query easier.

**Leave `observeNatureCandidates` alone and only fix budgets.** This was considered and explicitly
rejected during planning. It would have left the budget screen and the dashboard rings reporting
different totals for the same month, and "the two screens disagree" is a bug report the user files
against both.
