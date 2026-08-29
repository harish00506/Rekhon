# Session — 2026-08-30 — Issue 6.6: net-worth snapshots history

**Branch:** `feature/6-6-net-worth-snapshots-history`
**VERSION:** 0.6.5 → **0.6.6** · versionCode 26 → 27 · **schema unchanged at 19**

---

## 1 · Decisions this session

### 1.1 A percentage change is refused when the series starts at or below zero

The one judgement in this issue, and it is a refusal — recorded as
[ADR-0031](../adr/0031-a-percentage-change-is-refused-when-net-worth-starts-at-or-below-zero.md).

A percentage needs a denominator that is a magnitude, and net worth is not one. It is routinely
negative for a user with a home loan and a young portfolio, and it crosses zero on the way up:

| Series | Absolute change | Naive ratio |
|---|---|---|
| −₹50,000 → −₹10,000 | **+₹40,000** | −80% — the opposite of what happened |
| ₹0 → ₹25,000 | **+₹25,000** | divide by zero |
| −₹20,000 → +₹30,000 | **+₹50,000** | −250% — a catastrophe describing a recovery |

So `changeBps` is returned only when the first reading is strictly positive — `> 0`, never `>= 0`,
because zero is the same lie by division. The absolute change is always reported and is always
correct. The screen says *why* there is no percentage rather than leaving a gap, because a missing
figure with no explanation reads as a bug.

**`abs(first)` was rejected** and is the interesting rejection: it makes the first row come out right
and silently breaks the third, where a series crossing zero would report a percentage of a quantity
that changed meaning halfway through. A rule that is right on the cases you thought of and wrong on
the ones you did not is worse than a refusal, because it never announces itself.

### 1.2 The trend reads stored rows and never recomputes them

The whole reason `net_worth_snapshot` exists is that a trend must not move under the user. Nothing on
this path calls `computeAsOf`.

Proved rather than described: the test stores a figure deliberately at odds with what the fixture
account would produce today and asserts the **stored** one comes back. Substituting a re-derivation
in the mapper was tried on purpose and turns it red.

### 1.3 A change over one reading is absent, not zero

One snapshot says what net worth was that day and nothing about direction, so `change` is `null`
below two points. Zero would claim a stability nobody measured — the distinction the dashboard
already draws with `dashboard_net_worth_pending`.

It bites in the UI too: **`CfoSparkline` needs two points and draws nothing at all below that,
silently**, so a profile on its first day would get an empty box. The screen renders a sentence.

### 1.4 The trend carries its own engine id and version

`net-worth-trend` 1.0, leaving `compute`'s `net-worth` 1.0 untouched. `engine_version` is written
into **every** stored row (AI-ARC-006), so sharing one string would stamp thousands of snapshots as
the output of a formula that never moved — and that column exists precisely to signal that one did.

### 1.5 No migration, and the design system already had the chart

The `(profile_id, as_of_iso_date)` index covers the range scan, and Room's exported schema describes
tables rather than queries, so schema stays at **19**.

**`CfoSparkline` has existed since issue 1.8**, documented as being for "a balance or net-worth
trend", with zero production call sites. The AC "charts come from the design system" was met by
finally using it. Its `contentDescription` names the window and never an amount: it is read aloud, so
a figure in it would survive the privacy blur masking the same number on screen (§23).

### 1.6 The window is chosen by the repository, measured by the engine

`NetWorthRange` resolves to a from-date with `LocalDate.minusMonths` in the repository, because that
needs a clock (TIM-001). Calendar arithmetic, not day counts — one month back from 31 March is
28 February, and `minusDays(30)` would disagree with the chip's own label. The engine reads no clock,
so every golden case is reproducible to the byte (P-08).

---

## 2 · Flow changed this session

New section **FLOW.md §2.35** — net worth over time, the one figure that is read and never
recomputed:

```
DashboardScreen → net-worth CfoCard (clickable, Role.Button)
└─ navController.navigate(CfoRoute.NetWorthHistory)
       ▼
NetWorthHistoryViewModel: selected(range).flatMapLatest { repository.observeHistory(it) }
└─ RoomNetWorthRepository.observeHistory(range)
    ├─ activeProfileId.flatMapLatest { … }          demo and real never mix (ADR-0006)
    ├─ clock.today() → range.fromDate(today)        TIM-001, minusMonths not minusDays
    ├─ netWorthSnapshotDao().observeRange(...)      READS stored rows; tombstones excluded
    └─ engine.trend(...) → change, changeBps (ADR-0031), high, low
       ▼
NetWorthHistoryContent → CfoSparkline + the figures beside it
```

---

## 3 · Code changed this session

| Path | What it does now |
|---|---|
| `domain/engines/networth/NetWorthTrend.kt` | **New.** `NetWorthPoint`, `NetWorthRange`, `NetWorthTrendInput`, `NetWorthTrend`, and `internal object Trend` for the arithmetic |
| `domain/engines/networth/NetWorthEngine.kt` | Second operation, `trend`, total over its input |
| `domain/engines/networth/DefaultNetWorthEngine.kt` | Implements it; `TREND_ENGINE_ID` / `TREND_ENGINE_VERSION` kept separate from `compute`'s |
| `domain/engines/networth/ENGINE.md` | The trend's formula, the refusal, and a two-row version log |
| `golden/networth-trend.txt` | **New.** 10 records from an independent Python implementation |
| `core/database/dao/Daos.kt` | `NetWorthSnapshotDao.observeRange` — the first multi-row read for display |
| `data/repository/NetWorthRepository.kt` | `observeHistory(range)`, plus `NetWorthRange.fromDate` and `trendOf` at file scope |
| `feature/dashboard/NetWorthHistory{UiState,ViewModel,Screen}.kt` | **New.** The screen, its state and its one event |
| `feature/dashboard/DashboardScreen.kt` | The net-worth card is clickable with `Role.Button`; `DashboardActions` gains `onNavigateToNetWorthHistory` |
| `app/navigation/CfoRoute.kt`, `CfoNavHost.kt` | `NetWorthHistory` route and its one registration line |
| `app/androidTest/CfoSmokeTest.kt` | A device gate for the new screen; the recurring test's precondition relaxed (see §5) |

---

## 4 · Verification

| Gate | Result |
|---|---|
| `ktlintCheck detekt lintDebug` | **OK** |
| `unitTests koverVerify` | **OK** — engine ≥ 85%, money math 100% |
| `recordPaparazziDebug` | **OK** — 4 new baselines; the dashboard's moved only by the added line, checked by eye |
| `:app:connectedDebugAndroidTest` on `CfoTest(AVD)` | **OK** — 3/3, run twice |
| `check_issue_docs.py` | **PASS** |

**Gates broken on purpose (ADR-0005)**

| Break | Observed |
|---|---|
| `expect_bps` 333 → 334 in the fixture | golden runner red, restored |
| A golden record deleted | coverage meta-test red, restored |
| A re-derivation substituted for the stored figure | 2 repository tests red, restored |

---

## 5 · A mistake worth recording: an over-strict precondition made the suite order-dependent

Yesterday, while chasing the `CfoSmokeTest` flake, I added an assertion that the recurring test's
onboarding screen **must** be showing — built on a hypothesis that turned out to be wrong (the real
cause was an off-screen tap). Today's new test enters the demo and runs first alphabetically, so the
recurring test found the app already past onboarding and failed on that assertion.

The assertion was over-strict from the start: "not on onboarding" does not imply "no demo ledger", it
implies a sibling may already have entered the demo — in which case the fixture is present and there
is nothing to do. It is now the tolerant `if` it originally was, with the scroll fix and the longer
timeout kept, and a comment recording why the strict version was wrong.

The general lesson is the same one yesterday's investigation produced: a guard written from a
hypothesis outlives the hypothesis. When the cause turns out to be something else, go back and remove
what was added on the strength of it.

---

## 6 · Quiz

1. Why does the trend refuse a percentage instead of computing one from `abs(first)`?
2. What stops the history from following today's accounts, and how is that proved rather than
   asserted?
3. Why does the trend get its own engine version instead of bumping the one that already exists?
4. Why is `change` null for a single snapshot when `last − first` would obviously be zero?
5. Which module resolves "1M" into a date, and why can the engine not do it?

<details><summary>Answers</summary>

1. `abs(first)` is right for a negative-throughout series and silently wrong for one crossing zero,
   where the denominator changes meaning halfway through. A rule that is wrong only on cases you did
   not think of never announces itself; a refusal does.
2. Nothing on the path calls `computeAsOf`. The proof is a test that stores a figure deliberately at
   odds with what the fixture account would produce and asserts the stored one comes back —
   substituting a re-derivation turns it red.
3. `engine_version` is written into every `net_worth_snapshot` row, so bumping it for a change to a
   different operation would stamp thousands of stored rows as the output of a formula that never
   moved (AI-ARC-006).
4. Because `first` and `last` are the same reading. It measures a zero-length interval, which tells
   you nothing about direction — absent, not zero (P-03).
5. The repository, because resolving a window needs today's date in the profile zone and TIM-001 bans
   a wall-clock read in `:domain:*`. The engine receives points already bounded, which is also what
   makes every golden case reproducible.
</details>
