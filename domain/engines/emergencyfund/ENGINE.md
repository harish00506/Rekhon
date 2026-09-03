# EmergencyFundEngine — AI-EMF

**SRS:** §10.1, §15, §36 · **Pipeline layer:** L4 · **Module:** `:domain:engines:emergencyfund`
**Version:** 1.0 · **Status:** active

---

## Why this engine exists

§10.1 calls the runway **"the headline metric"** — how many months the user could live on what they
have liquid — and until this engine existed the app could not compute it. Three things had been
written down waiting for it:

- `RULE-RUNWAY-M` has named `AI-EMF` as its `multiplier_source` since the rulebook was created.
- `RULE-EMERG-FIRST` gates the goal waterfall on a runway nothing produced.
- `insight-orchestrator.yaml` lists `emergencyfund` in its nightly engine set.

Meanwhile `QuickSetupEngine` shipped a **stand-in**: a flat three-month target sized off whatever
the user typed at onboarding, citing `RULE-EMERG-FIRST`. This engine is what all four were waiting
for.

The division is trivial. Deciding what to divide is not:

- **What counts as a month's spending.** The figure has to be what the user *actually* spends on
  essentials, not what they once said they would — but a new install has no history, and refusing
  to answer is better than sizing a target off nothing. Hence `EssentialsBasis`.
- **What counts as spendable.** A ₹12,00,000 mutual-fund holding is not an emergency fund. See
  *Assumptions*.
- **How deep is deep enough.** Six months is the consensus floor; a freelancer needs more. That is
  the whole content of `RULE-EMF-MULT`, and it is the only reason M is *personal*.
- **A standard deviation is a square root**, and `Math.sqrt` returns a `Double`. See *Assumptions*.

---

## Contract

```kotlin
interface EmergencyFundEngine {
    fun assess(input: EmergencyFundInput): Result<EmergencyFundPlan, AppError>
}
```

### Input

| Field | Meaning |
|---|---|
| `monthlyEssentials` | a month of essential living costs, paise (MNY-001), as the caller resolved it. **Null means "not known yet"** and is reported rather than guessed at. |
| `essentialsBasis` | `OBSERVED_MEDIAN` / `DECLARED_ENVELOPE` / `NONE` — where that figure came from. Must be `NONE` exactly when the figure is null; the `init` enforces it. |
| `monthlyIncomes` | total income per **whole closed month**, paise. The live month is excluded by the caller: a half-elapsed month is not a month's income and would read as a collapse every time. |
| `liquidFunds` | what could be spent today. Never negative — an overdrawn account is a liability, not negative liquidity. |
| `liquidAccountNames` / `essentialCategoryNames` | the evidence §10.1 requires ("which accounts counted as liquid, which categories counted as essential"). Names only, no per-account amounts. |
| `today` | the day to reckon from, **already resolved in the profile's time zone by the caller** (TIM-001). |
| `nowUtcMillis` | the caller's instant. Stamped onto the provenance and **never read as a clock**. |
| `rules` | the thresholds. Injected, so a test can move a band and assert the engine moves with it. |

### Output

One `EmergencyFundPlan` with the six figures below plus its `EngineProvenance`. Amounts are exact
paise; **both ratios are integer basis points** (MNY-002) — `fundedRatioBps` where 10 000 is 100%,
and `runwayMonthsBps` where **10 000 is one month**, so 15 000 reads as one and a half.

---

## Formula / algorithm

```
incomeCvBps     = stdDev(monthlyIncomes) / mean(monthlyIncomes) x 10 000
                  null when fewer than `min_months_observed` months, or the mean is not positive
M               = (base_months + volatilityBump(incomeCvBps))
                    .coerceIn(clamp_months[0], clamp_months[1])          RULE-RUNWAY-M
target          = monthlyEssentials x M
shortfall       = max(0, target - liquidFunds)
topUpMonthly    = shortfall.split(M).max()                              0 when there is no shortfall
runwayMonthsBps = liquidFunds / monthlyEssentials x 10 000              null when essentials <= 0
fundedRatioBps  = liquidFunds / target x 10 000                         0 when target <= 0, not capped
status          = UNKNOWN -> SURPLUS -> FUNDED -> BUILDING -> URGENT    RULE-EMF-COACH
```

`volatilityBump` is `RULE-EMF-MULT`'s two band edges: **below** `cv_low_bps` adds nothing,
**up to and including** `cv_high_bps` adds `cv_mid_bump`, above it adds `cv_high_bump`. A cv of
exactly 1 000 is therefore already the middle band, and a cv of exactly 3 000 is still the middle
band. All four edges are pinned by golden records.

The status order is not arbitrary. **Unknown first**, because every test below compares against a
target that does not exist yet and a zero target would make an empty fund read as fully funded.
**Surplus before funded**, because a surplus is also funded and the more specific verdict is the
useful one. **Funded before the runway bands**, because a user with a deliberately small target
should not be told they are urgent for having cleared it.

### Rules applied

| Rule | Version | What it supplies |
|---|---|---|
| `RULE-EMF-MULT` | 1.0 | `base_months`, the cv band edges and bumps, the lookback and the minimum months observed |
| `RULE-EMF-COACH` | 1.0 | `urgent_below_months`, `surplus_above_target_months` |
| `RULE-RUNWAY-M` | 1.0 | `clamp_months` — **cited only when the clamp changed the answer** |

Citing a rule that did not fire is the quiet kind of wrong: it survives every test that looks at
amounts, and it tells the user a threshold shaped their number when it did not (P-02). The same
discipline `QuickSetupRules.runwayWasClamped` keeps.

---

## Assumptions

**Only two of §10.1's five multiplier terms are applied, and the gap is deliberate.** The spec's M
also adds +1 for a single-earner household with dependents, +1 for no health cover, and ±1 for a
job-stability self-assessment. **No field anywhere in this app holds any of the three** — not in
`cfo_settings.proto`, not on `profile`. Minting rulebook params for them would ship three numbers
nothing reads, and a threshold nothing reads looks identical to one that works. `RulebookDriftTest`
asserts their **absence** from the rulebook, so the issue that adds the fields has to add the params
in the same breath. See ADR-0034.

**With the shipped params, `RULE-RUNWAY-M`'s clamp never fires.** 6 base months plus at most a
3-month bump is 9, comfortably inside [3, 12]. It is implemented and tested anyway — against moved
rules in `EmergencyFundEngineTest`, not in the golden file, because a golden record that clamped
would be a fixture asserting something the shipped rulebook never does. Even with all three deferred
terms the range would be 5..12, so the clamp's floor stays unreachable; it is a guard against a
rulebook edit or a future user override, not against §10.1's own arithmetic.

**Liquid = savings and cash only.** §10.1 also counts "FDs breakable without major penalty + liquid
MF", mapped by a per-account liquidity tier that "is stored per account and user-editable". No such
column exists. Rather than guess a tier from `AccountType` — which would silently decide that every
FD is breakable, or that none is — the engine is given only what is unambiguous. This **understates**
the runway, which errs in the safe direction, and the evidence list names exactly which accounts
counted so the gap is visible rather than hidden. The tier is its own issue (ADR-0034).

**No assumed rate of return.** No return rate exists anywhere in `ai/rules/`, and inventing one
would be exactly the hardcoded financial number CLAUDE.md §6 forbids. An emergency fund is held
liquid; compounding it would be advice this app made up (P-03).

**The standard deviation is an integer square root**, by Newton's method, not `Math.sqrt`. MNY-002
admits no floating point, and a `Double` would make the answer depend on the platform's rounding,
breaking P-08's "fixed input, fixed output". It floors, which understates volatility, which can only
ever shrink a target — never inflate one on rounding alone.

**The variance is population, not sample** (÷n rather than ÷n−1). This describes the months actually
observed rather than inferring a parameter of a wider population the user does not have, and with
`min_months_observed` as low as 3 the Bessel correction would inflate the reading by a fifth for no
gain in truth.

**An unmeasurable cv adds zero, not the maximum.** Too little history is an absence of evidence;
inflating a stranger's target on no data would be the app inventing a number about them (P-03).

---

## Consumers

| Consumer | What it reads |
|---|---|
| `EmergencyFundRepository` (`:data:repository`) | resolves the essentials, the income series and the liquid balances, then calls this engine on every emission |
| `:feature:emergencyfund` | the whole plan — the runway headline, the coach line, and the evidence drill-down |
| **7.3** goal feasibility waterfall | `RULE-EMERG-FIRST` gates goals below the emergency fund on `runwayMonthsBps` |
| **7.5** financial order of operations | the same gate, as an `AI-FOO` stage |
| **9.4** `AI-FHS` | the protection pillar scores the runway against the target |
| **10.7** `AI-MKT` | `RULE-RUNWAY-M`'s `capacity_gate` — no opportunity is surfaced below the target |

---

## Version log

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-09-02 | Created for issue 7.2. Base months + income volatility, clamped; runway, funded ratio, top-up and the coach bands. |
