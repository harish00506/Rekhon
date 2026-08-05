# ADR-0010 — A future-dated transaction is excluded from actuals by its date, not by its posting stamp

- **Status:** accepted
- **Date:** 2026-08-03
- **Deciders:** Harish G (solo), implementing issue 3.4
- **SRS refs:** §5.3 (FR-TXN-010, FR-TXN-001), §5.2 (FR-HOME-001), §20.2 (`transactions` DDL),
  §20.3 (DB-001, DB-003), §21.4 (TIM-001, TIM-002), P-04, P-08

## Context

FR-TXN-010 is a MUST, and it is one sentence:

> Future-dated transactions MUST be supported and excluded from actuals but included in forecasts.

The SRS says nothing about *how* a scheduled row becomes an actual one. The generated issue doc
added a sentence the SRS does not contain — "on date rollover they post automatically (WorkManager),
idempotently" — authored in `scripts/gen_issue_docs.py`, and it also cited no requirement id at all.
This is the third generated-backlog correction in a row after issue 3.1's wrong FR id and issue
3.3's non-existent API. **The user was asked and chose to build the WorkManager state machine
anyway.** This ADR records the shape that satisfies that choice without inheriting its failure mode.

Two facts about the codebase shaped the decision.

**Half the exclusion was already structural, and half of it was a latent bug.** The net-worth
queries have bounded on `booked_on_iso_date <= :asOfIsoDate` since issue 2.6, and
`balancesForNetWorth`'s own doc comment predicted this issue by name: "wrong here the moment issue
3.4 lands future-dated transactions". But `observeWithBalances` and `findWithBalance` — the queries
behind the **accounts screen**, the account editor and reconciliation — had no such bound. They
summed every live transaction whenever it happened. Nothing was wrong while no row could be booked
past today; the first scheduled payment would have been subtracted from the balance on the accounts
screen while net worth, on the same data, showed a different figure.

**A background job is the least reliable thing in the app.** `NetWorthSnapshotWorker` established
the pattern in issue 2.6, and with it the reasons a daily job may not run: WorkManager defers under
Doze, a powered-off device runs nothing at all, and the gated database *throws* while the app is
locked (SEC-002), so every worker in this codebase must decline to run and retry instead.

## Decision

**1. `transactions.posted_at_utc_millis` (nullable) is added at schema v8, and
`ScheduledTransactionWorker` stamps it daily.** This is the state machine the requirement asks for.
Null means "not yet posted".

**2. The column does not gate a single balance, and nothing may make it.** Every amount query
bounds on `booked_on_iso_date <= today`, and that is what excludes a scheduled row from actuals. The
date cannot be deferred; a job can. A design where the balance waited for the stamp would show a
user the wrong number after a night with the phone off, after a Doze window, or simply while the app
was locked.

**3. Both account-balance queries gain the same bound** (`observeWithBalances`, `findWithBalance`).
The accounts screen, the editor and reconciliation now agree with net worth by construction rather
than by coincidence.

**4. "Is this scheduled?" is `bookedOn > today` everywhere — money and UI alike.** Between midnight
and the worker's next run a row is already in the balance while still carrying no stamp; deriving
the label from the stamp would put the two in visible disagreement on screen. `Transaction`
therefore has `isScheduledOn(todayIsoDate)` and deliberately **no** clock-reading `isScheduled`
property, because `:core:model` may not read a clock (TIM-001, `CfoWallClockInDomain`).

**5. What the stamp is actually for:** the worker's idempotence key (`WHERE posted_at IS NULL` is
what makes a second run stamp nothing), and a durable record of the rollover that issue 3.7's
recurring-auto series and any later "posted today" surface can read.

**6. The 7 → 8 migration backfills every existing row** with its `occurred_at_utc_millis`. `ADD
COLUMN` alone gives every row `NULL` — exactly the value meaning "not posted" — so an upgraded
install would show the user's entire history in the Scheduled group with an empty recent list.

**7. Two read flows, not one widened window.** `observeRecent()` keeps its `<= today` bound and
`observeUpcoming()` covers tomorrow to today + 90 days. The two halves therefore cannot be summed by
accident: a scheduled row is simply not in the list day totals are computed from, so there is no
filter for a later screen to forget. `observeUpcoming` is also the seam Epic 6's cash-flow forecast
and FR-HOME-001's fourteen-day obligations card read — the "included in forecasts" half of
FR-TXN-010.

**8. Past dates are refused.** `Clock.stampsFor` returns `null` for a date before today, and the
picker will not offer one. Back-dating is not merely out of scope: `net_worth_snapshot` already
holds one written row per past day and nothing recomputes them, so a row inserted into last week
would make the sparkline disagree with today's figure. Issue 3.6 owns editing and can revisit it
with the recompute it would need.

**9. A future row's `occurred_at_utc_millis` is the start of its own day**, via `Clock.startOfDay`,
not "now". The list orders by that column, so stamping now would sort next month's rent among
today's rows; and resolving local midnight through the `ZoneId` is what makes it correct on a day
whose midnight does not exist (Chile, 2026-09-06 — asserted in `FutureDatedTransactionTest`).

## Consequences

**Good.**

- Posting is correct in every zone and on every DST boundary, and is correct on a device that has
  been switched off for a week, because it is a property of the date rather than of a job having run.
- The accounts screen and net worth can no longer disagree about the same money.
- `postDueTransactions()` returning `0` is a success, so the overwhelmingly common case — a user who
  has scheduled nothing — never makes WorkManager back off.
- The forecast seam exists before the forecast does, so Epic 6 adds an engine rather than a query.

**Bad, and accepted.**

- **`posted_at_utc_millis` is not load-bearing today.** Nothing would break if the worker never ran;
  its first real consumer is issue 3.7. That is the price of the explicit state machine, and it is
  the right price — the alternative made a balance depend on a deferrable job.
- **Two sources of truth about one fact, in appearance.** The stamp and the date can differ for up
  to a day. Decision 4 resolves it — the date wins everywhere a user can see — and a test pins it
  (`a stamp is not what decides whether a row counts`).
- **A ninetieth-day cliff.** `UPCOMING_WINDOW_DAYS = 90` bounds the upcoming query, so a payment
  scheduled further out is invisible until it approaches. It is not lost, and the window is a
  constant to raise if a real user ever schedules further ahead.
- **Editing a scheduled row is impossible**, as it is for every other transaction — there is no edit
  path anywhere in the app yet. It can be deleted and re-entered. Issue 3.6 owns this.

## Alternatives considered

**Derive posted-ness from the date and add no column or worker at all.** Strictly simpler, and it
was the recommendation: posting would be idempotent by construction with no job to miss, run twice
or run while locked. Rejected by the user in favour of the explicit state machine, which the
generated criteria called for. Decisions 2 and 4 keep the money side of that simpler design intact,
so what was added is a record rather than a dependency.

**Gate balances on `posted_at IS NOT NULL`.** The literal reading of "they post automatically". It
would make the stamp meaningful — and would mean a user whose phone was off overnight opens the app
to a balance that still includes money that has left their account. Rejected outright.

**A separate `scheduled_transactions` table.** Considered and dropped for the reason ADR-0008
dropped a `transfers` table: a scheduled payment is an ordinary transaction whose date has not
arrived, not a different kind of thing, and a second table would need every read, every delete and
every migration duplicated — plus a promotion step that could fail halfway.

**Widen `observeRecent` to include future rows and filter in the UI.** One flow instead of two, but
it puts a scheduled row inside the list that day totals are computed from, and correctness then
depends on every present and future consumer remembering to filter. Decision 7 makes the split
structural instead.
