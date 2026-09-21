# ADR-0047 — One notification gate: §17.2's caps and quiet hours as rules, claim after the gate, the digest deferred

- **Status:** accepted
- **Date:** 2026-09-20
- **Deciders:** Harish G (solo)
- **SRS refs:** §17.1 (taxonomy and defaults), §17.2 (NTF-001 … NTF-006), §7.2 stage 6, AI-ARC-003/004,
  P-03, P-04, P-08, TIM-001, DB-003, CLAUDE.md §6; issue 9.6. Builds on ADR-0017 (typed mirrors),
  ADR-0022 (the blur on notifications), ADR-0046 (the feed it notifies from)

## Context

§17.2 sets the policy:
- **NTF-001** — at most 2 non-critical notifications a day and 8 a week. The excess folds into the
  weekly digest. Critical money events are exempt.
- **NTF-002** — quiet hours from 22:00 to 08:00, or a window learned from the hours the app is
  opened. Critical events may bypass them.
- **NTF-003** — every notification deep-links to its screen with actions.
- **NTF-004** — amounts are hidden on the lock screen by default.
- **NTF-005** — notifications are local only.
- **NTF-006** — one Android channel per taxonomy row.

§17.1 lists seven rows: Critical, Budget & discipline, AI insights, Maintenance, Goal events, Habit,
and a weekly digest. Critical, Budget, Maintenance and Goal events are on by default. AI insights
are digest-only by default, and Habit is off.

Before this issue three things posted notifications: `BudgetAlertWorker` and `CardAlertWorker`
(4.5, 6.1), with 9.5's feed about to be a third. Each claimed and posted on its own, with no shared
count and no clock check. Two workers that each allowed two a day would allow four between them.

## Decision

**1. One pure engine, AI-NTF 1.0 (`:domain:engines:notification`).**
- **Input:** candidates, what has been sent, local time and rules. **Output:** one decision per
  candidate.
- **The questions, in order.** First, has this key already been sent? If so the answer is
  `ALREADY_SENT`, since a repeat is not a second message. Next, is it quiet hours? If so it is
  `WAIT_FOR_QUIET_HOURS` until the end hour. Last, is the day's or the week's ration spent? If so
  it is `FOLD_INTO_DIGEST`. Otherwise it is `DELIVER`.
- **It reads no clock.** The repository resolves local time in the profile's zone from the
  injected `Clock` (TIM-001), so each boundary can be tested to the minute (P-08).
- **Quiet hours:** inclusive at 22:00 and exclusive at 08:00.
- **A held message costs nothing** from the ration, so a message waiting for morning cannot
  silence the one after it.
- **The plan counts its own deliveries.** If a batch of three arrives with two slots left, the
  third folds.

**2. The numbers are rules.**
- `RULE-NTF-BUDGET` holds `daily_max 2`, `weekly_max 8`, `window_days 7` and `critical_exempt`.
- `RULE-NTF-QUIET` holds `22`, `8` and `critical_may_bypass`.
- Both are in rules-kb 1.19.0 with a typed mirror and a drift test (ADR-0017).
- The week is a **rolling seven days**, not a calendar week. A calendar week would allow eight on
  Sunday and eight more on Monday.

**3. One gate with a memory: `NotificationRepository` over `notification_log` (schema 24).**
- **One row per key per profile**, enforced by a unique index. An upsert keeps the row's id and
  creation time.
- **A delivery is recorded as sent before the caller posts it.** This is the claim-then-notify
  order the workers already use. If posting then fails, the log still says sent, so the error is
  on the side of silence, which is the side NTF-001 protects.
- **No tombstone.** The table is exempt from the soft-delete invariant for the reason `insight` is
  (`MigrationSafetyTest` records it): the unique index would count a tombstone, and deleting a
  send would un-send it for the never-twice rule.
- The table goes into backup, restore and the demo wipe like every other table.

**4. The workers ask the gate before they claim.**
- A claim in `budget_alert` or `card_alert` is permanent, so claiming an alert the policy then held
  would lose it. Only a delivered alert is claimed and posted.
- A held or folded alert stays pending, so the next daily run offers it again, and the in-app
  banner shows it meanwhile.
- Keys:
  - budget: `budget:<id>:<band>:<yyyy-MM>`, which is `RULE-BUD-ALERT`'s once per band per month;
  - card: `card:<account>:<kind>:<cycle>`.
- Kinds: a card coming due is **Critical**, a utilisation warning is **Debt discipline**, and a
  budget alert is **Budget discipline**.
- The overspend is offered before the warning, so a day with one slot left spends it on the worse
  news.

**5. §7.2 stage 6: the feed is offered to the gate after each refresh, filtered to §17.1's defaults.**
- **What is offered:** a crunch day is Critical and a goal falling behind is a Goal event.
- **What is not:**
  - An overspent budget is already told by the budget worker on its own channel. Saying it twice
    would spend two of the ration on one fact.
  - The emergency-fund, seasonal and health-lever cards are the AI-insights row, which is
    digest-only by default.
- **Order:** the feed's own RULE-INS-RANK order.
- **Status:** only `ACTIVE` cards. Dismissing a card is the user saying "not now", and that
  outranks the policy's yes.
- **A crunch is keyed by its first crunch day,** not by its fingerprint. Its period is the day it
  was computed, so the fingerprint changes daily. Keyed that way, a crunch that persisted would
  notify every morning on the one channel that ignores the caps.

**6. Every message is guardrailed; with the blur on, no digit is shown; and every message is private.**
- `AndroidInsightNotifier` formats every figure from the insight's own fields. `NumericGuardrail`
  checks the text against exactly those fields, and a mismatch posts nothing (AI-ARC-004, P-03).
- With the privacy blur on, the text carries no digits at all, dates included (NTF-004,
  ADR-0022).
- Insight notifications are `VISIBILITY_PRIVATE`, so the lock screen shows only that a message
  exists.
- A tap opens the dashboard, whose feed shows the card with its evidence (NTF-003, P-02).

**7. One channel per §17.1 row, all seven, each id taken from `NotificationKind.channelId` (NTF-006).**
- A test holds the two lists together. On API 26+, a kind with no channel posts into nothing,
  silently.
- Maintenance and Habit have no sender yet. Their channels exist now so they can be turned down in
  advance.
- Habit is `IMPORTANCE_LOW`.

## Deferred, and why

- **The weekly digest notification.** A folded message is not lost: it is still in the feed, and it
  is offered again on the next run. The digest is a composed summary, which needs its own wording,
  guardrail inputs and schedule. That is a feature in its own right, not a side effect of the gate.
- **Per-type in-app switches** (§17.1's "default ON/OFF"). No settings field holds them, for the
  reason ADR-0034 gives: a switch minted without its screen is a switch nobody can reach. The
  defaults are applied by what is offered (item 5), and Android's per-channel switch works today
  because the channels are the rows (item 7).
- **NTF-002's learned window** from the hours the app is opened. Nothing records app-open hours,
  and the fixed window is the SRS's own fallback.
- **Notification actions** such as "Snooze" or "Mark paid". The feed already has these; putting them
  on the notification needs a receiver per action and a locked-session path that reaches the
  database, which SEC-002 forbids from a broadcast.
- **The debounced transaction trigger** that ADR-0046 deferred to this issue. The gate is its
  prerequisite, not its scheduler. A transaction-time trigger would notify about a purchase while
  the user is still making it, which is the moment 4.5 argued against.

## Consequences

- Every notification the app can post now passes one gate and spends one ration. A future sender
  gets the policy by asking the gate, and cannot get around it without bypassing `NotificationRepository`.
- The engine is proven at every boundary: 21:59 / 22:00, 07:59 / 08:00, the second and third of the
  day, the eighth and ninth of the window, and a send just outside the window. It is also checked
  against an independent Python oracle and by property tests. The repository is proven against a
  real database, including a profile zone that is not UTC.
- Schema 24 adds a table. The migration round-trip test and the restore drill cover it.
