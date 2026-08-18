# SafeToSpendEngine — AI-STS

**SRS:** §5.2, §14 · **Pipeline layer:** L5 (decisions) · **Module:** `:domain:engines:safetospend`
**Version:** 1.0 · **Status:** active

## Why this engine exists

Safe-to-Spend is the figure at the top of the app's home screen — the one number a user is expected
to glance at and act on. Until issue 5.2 it was a literal: `Money(12_500_00L + today.dayOfMonth)`,
invented in `DashboardViewModel` and shipped in every build from issue 1.10 onward. A fabricated
number on the first screen the user sees is the plainest possible breach of **P-03** ("numbers from
math, words from AI").

The subtraction itself is trivial. What is not trivial is deciding what the month has already
claimed. Money already spent is obvious; the rest is not. A bill the user scheduled for the 28th is
spent as surely as one paid this morning. A confirmed recurring rule due on the 25th is a commitment
the app knows about and the user has forgotten. And savings the user told this app they intend to
make are not spare cash merely because they have not moved yet. A Safe-to-Spend that ignored any of
the three would be optimistic in exactly the way that ends a month short — the failure the figure
exists to prevent.

Downstream: the Purchase Advisor (issue 10.1, AI-PA) runs affordability against this figure, the
financial-health score (§14, AI-FHS) reads it, the Glance widget (issue 5.5) renders the cached
value, and `RULE-IDLE-CASH` compares liquid balances against "Safe-to-Spend needs + buffer".

## Contract

```kotlin
interface SafeToSpendEngine {
    fun compute(input: SafeToSpendInput): Result<SafeToSpend, AppError>
}
```

- **Input** — `SafeToSpendInput`:
  | Field | Meaning | Format |
  |---|---|---|
  | `income` | The period's income, resolved by the caller per `RULE-STS.income_basis` | `Money`, paise, non-negative |
  | `spentToDate` | §8.3's true spend so far (needs + wants, issue 4.3) | `Money`, paise, magnitude |
  | `scheduled` | Future-dated transactions due inside the window (FR-TXN-010) | `Money`, paise, magnitude |
  | `recurringDue` | Confirmed recurring rules due inside the window (FR-TXN-006), deduplicated against `scheduled` by the caller | `Money`, paise, magnitude |
  | `goalContributionsRemaining` | The month's savings envelope not yet made | `Money`, paise, magnitude |
  | `inputWindow` | The period read | `yyyy-MM-dd..yyyy-MM-dd` (TIM-002) |
  | `nowUtcMillis` | The caller's instant — **passed in, never read** (TIM-001) | UTC epoch millis |
  | `rules` | `RULE-STS`'s thresholds, injected so a test can move one | `SafeToSpendRules` |

- **Output** — `SafeToSpend`: `amount` (signed paise — **negative is a valid answer**), `lines`
  (the ordered breakdown, one `SafeToSpendLine` per non-zero term), and `provenance`
  (`engineId = "safe-to-spend"`, `engineVersion = "1.0"`, `inputWindow`, `computedAtUtcMillis`,
  `evidence = [RULE-STS v1.0]`; **no `confidenceBps`** — this is arithmetic over resolved amounts,
  not an inference, and a confidence here would be a number with nothing behind it).

## Formula / algorithm

```
buffer          = income × RULE-STS.buffer_pct %          (HALF_EVEN to whole paise, MNY-002)
goals           = include_goal_contributions ? goalContributionsRemaining : 0

raw             = income − buffer − spentToDate − scheduled − recurringDue − goals
safeToSpend     = floor_at_zero && raw < 0  ?  0  :  raw
```

The breakdown is **built first and the figure folded from it**, not the other way round. That
ordering is the design: the card's arithmetic is literally the headline's arithmetic, so the two
cannot disagree. `SafeToSpend`'s constructor asserts that the signed lines sum to `amount`.

A zero term is **left out of the breakdown entirely** — a card reading "Bills due ₹0 · Scheduled ₹0 ·
Savings ₹0" is noise around the one line that matters, and a breakdown the user stops reading has
stopped satisfying P-02. Income is the exception and is always shown; a card that opened with a
deduction would be explaining a subtraction from nothing.

When the floor engages, the clamp does **not** silently drop the shortfall: a `SHORTFALL` line is
appended carrying `|raw|`, so the breakdown still sums to the ₹0 headline. A clamp that dropped it
would leave a negative breakdown beside a zero figure — a plausible-looking fiction, which is worse
than showing no breakdown at all. In the shipped rulebook `floor_at_zero` is `false`, so the
component never appears; it exists so the parameter is honoured rather than decorative.

## Assumptions & guardrails

- Money is `Long` paise end-to-end (MNY-001); the buffer is applied as basis points via
  `Money.percentOf` with HALF_EVEN rounding (MNY-002). **No `Double` anywhere.**
- No clock (TIM-001) and no randomness — same input, same output, forever (P-08). `nowUtcMillis` is
  stamped into provenance and never read for a decision.
- **It decides nothing about which rows count.** Every term arrives pre-summed;
  `SafeToSpendRepository` answers "what belongs in `scheduled`?" because that is a storage question
  (ARC-005). This is what makes the whole formula provable on the JVM.
- **Cold start:** a profile with no quick-setup envelopes and no posted income has *no income basis*.
  The engine is not called at all — `SafeToSpendRepository` emits `null` and the dashboard renders
  the absence. Calling it with `income = Money.ZERO` would produce a confident ₹0, which is a figure
  the app made up (P-03); `SafeToSpendInput` cannot prevent that (zero is legal) so the rule lives in
  the repository and its test.
- **Never floors by default.** A user ₹8,000 past the plan is told by how much; a clamped ₹0 reads
  identically to a month with nothing left and nothing wrong.
- **It advises, it does not act** (P-07). Nothing here writes a row, moves a rupee or blocks a spend.
- It produces a number and an ordered list of terms — **never prose** (P-03). The words for each
  component live in `:feature:dashboard`'s `strings.xml` (§21.6).

### Stated limitation — "goal contributions" is a stand-in until issue 7.1

§5.2 names goal contributions as a term, and the goals engine (AI-GOAL, issue 7.1) does not exist in
this build. The repository substitutes **the quick-setup INVEST envelope, in full** — the user's own
declared monthly saving. When 7.1 lands, this term becomes the sum of the month's linked goal
contributions and the engine's contract does not change; only what the repository puts in the field.

**In full, not netted against what has already been saved** — the first draft netted it, and that
was wrong in a way worth recording. §8.3's `trueSpend` is `NEED + WANT`, so it *already excludes*
every conversion: money that went into an SIP appears in neither `spentToDate` nor a netted goal
term, so netting would leave it deducted from nothing at all and Safe-to-Spend would **rise** by
exactly the amount the user had just saved. Taking the envelope whole counts each planned rupee once,
whether it has moved yet or not, and keeps the figure steady across the month instead of stepping up
on the day the SIP debits. It also makes the mechanism irrelevant: an SIP booked as an expense row
and one booked as a transfer to a mutual-fund account are both excluded from `trueSpend`, so both are
covered here once.

### The terms must not overlap, and one pair did

`spentToDate` comes from `TransactionRepository.observeNatureBreakdown()` and `scheduled` from
`observeUpcoming()`. Until issue 5.2 the nature breakdown ran to the **month's last day**, so every
future-dated row inside the month was in both — a bill scheduled for the 28th was subtracted twice.

The fix was on the 4.3 side, because that read was independently wrong: it feeds a card captioned
"This month, **actually**", and FR-TXN-010 says future-dated rows are "excluded from actuals but
included in forecasts". It is now bounded at `MonthWindow.actualsEndIsoDate`, the same bound
`observeMonthCashFlow` three methods below has always used, which makes the two sets disjoint by
construction rather than by anyone remembering.

## Rules / knowledge consumed

| ID / file | What it provides |
|-----------|------------------|
| `RULE-STS` (`ai/rules/rules-kb.json` v1.13.0) | `income_basis`, `horizon`, `buffer_pct`, `include_goal_contributions`, `floor_at_zero` |

Mirrored as `SafeToSpendRules` — a typed copy, not a runtime read, for the reason ADR-0017 records
(nothing in the app loads `ai/` yet). `RulebookDriftTest` is the mechanism that keeps the copy
honest, and it covers `income_basis` and `horizon` too, even though no field mirrors them: both
describe how `SafeToSpendRepository` resolves a term, and changing either in the rulebook without
changing the repository would leave every threshold assertion green while the figure came from the
wrong data entirely.

## Evidence shown to the user (P-02)

The dashboard card renders the headline figure, then **every line of the breakdown** with its
`strings.xml` label and amount, then the citation `RULE-STS v1.0` — the same `dashboard_reason_rule`
format the budget-alert line already uses. Nothing on the card is re-derived by the screen; it
renders `SafeToSpend` exactly as the engine produced it.

## Tests

- **Golden-file** (`src/test/resources/golden/safe-to-spend.txt`, 12 records): an ordinary salaried
  month, cold start, an overcommitted month, a zero buffer, HALF_EVEN rounding, goals switched off,
  the floor engaged, the floor at exactly zero, the floor on a solvent month, a one-rupee income, the
  maximum legal buffer (99%), and fifty-lakh amounts. Each record fixes the **breakdown as well as
  the figure**, because four of the six deductions are plain subtractions and any two could be
  transposed with every total still correct.
- **Property** (500 seeded months): the breakdown always sums to the figure; raising any commitment
  never raises the figure; raising income never lowers it; the same month always computes the same
  answer; and the generator is reproducible from its seed.
- **Determinism:** covered by the two properties above plus
  `SafeToSpendEngineTest."the same month computes to the same answer every time"`.
- **Drift:** `RulebookDriftTest` fails the build when `RULE-STS` and `SafeToSpendRules` disagree.
- Coverage: engine ≥ 85%, money math 100%.

Both gates were watched go red before being trusted — a mis-labelled golden record and an edited
`buffer_pct` each fail the build.

## Version log

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-08-16 | Initial implementation from SRS §5.2/§14 (issue 5.2), against `RULE-STS` v1.0. |
