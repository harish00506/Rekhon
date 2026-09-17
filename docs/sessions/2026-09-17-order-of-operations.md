<!--
  Why:  CLAUDE.md §10 — one file per working session, holding the full reasoning behind the
        one-liners that go up to DECISIONS.md and the arrow chains that go into FLOW.md.
  What: issue 7.5 — the Financial Order of Operations (§36, AI-FOO, FOO-001/002/003).
  Result: a reader can reconstruct why the ranking reads the FOO file, what it could not judge and
          says so, and what running the app found that a green build could not.
  Changelog: 2026-09-17 — Created.
-->

# 2026-09-17 — Financial Order of Operations (issue 7.5)

**Branch:** `feature/7-5-financial-order-of-operations-ai-foo` off `dev` (`1b61319`) · **VERSION**
0.7.4 → **0.7.5** · **versionCode** 30 → 31 · **Schema** 22, unchanged · **`AI-FOO`** new at **1.0** ·
**Rulebook** 1.15.0, unchanged · **FOO file** 1.0, unchanged

This closes Epic 7.

**Environment.** First issue built on the Linux workstation: Temurin JDK 21 (`~/.jdks/temurin-21`),
Gradle 8.13 unchanged, Android SDK at `~/Android/Sdk`, emulator `CfoTest` (API 36, Google APIs,
x86_64). A Gradle 9 / JDK 25 route was tried and abandoned before this issue began: AGP 8.x cannot run
past Gradle 9.5, and Hilt 2.56.2's `jar-for-dagger` transform is ambiguous under Gradle 9. Nothing was
committed for it.

---

## 1 · Decisions this session

Four were put to the user before any code, and all four took the recommended option.

### 1a · The FOO file is the rule set (user's choice)

FOO-003 says "stage thresholds are rulebook rows (§29 schema) — editable, versioned, cited". The
thresholds are not in `rules-kb.json`; they are in `ai/rules/financial-order-of-operations.json`,
which has stage ids, `params_json` and a version, and which nothing read before today.

Minting `RULE-FOO-*` rows would be the literal reading, and it would give each threshold **two
sources** (the FOO file still carries them) and bump the rulebook's `_meta.version`, which forces all
six typed mirrors to restate it. The user chose "the rules present": `OrderOfOperationsRules` mirrors
the FOO file, `OrderOfOperationsRulesDriftTest` holds it there, and each stage is cited as
`FOO.<STAGE_ID>` at the file's version. The dotted prefix follows the facet-id convention
(`AI-GOAL.waterfall`, `AI-FOO.stage7`) and cannot collide with a `RULE-*` id.

**Units.** The file writes `apr_threshold_pct: 13.5` — a decimal percentage. The mirror holds `1350`
(MNY-002). The drift test converts the file's text to basis points by **string arithmetic** — whole
part × 100 plus the two-digit fraction — so not even the check touches a `Double`, and `13.5` must come
out as exactly `1350`.

**Only what is read is mirrored** (ADR-0034). `nps_1b_inr` belongs to Stage 4, which never computes;
`apr_range_pct[1]` is not used as a cut-off (1d). The drift test asserts both still say what the code
assumes, so an edit to either still fails the build even though neither is copied.

### 1b · `RULE-EMERG-FIRST` stays a citation

Stage 3's `gate_rule` is `RULE-EMERG-FIRST`. `QuickSetupRules` has mirrored its `min_runway_months`
since 2.3; 7.3 routed around a second mirror (ADR-0035), and ADR-0017 trigger 2 says a second mirror
is the moment to build the runtime loader instead. So the number arrives as
`OrderOfOperationsInput.emergencyGateMonths`, resolved by the repository from `QuickSetupRules`, and
the engine holds only the citation. The drift test's reflection guard asserts the mirror's instance
fields are exactly the five it applies.

### 1c · Dependencies waived, substitutes named (user's choice)

- **9.2 (forecast) does not exist.** The surplus is the **goal waterfall's own figure** — 7.3's
  observed-P50 stand-in — read from `GoalWaterfall`, not derived again. Two screens pouring two
  independently computed surpluses would disagree about how much money exists before they disagreed
  about anything else. `surplusBasis` travels with it, and the full-order screen says which basis it
  used.
- **Stage 1 (EPF/VPF)** — the app holds no EPF contribution data. **Stage 4 (tax)** — needs §38's
  regime comparator, issue 13.4. Both are **always reported as `SKIPPED` with the reason**, because §36
  says "every skipped stage shows why". Leaving them out would hide that the ranking is incomplete.
- **Stage 7 (low-rate debt)** — §36 hands it to the prepay-vs-invest simulator, issue 10.3, which does
  not exist. So it is `DEFER_TO_SIMULATOR` with **no amount**: any figure here would be the verdict §36
  explicitly declines to give.

The ranking's **order** does not depend on the surplus at all — status, reason, need and debts are
decided before a rupee is poured. A property test asserts it across 500 seeded households. That is
why waiving 9.2 costs the amounts some precision but leaves the shape of the advice intact.

### 1d · The grey band runs to the fire threshold

Stage 2 fires at ≥ 13.5%. Stage 6 is written "APR ~10–12%" with `apr_range_pct: [10, 12]`. Taken
literally, a 12.5% loan is in neither and falls through to Stage 7's "genuine toss-up". The tilde
marks 12 as a typical top, so the band is `[1000, 1350)` bps. The drift test asserts the file's 12 is
still there **and still below** the fire line — if someone raised it past 13.5%, the reading would stop
following from the file and the build would say so. Unit tests pin both edges (1349/1350, 999/1000).

### 1e · Below the gate, the fund takes its whole shortfall

Past the gate, Stage 3 takes AI-EMF's `topUpMonthly`, the same pace the emergency-fund screen shows.
While `RULE-EMERG-FIRST` holds, nothing below Stage 3 may be funded. Capping Stage 3 at its pace would
leave the rest **idle behind the rule whose whole purpose is building that fund**, so there it may take
up to its whole shortfall. An unknown runway holds the gate, as in 7.3.

### 1f · Composing 7.3, not replacing it (user's choice)

§36 "supersedes" §15's waterfall. 7.3 shipped two weeks ago and the goals screen renders it. Stage 5 is
one aggregate line — Σ goals' required monthly — linking to the goals screen, which keeps its per-goal
split.

**Known divergence, recorded rather than hidden:** past the gate, AI-FOO gives the fund its pace ahead
of the goals, while 7.3 gives the fund nothing. Each is right about its own rule; they disagree about
the goals' share. Re-pointing 7.3 at what AI-FOO leaves after Stage 3 is ADR-0037's first follow-up.

### 1g · Debts: who counts, and at what rate

The repository joins `account` (live, unarchived, with balances) to `credit_card.apr_bps` and
`loan.annual_rate_bps`. The outstanding is the negated balance floored at zero —
`RoomCreditCardRepository`'s own reading, so the two screens agree on what is owed.

- **A card with no rate is fire debt**, reason `FIRE_DEBT_CARD_RATE_UNKNOWN`; §36 itself names cards as
  36–42%. It sorts ahead of every known rate.
- **A loan with no terms is not sent** — no rate means no band that is not a guess (P-03).
- Payables, paid-off debts, archived accounts and other profiles' debts are never ranked; the
  repository test covers each.

### 1h · Where it lives

- **Module `:domain:engines:orderofoperations`.** The registry had pre-registered
  `:domain:engines:orchestrator`, but "orchestrator" is AI-ORCH, §7.2's insight pipeline. The registry
  row was corrected, and AI-GOAL's `reads:` stopped claiming the FOO file it never read.
- **It depends on `:domain:engines:goals`** for `SurplusBasis` only — L5 over L4, the direction CLAUDE.md
  §2 allows, and one enum rather than two that could drift.
- **The engine binding is in `GoalEngineModule`**, not a new Hilt module as the plan said. That
  module's own KDoc had already placed 7.5's engine there in 7.1.
- **UI in `:feature:dashboard`** (user's choice): the next-best-rupee card (FOO-002's Home half) and a
  full-order screen behind it, on `CfoRoute.OrderOfOperations`. The Advisor hub is Epic 10's; a
  `:feature:advisor` created now would be named and shaped by a guess. Feature modules may not depend
  on each other (ARC-001), so the list lives beside the card that opens it.

### 1i · Deferred on purpose

FOO-001's user reordering and its "cost of deviation" (needs an interest projection per deviation);
§36's one-tap goal/contribution creation from a card; the list's move to the Advisor hub.

---

## 2 · What only running it could find

### 2a · The card editor has no rate field — and the first build promised one

The first build ranked an unrated card as fire debt and put an **"Add the rate in Accounts"** button
under it. On the device, the button worked: it opened Accounts. The ICICI card's editor then offered a
credit limit, a statement day, a due day, a last statement amount and a minimum due. **There is no APR
field.** `credit_card.apr_bps` has existed since 6.1, in the schema, the entity, the model and the card
engine, and **nothing in the UI has ever written it**.

The button led somewhere that could not keep its promise, so it was removed, and the reason now says
the rate is *not recorded* and was assumed. A Compose test asserts no "rate in Accounts" prompt exists
for an unrated card. The editor gap goes to ADR-0037's follow-ups.

This is `docs/memory.md`'s 0.3.6 lesson again, word for word: **a field being plumbed end to end is not
evidence anything can produce a value for it.** Here it was found by following my own button rather
than by grepping.

**Consequence:** until the editor has a rate field, every card is fire debt and no card reaches
Stage 6. For Indian card rates that is the right answer — but it is an assumption the user cannot
correct yet, and the ADR says so.

### 2b · Bands move live, verified through a loan

Card rates can't be edited, so the band check went through the loan editor, which does have a rate:

| Action | Observed |
|---|---|
| Add *Car Loan*, −₹4,00,000, 11.5% | Step 7 · **Your call** · `Car Loan · ₹4,00,000.00 owed · 11.50% a year` · "Investing is expected to earn about 12.00% a year." No amount — strict order sent the whole ₹19,000 to the card |
| Edit the rate to 14% | Step 3 now lists the unrated ICICI card **then** the loan at `14.00%`, need ₹4,92,534 (= 92,534 + 4,00,000); Step 7 · **Doesn't apply to you** |

### 2c · Gates proved red on purpose

Every gate that went green on its first run was broken deliberately before being trusted:

| Gate | Broken how | Result |
|---|---|---|
| drift | `cap_inr` 50000 → 60000, **JSON only** | *the starter buffer matches Stage 0* FAILED |
| drift | `apr_threshold_pct` 13.5 → 14, JSON only | *the fire threshold matches Stage 2 in basis points* FAILED |
| drift | GREY_ZONE_DEBT ↔ LOW_RATE_DEBT ids swapped, JSON only | *stages and their order* + *grey band* FAILED |
| golden | one amount +1 paise | *every record ranks all eight stages exactly as written* FAILED |
| golden | a reason swapped, amount unchanged | same FAILED |
| golden | a debt order reversed | *every stated debt order is the order shown* FAILED |
| repository | balance sign not flipped | 6 tests FAILED |
| repository | unsized fund passed on as ₹0 | *an empty profile … calls the fund unsized* FAILED |
| repository | archived accounts included | *debts the engine cannot act on are left out* FAILED |
| repository | fallback removed (hard cast) | *sums that overflow fall back* FAILED |
| UI | card amount bypasses `maskedAmount` | `DashboardPrivacyBlurTest` FAILED |
| UI | debt line bypasses `maskedAmount` | *no amount survives the privacy blur on the full order* FAILED |
| UI | a navigation button dropped | *each button goes where it says* FAILED |

The drift drills ran **without `--rerun-tasks`**; the test task re-ran with `testClasses` UP-TO-DATE,
which proves `build.gradle.kts` declares the FOO file as a test input.

**One gate was weaker than it looked, and was fixed.** Under the sign-flip sabotage the overflow test
*still passed*: the un-flipped cards owed zero, nothing overflowed, and "no fire debt, no surplus"
held for the wrong reason. It now first proves one huge card **is** ranked, then adds the second.

**One drill initially proved nothing, and was rerun.** The first drift drill filtered its output with
`head -4`, which filled with `build-logic` UP-TO-DATE lines before reaching the test task. It was rerun
with a filter on the task itself before any result was trusted.

### 2d · Smaller things

- **Python ate the XML escapes.** The strings were appended through a Python heredoc, which turned
  `\'` into `'`; aapt then rejected eight strings as "Invalid unicode escape". They were re-escaped
  inside `<string>` bodies only.
- **Paparazzi:** all five dashboard baselines failed — expected, since the populated fixture gained
  the card and the card always renders, even on the empty dashboard. To rule out a Linux-vs-Windows
  rendering difference, `:core:designsystem`'s untouched baselines were force-verified on this
  machine first (green), then the five were re-recorded and reviewed as images: light, dark, 200% font
  (wraps without clipping) and blurred (the card's amount is masked).
- **`DashboardViewModel`** reached detekt's seven-parameter ceiling with the new repository, and gained
  a reasoned `LongParameterList` suppression beside its existing `TooManyFunctions` one.

---

## 3 · Flow changed this session

New section **§2.8** in `FLOW.md`. In summary:

```
DashboardScreen → NextBestRupeeCard ─ "See the full order" → CfoRoute.OrderOfOperations
                                                           → OrderOfOperationsScreen → ViewModel
└─ OrderOfOperationsRepository.observe()
    └─ combine(
        ├─ GoalWaterfallRepository.observeWaterfall()     surplus + basis REUSED; Σ required; goal count
        ├─ EmergencyFundRepository.observeEmergencyFund() essentials, liquid, shortfall (null if UNKNOWN),
        │                                                 top-up, runway
        └─ observeDebts()
            └─ activeProfileId.flatMapLatest { combine(
                   accountDao.observeWithBalances(unarchived), creditCardDao.observeForProfile,
                   loanDao.observeForProfile) → CARD always · LOAN only with terms }
       ) → OrderOfOperationsEngine.rank(…, gate = QuickSetupRules().emergencyRunwayMonths)
           └─ Err (overflow) → rank again without surplus, debts and goals
```

Nothing is written. Editing an account's rate re-emits the ranking through the Room flows.

---

## 4 · Code changed this session

| Path | What it does now |
|---|---|
| `settings.gradle.kts` | includes `:domain:engines:orderofoperations` |
| `domain/engines/orderofoperations/build.gradle.kts` | **new** — pure-Kotlin engine; declares the FOO file as a test input |
| `…/orderofoperations/OrderOfOperationsEngine.kt` | **new** — the interface, input, `DebtPosition`, result, `StageOutcome`, the four enums, the factory |
| `…/orderofoperations/OrderOfOperationsRules.kt` | **new** — the typed mirror of the FOO file; stage and rule citations |
| `…/orderofoperations/DefaultOrderOfOperationsEngine.kt` | **new** — the eight stages in order, the bands, the gate, the top action |
| `…/orderofoperations/ENGINE.md` | **new** — contract, formula table, assumptions, rules, tests, version log |
| `…/orderofoperations/src/test/…` | **new** — engine (33), golden (4 over 12 households), property (6 × 500), drift (9); `golden/order-of-operations.txt` |
| `data/repository/build.gradle.kts` | `api` on the new engine |
| `data/repository/…/OrderOfOperationsRepository.kt` | **new** — the composition and the debt read |
| `data/repository/…/RepositoryFactory.kt` | `orderOfOperations(...)` |
| `data/repository/src/test/…/OrderOfOperationsRepositoryTest.kt` | **new** — 8 tests on in-memory Room with real engines |
| `app/…/di/RepositoryModule.kt`, `GoalEngineModule.kt` | provide the repository and the engine |
| `app/…/navigation/CfoRoute.kt`, `CfoNavHost.kt` | `OrderOfOperations` route, wired from the dashboard |
| `app/build.gradle.kts` | `versionCode` 31 |
| `feature/dashboard/build.gradle.kts` | the engine and goals modules; the new Compose test excluded from release |
| `feature/dashboard/…/NextBestRupeeCard.kt` | **new** — the card's three states |
| `feature/dashboard/…/OrderOfOperationsScreen.kt`, `…UiState.kt`, `…ViewModel.kt`, `…Labels.kt` | **new** — the full-order screen and its enum wording |
| `feature/dashboard/…/DashboardScreen.kt`, `DashboardUiState.kt`, `DashboardViewModel.kt` | the card, its state and its collector; `DashboardActions.onNavigateToOrderOfOperations` |
| `feature/dashboard/src/main/res/values/strings.xml` | 64 new strings; no rule's number in any of them |
| `feature/dashboard/src/test/…` | fake repository on the real engine; 4 dashboard VM tests; 4 screen VM tests; 11 Compose tests; fixtures and action call sites updated |
| `feature/dashboard/src/test/snapshots/images/*` | five baselines re-recorded for the card |
| `ai/orchestrator/engine-registry.yaml` | AI-FOO's module, contract and reads; AI-GOAL no longer lists the FOO file |
| `docs/adr/0037-…`, `DECISIONS.md`, `FLOW.md` §2.8, `VERSION`, `CHANGELOG.md` | the records |

---

## 5 · Not delivered, stated plainly

- **A rate field on the card editor.** Found here, belongs to 6.1's screen, recorded as a follow-up.
- **FOO-001 reordering** and its cost of deviation.
- **One-tap goal or contribution creation** from a stage.
- **The Advisor hub.** The full list is a dashboard-owned screen until Epic 10.
- **Re-pointing 7.3** at what AI-FOO leaves — the divergence in 1f stays until then.
- **Stages 1, 4 and 7** stay unjudged until EPF data, 13.4's comparator and 10.3's simulator exist.
- **Instrumented E2E (`connectedDebugAndroidTest`)** was not run: no schema or migration changed, and
  the device leg was driven by hand, including airplane mode.
