<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 9.6 — AI-NTF, §17.2's notification policy, and the one gate every notifying worker asks.
  Result: a reader can see why there is one gate rather than a policy per worker, why the gate comes
          before the claim, and what §17 this issue deliberately left for later.
  Changelog: 2026-09-21 — Created.
-->

# 2026-09-21 — The notification policy (issue 9.6, ADR-0047)

**Branch:** `feature/9-6-notification-engine-policy` off `dev` (`7201994`)
**Versions:**
- **VERSION** 0.9.5 → **0.9.6**
- **versionCode** 41 → 42
- **Schema** 23 → 24 (`notification_log`)
- **rules-kb.json** 1.18.0 → **1.19.0**
- `AI-NTF` 1.0 (new)

---

## 1 · Decisions this session

The full argument for each is in ADR-0047.

- **One gate for the whole app, not a policy per worker.** Before this issue, the budget and card
  workers each claimed and posted on their own. Two workers allowing two a day each would allow
  four. `NotificationRepository` is a singleton over one log, so every sender spends one ration.
- **The engine is pure; the repository owns time.** The repository reads local time in the
  profile's zone from the injected `Clock` (TIM-001) and passes it in, so every boundary can be
  tested to the minute. `NotificationRepositoryTest` runs in Kolkata time. 22:30 local time is only
  17:00 UTC, so a gate measuring in UTC would deliver when it should hold. That test was watched
  red.
- **The questions are asked in a fixed order:** already sent, then quiet hours, then the caps.
  - A repeat is not a second message, so it cites no rule.
  - **A held message costs nothing.** Otherwise a message waiting for 08:00 would silence the next
    one.
  - The plan counts its own deliveries, so a batch cannot overspend.
- **The week is a rolling seven days.** A calendar week would allow eight on Sunday and eight more
  on Monday.
- **The gate comes before the claim.** A claim in `budget_alert` or `card_alert` is permanent, so
  claiming an alert the policy then held would lose it for the month or the cycle. A held alert
  stays pending: the next daily run offers it again, and the banner shows it meanwhile.
- **A delivery is logged as sent before it is posted.** This is the claim-then-notify order the
  workers already use, and it errs toward silence.
- **`notification_log` has no tombstone.** It is exempt from the soft-delete invariant, argued in
  `MigrationSafetyTest` like `insight`: a tombstone would take the key's unique slot, and deleting
  a send would un-send it for the never-twice rule.
- **§7.2 stage 6 offers only what §17.1 enables by default.**
  - A crunch is offered as Critical, and a goal falling behind as a Goal event.
  - An overspent budget is already told by its own worker.
  - The emergency-fund, seasonal and lever cards are the AI-insights row, which is digest-only by
    default.
  - Only `ACTIVE` cards are offered, in the feed's rank order.
- **A crunch is keyed by its first crunch day,** not its fingerprint. Its period is the day it was
  computed, so its fingerprint changes daily. Keyed by fingerprint it would notify every morning,
  on the channel that ignores the caps.
- **All seven §17.1 channels are created at start-up** (NTF-006). Each id comes from
  `NotificationKind.channelId`, and a test holds the two lists together.
- **Insight notifications are `VISIBILITY_PRIVATE`, and a blurred one carries no digit.** Dates are
  included in that (NTF-004).
- **Deferred:**
  - the weekly digest;
  - per-type in-app switches;
  - the learned quiet window;
  - actions on a notification;
  - the transaction trigger.

  Each is argued in ADR-0047. A folded message is not lost: it is still in the feed, and it is
  offered again.

## 2 · Flow changed this session

```
BudgetAlertWorker  pending → sort EXCEEDED first → NotificationRepository.decide
                   → for DELIVER only: markNotified → BudgetAlertNotifier.notify
CardAlertWorker    pending → NotificationRepository.decide (DUE_SOON = CRITICAL_MONEY,
                   UTILISATION = DEBT_DISCIPLINE) → for DELIVER only: markNotified → notify
InsightRefreshWorker refresh() → observeFeed().first() → ACTIVE ∩ InsightNotifications.kindFor
                   → NotificationRepository.decide → for DELIVER only: InsightNotifier.notify
NotificationRepository.decide
  → notificationLogDao().sentSince → NotificationPolicyEngine.decide (pure, local time)
  → upsert one notification_log row per decided key (ALREADY_SENT not rewritten)
CfoApplication → CfoNotifications.createChannels → all seven §17.1 channels
```

`FLOW.md` §2.11 holds the full chain.

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `ai/rules/rules-kb.json`, `rulebook.md` | 1.19.0: RULE-NTF-BUDGET, RULE-NTF-QUIET |
| nine `*Rules.kt` mirrors | restate `RULEBOOK_VERSION` 1.19.0 |
| `domain/engines/notification/` (new) | AI-NTF: the interface, the kinds and channels, the policy, the mirror, and its tests, golden file and oracle, plus `ENGINE.md` |
| `core/database/.../Entities.kt`, `Daos.kt`, `CfoDatabase.kt`, `Migrations.kt` | schema 24: `notification_log`, `NotificationLogDao`, `MIGRATION_23_24` |
| `core/database/.../MigrationSafetyTest.kt`, `MigrationRoundTripTest.kt` | the argued exemption; 23 → 24 round trip, one row per key |
| `data/repository/NotificationRepository.kt` (new) | the gate: reads the log, resolves local time, runs AI-NTF, records decisions |
| `data/repository/RepositoryFactory.kt` | `notifications(...)` |
| `data/repository/Archive.kt`, `ArchiveRepository.kt`, `DemoModeRepository.kt`, `DrillFixture.kt` | `notification_log` is backed up, restored, counted, wiped and drilled |
| `data/repository/.../NotificationRepositoryTest.kt` (new) | covers the ration across runs, never twice, a fold then its delivery, the profile's quiet hours, one row per key, critical, and profile scope |
| `app/.../di/RepositoryModule.kt` | provides the engine and the singleton gate |
| `app/.../di/NotificationModule.kt` | binds `InsightNotifier` |
| `app/.../work/BudgetAlertWorker.kt`, `CardAlertWorker.kt` | ask the gate before they claim |
| `app/.../work/InsightRefreshWorker.kt` | runs §7.2 stage 6 after the refresh |
| `app/.../notification/InsightNotifier.kt` (new) | holds the mapping and keys, and composes, guardrails and posts each insight |
| `app/.../notification/CfoNotifications.kt` | creates all seven channels, as a table |
| `app/src/main/res/values/strings.xml` | four channel names and descriptions; crunch and goal copy with blurred twins |
| `app/src/test/.../FakeNotificationRepository.kt` (new) and the worker, notifier and channel tests | the gate's seam, and each claim watched red |
| `docs/adr/0047-…`, `DECISIONS.md`, `FLOW.md` §2.11, `ai/orchestrator/*.yaml` | the records |
