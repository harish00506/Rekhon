<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 9.4 — AI-FHS, §14's Financial Health Score, and its dashboard card.
  Result: a reader can see which of §14's sixteen scoring bases are scored and why the rest are not,
          how a missing pillar is handled, and where every number in the score comes from.
  Changelog: 2026-09-20 — Created.
-->

# 2026-09-20 — The Financial Health Score (issue 9.4, ADR-0045)

**Branch:** `feature/9-4-financial-health-score-ai-fhs` off `dev` (`32c8e72`) · **VERSION** 0.9.3 →
**0.9.4** · **versionCode** 39 → 40 · **Schema** 22, unchanged · **rules-kb.json** 1.16.0 →
**1.17.0** · `AI-FHS` 1.0 (new)

---

## 1 · Decisions this session

The full argument for each is in ADR-0045.

- **Six signals are scored; the rest are not guessed.** §14.1 lists sixteen scoring bases across
  five pillars. Six have a source in this app — the emergency fund's runway, obligations over
  income, card utilisation, the savings rate, budget adherence, goals on track. The others have no
  field anywhere (insurance, dependants, retirement — ADR-0034) or no number in §14 (how many
  alerts is "frequent"; what volatility scores zero).
- **Missing is never zero.** A signal without data is absent, a pillar without a signal is "—", and
  its weight goes to the pillars that have data. Protection has no signal in v1.0, so its 15% is
  always shared out and the card says so. Confidence records the weight that rested on data.
- **Two anchors are minted, two are borrowed.** §14 does not say how big the runway floor is (25
  points) or where utilisation reaches zero (100%); both are new rulebook params whose source notes
  say they were minted here. The utilisation and savings tops already exist as RULE-CC-UTIL and
  RULE-SAVE-RATE and are **read**, not restated — the drift test asserts both halves.
- **The join decisions:** an EMI recorded both as a loan and as a FIXED liability stream is counted
  once; rent comes from AI-CLS rather than a category name; utilisation uses the **statement**
  balance (what RULE-CC-UTIL and a credit bureau mean, and stable enough for §14's weekly cadence);
  the savings rate is Σ kept / Σ income over three closed months, with liability payments counted
  as spent.
- **The parts add up.** Contributions and effective weights are apportioned (floors, then the spare
  points to the largest remainders), so they sum to the total and to 100% exactly.
- **The lever** — §14's "single highest-leverage action" — is the signal with the most points of
  the total still to gain, which is arithmetic on figures already on screen.
- **Deferred with triggers:** the weekly cadence, movement with a cause list and the what-if slider
  (all need stored snapshots); the whole Protection pillar; the revolving flag and no-debt bonus;
  alert frequency, volatility, the retirement flag and the funding streak; the Advisor hub.

## 2 · What the tests caught

The oracle was written before the engine and reads the rulebook itself. Its seven cases include the
exact anchors, the runway floor, a month with spending and no income, and a profile whose only
signal is its goals. The engine matched it on the first run, so each gate was checked to fail:
- removing the runway floor failed the golden and behaviour tests;
- removing the re-weighting failed the golden, behaviour **and** property tests;
- editing the KB's `obligation_zero_pct` alone failed the drift test;
- in the repository, dropping the liability-stream exclusion failed the double-counting test, and
  counting liability payments as saved failed the savings test.

All were reverted. Two of my own test set-ups were wrong before their first run: an amortisation row
whose balance did not fall by its principal, and two coroutine tests that used different test
dispatchers.

**The repository's tests were written after its code**, unlike the engine's. The two mutations above
are what stands in for the red step there; the tracker records it.

**The device found a real fault.** The demo pays ₹28,000 of rent, but AI-CLS needs three closed
months before it will call a stream FIXED (§8.2). The obligation signal therefore measured 0% of
income and scored full marks, and the card read **992, Excellent**. Zero obligations is a claim the
data did not support. The rule is now §14's own: with no fixed stream and no loan, obligations are
unknown, the pillar shows "—" and its weight moves. The demo then reads 988 on "2 of 5 pillars",
and after confirming the Landlord repeat the pillar returns with "Fixed costs and EMIs 29.4% of
income". A failing test was written first; one existing test that asserted the old behaviour was
corrected rather than kept.

## 3 · Flow changed this session

New `FLOW.md` §2.09 — the widest read in the app, seven repositories and no DAO:

```
DashboardViewModel.observeHealthScore()
└─ HealthScoreRepository.observeHealthScore()
    └─ combine(emergency fund · monthly ledger(3) · streams · loans · categories · cards · budgets · goals)
       → HealthSignals.* → HealthScoreEngine.score()
            each signal on its line · pillar = mean · missing pillars re-weighted · lever
  ⇣ uiState.health → HealthSection
```

## 4 · Code changed this session

| Path | What it does now |
|------|------------------|
| `domain/engines/healthscore/**` (new) | `HealthScoreEngine` + types, `HealthRules` (RULE-FHS-*), `WeightedHealthScoreEngine` (`SignalCurves`, apportionment), `ENGINE.md`; tests: behaviour (18), property (6 × 300), golden (7 cases) with `health_oracle.py`, drift (7) |
| `ai/rules/rules-kb.json`, `ai/rules/rulebook.md` | `RULE-FHS-PILLARS`, `RULE-FHS-BANDS`, `RULE-FHS-SIGNALS`; 1.17.0 |
| seven `*Rules.kt` mirrors | `RULEBOOK_VERSION` 1.17.0 (no mirrored row changed) |
| `data/repository/.../HealthScoreRepository.kt` (+ test, 11) | `HealthSignals` — the six mappings; `ComposedHealthScoreRepository` over seven repositories' flows |
| `data/repository/.../RepositoryFactory.kt`, `build.gradle.kts` | `healthScore(...)`; the new module |
| `app/.../di/RepositoryModule.kt` | Provides the engine and the repository |
| `feature/dashboard/.../HealthSection.kt` (new, + test, 7), `DashboardScreen/UiState/ViewModel`, `strings.xml` | The "Financial health" card |
| `feature/dashboard/src/test/**` | `FakeHealthScoreRepository`, `fixtureHealth`, VM tests (+2), the fixture for screenshots |
| `settings.gradle.kts`, `ai/orchestrator/engine-registry.yaml` | The module; AI-FHS's contract and sources |
| `docs/adr/0045-…`, `DECISIONS.md`, `FLOW.md` | The decision, its row, §2.09 |

## 5 · Quiz

**Outcome: not yet taken.** The answers in italics are the author's. Record the developer's own pass
here, fails included.

1. A profile has only goals set up, and half are on track. What does the card show, and why is that
   not misleading? *(≈429 of 1000, and "Based on 1 of 5 pillars" — §14 re-weights rather than
   scoring the rest as zero, and the coverage line says what it rests on.)*
2. Why is a FIXED stream in the EMI category dropped when a loan account exists? *(Its payments are
   that loan's instalments; counting both would double the obligation ratio.)*
3. Why the statement balance rather than the live one for utilisation? *(It is what RULE-CC-UTIL
   and a credit bureau mean, and the live figure would move the score with every swipe.)*
4. Where do the runway floor's 25 points come from, and how would a reviewer know? *(RULE-FHS-SIGNALS
   — its source note says §14 does not state the size and that it was minted for this issue.)*
5. Two pillars have data. Why do their contributions still add to exactly the total? *(They are
   apportioned: floors of the exact shares, and the spare points go to the largest remainders.)*
