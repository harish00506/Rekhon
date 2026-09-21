# NotificationPolicyEngine — AI-NTF (the notification policy)

**SRS:** §17.1, §17.2 (NTF-001/002/005/006)  ·  **Pipeline layer:** L5  ·  **Module:** `:domain:engines:notification`
**Version:** 1.0  ·  **Status:** active  ·  **Engine id on results:** `AI-NTF`

## Why this engine exists
An app that can interrupt has to ration the interruptions. §17.2 sets the ration:
- at most two non-critical notifications a day and eight a week;
- nothing non-critical between 22:00 and 08:00;
- critical money events exempt from both.

Getting it wrong costs more than one missed message. The user turns the channel off, and then the
notification that mattered never arrives either. Before this engine, each notifying worker counted
for itself, so two workers that each allowed two a day allowed four between them.

## Contract
```
interface NotificationPolicyEngine {
    fun decide(input: NotificationInput): Result<NotificationPlan, AppError>
}
```
- **Input** — `NotificationInput`:
  - `candidates` — each has a `key` and a `NotificationKind`, in the order they should spend the
    ration;
  - `history` — `SentNotification`s, each with a key, a kind and a local `sentAt`;
  - `now` — local time in the profile's zone;
  - `nowUtcMillis` and `rules`.
- **Output** — `NotificationPlan`:
  - one `NotificationDecision` per candidate, in order, each with an outcome, a `deliverAfter` when
    it is held, and the rules that decided it;
  - `deliverable`, the candidates to post now;
  - provenance: AI-NTF 1.0, citing both rules. `inputWindow` is the seven days to now, and there is
    **no confidence**, because a policy decision is not an estimate.
- `NotificationKind` is §17.1's taxonomy, one Android channel per row (NTF-006). `critical` marks the
  row the caps and the quiet hours exempt.
- `Err(Validation(...))` is returned only for an impossible input:
  - `notification.key` for a blank key;
  - `notification.history` for a send dated after `now`.

**No clock and no I/O** (P-08, TIM-001): `NotificationRepository` resolves the zone from the
injected `Clock` and reads `notification_log`.

## Formula / algorithm
```
for each candidate, in the order offered:
  key already sent                                  → ALREADY_SENT        (cites nothing)
  not (critical ∧ may_bypass) ∧ quiet(now)          → WAIT_FOR_QUIET_HOURS, deliverAfter = next end hour
  not (critical ∧ exempt) ∧ (today ≥ daily_max ∨ window ≥ weekly_max)
                                                    → FOLD_INTO_DIGEST    (RULE-NTF-BUDGET)
  otherwise                                         → DELIVER             (both rules)
  a non-critical DELIVER adds one to today and to window

quiet(t)   start ≤ hour(t) ∨ hour(t) < end      (the window wraps midnight: 22:00 in, 08:00 out)
today      non-critical sends on now's date
window     non-critical sends after now − window_days   (rolling, not a calendar week)
```

## Assumptions & guardrails
- **A held message costs nothing.** A message waiting for 08:00 does not spend the day's ration.
- **Folded is not dropped.** The finding stays in the feed and is offered again on the next run.
  The weekly digest notification is deferred (ADR-0047).
- **Never twice.** Every key ever sent is in the history. The log keeps one row per key, so the
  history is bounded by the number of distinct messages, not by time.
- **The policy decides; it words nothing.** Wording and the numeric guardrail belong to the
  notifiers in `:app` (AI-ARC-004), which run only for a `DELIVER`.
- **Not implemented** (ADR-0047):
  - NTF-002's window learned from app-open hours;
  - per-type in-app switches;
  - the weekly digest.

## Rules / knowledge consumed
| ID / file | What it provides |
|-----------|------------------|
| RULE-NTF-BUDGET v1.0 (`rules-kb.json` 1.19.0) | `daily_max` 2, `weekly_max` 8, `window_days` 7, `critical_exempt` |
| RULE-NTF-QUIET v1.0 | `start_hour` 22, `end_hour` 8, `critical_may_bypass` |

These are mirrored as `NotificationRules` and guarded by `NotificationRulebookDriftTest`. The drift
test also pins every kind's channel id, because renaming a channel would orphan the user's setting.

## Evidence shown to the user (P-02)
A notification states the engine's figures, verified by the guardrail, and a tap opens the screen
that shows the card with its evidence and rules (NTF-003). The log records, per key, the outcome,
when it was decided, when it was sent, and when a held message may go.

## Tests
- **Behaviour** (`NotificationPolicyEngineTest`, 16) covers the boundaries:
  - quiet hours at 21:59 / 22:00 and 07:59 / 08:00, with where the wait ends;
  - the daily cap at the second / third send, and one sent leaving room for one;
  - yesterday not counting;
  - the week's eighth send, and a send aged out of the window;
  - both exemptions;
  - the plan spending its own ration in order;
  - a repeat costing nothing;
  - a held message not spending the ration;
  - the refusals, provenance, and the rules seam.
- **Golden** (`golden/notification.txt`, 4 scenarios): the file is compared line for line with an
  **independent** oracle, `notification_oracle.py`, which reads the rulebook.
- **Property** (`NotificationPropertyTest`, 6 × 300):
  - never more delivered than the day's or the window's remaining ration;
  - critical always delivered;
  - never twice;
  - a hold names a later time;
  - determinism.
- **Drift** (`NotificationRulebookDriftTest`, 6).
- **Watched red:**
  - a quiet window starting an hour late;
  - a plan that does not count its own deliveries;
  - editing the KB's `daily_max` alone.
- **Downstream:** `NotificationRepositoryTest` in `:data:repository` checks, against a real
  database, that the ration and the never-twice rule hold across runs, and that the quiet hours use
  the profile's zone rather than UTC. It was watched red for UTC, and for sends that were never
  stamped.

## Version log
| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-09-20 | Initial implementation from SRS §17.2 (issue 9.6, ADR-0047). |
