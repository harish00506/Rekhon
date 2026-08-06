# ADR-0011 — A scanned receipt may be back-dated; the daily net-worth series does not follow it

- **Status:** superseded by [ADR-0012](0012-back-dating-and-the-repairable-net-worth-series.md) (2026-08-06)
- **Date:** 2026-08-06
- **Deciders:** Harish G (solo), implementing issue 3.8
- **SRS refs:** §5.4 (FR-OCR-003, FR-OCR-004), §5.3 (FR-TXN-010), §5.1 (FR-ACC-005), §18.1,
  §21.4 (TIM-002), P-02, P-07

> **Superseded the same day.** This ADR allowed back-dating for a *receipt* and left the daily
> net-worth series knowingly stale, naming the recompute as debt for whichever issue needed it next.
> ADR-0012 wrote that recompute, which removed the reason for the narrow rule — back-dating is now
> allowed on every path and the series repairs itself. The record below stands as the reasoning at
> the time; the provenance-based `recordsAPastEvent` rule it describes no longer exists.

## Context

FR-OCR-003 and FR-OCR-004 are both MUSTs, and together they require a *date*:

> FR-OCR-003 — "The parser MUST extract: total amount, **date**, merchant name … each field shows a
> confidence indicator and is editable before save."
> FR-OCR-004 — "Extraction review screen MUST prevent saving without an amount **and date**."

A receipt is a record of a purchase that already happened. Its date is therefore almost always in
the past — the day before, or a week ago when the user finally gets round to scanning the pile.

Issue 3.4 refused exactly that. `Clock.stampsFor`, written for FR-TXN-010's future-dating, returns
`null` for any day before today, and its doc comment gives the reason plainly:

> **A past date is refused.** Back-dating is not what this issue is for, and it is not harmless:
> `net_worth_snapshot` already holds one written row per past day, and nothing recomputes them, so a
> row inserted into last week would make the sparkline disagree with today's figure.

That reasoning was right for issue 3.4 and it is still right about the consequence. But it makes the
receipt scanner unable to save any receipt not dated today — which is to say, almost all of them.
**This was found on the emulator, not in review**: the parser read the bill correctly, the review
screen pre-filled it, Save was enabled, and the write came back "Check the amount and try again."
Every unit test passed, because every unit test fed the repository a draft dated today.

## Decision

**`stampsFor` gains an `allowPast` parameter, defaulted to `false`.** Every existing caller is
unchanged and every existing test still holds. `ReceiptRepository.save` passes `true`.

A back-dated row is stamped with the **start of its own booked day** in the profile zone — the same
rule a future-dated row already uses — and `postedAtUtcMillis` is set, because the day has arrived:
the money has moved, and `TransactionDao.postDue` must not later try to post it again.

**The daily net-worth series is deliberately left alone.** Snapshots for days already recorded are
not recomputed when a back-dated row lands.

## Consequences

**What works.** A receipt dated any day up to today saves, with the date the user confirmed on the
review screen (FR-OCR-003, FR-OCR-004). Every derived balance is correct immediately, because every
balance query bounds on `booked_on_iso_date` and reads the ledger — nothing is cached (ADR-0007).

**What is now knowingly approximate.** `net_worth_snapshot` holds one frozen row per past day and
nothing rewrites them (FR-ACC-005, and the whole reason that table exists — a *trend* must not move
under the user). A receipt back-dated into a week already snapshotted leaves those days' stored
figures unchanged, so issue 6.6's trend chart will under-report them until the affected days roll
out of the window. Today's figure, and every figure from today onward, is right.

That is the lesser of two errors, and the direction matters. The alternative — recomputing history
whenever any row is back-dated — is exactly what ADR's predecessor rejected for FR-ACC-005: it would
make the past move every time a user corrected an old entry, which is the failure the snapshot table
exists to prevent. A trend that is slightly stale for a few past days is recoverable and visible; a
trend that silently rewrites itself is neither.

**Bounded, and not unique to this feature.** The same drift will arise from issue 3.6's editing and
issue 5.4's statement import, both of which will need back-dating for the same reason. Whichever of
those lands first should carry the fix: a targeted recompute of the affected snapshot days, which is
cheap (`assets − liabilities` over a bounded date range) and belongs with the engine that writes
them. Recorded here so the debt is attributable rather than discovered again.

**Rejected alternatives.**

- **Clamp the receipt to today.** Saves without a schema change, and throws away the one field
  FR-OCR-004 refuses to save without. The user would confirm a date on the review screen and the app
  would silently store a different one — the opposite of P-02.
- **Refuse a back-dated receipt and tell the user.** Honest, and it makes the feature useless: a
  scanner that only accepts receipts scanned on the day of purchase is a worse manual-entry screen.
- **Recompute the whole snapshot series on every back-dated write.** Correct, and it makes an
  ordinary save O(days-of-history) while reintroducing the moving-history problem FR-ACC-005 named.
