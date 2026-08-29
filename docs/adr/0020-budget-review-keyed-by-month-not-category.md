# ADR-0020 — `budget_review`'s claim is keyed by (profile, month), not (profile, month, category)

- **Status:** accepted
- **Date:** 2026-08-15
- **Deciders:** Harish G (solo), implementing issue 4.6
- **Refs:** [ADR-0019](0019-budget-alert-bands-mint-a-new-rule-row.md), CLAUDE.md §6, SRS §5.5
  (FR-BUD-*), §29 (AI-ARC-006)

## Context

`RULE-BUD-REVIEW`'s `review_once_per_month` param, like `RULE-BUD-ALERT`'s
`notify_once_per_band_per_month` before it, is documentation rather than enforcement — the rule row
records the intent, and a schema constraint has to be the mechanism, exactly as ADR-0019 and
`RulebookDriftTest`'s citation of `budget_alert`'s unique index already establish for the alert
path. Issue 4.6 needs the same shape of table for the monthly review, and `budget_alert`'s own key —
`UNIQUE(profile_id, budget_id, month_start_iso_date, band)` — is the obvious template to copy. It is
also the wrong key for this table, and this ADR is why.

`budget_alert` is keyed one level finer than a bare month because two different bands are two
different messages: crossing 80% and later crossing 100% must each notify, so the band has to be
part of what makes a row unique, or the second alert would be silently refused by the first's claim.

A monthly review does not have that shape. `BudgetEngine.review` returns **one** `BudgetReview` for
the whole closed month — a single card listing every material category's finding — not one result
per category the way `alert` returns one result per budget. There is no second, distinct review to
protect a slot for the way there is a second, distinct band.

## Decision

**`budget_review` is keyed `UNIQUE(profile_id, month_start_iso_date)` — no `band`, no `category_id`,
no `budget_id`.** One dismissal claims the whole month's card. `BudgetReviewRepository.dismissReview`
takes no category argument for the same reason: there is one thing to dismiss, not one per finding.

This is a decision about **what the UI is**, not a database convenience. The review card is a
review-and-move-on task — read it, act on what you want to act on, dismiss it — rather than an
ongoing status like the alert bands, which stay true and stay visible for as long as the condition
holds. Keying the claim per category would imply a screen where each finding could be acknowledged
independently while the rest of the card stayed open, which is a different, larger feature this
issue does not build.

## Consequences

**Accepting one category's proposal does not dismiss the card.** `acceptReviewProposal` and
`dismissReview` are deliberately separate calls in `BudgetRepository` — a user who accepts Groceries'
proposal but wants to think about Dining a while longer still sees the card, because nothing about
accepting one row answers "have I finished with this month's review?"

**A future "per-finding acknowledged" feature is not blocked, only not built.** If it turns out users
want to dismiss one finding at a time, that is a new column and a new key on this table, not a
redesign — `ReviewedCategory` already carries `categoryId`, so nothing about the engine's output
would need to change, only what the claim table records.

**The totals stamped on the claim row are audit-only, exactly as `budget_alert` stores no amounts.**
`total_budgeted_minor`/`total_actual_minor` are a record of what the month looked like when
dismissed, never re-read by the app — the live figures always come from `BudgetEngine.review`, so a
claim row can never disagree with the screen about what happened.

## Alternatives rejected

**Key by `(profile_id, month_start_iso_date, category_id)`, one row per material finding.** Rejected:
it answers a question this issue was not asked — "which findings has the user seen?" — and it would
let the card empty out one row at a time as findings were dismissed, which contradicts the "review
the month, then move on" framing the copy (`budgets_review_title`, "Got it") is written around.

**No claim table at all — recompute the review every time and never hide it.** Rejected outright.
`RULE-BUD-REVIEW.review_once_per_month` is an explicit rule param, and the rulebook's whole
discipline (§6, `RulebookDriftTest`) is that a param means something enforced, not a suggestion. A
card that reappeared every time the user opened the screen would also be the nagging FR-BUD-004's
own alert design deliberately avoids on the notification side.

**Reuse `budget_alert` and give it a nullable `band`.** Rejected: it would make one table answer two
different questions — "was this band's notification sent?" and "was this month's review shown?" —
and a query or a migration that assumed every row in `budget_alert` had a real band would be wrong
about the ones this repurposing added.
