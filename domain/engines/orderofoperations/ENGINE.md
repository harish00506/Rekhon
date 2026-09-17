# OrderOfOperationsEngine — AI-FOO

**SRS:** §36 (FOO-001, FOO-002, FOO-003)  ·  **Pipeline layer:** L5  ·  **Module:** `:domain:engines:orderofoperations`
**Version:** 1.0  ·  **Status:** active

## Why this engine exists

Every engine before this one answers its own question — how big the buffer should be (AI-EMF),
what a goal needs each month (AI-GOAL), how much of a card is used. None answers the question a user
has on payday: *what should my next rupee do?* §36 calls AI-FOO "the connective tissue the earlier
engines lacked". It ranks the whole household — a starter buffer, high-interest debt, the full
emergency fund, tax headroom, goals, then cheaper debt — into one strict order, and says how much of
this month's surplus each stage would take.

The dashboard's next-best-rupee card leads with its top action (FOO-002), and the full-order screen
shows all eight stages. Both read `OrderOfOperationsRepository`, which composes the waterfall (AI-GOAL
7.3), the emergency fund (AI-EMF 7.2) and the profile's debts.

## Contract

```
interface OrderOfOperationsEngine {
    fun rank(input: OrderOfOperationsInput): Result<OrderOfOperations, AppError>
}
```

- **Input** — `OrderOfOperationsInput`:
  - `monthlySurplus: Money?` + `surplusBasis: SurplusBasis` — the figure poured and where it came from.
    **Null is unknown, not zero**; a negative surplus is legal and pours nothing.
  - `monthlyEssentials: Money?`, `liquidFunds: Money` — sizes Stage 0.
  - `emergencyShortfall: Money?` — **null when the fund cannot be sized** (`EmergencyStatus.UNKNOWN`),
    `emergencyTopUpMonthly: Money`, `emergencyRunwayMonthsBps: Int?` — basis points of a month.
  - `emergencyGateMonths: Int` — `RULE-EMERG-FIRST.min_runway_months`, resolved by the caller.
  - `debts: List<DebtPosition>` — `accountId`, `name`, `kind` (CARD | LOAN), `outstanding` (a positive
    magnitude, paise), `aprBps: Int?` (null only for a card with no rate entered).
  - `goalsRequiredMonthly: Money`, `goalCount: Int`.
  - `today: LocalDate`, `nowUtcMillis: Long`, `rules: OrderOfOperationsRules`.
- **Output** — `OrderOfOperations`: `stages` (**always eight, in §36's order**), `topAction`,
  `unallocated`, the echoed surplus and basis, and `provenance`
  (`engineId = "AI-FOO"`, `engineVersion`, `inputWindow` = the day, `computedAtUtcMillis`,
  `evidence` = every citation any stage carries; no confidence — this is arithmetic over resolved
  figures, not an inference).
  - Each `StageOutcome`: `stage`, `status`, `need: Money?`, `amountMonthly`, `reason`, `debts`,
    `comparisonBps`, `citations`.

## Formula / algorithm

```
distributable = max(0, surplus ?: 0)
gate          = runway == null  ||  runway < emergencyGateMonths × 10 000     (RULE-EMERG-FIRST)

bands (each highest rate first; unknown-rate cards sort first; zero balances dropped):
  fire = rate ≥ 1 350 bps, or a card with no rate
  grey = 1 000 ≤ rate < 1 350
  low  = rate < 1 000

for each stage, in order, amount = min(remaining, claim); remaining −= amount
```

| # | Stage | Need | Claim | Status |
|---|---|---|---|---|
| 0 | STARTER_BUFFER | `max(0, min(cap, essentials × 1) − liquid)`; unknown essentials → `cap − liquid` | need | ACTION / SATISFIED |
| 1 | CAPTURE_EPF_VPF | — | — | always SKIPPED (`NO_EPF_DATA`) |
| 2 | KILL_FIRE_DEBT | Σ fire outstanding | need | ACTION / NOT_APPLICABLE |
| 3 | FULL_EMERGENCY | EMF shortfall (null when unsized) | **gate: whole shortfall** · clear: `min(topUp, shortfall)` · unsized: 0 | ACTION / SATISFIED |
| 4 | TAX_ADVANTAGED | — | — | always SKIPPED (`NO_REGIME_COMPARATOR`) |
| 5 | GOAL_INVESTING | Σ goals' required monthly | need, unless gated | NOT_APPLICABLE (no goals) / SATISFIED (need 0) / BLOCKED (gate) / ACTION |
| 6 | GREY_ZONE_DEBT | Σ grey outstanding | need, unless gated | NOT_APPLICABLE / BLOCKED / **CHOICE** |
| 7 | LOW_RATE_DEBT | Σ low outstanding | **nothing** | NOT_APPLICABLE / BLOCKED / DEFER_TO_SIMULATOR |

```
topAction   = first ACTION stage, else first CHOICE stage, else null
unallocated = remaining
invariant   = Σ amounts + unallocated == distributable      (asserted on the type)
```

Strict priority, not pro rata: `min(remaining, claim)` in sequence can neither create nor lose a
paise, so no rounding rule appears anywhere and `Money.allocate` is deliberately not used.

## Assumptions & guardrails

- **Money is `Long` paise, rates are `Int` basis points** (MNY-001/002). The FOO file writes
  `apr_threshold_pct: 13.5`; the mirror holds `1350`, and the drift test converts by string arithmetic
  so no `Double` is involved even in the check.
- **No clock is read** (TIM-001): the day and the instant arrive in the input. No randomness exists here.
- **The ranking does not depend on the surplus.** Status, reason, need and debts are decided before a
  rupee is poured; only the amounts move with the surplus. A property test asserts it.
- **The gate holds when the runway is unknown** — the expensive side to be wrong on, and the reading
  7.3's waterfall takes. While it holds, nothing below Stage 3 is funded, so Stage 3 may take its
  **whole** shortfall rather than idle money behind a rule that exists to build the fund.
- **A card with no rate is fire debt.** §36 names credit cards as 36–42%; the reason says the rate was
  assumed. The card editor (6.1) has **no rate field**, so today every card lands here — found on the
  device run, recorded in ADR-0037. A **loan** with no terms is never sent — no band can be guessed
  for it.
- **Stages 1 and 4 are always skipped, and say why** (§36: "every skipped stage shows why"). The app
  holds no EPF data; the §38 regime comparator is issue 13.4.
- **Stage 7 proposes no amount.** §36 hands it to the prepay-vs-invest simulator (issue 10.3), which is
  not built; any figure here would be a verdict §36 declines to give.
- **The surplus is a stand-in.** §36 asks for the P50 *forecast* surplus; issue 9.2 was never built, so
  the repository reuses 7.3's observed-P50 figure and `surplusBasis` says which it was (ADR-0035).
- **It orders nothing** (P-07). Every amount is a suggestion; FOO-001's user reordering is deferred.
- **Numbers only** (P-03): the engine emits enums, and `:feature:dashboard` words them.

## Rules / knowledge consumed

| ID / file | What it provides |
|-----------|------------------|
| `ai/rules/financial-order-of-operations.json` v1.0 | The eight stages and their order; `cap_inr` (₹50,000), `or_months_essentials` (1), `apr_threshold_pct` (13.5), `apr_range_pct[0]` (10), `equity_nominal_pct` (12) — mirrored in `OrderOfOperationsRules`. Each stage is cited as `FOO.<id>` at the file version (FOO-003) |
| `RULE-EMERG-FIRST` 1.0 (`rules-kb.json`) | Stage 3's gate. **Cited, not mirrored** — the number arrives from `QuickSetupRules`, the repository's one mirror (ADR-0017 trigger 2, ADR-0035) |
| `RULE-HORIZON` 1.0 | Stage 5's `bucket_rule`. Cited; AI-GOAL applies it per goal |
| `RULE-PREPAY-VS-INVEST` 1.0 | Stage 7's deferral. Cited; already names `AI-FOO.stage7` |

**Deliberately not mirrored** (ADR-0034's rule — only what the engine reads): `nps_1b_inr` (Stage 4
never computes) and `apr_range_pct[1]` = 12 (the grey band runs to the fire threshold; see ADR-0037).
The drift test asserts both still say what this document says.

**No rulebook row was minted**; `rules-kb.json` stays at 1.15.0.

## Evidence shown to the user (P-02)

- **Dashboard card:** the top stage's name, the reason sentence, the suggested amount (or what it
  still needs when there is no surplus), and `Rule FOO.<STAGE> v1.0`. Nothing to do → says so and
  names any idle money.
- **Full-order screen:** where the surplus came from, then every stage: its step and name, status,
  reason, need, suggested amount, each debt with its rate (or "rate not entered"), the equity
  comparison on Stage 6, and every rule id and version that placed it. Every amount obeys the privacy
  blur.

## Tests

- **Unit** (`OrderOfOperationsEngineTest`, 33): every stage × status × reason, both band edges
  (1 349/1 350, 999/1 000), the gate at, above and below, unknown runway, the unsized fund, overflow →
  `Err`, and every input, debt, rule and result guard.
- **Golden file** (`golden/order-of-operations.txt`, 12 households): cold start, card debt with a thin
  buffer, a gated fund with a home loan, gated money left over, everything done, a grey-zone car loan,
  a home loan alone, a month in the red, an unknown card rate on every band edge, an unsized fund,
  and the gate at exactly three months and one basis point short. **Every expectation is written out,
  never derived.** Proven red three ways (an amount off by a paise, a reason swapped, a debt order
  reversed).
- **Property** (`OrderOfOperationsPropertyTest`, 500 seeded households × 6): the paise invariant, no
  stage overfilled, monotone in the surplus, ranking independent of the surplus, the gate, determinism.
- **Drift** (`OrderOfOperationsRulesDriftTest`, 9): every mirrored param, the stage order, the
  unmirrored params, the cited rows, and the mirror's field list. Proven red three ways by editing the
  JSON alone — which also proves `build.gradle.kts` declares the file as a test input.
- **Coverage:** 100% lines, 100% branches.

## Version log

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-09-17 | Created for issue 7.5 from SRS §36 and `financial-order-of-operations.json` v1.0. Eight stages, strict order; Stages 1 and 4 skipped with reasons; Stage 7 deferred to the unbuilt simulator; the surplus is 7.3's observed-P50 stand-in (ADR-0035, ADR-0037). |
