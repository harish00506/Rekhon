# 2026-09-02 — Issue 7.2: the emergency-fund engine (AI-EMF)

The user asked to "work on 7.1 with rules", then redirected to **7.2** before any code was written.
7.1 was already complete on its branch — 6 commits ahead of `dev`, working tree clean, only its PR
outstanding — so 7.2 was cut from **7.1's branch rather than from `dev`**: it needs 7.1's
`GoalEngineModule`, `:feature:goals` and schema 20, none of which are on `dev` yet. **7.1 must merge
first.**

Four scope calls were put to the user before coding, and all four came back as the recommended
option. They are the spine of this session.

---

## 1 · Decisions this session

### 1.1 §10.1 asks for inputs this app does not have, and there is more than one way to be wrong

The SRS specifies `AI-EMF` exactly — base 6 months, a cv-of-income band, +1 for dependents, +1 for no
health cover, ±1 for job stability, clamped to [3, 12]; liquid funds by a per-account liquidity tier.
**Three of the five multiplier terms and the whole liquidity tier have no storage in this codebase.**

The tempting answers were both wrong. Minting rulebook params for the three missing bumps would ship
three numbers nothing reads — and **a threshold nothing reads looks exactly like one that works**, to
a reviewer and to a test. Guessing a liquidity tier from `AccountType` would decide, for every user
at once, that every `INVESTMENT` row is a breakable FD or that none is; in this schema that column
holds FDs, equity funds and PPF lock-ins alike.

So: apply what the app can measure, and **assert the absence of the rest**. `RulebookDriftTest` fails
if `dependents_bump`, `single_earner_bump`, `no_health_cover_bump` or `job_stability_bump` ever
appears in the rulebook — the same inverted guard ADR-0033 used when 7.1 minted nothing at all. The
issue that adds the fields has to add the params in the same breath.

### 1.2 Two rows, not one, and `RULE-RUNWAY-M` untouched

`RULE-EMF-MULT` sizes the fund; `RULE-EMF-COACH` frames it. Two rows on ADR-0019's and ADR-0025's
precedent, and on the merits: they are different questions with different severities, and sharing a
version would mean retuning the urgent threshold invalidated every stored assessment that cited the
multiplier (AI-ARC-006).

`RULE-RUNWAY-M` was **deliberately not touched**. Its params were already right, and §10.1 puts the
multiplier *inside* AI-EMF — so this issue makes its `multiplier_source: "AI-EMF"` true for the first
time without changing a number in it, and ADR-0017's trigger 3 stays unfired.

`_meta.version` moved 1.14.0 → 1.15.0, which forced five unrelated typed mirrors to restate it. That
churn is the price of the rows, and it was paid on purpose.

### 1.3 A consequence worth saying out loud: the clamp never fires

6 base months plus a maximum 3-month bump is 9, comfortably inside [3, 12]. Even with all three
deferred terms the range would be 5..12, so the **floor is unreachable by §10.1's own arithmetic**.

The clamp is still implemented, and tested — against *moved rules* in `EmergencyFundEngineTest`, not
in the golden file. A golden record that clamped would be a fixture asserting something the shipped
rulebook never does. The golden file says so in its own header, because a reader is entitled to know
which of its records describe reality.

### 1.4 The median, and why the mean is not merely different but wrong

§10.1 says `median(SemiFixed)`, and the reason shows up immediately in the repository test's own
fixture: three months of ₹30,000 / ₹40,000 / ₹1,00,000 give a median of ₹40,000 and a mean of
₹56,666.67 — a target **42% too high** because somebody replaced a fridge once.

**The live month is excluded**, and this turned out to matter more than expected: on the device, a
₹3,00,000 expense booked today left the target at ₹3,36,144.00, exactly where it was. Included, the
target would drift down through every month and jump back on the 1st — wrong in the way nobody
reports, because each individual reading looks reasonable.

**Below `min_months_observed` the quick-setup envelope is the fallback, and below that the answer is
`UNKNOWN` — never zero.** A zero target is the dangerous reading: `liquidFunds >= target` is true for
someone with nothing saved, and the screen would congratulate them.

`QuickSetupEngine`'s three-month stand-in is superseded but **left in place**: it runs at onboarding,
before any history exists, and it is what fills the fallback this engine reads.

### 1.5 The evidence is not decoration

§10.1 requires "every number in the explanation links to its evidence — which categories counted as
essential, which accounts counted as liquid". `EngineProvenance.evidence` is `List<RuleCitation>`
only, so the human half lives on the result type, and the screen has a drawer for it. `RULE-RUNWAY-M`
is cited **only when the clamp changed the answer** — citing a rule that did not fire survives every
test that looks at amounts, and tells the user a threshold shaped their number when it did not.

---

## 2 · What proving the gates taught us

Three gates were deliberately broken. All three were real — but the first one exposed something else.

### 2.1 A gate that could be *skipped*, which is not the same as a gate that does not check

Bumping `_meta.version` to 1.15.0 should have failed all five typed mirrors. It failed **four**.
`:domain:engines:goals:test` was reported `UP-TO-DATE` and never ran; `--rerun-tasks` then failed it
immediately.

So the assertion was fine and its **scheduling** was wrong: `ai/rules/rules-kb.json` is read at
runtime from the repository root and was **not a declared input to any Gradle task**. Edit a
threshold, and every `RulebookDriftTest` in the repository could stay green.

`CfoKotlinLibraryConventionPlugin.configureRulebookAsTestInput` now declares it; re-verified, all
five go red with no `--rerun-tasks`.

**This is the fourth gate in this repository found to read as present and check nothing** — after the
governance audit, 6.7's finding, and 7.1's discovery that `runMigrationsAndValidate` does not check
index names. The habit that caught all four is the same one, and it is worth naming again: *never
write "this is enforced by X" without making X fail once.*

### 2.2 The golden gate is real, both ways

Broken with a wrong amount, and with a **right amount beside a wrong verdict**. Both went red. The
second is the one that matters: a top-up of ₹0.00 is correct for a funded fund, for one in surplus,
**and** for one whose essentials are unknown — three things a screen has to say differently.

### 2.3 The absence assertion is real too

Adding `dependents_bump` to the rulebook failed the drift test, which is the point: §10.1's deferred
terms cannot arrive by accident.

### 2.4 The type system caught the 6.5 failure mode for us

Adding `observeMonthlyLedger` to `TransactionRepository` broke three fakes — `:feature:dashboard`,
`:feature:transactions` and `:app`'s worker double. That is exactly the shape of the bug 6.7 found in
6.5, where `FakeInvestmentRepository` silently dropped `priceKey` and no test noticed. Here the
compiler refused to let it happen.

### 2.5 One defect only the running app could show — again

Every test passed, and the evidence drawer still said:

> **Counted as essential: NEED**

§8.3's raw nature token, on a real screen, in a word the user has met nowhere else in the app. The
engine was right, the string was externalised, `CfoHardcodedUiString` was satisfied, and the sentence
was still unreadable.

This is the third instance across 7.1 and 7.2 of the same class: **no assertion about a figure
catches a sentence.** Fixed in the screen (the domain must not decide wording), with a regression
test, and re-verified on the device.

### 2.6 The Paparazzi gate did its job

Adding the dashboard entry turned the baseline stale and the build went red. Re-recorded; the diff is
that one button.

---

## 3 · Flow changed this session

New — `FLOW.md` §2.6. The full chain is there; the shape of it:

```
EmergencyFundScreen → EmergencyFundViewModel → EmergencyFundRepository.observeEmergencyFund()
└─ combine(observeMonthlyLedger(6), observeAccounts(), observeLatestEnvelopes())   three, so TYPED
    ├─ clock.today() READ ONCE PER EMISSION (TIM-001)
    ├─ essentials = median(closed months' NEED) ?: envelope ?: null → UNKNOWN, never zero
    ├─ liquid     = {BANK, CASH} ∧ inNetWorth ∧ balance > 0
    └─ EmergencyFundEngine.assess(...)
        incomeCvBps     = integerSqrt(variance) ÷ mean × 10 000    Newton, no Math.sqrt
        M               = (base + bumpFor(cv)).coerceIn(RULE-RUNWAY-M.clamp_months)
        target          = essentials × M
        topUpMonthly    = shortfall.split(M).max()
        runwayMonthsBps = liquid ÷ essentials × 10 000             10 000 bps = ONE MONTH
        status          = UNKNOWN → SURPLUS → FUNDED → BUILDING → URGENT
        evidence        = [MULT, COACH] + RUNWAY_CLAMP iff it fired
```

**`observeMonthlyLedger` is new and lives on `TransactionRepository` on purpose.** The nature of a row
is decided by a precedence — stored override, then category, then account type — that class already
owns. A copy in `EmergencyFundRepository` would be a second answer to "is this rupee a need?", and
the two would disagree the first time either changed. It is the argument `MonthWindow` settled for
"where does a month end".

---

## 4 · Code changed this session

| Path | What it does now |
|---|---|
| `ai/rules/rules-kb.json` | **`RULE-EMF-MULT` and `RULE-EMF-COACH`**, both v1.0; `_meta.version` 1.15.0 with the reasoning in its changelog |
| `ai/rules/rulebook.md`, `ai/orchestrator/engine-registry.yaml` | The human table and the `AI-EMF` row's `reads` |
| `build-logic/.../ProjectExtensions.kt`, `CfoKotlinLibraryConventionPlugin.kt` | **`configureRulebookAsTestInput()`** — the rulebook is now a declared test input, so a threshold edit can no longer leave the drift tests `UP-TO-DATE` |
| `settings.gradle.kts` | **New.** `:domain:engines:emergencyfund`, `:feature:emergencyfund` |
| `domain/engines/emergencyfund/**` | **New.** `EmergencyFundEngine` + input/result/status/basis + factory, `DefaultEmergencyFundEngine`, `EmergencyFundRules`, `ENGINE.md`, four test files + a 16-record golden resource |
| `domain/engines/{budget,card,goals,investment,safetospend}/**Rules.kt` | `RULEBOOK_VERSION` 1.14.0 → 1.15.0. One line each; nothing else moved |
| `data/repository/.../TransactionRepository.kt` | **`observeMonthlyLedger(months)`** — the same §8.3 classification, month by month, over closed months; `breakdownOf` split so the merchant overrides are fetched once rather than once per month |
| `data/repository/.../MonthlyLedger.kt` | **New.** One closed month: its key, its nature breakdown, its income |
| `data/repository/.../EmergencyFundRepository.kt`, `RepositoryFactory.kt` | **New.** The only repository here built from other repositories rather than from the database, and the reason is in its KDoc |
| `feature/emergencyfund/**` | **New.** UiState/Event, ViewModel, screen, labels, strings, 9 tests |
| `feature/dashboard/**` | An "Emergency fund" entry below "Your goals", its string, and a re-recorded screenshot baseline |
| `app/.../di/GoalEngineModule.kt`, `RepositoryModule.kt`, `navigation/CfoRoute.kt`, `CfoNavHost.kt` | The engine binding (in the module whose KDoc already promised it), the repository binding, the typed route and the destination |
| Three `TransactionRepository` fakes | `observeMonthlyLedger` implemented — empty in the feature modules, `unsupported()` in the worker's |
| `docs/adr/0034-*.md`, `DECISIONS.md`, `FLOW.md`, `CHANGELOG.md`, `VERSION`, the tracker, this file | The records |

---

## 5 · Quiz — what a reader should be able to answer

1. **§10.1 gives five terms for the multiplier M. Why does this engine apply two?**
   Three of them — dependents, health cover, job stability — have no field anywhere in the app. A
   rulebook param nothing reads is indistinguishable from one that works, so none was minted, and
   `RulebookDriftTest` asserts their absence so they must arrive deliberately.
2. **With the shipped rulebook, when does `RULE-RUNWAY-M`'s clamp change the answer?**
   Never. 6 + at most 3 is 9, inside [3, 12]; even all five §10.1 terms would give 5..12. It is
   tested against moved rules, and the golden file says explicitly that it contains no clamped
   record.
3. **Why is the standard deviation computed with Newton's method rather than `Math.sqrt`?**
   `Math.sqrt` returns a `Double`. MNY-002 admits no floating point, and a `Double` would make the
   answer depend on the platform's rounding — breaking P-08's "fixed input, fixed output". It floors,
   which understates volatility, which can only ever shrink a target.
4. **A user with nothing recorded opens the screen. Why must it not show a target of ₹0.00?**
   Because `liquidFunds >= target` would then be true, and the app would tell someone with no savings
   that their emergency fund is complete. The answer is `UNKNOWN` and an explanation.
5. **Why is the essentials figure a median, and why does the live month not count?**
   A mean is moved by one unusual month — on the repository test's own fixture, 42% too high. The
   live month is partly unspent by definition, so counting it would drift the target down through the
   month and jump it back on the 1st. Confirmed on the device: a ₹3,00,000 expense today moved the
   target not at all.
6. **The `_meta.version` bump failed four of five drift tests. What did the fifth one prove?**
   That the rulebook was not a declared Gradle task input, so a threshold edit could leave every
   drift test in the repository `UP-TO-DATE` and the build green. The assertion was fine; its
   scheduling was not. That is the fourth such gate found in this repo.
7. **Every test passed and the screen still said something wrong. What, and what does it generalise
   to?**
   "Counted as essential: NEED" — a domain token rendered as user-facing text. No assertion about a
   figure catches a sentence, which is what §9's "the app must be run and observed" is for.
