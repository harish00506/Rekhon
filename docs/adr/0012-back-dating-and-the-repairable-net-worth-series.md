# ADR-0012 — Any transaction may be back-dated, because the net-worth series can repair itself

- **Status:** accepted
- **Date:** 2026-08-06
- **Deciders:** Harish G (solo), follow-up to issue 3.8
- **Supersedes:** [ADR-0011](0011-back-dating-a-scanned-receipt.md)
- **SRS refs:** §5.1 (FR-ACC-005), §5.3 (FR-TXN-001, FR-TXN-010), §5.4 (FR-OCR-003), §20.2
  (`net_worth_snapshot`), §21.4 (TIM-002), P-02, P-03, P-08

## Context

Issue 3.4 refused to book a transaction on a day before today, and gave a good reason:
`net_worth_snapshot` stores one **frozen** row per past day and nothing recomputes it, because
FR-ACC-005's whole point is that a trend must not move under the user. A row inserted into last week
would leave those days' stored figures behind, so the history chart would disagree with the
dashboard.

Issue 3.8 hit that wall immediately — a receipt is by definition already spent — and ADR-0011 opened
the narrowest door it could: back-dating allowed by **provenance**, so a row read off a record could
be back-dated and a row a person typed could not. It recorded the stale history as debt and named
whichever issue needed back-dating next as the one that should pay it.

That issue turned out to be the next question asked: *can old data be added?* The honest answer was
"only by photographing a receipt", which is not a feature anyone would design.

Two facts made the fix smaller than it looked.

**The dashboard was never affected.** `observeCurrent()` derives net worth from the ledger on every
change (ADR-0007 — balances are derived, not stored), so a back-dated row is already in the headline
figure the moment it is saved. Only the stored *history* series goes stale, and its one consumer —
issue 6.6's trend chart — does not exist yet.

**Staleness is derivable from the rows.** A stored day is wrong if and only if some transaction
booked on or before it was written, edited or removed *after* that day's figure was computed. Every
column that answer needs is already on the two tables.

## Decision

**Back-dating is allowed everywhere.** `Clock.stampsFor` no longer refuses a past date, `allowPast`
and `TransactionSource.recordsAPastEvent()` are deleted, and the add screen's date picker no longer
bounds its selectable days. A back-dated row is stamped with the start of its own booked day and is
`postedAt` immediately, because the day has already arrived.

**`NetWorthRepository.repairStaleHistory()` corrects the days that a write invalidated.** It is the
one narrow exception to the freeze:

- The affected days come from `NetWorthSnapshotDao.findEarliestStaleDay`, a single query joining
  snapshots to the transactions booked on or before them and comparing `updated_at` and `deleted_at`
  against `computed_at`. **Both terms are needed** — `updated_at` catches a row created or edited,
  `deleted_at` catches one removed, because `softDelete` deliberately does not touch `updated_at`.
  It is the only query in that file that does not filter tombstones, because a deleted transaction is
  exactly the change being looked for.
- It recomputes from that day forward to today, in one transaction, capped at `MAX_BACKFILL_DAYS`
  per call. A repaired day stops being reported as stale, so successive runs converge.
- **It never invents history.** The earliest day it can touch is the earliest day already stored,
  because the detection starts from stored snapshots. Back-dating to before the user had the app
  corrects nothing, which is correct: there is no figure for those days to be wrong (P-03).

**Only `NetWorthSnapshotWorker` calls it**, before its existing backfill. The alternative — having
every write path that can back-date notify the net-worth series — would put knowledge of a reporting
table into the ledger and would be one more thing a future write path could forget. Deriving the
staleness means nothing has to remember to declare it.

## Consequences

**What is now possible.** A user can record a purchase on the day it happened, whether they type it,
transfer it, split it or scan it. That is what FR-TXN-001 always implied and what FR-OCR-003 requires.

**What is bounded.** The correction is not instantaneous: it runs on the daily job, which is
scheduled as unique periodic work on every process start. So a back-dated row is in every balance
immediately and in the stored *history* within a job run. Since nothing renders that history yet
(issue 6.6), the window is invisible today; when the chart lands it should trigger a repair on open,
which is one call.

**What is deliberately not done.**

- **No `dirty` column, no repair queue.** A flag is state that a write path can fail to set, and the
  failure is silent. The derived query cannot be forgotten because there is nothing to remember.
- **No unbounded date floor in the picker.** A user can now scroll to any past year, including by
  accident. That is the same latitude every finance app gives and the review screens show the chosen
  date back; adding a floor would be inventing a limit no requirement asks for.
- **No recompute of days before the first snapshot.** Reconstructing a stretch of history the user
  never had the app for would be inventing data — the same argument `snapshotUpToToday` already makes
  for its first ever run.

**Rejected alternatives.**

- **Keep ADR-0011's provenance rule and leave manual entry blocked.** Coherent, and it makes the app
  unable to record a purchase from yesterday unless it was photographed.
- **Recompute the whole series on every write.** Correct and makes an ordinary save
  O(days-of-history). The repair is scoped to demonstrably wrong days precisely to avoid this.
- **Have `TransactionRepository` notify the net-worth series.** Immediate, and it couples the ledger
  to a reporting table and adds a step three write paths must each remember.
