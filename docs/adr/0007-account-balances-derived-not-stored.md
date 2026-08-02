# ADR-0007 — Account balances are derived on read, and `wallet` is not an account type

- **Status:** accepted
- **Date:** 2026-07-28
- **Deciders:** Harish G (solo), implementing issue 2.5
- **SRS refs:** §5.7 (FR-ACC-001, FR-ACC-006, FR-ACC-007), §20.2 (`accounts` DDL), §20.3 (DB-001,
  DB-002), MNY-001, P-03

## Context

Issue 2.5 builds the first CRUD a user has over accounts. The `account` table has existed since
issue 1.6 and carries **two** money columns:

```
opening_balance_minor INTEGER NOT NULL,
current_balance_minor INTEGER NOT NULL,
```

Nothing had ever maintained the second one. The only writer was issue 2.4's demo dataset, which
computes it once from its own fixed transactions and never touches it again.

DB-001 is explicit about what that column is:

> Balances are never mutated ad hoc: `current_balance` is derivable from opening balance +
> transactions and is verified by a nightly integrity job; discrepancies raise an internal alert and
> an adjustment prompt (FR-ACC-006).

So the SRS already says the stored value is *checkable output*, not input. What it does not say is
which of the two a screen should read — and this issue is the first one where that matters, because
it is the first with a screen that shows a balance the user can affect.

A second, smaller question arrived with it. `AccountEntity`'s doc comment (issue 1.6) lists six
account types: `bank | cash | wallet | card | loan | investment`. FR-ACC-001 lists eleven, and
`wallet` is not among them; §20.2's `CHECK` constraint agrees with FR-ACC-001. The column is plain
`TEXT` with no constraint in Room, so nothing had ever caught the difference — and the demo dataset
was writing `"card"`, which is in neither list (the SRS spells it `credit_card`).

## Decision

**1. Every balance the app reads is derived: `opening_balance_minor + SUM(live transactions)`.**

The derivation lives in one SQL query, `AccountDao.observeWithBalances`, as a correlated subquery:

```sql
SELECT a.*, COALESCE((
    SELECT SUM(t.amount_minor) FROM transactions t
    WHERE t.account_id = a.id AND t.deleted_at_utc_millis IS NULL
), 0) AS movement_minor
FROM account a ...
```

`AccountRepository` adds the two with `Money`'s overflow-checked arithmetic and returns an `Account`
carrying **both** figures — the opening balance and the current one — because they answer different
questions and a screen showing only one makes the other unverifiable (P-02).

`current_balance_minor` **stays in the schema and stays written on create**, seeded from the opening
balance. It is the left-hand side of the comparison DB-001's nightly integrity job will make; issue
2.7 (FR-ACC-006 reconciliation) is what builds that job and the adjustment prompt.

**2. `AccountDraft` cannot express a balance.** The type a caller hands to `create` and `update`
holds the opening balance and nothing else, so "correct my balance" is unrepresentable in the API.
That is deliberate: FR-ACC-006 says a correction is posted as an adjustment *transaction*, "never
silently mutated".

**3. The account type vocabulary is `AccountType` in `:core:model`, with FR-ACC-001's eleven values
and §20.2's exact stored strings.** `wallet` is dropped. The demo dataset's `"card"` becomes
`credit_card`, which reds its golden test — the test is updated, and a new one now asserts every
demo account carries a type `AccountType.fromStored` recognises.

## Consequences

**Good.**

- A balance can no longer be wrong-but-plausible. The number on screen is a function of rows the
  user can see, so P-02's "show the work" is satisfiable by construction rather than by a comment.
- There is no maintenance code to get wrong. Nothing writes transactions yet (issue 3.x), so a
  stored-and-maintained balance would have shipped with no real caller and no way to test the path
  that matters — an update that runs when a transaction is edited or soft-deleted.
- Soft-deleting a transaction changes the balance immediately and correctly, for free.
- The type set is now enforced by the compiler above the database, so `"card"`-shaped bugs cannot
  recur silently.

**Bad.**

- **A read now costs a subquery per account.** For a household with tens of accounts and years of
  transactions this is a `SUM` over an indexed `account_id` per row; it is fine at this size and it
  is not free. If it ever stops being fine, the answer is the DB-001 integrity job maintaining
  `current_balance_minor` and the read switching to it — which is why the column is kept rather than
  dropped. The schema does not need to change for that; only the query does.
- **The two figures can disagree**, and until issue 2.7 nothing notices. `current_balance_minor` is
  seeded at create and then never updated, so on any account with transactions it is stale by
  design. Nothing reads it, so nothing is wrong today — but a future reader who trusts the column
  will be reading a lie. The column's doc comment says so.

**Neutral.**

- The demo dataset's account balances are unaffected in value: it already derived its closing
  balances from its own transactions, so switching the *reader* to derive as well produces the same
  numbers by a different route.
- Requirement traceability: FR-ACC-006 remains open and is now explicitly issue 2.7's, along with
  the integrity job.

## Alternatives considered

**Maintain `current_balance_minor` on every transaction write.** Rejected for now. It is the
performance answer, and it is what the SRS's own column shape anticipates — but it is the *opposite*
of DB-001's "never mutated ad hoc" unless the integrity job exists to check it, and that job is
issue 2.7's. Building the maintenance without the check would create exactly the silent-drift
failure DB-001 is written to prevent, and it would ship untested by any real caller because nothing
writes transactions yet.

**Drop `current_balance_minor` entirely.** Rejected: DB-003 forbids destructive migrations, and the
column is the thing FR-ACC-006's reconciliation compares against. Removing it would have to be
un-removed by issue 2.7.

**Keep `wallet` as a synonym for `cash`.** Rejected: a type that is in no requirement is a type no
engine will ever handle, and "which of these two do I pick?" is a question no user should be asked.
Nothing in the app had written it — the demo used `cash` for its cash wallet — so dropping it costs
no data.

---

## Update — 2026-08-02, issue 2.7: the integrity job exists, and the *Bad* consequence is closed

This ADR left one consequence open in as many words:

> **The two figures can disagree**, and until issue 2.7 nothing notices. […] a future reader who
> trusts the column will be reading a lie.

Issue 2.7 built the job. `AccountDao.refreshCachedBalances` re-derives `current_balance_minor` for
every live account in a profile in **one `UPDATE`**, using a subquery character-for-character
identical to `observeWithBalances`'s. `BalanceIntegrityWorker` runs it daily. So the column is now
either correct or corrected within a day, rather than stale by construction.

Three details are worth recording because they are not obvious from the code:

**The job repairs; it does not merely report.** Drift here was never a symptom of corruption — it
was the guaranteed state of a column written once and never updated. A job that only flagged it
would have flagged every account with transactions, every night, and meant nothing.

**The row count is the drift count, and that took a `WHERE` clause.** The `UPDATE` ends with
`AND current_balance_minor <> opening_balance_minor + COALESCE((…), 0)`. Without it the statement
matches every row and "rows affected" is just the account count. With it, the figure is exactly how
many caches were wrong, and nothing is written that was already right.

**Reconciliation refreshes through the same query rather than asserting the answer.** `reconcile`
knows the new balance — the user just stated it, and the adjustment it writes makes it true — so it
could set the column directly. It does not: it runs the same profile-wide refresh inside the same
transaction. Asserting the figure would be true by construction today and quietly wrong the moment
anything about the derivation changed, and the whole point of this ADR is that there is exactly one
definition of what a balance means.

**What is still true:** nothing *reads* the column. Every balance the app shows is still derived
(the *Good* consequences above are unchanged). The job's value is the invariant, not a screen — it
is the precondition for switching the read path onto the cache if the per-account subquery ever
stops being cheap enough, which is the *Bad* consequence about read cost, and that one remains open.

**Alternatives reconsidered and still rejected.** "Maintain `current_balance_minor` on every
transaction write" is still not done — nothing outside the demo dataset and this issue's adjustment
writes a transaction yet (Epic 3 owns that). When it does, the maintenance becomes worth having, and
now it would have a check: this job.
