<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: closing ADR-0037's first follow-up — the goals screen and the order of operations disagreed
        about the goals' share past the emergency gate (§36, §15.1).
  Result: a reader can see why the dependency had to turn round, what changed meaning, and how the
          agreement is now pinned by a test that actually discriminates.
  Changelog: 2026-09-18 — Created.
-->

# 2026-09-18 — The ranking and the goals screen agree (ADR-0038)

**Branch:** `feature/foo-goals-agree` off `dev` (`634ce8d`) · **VERSION** 0.7.6 → **0.7.7** ·
**versionCode** 32 → 33 · **Schema** 22, unchanged · `AI-FOO` 1.0 and `AI-GOAL.waterfall` 1.0 both
unchanged — **no engine logic moved**

---

## 1 · Decisions this session

### 1a · The ranking is the base, not the consumer

§36 says AI-FOO "supersedes the simple waterfall in §15". Issue 7.5 composed the two instead and
recorded the cost. Past the gate the dashboard funded the emergency fund's pace before goals; the
goals screen poured the whole surplus into goals. Neither was wrong about its own rule, and the app
had two answers to one question — the thing P-02 exists to stop.

The fix is the one ADR-0037 named: the goal split allocates **what the ranking leaves**. Which way the
dependency points follows from §36: the ranking is the base.

### 1b · The surplus had to leave both

`OrderOfOperationsRepository` read the surplus, the goals' need and the goal count **out of**
`GoalWaterfallRepository`. Simply reversing the flow would have been a cycle. So the derivation —
ADR-0035's observed-P50 median with the declared-envelope fallback — moved into `SurplusRepository`,
**unchanged line for line**, and now belongs to neither screen. AI-FOO reads it, and sums the goal
projections itself rather than a waterfall's totals.

### 1c · Two echoes, not two terms

`GoalWaterfall` gains `claimedBeforeGoals` and `grossSurplus`. The allocation is already net of them
and nothing subtracts them again — they exist so the card can say *why* its figure is smaller than the
month's surplus. Without that line the goals screen would show a smaller number than the dashboard
with no explanation, which reads as money going missing between two screens.

`emergencyTopUpMonthly` is now passed as **zero**: Stage 3 already claimed AI-EMF's pace, and claiming
it again here would hide a month of it from the goals. The runway and the gate still travel, so a
held goal still says `blockedByEmergencyFund` in the words the card has always used.

### 1d · What changed meaning, stated plainly

`GoalWaterfall.monthlySurplus` is now **what the goals may have**, not the month's surplus. Two
repository tests asserted the old meaning and were updated to read `grossSurplus`; the card reads it
too. A plan can now be infeasible because a card is being paid off — the answer §36 intends.

---

## 2 · What only running it could find

Nothing was wrong on the device, and the demo profile happened to be the perfect witness: a 42% ICICI
card owing ₹92,534 means high-interest debt claims the whole ₹19,000 month.

| Screen | Before this change | Now |
|---|---|---|
| Full order, Step 6 | `Needed: ₹10,000.00`, no amount | unchanged — `Needed: ₹10,000.00`, no amount |
| Goals screen | would have funded the goal ₹10,000 from the same month | `₹0.00 a month from this plan`, and the plan line: *"₹19,000.00 of that goes first to your starter buffer, high-interest debt and emergency fund, leaving ₹0.00 for goals."* |

**The test that mattered was the one that nearly did not.** The first agreement test seeded the
household the 7.3 suite already used, passed, and then **passed again under a deliberate regression**
that restored the old behaviour. It was proving a coincidence: in that household the buffer is
satisfied and the fund complete, so nothing is claimed before goals and both behaviours agree. The
same is true below the gate, where every stage under the fund is blocked either way.

The one shape that tells them apart is a household **past** the gate with an unfinished fund — three
closed months of ₹1,00,000 income and ₹40,000 of needs, leaving ₹1,80,000 in the bank: 4.5 months of
cover against a six-month target, so AI-EMF's pace claims ₹10,000 before any goal. Rewritten around
that, the regression fails the test.

**Style, not behaviour:** the refactor pushed `setUp` past detekt's 40-line limit (extracted
`buildRanking`), a test fixture to seven parameters (a reasoned suppression), and one KDoc line past
120 characters.

---

## 3 · Flow changed this session

`FLOW.md` §2.6 rewritten and §2.8's read updated:

```
§2.8  OrderOfOperationsRepository            THE BASE
      ├─ SurplusRepository.observeMonthlySurplus()      ← extracted from §2.6
      ├─ GoalRepository.observeGoals()                  ← Σ requiredMonthly, count
      ├─ EmergencyFundRepository, and its own debt read
      └─ OrderOfOperationsEngine.rank(...)

§2.6  GoalWaterfallRepository                THE CONSUMER
      ├─ goals.observeGoals()
      ├─ ranking.observe()                              ← §2.8
      │    forGoals = max(surplus,0) − Σ amounts of every stage ABOVE GOAL_INVESTING
      │    claimedBeforeGoals + grossSurplus travel as echoes
      ├─ emergencyFund.observeEmergencyFund()           ← runway, for the gate's wording
      └─ GoalWaterfallEngine.allocate(emergencyTopUpMonthly = ZERO, …)
```

---

## 4 · Code changed this session

| Path | What it does now |
|---|---|
| `data/repository/…/SurplusRepository.kt` | **new** — the month's surplus and its basis, owned by neither screen |
| `data/repository/…/OrderOfOperationsRepository.kt` | reads the surplus and the projections; no longer knows the waterfall exists |
| `data/repository/…/GoalWaterfallRepository.kt` | pours the ranking's remainder; passes a zero top-up; carries the two echoes |
| `data/repository/…/RepositoryFactory.kt`, `app/…/di/RepositoryModule.kt` | `surplus(...)` added; both repositories rewired |
| `domain/engines/goals/…/GoalWaterfallEngine.kt`, `DefaultGoalWaterfallEngine.kt` | `claimedBeforeGoals` and `grossSurplus`, defaulted and carried through — **no allocation logic changed** |
| `feature/goals/…/GoalWaterfallCard.kt`, `strings.xml` | the basis line reads the gross figure; a new line says what took the rest |
| `data/repository/src/test/…/GoalWaterfallRepositoryTest.kt` | two agreement tests, two updated for the new meaning, `buildRanking` extracted |
| `data/repository/src/test/…/OrderOfOperationsRepositoryTest.kt` | rewired to the new composition |
| `feature/goals/src/test/…/GoalsFlowTest.kt` | the "claimed first" line |
| `docs/adr/0038-…`, `DECISIONS.md`, `FLOW.md`, `CHANGELOG.md`, `VERSION`, `app/build.gradle.kts`, `docs/memory.md` | the records |

---

## 5 · Not delivered

- **A dashboard link from the goals card**, or the reverse. Each screen explains itself; neither
  points at the other beyond the ranking's existing "Open your goals".
- **ADR-0037's other follow-ups** — FOO-001 reordering, one-tap goal creation, the Advisor hub, and
  Stages 1, 4 and 7 — are untouched.
