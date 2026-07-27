# QuickSetupEngine — FR-ONB-002

**SRS:** §5.1 (FR-ONB-002) · **Pipeline layer:** L3 (rules) · **Module:** `:domain:engines:quicksetup`
**Version:** 1.0 · **Status:** active

## Why this engine exists

Onboarding asks for three rough figures — monthly income, rent or EMI, typical savings — and
FR-ONB-002 says they are "used to seed budgets and the emergency-fund target". This engine is that
derivation, and it is the only place it lives: the onboarding summary renders what this returns and
`QuickSetupRepository` persists what this returns. Neither computes a rupee of its own (P-03).

Downstream: the onboarding quick-setup step (issue 2.1's screen), `QuickSetupRepository`
(issue 2.3), and the dashboard's needs/wants/savings bar. Issues 4.4 (budgets) and 7.2
(emergency fund) take over the persisted rows when their own screens exist.

## Contract

```kotlin
interface QuickSetupEngine {
    fun plan(input: QuickSetupInput): Result<QuickSetupPlan, AppError>
}
```

- **Input** — `QuickSetupInput`:
  - `monthlyIncome`, `rentOrEmi`, `typicalSavings` — `Money?` (`Long` paise, MNY-001). `null` when
    the user left the field blank; **a zero is read as blank**, matching how `SettingsStore` stores
    an unanswered seed.
  - `periodStartIsoDate` — the budget month's first day, ISO `yyyy-MM-dd` in the profile zone (TIM-002).
  - `nowUtcMillis` — the instant stamped into provenance (TIM-001). **Passed in, never read**:
    `CfoWallClockInDomain` fails the build on a wall-clock read in `:domain:*`.
  - `rules` — `QuickSetupRules`, the thresholds (see below).
- **Output** — `QuickSetupPlan`:
  - `envelopes: List<BudgetEnvelope>` — needs/wants/savings in display order, each with the rule
    that sized it. Empty without an income.
  - `recurring: List<RecurringSeed>` — one per answered figure; `amount` is **signed** (inflow
    positive, outflow negative) to match `TransactionEntity.amountMinor`.
  - `emergencyFundTarget: Money?`, `obligationLoadBps: Int?` (MNY-002), `obligationVerdict`.
  - `provenance` — `engineId = "quick-setup"`, `engineVersion`, `computedAtUtcMillis`, `evidence`.

## Formula / algorithm

```
income  := monthlyIncome  if > 0 else absent          (a zero is "not answered", not "nothing")
rent    := rentOrEmi      if > 0 else absent
savings := typicalSavings if > 0 else absent

# 1 · Budget envelopes — only when income is present
baseNeedsShare := income * needsPctMax / 100
if rent present and rent > baseNeedsShare:                     # RULE-50-30-20 auto_flex_to_fixed_load
    requiredPct := ceil(rent * 100 / income)
    needsPct    := clamp(requiredPct, needsPctMax, metroNeedsPctMax)
else:
    needsPct    := needsPctMax
wantsPct   := 100 - needsPct - savingsPctMin                   # savingsPctMin is a FLOOR, never taken from
envelopes  := income.allocate([needsPct, wantsPct, savingsPctMin])

# 2 · Emergency-fund target
months := clamp(emergencyRunwayMonths, runwayMinMonths, runwayMaxMonths)
emergencyFundTarget := needsEnvelope * months

# 3 · Obligation load
obligationLoadBps := rent * 10_000 / income                    # integer, truncated
verdict := HARD_FAIL   if bps >= failPct * 100
           ABOVE_LIMIT if bps >  warnPct * 100
           WITHIN_LIMIT otherwise
           UNKNOWN      if either figure absent
```

**`Money.allocate` does the one division.** Largest-remainder (Hamilton) distribution means the
three envelopes total the income **exactly** — computing `income * pct / 100` per envelope would
drop the truncated paise. There is no `Double` in the engine and no rounding of rates: the
obligation ratio is truncated to whole basis points so the arithmetic never pushes a user into a
worse verdict than their real numbers warrant.

## Assumptions & guardrails

- Money is `Long` paise (MNY-001); rates are integer basis points (MNY-002). Both clock readings
  are arguments, never reads (TIM-001), so every case is reproducible (P-08).
- **Absent stays absent (P-03).** A blank field produces no envelope, no recurring seed and no
  rule citation — never a ₹0 row. All three blank → an empty plan, and `QuickSetupPlan.isEmpty` is
  what tells the caller to persist nothing.
- **The flex never breaches the savings floor.** When rent exceeds what the frame allows, needs
  rises only to the metro ceiling and the difference comes from wants. Past that ceiling the needs
  envelope is left **visibly short of the rent** beside a `HARD_FAIL` verdict. A budget that
  balanced itself by cancelling the user's saving would report an unaffordable situation as normal.
- **Cold start:** there is no history to read — these are stated figures, not observed ones — so
  the plan carries no confidence value and no input window. Issue 4.2's budget suggester replaces
  these envelopes from three months of real spending once that history exists.
- **It does not act (P-07).** Nothing is written, nothing is scheduled. The caller persists; the
  user decides.

## Rules / knowledge consumed

| ID / file | What it provides |
|-----------|------------------|
| `RULE-50-30-20` v1.0 (`ai/rules/rules-kb.json`) | `needs_pct_max` 50, `wants_pct_max` 30, `savings_pct_min` 20, `metro_preset.needs_pct_max` 60, `auto_flex_to_fixed_load` |
| `RULE-EMERG-FIRST` v1.0 | `min_runway_months` 3 — how many months of needs the target covers |
| `RULE-RUNWAY-M` v1.0 | `clamp_months` [3, 12] — cited **only when the clamp changes the answer** |
| `RULE-EMI-40` v1.0 | `warn_pct` 40, `fail_pct` 50 — the obligation bands |

**These thresholds are currently typed Kotlin defaults in `QuickSetupRules`, not loaded from
`ai/`** — a deliberate deferral of CLAUDE.md §6 recorded in
[ADR-0005](../../../docs/adr/0005-quick-setup-thresholds-deferred-rulebook-loader.md).
`RulebookDriftTest` reads `ai/rules/rules-kb.json` directly and fails the build if any of these
numbers or versions stop matching, so the duplicate cannot drift while the loader is outstanding.

## Evidence shown to the user (P-02)

The onboarding summary card shows the three envelopes, the emergency-fund target, and the
obligation verdict — each beside the rule id that produced it. `provenance.evidence` lists only the
rules that **fired**: an empty plan cites nothing, an income-only plan does not cite the obligation
rule, and `RULE-RUNWAY-M` appears only when its clamp actually moved the runway. Padding the list
with rules that changed nothing is how a reasoning card becomes noise.

## Tests

- **Golden-file cases** (`QuickSetupEngineTest`, 15): the ordinary salary (no flex), the metro flex
  at exactly the cap, rent past the cap (envelope stays short), income-only, rent-only,
  nothing-answered, and a stored zero.
- **Property tests** (`QuickSetupPropertyTest`, 5 × 800 seeded cases): envelopes total the income
  exactly, the savings floor is never breached, needs covers the rent whenever the cap allows,
  nothing goes negative, every case is deterministic. Seed `20260727`, fixed (P-08).
- **Boundaries:** RULE-EMI-40 at 40%, just past it, just under 50%, and at 50%.
- **Determinism:** same input twice, and across two engine instances.
- **Rulebook drift** (`RulebookDriftTest`, 7): every threshold and version re-read from
  `ai/rules/rules-kb.json`. Verified to fail on a deliberate one-point mismatch before being
  trusted.
- **Coverage:** 27 tests, 0 skipped. Money math is exercised at both the worked-example and
  property level.

## Version log

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-07-27 | Initial implementation from SRS §5.1 (FR-ONB-002), issue 2.3. |
