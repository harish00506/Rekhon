# ADR-0025 — Card alerts mint `RULE-CC-DUE`, and claim once per **statement cycle**, not per month

- **Status:** accepted
- **Date:** 2026-08-17
- **Deciders:** Harish G (solo), implementing issue 6.1
- **Refs:** [ADR-0019](0019-budget-alert-bands-mint-a-new-rule-row.md),
  [ADR-0017](0017-budget-thresholds-stay-a-typed-mirror.md),
  [ADR-0005](0005-quick-setup-thresholds-deferred-rulebook-loader.md), CLAUDE.md §6,
  SRS §5.7 (FR-ACC-002), §17.1 (NTF-001, critical money events), §29 (AI-ARC-006),
  `ai/chat/guardrail.md` (AI-ARC-004)

## Context

Issue 6.1 gives a credit card its terms — limit, statement day, due day, minimum due, APR — and
turns two of them into notifications: *your payment is due*, and *you are over the utilisation line*.
Two questions had to be answered before any of it could be written.

**Where do the thresholds live?** `RULE-CC-UTIL` already exists. It shipped long before this issue,
already carries `max_utilisation_pct: 30`, and already names `card_alerts` in its `consumed_by` —
the rulebook was written expecting this engine. It has no reminder window, though, and there is
nowhere in it that a date belongs. Adding `remind_days_before` to its `params_json` is the obvious
move, and it is the one ADR-0019 already refused once for `RULE-BUD-PACE`: a params change bumps the
row's `version` (§29's schema note), which is [ADR-0017](0017-budget-thresholds-stay-a-typed-mirror.md)'s
**trigger 3** — an insight stored under the old version must stay reproducible (AI-ARC-006), and a
typed mirror cannot hold two versions at once. Firing trigger 3 means building `:core:rules`, an
`ai/`-to-assets pipeline, a `rules_knowledge_base` table with a migration, and retrofitting every
shipped mirror onto it, before this issue could compile.

**What stops a reminder repeating?** `BudgetAlertWorker` claims each alert in `budget_alert` keyed by
`(profile, budget, month_start, band)` before it notifies. A card looks like the same problem and is
not: a card billing on the 25th has a cycle that straddles two calendar months. Keyed by month, the
statement cut on 25 August and due on 14 September would be claimed under August, then re-derived on
1 September, find no September claim, and fire a second reminder for the same bill.

## Decision

**1. Mint `RULE-CC-DUE` at `version: "1.0"`; leave `RULE-CC-UTIL` untouched.**

The new row carries `remind_days_before: 3`, `remind_on_due_day: true`, `skip_when_nothing_due: true`.
Nothing shipped is bumped, so trigger 3 does not fire and the typed-mirror pattern (`CardRules` +
`RulebookDriftTest`) continues for one more issue. `_meta.version` moves 1.12.0 → 1.13.0; that number
describes the file and moves freely, so `BudgetRules.RULEBOOK_VERSION` and
`SafeToSpendRules.RULEBOOK_VERSION` are restated to match without either engine's rows changing.

The split is also right on the merits, not only on the process. `RULE-CC-UTIL` scores *how much of a
limit is in use* and feeds the financial-health score; `RULE-CC-DUE` decides *when to interrupt
someone about a date*. §17.1 classes a card payment as a **Critical money event** — exempt from
NTF-001's daily notification budget and allowed past quiet hours — while a utilisation nudge is
ordinary discipline on an ordinary channel. One row with both jobs would have one severity for two
severities' worth of decisions.

**2. Claim per statement cycle: `UNIQUE(profile_id, account_id, cycle_start_iso_date, kind)`.**

`cycle_start_iso_date` is the **statement date** of the cycle the alert belongs to (ISO `yyyy-MM-dd`,
TIM-002), produced by `BillingCycles.of(today, statementDay, dueDay)`. `kind` is in the key because
`DUE_SOON` and `UTILISATION` can both be true on the same day and are different messages on different
channels — a key without `kind` would silently drop one.

As with budget alerts, the **claim is the database constraint** and the worker claims before it
notifies. A crash between the two costs one notification, never a duplicate.

## Consequences

- The engine reads no clock and holds no state: "once per cycle" is enforced by the index, not by a
  flag on the card, so a reinstall that restores the database restores the claims with it.
- Both reminders are suppressed when nothing is owed (`skip_when_nothing_due`) and when no statement
  has been recorded. A reminder to pay ₹0 is exactly the notification that teaches a user to mute
  this channel — and this is the channel that must still work in eleven months.
- The utilisation alert reads the **statement** figure, not the live balance. A live figure moves
  with every swipe, so it would either nag or be claimed once and then be wrong for the rest of the
  cycle. The screen still shows both, each labelled with its `UtilisationBasis` (P-02).
- The cost of the split is a second citation to maintain and a second row a reviewer must open.
  `RulebookDriftTest` covers it: it asserts the reminder window lives on `RULE-CC-DUE` and *not* on
  the shipped utilisation row, and the threshold on `RULE-CC-UTIL` and not duplicated onto the new
  one — so a future edit that merges them goes red.
- The debt this defers is unchanged and still ADR-0005's: nothing loads `ai/` at runtime. The
  mirrors now number nine, and the day a row's version genuinely must move is the day the loader
  gets built.

## Alternatives considered

| Option | Why not |
|---|---|
| Add `remind_days_before` to `RULE-CC-UTIL` | Bumps a shipped row → ADR-0017 trigger 3 → the runtime rules loader, before this issue compiles. Also merges two severities (§17.1) into one row. |
| Key the claim by month start, like `budget_alert` | A cycle straddling a month boundary fires twice for one statement. The bug is invisible for cards billing early in the month, which is what makes it dangerous. |
| One alert kind carrying both reasons | The two go to different channels with different quiet-hours behaviour (§17.1); collapsing them loses one message and mis-severities the other. |
| Alert on the live balance | It changes hourly; once claimed, the figure in the notification is already stale. The statement is what is actually owed and what a bureau records. |
| Hold "already notified" as a flag on `credit_card` | One flag cannot express two kinds across successive cycles, and it would have to be reset by something — a scheduled write that can fail, replacing a constraint that cannot. |
