# 2026-08-29 — Issue 6.4: allocation and diversification (AI-INV)

**Branch:** working tree on top of 6.3's uncommitted work · **VERSION:** 0.6.3 → 0.6.4
**Asked for:** "work on 6.4 with rules." **Delivered:** FR-INV-002 — allocation % by asset class,
concentration flagged against three rulebook rows, every warning showing its inputs and its rule.

---

## 1 · Decisions this session

### 1.1 · Extend `:domain:engines:investment`; do not scaffold a module

The issue's Skill Rules table lists `new-engine`, which is a module-scaffolding skill, so the
default reading was "build `:domain:engines:allocation`". `ai/orchestrator/engine-registry.yaml`
says otherwise: `AI-INV` is already bound to `":domain:engines:investment"` with
`contract: "holdings -> {allocation, diversification, XIRR, rebalancing signal}"` and
`reads: [../rules/rules-kb.json]`. The registry had been describing this issue's engine since the
file was written; 6.3 simply had not filled in its half.

That made the whole DI question disappear — `WealthEngineModule` already provides
`InvestmentEngine`, so 6.4 touched no Hilt module at all. It also kept the two operations that
share a denominator in one place: 6.4's `xirr` seam comment ("exposed separately so issue 6.4 can
pool flows across holdings") was written on the assumption they would stay together.

### 1.2 · The portfolio is the investable accounts → [ADR-0029](../adr/0029-the-portfolio-is-the-investable-accounts.md)

The rulebook rows are worded "Gold <= 10% **of portfolio**", but the app stores accounts across
eleven types and no such thing as a portfolio. I put the choice to the user rather than guessing,
because it changes what the app *says* rather than how it computes: the answer was the investable
set — investment, gold and crypto — which 6.3 had already defined as `INVESTABLE`.

The argument that decided it is `RULE-CONC-15-70`'s 70% single-class line. Counting savings would
put nearly every Indian user permanently over it, and a warning that fires for everyone on day one
is one they learn to dismiss — which then costs them the message that mattered. The honest cost is
recorded in the ADR: a user with a flat and a small SIP is told their portfolio is 100% equity,
which is true of their portfolio and startling next to their net worth.

**This contradicts a comment 6.3 left behind.** `InvestmentRepository.INVESTABLE` said property and
vehicle would be "counted through their account balance" by 6.4. They are not. I corrected the
comment in place rather than leaving two statements of intent to disagree, and ADR-0029 records the
supersession explicitly.

### 1.3 · Unpriced holdings are excluded and the gap is reported

6.3 established that an unpriced holding is `null`, never ₹0, because zero would report the user's
whole cost as a loss. The same rule has a second edge here: an unpriced holding cannot be placed in
a split at all. It is excluded from the denominator and *counted* — `valuedCount`/`unvaluedCount`
on the result, coverage on `provenance.confidenceBps`, and a sentence on the screen.

I considered suppressing the flags below a coverage floor and rejected it: the floor would be a new
financial threshold, and CLAUDE.md §6 says those are rulebook rows. Minting a row to paper over a
presentation problem is the wrong instinct, and the coverage line lets the user judge the evidence
themselves, which is what P-02 asks for.

### 1.4 · The rulebook was read, not written — `_meta.version` stays 1.13.0

`RULE-GOLD-CAP`, `RULE-CRYPTO-CAP` and `RULE-CONC-15-70` all shipped already naming
`AI-INV.diversification` in `consumed_by`. The rulebook was written expecting this engine, so all
three thresholds are mirrored and cited untouched — the same position issue 6.1 took with
`RULE-CC-UTIL`. No row is edited, so ADR-0017's trigger 3 never fires, and no row is mirrored twice,
so trigger 2 does not either.

`RULE-GOLD-CAP` and `RULE-CRYPTO-CAP` share `formula_id: asset_class_cap`, so the mirror holds a
**map keyed by `AssetClass`** with a citation per entry rather than two scalar fields. That is what
lets a gold breach cite the gold row and nothing else, and it makes a future silver cap a data
change.

### 1.5 · Two AI-INV rows were deliberately left unmirrored

`RULE-AGE-EQUITY` needs `100 − age`; there is no date of birth anywhere in this app — not on
`ProfileEntity`, not in `cfo_settings.proto`. `RULE-5-25` needs a target band to drift from, which
means §11.2's Conservative/Balanced/Growth risk profile, equally absent. Mirroring a threshold the
engine cannot evaluate would put a number in the codebase that no test could pin to behaviour.

Also deferred: §11.2's **0–100 diversification score**. It is defined partly from "overlap of goals
on same asset", and goals are Epic 7. A score whose definition is known to change is worse than no
score, and the two concentration flags it would be built from ship here anyway.

### 1.6 · Deviations from the approved plan

- The plan said "add a small `assetClassColor` helper" and a possible Paparazzi test. `:feature:accounts`
  has no Paparazzi (only `:core:designsystem`, `:feature:dashboard` and `:app` do), so the UI gate
  is a Robolectric Compose test, matching `HoldingsScreenTest`'s precedent.
- `RoomInvestmentRepository` went one function past detekt's `TooManyFunctions` ceiling. `positions`
  and `accountAsPosition` touch no instance state, so both moved to file scope — which forced
  `INVESTABLE` out of the private companion too.
- The plan predicted a new `Allocation` route as a `data object`; that held.

---

## 2 · Flow changed this session

New `FLOW.md` **§2.4 · How the portfolio is spread**, and a correction to §2.3's closing paragraph,
which claimed the rulebook was untouched by this path in terms that read as permanent.

```
AccountsScreen → onOpenAllocation → CfoRoute.Allocation → AllocationScreen → AllocationViewModel
  → InvestmentRepository.observeAllocation()      three streams, not two: a balance is part of the answer
    → combine(accounts+balances, holdings, lots)
      → price(holdings, lots) → InvestmentEngine.holding(..)         §2.3's figures, reused
      → positions(..)                                                ADR-0029 decides the denominator
      → InvestmentEngine.allocation(..) → Allocation.compute
         → slices: group, drop empties, floors + largest remainder   sums to exactly 10 000 bps
         → flags: classCap → singleClass → singleHolding             narrowest rule first
```

The first read path in the app where a rulebook row **accuses** something rather than explaining a
figure the app proposed.

---

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| **`domain/engines/investment/.../InvestmentRules.kt`** | The typed mirror: `AssetClassCap` keyed by `AssetClass`, the two concentration ceilings, three `RuleCitation`s, `RULEBOOK_VERSION` |
| **`domain/engines/investment/.../Allocation.kt`** | The arithmetic: denominator, largest-remainder apportionment, the three concentration checks |
| `domain/engines/investment/.../InvestmentEngine.kt` | Gained `allocation`, `PortfolioPosition`, `AllocationSlice`, `ConcentrationKind`, `ConcentrationFlag`, `AllocationUnavailable`, `PortfolioAllocation`, `AllocationInput` |
| `domain/engines/investment/.../DefaultInvestmentEngine.kt` | Assembles the allocation, stamps evidence + coverage; `ENGINE_VERSION` 1.0 → 1.1 |
| `domain/engines/investment/build.gradle.kts` | The `inputs.file(rules-kb.json)` block its own 6.3 comment promised; header rewritten |
| **`.../test/.../RulebookDriftTest.kt`** | The module's first drift gate; reads each `cap_pct` from its own row slice |
| **`.../test/.../AllocationTest.kt`**, **`AllocationPropertyTest.kt`**, **`AllocationGoldenTest.kt`**, **`golden/allocation.txt`** | Boundaries, 1 000 seeded portfolios, 9 independently-computed records |
| `domain/engines/investment/ENGINE.md` | Allocation contract, guardrails, rules consumed, tests, version log |
| `data/repository/.../InvestmentRepository.kt` | `observeAllocation()`; `positions`/`accountAsPosition`/`INVESTABLE` at file scope; stale 6.3 comment corrected |
| `data/repository/src/test/.../InvestmentRepositoryTest.kt` | Six allocation cases incl. the savings exclusion and the counted-whole path |
| **`feature/accounts/.../AllocationUiState.kt`**, **`AllocationViewModel.kt`**, **`AllocationScreen.kt`** | The read-only portfolio screen |
| `feature/accounts/.../AccountsActions.kt`, `AccountsScreen.kt` | `onOpenAllocation`, and the "See allocation" button gated on an investable account existing |
| `feature/accounts/src/main/res/values/strings.xml` | 20 `allocation_*` strings incl. the ICU coverage plural |
| **`.../test/.../AllocationViewModelTest.kt`**, **`AllocationScreenTest.kt`** | Turbine sequence; rendered-tree assertions |
| `app/.../navigation/CfoRoute.kt`, `CfoNavHost.kt` | `data object Allocation` + its destination |
| `ai/orchestrator/engine-registry.yaml` | `AI-INV` version 1.0 → 1.1 |

---

## 4 · What the tests caught

- **My own wrong fixture, twice.** `only the single holding past its ceiling is flagged` first used
  two positions — but with two, the remainder is 85%, so *both* are over 15%. A portfolio cannot
  contain exactly one holding above the line unless the rest are numerous enough to stay below it;
  six fillers is the minimum. The engine was right and my test was wrong.
- **My own wrong arithmetic.** The repository test expected ₹33,000 where the figure was ₹24,750: I
  had read `Money(8_250)` as the holding's value when it is the price *per unit*, and 100 units at
  ₹82.50 is ₹8,250. The kind of slip that is invisible in a screenshot.
- **The version bump caught by 6.3's own test.** `a solved rate carries the flow count and span it
  was solved over` pinned `engineVersion` to `"1.0"` and went red the moment I moved it — which is
  exactly what that assertion is for, and the reason the bump is now argued in a comment rather than
  made silently.
- **A `joinToString` that would not compile in a composable**: its transform is not an inline
  lambda, so `stringResource` inside it is not in a composable context. `map` is inline; joined
  after.
- **Six gates broken on purpose and watched fail**, then reverted (ADR-0005). The most informative
  was dropping the remainder distribution: it turned five tests red at once, including the golden
  file, because the shares stopped summing to 10 000 — a bug that produces a pie adding to 99.97%
  and no error anywhere.

---

## 5 · Not done

- **Nothing is committed.** Git is untouched — 6.3's work is still uncommitted in the same tree and
  6.4 sits on top of it. Both need the user's go-ahead (§7), and they need separating before either
  is reviewable.
- No portfolio-level XIRR, though `xirr()` is exposed for it — outside this issue's acceptance
  criteria.
- No 0–100 diversification score, no `RULE-AGE-EQUITY`, no `RULE-5-25` (see §1.5).
- No Paparazzi baseline: the module has no Paparazzi plugin, and adding one is a `:core:designsystem`
  question rather than a 6.4 one.

---

## 6 · The device gate (2026-08-29)

AVD `CfoTest`, demo mode, light and dark, 100% and 200% font.

| Gate | Result |
|------|--------|
| Entry point gated on an investable account | OK |
| Split renders; savings balance outside the denominator | OK — ₹3,41,600 correctly ignored |
| Account with no holdings counted whole | OK — ₹20,000 gold account, `AssetClass.defaultFor` |
| Largest-remainder apportionment | OK — 85% / 14%, gold took the leftover basis point |
| All three flag kinds, each citing its own row | OK — `RULE-GOLD-CAP v1.0`, `RULE-CONC-15-70 v1.0` ×2 |
| Coverage line with an unpriced holding | OK — "Based on 2 of 3 holdings - one has no price yet" |
| Privacy blur | OK — amounts masked, percentages and citations not |
| Airplane mode | OK — unchanged; nothing on this path touches the network |
| Dark mode · 200% font | OK — both legible, nothing clipped |
| §11.1 disclaimer on screen | OK |

Three things the device run turned up that the tests did not:

1. **The demo dataset has no investment *holdings*** — its "Index Fund Folio" is an account, not a
   holding. So the first allocation I saw on device was two accounts counted whole, and I had to add
   a holding by hand to exercise the per-holding path at all. Worth knowing before 6.6.
2. **The `SINGLE_HOLDING` flag can name an account**, and on real data usually will until users
   enter holdings. The wording ("... above the 15% we flag at for one holding") is defensible but
   reads oddly against an account name.
3. **A holding priced but with no lots is worth ₹0, not "unvalued"** — correct per 6.3, but it means
   such a holding silently leaves the split via the `> Money.ZERO` filter rather than appearing in
   the coverage count. Consistent, and worth stating.
