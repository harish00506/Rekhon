# InsightEngine — AI-ORCH (the Insight Orchestrator's assembly step)

**SRS:** §7.2 (and FR-AI-002, FR-HOME-001)  ·  **Pipeline layer:** L5  ·  **Module:** `:domain:engines:insight`
**Version:** 1.0  ·  **Status:** active  ·  **Engine id on results:** `AI-ORCH`

## Why this engine exists
The app computes a great deal and says almost none of it unprompted. §7.2's pipeline ends with
"persist Insights → notify", so something has to decide **which** engine result is worth a card, in
what order, and how a card stays the same card when the numbers behind it are recomputed.

It computes no figure of its own (P-03). Every insight repeats a number another engine published and
carries that engine's id, version, rules and confidence with it (AI-ARC-003/006).

## Contract
```
interface InsightEngine {
    fun insights(input: InsightInput): Result<InsightFeed, AppError>
}
```
- **Input** — `InsightInput`: `today`, `nowUtcMillis`, `rules`, and five optional signals, each a
  small type declared in this module and filled by the repository:
  - `ForecastSignal` — crunch days, the first of them, that day's balance, the buffer, and the
    months the season makes dearer;
  - `EmergencyFundSignal` — shortfall, monthly top-up, runway;
  - `HealthSignal` — score, the biggest lever and its points;
  - `BudgetSignal` — an overspent category for a month;
  - `GoalSignal` — a goal short of its plan.
- **Output** — `InsightFeed`: `insights` in RULE-INS-RANK's order, `dashboard` (the first
  `dashboard_max`), and provenance (AI-ORCH 1.0, citing RULE-INS-RANK and RULE-INS-DEDUP, with
  **no confidence of its own** — each insight carries the confidence of the engine it repeats).
- `Err(Validation("insight.<signal>"))` only for an impossible signal: a negative count or amount.

**No dependency on another engine module, deliberately.** An L5 engine that imported AI-FCT and
AI-FHS could start re-deriving what they own; this one can only repeat what it is handed.

## Formula / algorithm
```
raise            one card per signal worth one:
                   crunch days > 0 · a month whose seasonal adjustment is positive ·
                   shortfall > 0 · a lever with points to gain · a budget overspent ·
                   a goal short of its plan
severity         the type's, never per card: crunch = CRITICAL; budget, fund, goal = WARNING;
                 seasonal month, lever = INFO
fingerprint      type + "|" + subject (or "-") + "|" + period            (RULE-INS-DEDUP)
order            severity, then amount at stake descending (no amount last), then fingerprint
dashboard        the first `dashboard_max` of that order                 (FR-HOME-001's "top 3")
```

## Assumptions & guardrails
- **A card repeats, it never computes.** Amounts, dates and counts come from the signal; so do the
  citations and the confidence.
- **The finding is a type and its figures, not a sentence.** §21.6 keeps user-visible words in
  `strings.xml`; the screen builds the sentence.
- **Only the dearer seasonal months are raised** — a cheaper month is good news that needs no
  action, and the feed is what needs attention.
- **The lever carries no amount**, so it sorts below anything with rupees at stake.
- Deterministic and pure: no clock, no randomness, no I/O (P-08). The same signals always give the
  same feed, which is what makes a fingerprint worth dismissing.
- **Not raised yet** (ADR-0046): card-utilisation and revolving-interest cards (the health lever
  covers utilisation today), anomaly alerts (§ANM), market and tax insights, and anything the
  notification engine (9.6) will own.

## Rules / knowledge consumed
| ID / file | What it provides |
|-----------|------------------|
| RULE-INS-RANK v1.0 (`rules-kb.json` 1.18.0) | the severity order, the tie-breaks, `dashboard_max` 3 |
| RULE-INS-DEDUP v1.0 | the fingerprint, `on_match: update_existing`, a 7-day snooze |

Mirrored as `InsightRules` and guarded by `InsightRulebookDriftTest`, which also pins the rule's
**words** — the severity order against the `Severity` enum, and the tie-break string.

## Evidence shown to the user (P-02)
The dashboard's "What needs attention" shows, per card: the finding with its figures, one
recommended action (FR-AI-002), and "From `<engine>` v`<version>` · `<rules>`". Two buttons —
"Later" and "Dismiss" — record the verdict; both suppress the card for the rulebook's window.
Every amount is masked by the privacy blur.

## Tests
- **Golden** (`golden/insight.txt`): a fixed engine set — a forecast with crunch days and three
  seasonal months, a fund, a score, three budgets and two goals — compared line for line with an
  **independent** oracle (`insight_oracle.py`), which reads the rulebook and re-implements the order
  from the rule's words.
- **Property** (`InsightPropertyTest`, 6 × 300 cases): the order holds everywhere; the same input
  gives the same feed and input order does not matter; fingerprints are unique; a later clock
  changes nothing; the dashboard is the feed's own first few; every card names an engine and a rule.
- **Behaviour** (`InsightEngineTest`, 15): each signal's card and its silence, the ranking and its
  tie-breaks, the fingerprint's stability, the refusals, provenance.
- **Drift** (`InsightRulebookDriftTest`, 7).
- **Watched red:** removing the severity order failed the golden, behaviour and property tests;
  raising cheaper seasonal months failed the golden and behaviour tests; editing the KB's
  `dashboard_max` alone failed the drift test.

## Version log
| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-09-20 | Initial implementation from SRS §7.2 (issue 9.5, ADR-0046). |
