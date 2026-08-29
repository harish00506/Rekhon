# ADR-0031 — A percentage change is refused when net worth starts at or below zero

**Status:** Accepted · 2026-08-30 · Issue 6.6 (net-worth snapshots history)

**Requirements:** FR-ACC-005, §5.7, §20.3, MNY-002, AI-ARC-006, P-02, P-03, P-08

---

## Context

FR-ACC-005 is a MUST: *"Net worth MUST equal assets − liabilities, snapshotted daily (DB §10
snapshots table), **with 1M/6M/1Y/All charts**."* Issue 2.6 built the table, the daily worker and the
backfill; issue 3.10 made the series repair itself when a transaction is back-dated. Issue 6.6 is the
chart, and with it the first question the app has to answer about *change over time* rather than
about a moment.

"How much has it moved, and by what share" looks like one question with two renderings. It is not.

## Decision

### 1 · The absolute change is always reported; the percentage only when it is sound

`changeBps` is returned **only when the first reading is strictly positive**. Otherwise it is `null`,
and the screen says why rather than rendering a gap.

The reason is arithmetic, not squeamishness. A percentage needs a denominator that is a magnitude,
and net worth is not one:

| Series | Absolute change | Naive ratio | What the ratio says |
|---|---|---|---|
| −₹50,000 → −₹10,000 | **+₹40,000** | −80% | fell by four fifths — the **opposite** of what happened |
| ₹0 → ₹25,000 | **+₹25,000** | divide by zero | nothing, or a crash |
| −₹20,000 → +₹30,000 | **+₹50,000** | −250% | a catastrophe, describing a recovery |

The first row is not a corner case. It is the ordinary state of a user with a home loan and a young
portfolio — exactly the person this app is for — and the figure would sit in the position they trust
most, under a chart that visibly slopes upward. P-03 says the app shows numbers it can stand behind;
a signed ratio here is a number it cannot.

**The test is `> 0`, never `>= 0`.** Zero is not a smaller version of the problem, it is the same lie
by division.

**Rejected:** using `abs(first)` as the denominator. It makes the sign come out right for the first
row and silently wrong for the third, where a series crossing zero would report a percentage of a
quantity that changed meaning halfway through. A rule that is right on the cases you thought of and
wrong on the ones you did not is worse than a refusal, because it never announces itself.

**Rejected:** dropping the percentage entirely. For a user whose net worth is positive throughout —
most users, eventually — it is genuinely informative, and refusing it for everyone to avoid a
minority case is the opposite trade.

### 2 · A change over one reading is absent, not zero

With fewer than two points, `change` is `null`. One snapshot tells you what net worth was that day
and nothing whatever about direction. Rendering "₹0 change" would claim a stability nobody measured —
the same absent-versus-zero line the dashboard already draws with `dashboard_net_worth_pending`.

This also lands in the UI: `CfoSparkline` needs two points and **draws nothing at all below that,
silently**. Without an explicit empty state a profile on its first day would get a blank box.

### 3 · The trend reads stored rows and never recomputes them

`observeHistory` maps `net_worth_snapshot` rows straight to points. Nothing on the path calls
`computeAsOf`. This is the whole purpose of the table — recomputing from today's accounts would
rewrite the past whenever an account is archived or a transaction back-dated, and the chart would
show a past that never happened (the concern `Entities.kt` records and ADR-0012 pays down).

It is proved rather than described: a test stores a figure deliberately at odds with what the fixture
account would produce today and asserts the stored one comes back. Substituting a re-derivation in
the mapper turns that test red.

### 4 · The trend carries its own engine id and version

`engineId = "net-worth-trend"`, version `1.0`, independent of `compute`'s `net-worth` 1.0.

`engine_version` is written into **every** `net_worth_snapshot` row (AI-ARC-006). Sharing one version
string would mean bumping it whenever the trend changed, stamping thousands of stored rows as the
output of a formula that never moved — and the column exists precisely to signal that a formula did
move. Two computations, two histories, both in `ENGINE.md`'s version log.

### 5 · The window is chosen by the repository, measured by the engine

`NetWorthRange` resolves to a from-date with `LocalDate.minusMonths` on the profile's calendar, in
the repository, because that needs a clock (TIM-001). The engine receives points already bounded and
reads no clock, so every golden case is reproducible to the byte (P-08).

Calendar arithmetic, not day counts: one month back from 31 March is 28 February, and `minusDays(30)`
would quietly disagree with the label on the chip.

## Consequences

- A user whose net worth is negative sees an amount and a sentence explaining why there is no
  percentage. That is one more line of text than a number, and it is the truth.
- `changeBps` being `Int?` forces every caller to decide what absence looks like. That is deliberate:
  a non-null `0` would have been silently rendered by the first screen that forgot.
- No migration and no schema bump — the `(profile_id, as_of_iso_date)` index already covers the range
  scan, and Room's exported schema describes tables, not queries.
- **`CfoSparkline` gets its first production call site.** It has existed since issue 1.8, documented
  as being for "a balance or net-worth trend", used by nothing. The AC "charts come from the design
  system" is met by using it rather than building a second chart.

## Alternatives considered

| Option | Why not |
|---|---|
| `abs(first)` as the denominator | Right on a negative-throughout series, silently wrong on one crossing zero |
| Clamp the percentage at 0% when unsound | A fabricated figure dressed as a measured one (P-03) |
| No percentage at all, ever | Discards real information for the majority to avoid a minority case |
| Report change as zero for a single reading | Claims a stability nobody measured; absent ≠ zero |
| Recompute history from today's accounts | Defeats the table's entire purpose and FR-ACC-005's freeze |
| Bump `net-worth` to 1.1 for the trend | Restamps stored rows for a formula that did not change (AI-ARC-006) |
| `minusDays(30)` for "1M" | Disagrees with the chip's own label at month boundaries |

## References

- SRS §5.7 (FR-ACC-005), §20.3; MNY-002 (integer basis points)
- [ADR-0007](0007-account-balances-derived-not-stored.md) — balances are derived; snapshots are the
  deliberate exception, and this ADR says why the exception holds
- [ADR-0012](0012-back-dating-and-the-repairable-net-worth-series.md) — the series repairs itself
- `domain/engines/networth/ENGINE.md` — both version logs
- `domain/engines/networth/src/test/resources/golden/networth-trend.txt` — five of ten records exist
  to pin the refusal
