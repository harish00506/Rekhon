# NetWorthEngine — FR-ACC-005

**SRS:** §5.7 (FR-ACC-005), §20.3 (DB-001)  ·  **Pipeline layer:** L2 (analytics)  ·  **Module:** `:domain:engines:networth`
**Version:** 1.0  ·  **Status:** active

## Why this engine exists

FR-ACC-005 is a MUST: *"Net worth MUST equal assets − liabilities, snapshotted daily (DB §10
snapshots table), with 1M/6M/1Y/All charts."* Until issue 2.6 the dashboard showed a hardcoded
`₹4,82,350.00` — a fabricated figure in the app's headline position, which is precisely what P-03
forbids. This engine replaces it.

It is the only place `assets − liabilities` is computed. `NetWorthRepository` hands it balances and
stores what it returns; `DashboardViewModel` renders what was stored. Neither adds up a rupee.

**Downstream:** issue 6.6 (net-worth history and the trend chart) reads the snapshots this produces.
Issue 9.4 (financial health score) and issue 7.x (goals) both take net worth as an input.

## Contract

```kotlin
interface NetWorthEngine {
    fun compute(input: NetWorthInput): Result<NetWorthResult, AppError>
}
```

- **Input** — `NetWorthInput`:
  - `balances: List<AccountBalance>` — one entry per account that counts. Each is
    `(accountId, type: AccountType, balance: Money)`, the balance **signed** paise (MNY-001):
    negative for a card the user owes on. **The caller has already decided which accounts count** —
    archived (FR-ACC-007), soft-deleted and opted-out accounts are filtered in SQL, because that is
    a storage question and this engine's job is the arithmetic.
  - `asOfIsoDate: String` — the day the figure describes, ISO `yyyy-MM-dd` in the profile zone
    (TIM-002).
  - `nowUtcMillis: Long` — stamped into provenance. **Passed in, never read** (TIM-001).
- **Output** — `NetWorthResult`: `asOfIsoDate`, `assets`, `liabilities`, `netWorth` (all `Money`),
  plus `provenance` (`engineId`, `engineVersion`, `computedAtUtcMillis`, `inputWindow`).

## Formula / algorithm

```
(liabilityRows, assetRows) = balances.partition { it.type.isLiability }

assets      = Σ assetRows.balance                  // signed
liabilities = −(Σ liabilityRows.balance)           // positive magnitude
netWorth    = assets − liabilities
```

`liabilities` is negated so a screen can render "you owe ₹18,000" without re-deciding what the minus
meant; the subtraction puts it back. The identity `netWorth == Σ every signed balance` therefore
holds, and `NetWorthPropertyTest` asserts it over generated portfolios — **the partition explains
the answer, it must never change it.**

**Which side an account falls on is `AccountType.isLiability`: `CREDIT_CARD`, `LOAN`, `PAYABLE`.**
`RECEIVABLE` is an asset — money owed *to* the user.

### The one judgement worth reading twice

**Classification is by type, never by sign.** The tempting shortcut is to call every negative balance
a liability. It is wrong twice: an overdrawn bank account would be reported as a debt the user took
on, and a credit card paid past zero as savings. Both are real situations, and **net worth is
identical either way** — so the error would never appear in the headline figure, only in the two
subtotals the user checks it against. `NetWorthEngineTest` pins both cases.

## Assumptions & guardrails

- Money is `Long` paise throughout; there is not a `Double` in the module. Addition goes through
  `Money.plus` (`Math.addExact`), so an impossible total fails loudly rather than wrapping into a
  negative fortune (MNY-001).
- **There is no division**, so there is nothing to round — the usual `HALF_EVEN` question does not
  arise here.
- Pure Kotlin (ARC-002): no Android imports, no clock read, no I/O. Fixed input → fixed output
  (P-08), including across account ordering.
- **Empty input is a zero, not an error.** A user who has added nothing has a net worth of zero, and
  returning an error would make the dashboard show a failure for an ordinary state.
- **It does not decide which accounts count.** Archived / opted-out / soft-deleted filtering happens
  in `AccountDao.balancesForNetWorth`, one layer down.
- **It advises nothing and writes nothing (P-07).** The caller decides what to do with the figure.

## Rules / knowledge consumed

**None**, and that is deliberate. This is arithmetic, not a threshold — there is no rulebook row to
cite and no financial number to hardcode, so CLAUDE.md §6 has nothing to bite on here.
`provenance.evidence` is therefore empty: attaching a citation would imply the app applied a rule it
did not, which is the kind of false trail P-02 exists to prevent.

What provenance *does* carry is `engineVersion`, stored on every `net_worth_snapshot` row
(AI-ARC-006) — so a figure computed today stays explainable after this formula changes.

## Version log

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-08-01 | Created for issue 2.6 (FR-ACC-005). Assets − liabilities over `AccountType.isLiability`. |

**Bump this whenever the formula changes**, and record why. A stored snapshot names the version that
produced it; a change without a bump makes every earlier row unexplainable.
