<!--
  Why:  CLAUDE.md §10 — one file per working session, holding the full reasoning behind the
        one-liners that go up to DECISIONS.md and the arrow chains that go into FLOW.md.
  What: issue 7.4 — linked contributions (§15, FR-GOAL-002, FR-GOAL-004).
  Result: a reader can reconstruct why goal progress is now two numbers, and what running the app
          found that a green build could not.
  Changelog: 2026-09-06 — Created.
-->

# 2026-09-06 — Linked contributions (issue 7.4)

**Branch:** `feature/7-4-linked-contributions` off `dev` · **VERSION** 0.7.3 → **0.7.4** ·
**Schema** 21 → **22** · **`AI-GOAL`** 1.0 → **1.1** · **Rulebook** 1.15.0, unchanged

---

## 1 · Decisions this session

### 1a · Two join tables, and neither holds an amount

`goal_contribution(goal_id, transaction_id)` and
`goal_funding_account(goal_id, account_id, linked_from_iso_date)`, both modelled on
`transaction_tags`: denormalised `profile_id`, three stamps, soft delete, and a **unique index on
the pair**.

Neither stores money. A contribution *is* the linked transaction's `amount_minor`, summed at query
time. That is ADR-0009's argument for split lines (the parent holds the money, the child holds only
the relationship) and ADR-0007's for balances (a stored copy drifts the moment the source is
edited) — and it has a pleasant side effect: **MNY-001 is satisfied trivially**, because no new
column holds a monetary value at all. There is nothing here to round and nothing for
`CfoMoneyAsFloatingPoint` to catch.

The alternative — an `amount_minor` on the link, so a movement could be *partly* attributed to a
goal — buys a case nobody asked for and pays for it with a second representation of one movement.

### 1b · The two link kinds sum differently, and that is the design

| Link | Sum | Why |
|---|---|---|
| explicit transaction | `ABS(amount_minor)` | linking is the user asserting *this movement funded the goal*. A ₹5,000 SIP debit is stored negative and still funds it; the direction is the act, not the sign |
| funding account | **signed** `SUM(amount_minor)` from `linked_from_iso_date` | the claim is about a *pot*, so what counts is how much it grew. An outflow must reduce it, and dedicating both accounts of an internal transfer must net to zero |

They look inconsistent side by side and are not: they answer different questions. Using `ABS` for
funding accounts would make a withdrawal *increase* a goal; using the signed amount for explicit
links would make a SIP payment *reduce* the goal it funds.

**Dedupe.** A movement inside a dedicated account that is *also* explicitly linked to that same goal
counts once, via the explicit link — the more specific statement wins. The exclusion is **per goal**:
the same movement may legitimately evidence a second goal, and those are separate claims.

**Both halves are bounded by `booked_on_iso_date <= today`**, the same bound every balance query
uses. A future-dated transaction (issue 3.4) has not happened, so it cannot be progress.

### 1c · `linked_from_iso_date` exists because history should not arrive by surprise

Dedicating an account that has held money for two years would otherwise credit the goal with all of
it the instant the user tapped. The chooser asks — *from today* or *counting everything in it* — and
stores the answer as a date either way, so a reader can see which the user picked rather than
inferring a policy.

### 1d · Progress is two numbers that never merge, and `saved_minor` stays

§15 does not say *replace* manual claims; it says *"ghost progress (manual claims without linked
funds) is visually distinct"*. So `goal.saved_minor` stays exactly as the editor wrote it — it is now
the **declared** half — and the evidenced half is derived beside it. `GoalSpec.savedEvidenced` and
`GoalProjection.savedEvidenced` / `savedDeclared`, with `evidenced + declared == saved` a `require`
on the type. The screen therefore does no subtraction of its own.

Both defaults keep every pre-7.4 caller compiling and every golden record valid: an untouched profile
reads as `evidenced = 0, declared = saved`, which is the truth about it.

**The pair is floored together, not the total at zero.** A dedicated account that has paid out more
than the declared figure would otherwise produce negative progress. The clamp goes on the *evidenced*
half at `-declared`, so `saved - savedEvidenced` still equals exactly what the user typed. Flooring
the total on its own would have made the declared half come out as a number they never entered — a
figure the app invented, which is the thing P-03 exists to stop.

### 1e · Reversal is a soft delete, because the criterion asks for two opposite things

*"Unlinking reverses it; provenance is retained."* A hard `DELETE` does the first and destroys the
second. The row survives with its `created_at`, stops counting, and a re-link **revives** it rather
than minting a second — which the unique index turns from a convention into a rule.

### 1f · `RULE-PAY-FIRST` consumed, mirroring nothing

The row has named `AI-GOAL` in `consumed_by` since the rulebook was written and had no reader — the
third such row, after `RULE-HORIZON` (7.1) and `RULE-EMERG-FIRST` (7.3). **Every rule that names
`AI-GOAL` now has one.**

Its `params_json` is `{"anchor": "salary_credit_day"}`: the *name of where to look*, not a threshold.
So `GoalRules` gains no instance field — `RulebookDriftTest` still asserts its fields are exactly
`{shortYearsMax, hybridYearsMax}` — and the day arrives as `GoalPlanInput.contributionAnchorDay`,
resolved by the repository from the quick-setup `income` recurring rule the profile already has
(issue 2.3). No field in this app holds a payday, and none was added: the fact already existed.

The citation is attached **only when the day is known**. That is a departure from 7.3's
`RULE-EMERG-FIRST` handling, and deliberately: a *gate* is evaluated every time and both of its
outcomes are outcomes, while an anchor the app does not have produces no advice at all. Citing it
anyway would make the evidence list a list of rules that exist rather than of rules that fired.

**No rulebook row minted.** `_meta.version` stays 1.15.0 and the six typed mirrors are untouched, on
7.1's and 7.3's precedent. `RulebookDriftTest` asserts the absence of the three keys somebody would
reach for first (`contribution_lookback_months`, `min_contribution_minor`,
`ghost_progress_tolerance_pct`).

### 1g · A route, not a fourth section on the goal card

`CfoRoute.GoalDetail(goalId)`, modelled on `Holdings(accountId)`. The goal card already carried three
competing "monthly" figures — 7.1's required, the user's planned, 7.3's allocated — and §2b.2 of the
7.3 session is the recorded account of what a *second* measurement did to the sentences already
there. The card gains exactly one line, the ghost note; everything else lives on the new screen.

The screen reads `TransactionRepository` and `AccountRepository`, not `:feature:transactions`
(ARC-001 holds), and it is a second repository rather than five more methods on `GoalRepository`:
ARC-005 asks that exactly one class touch a DAO, not that one class touch every DAO.

### 1h · Three things this was built on top of, repaired

None of these are 7.4's, and all three were found by needing them:

1. **`goal` was missing from `CfoArchive`** — added in 7.1, never wired into the export, so every
   archive taken between 7.1 and 7.4 silently dropped the user's goals. That class's own doc comment
   warns about exactly this failure and argues it away: *"a new column is in the archive the moment
   it is in the table"*. True of a **column**. False of a **table**. The failure arrived one level up
   from where it was being watched.
2. **`rowCount()` had never counted eight of its lists** — `credit_card`, `card_alert`, `loan`, both
   investment tables and now the three goal ones. Those five were exported and restored correctly and
   simply not *reported*, so the user was told a smaller number than the file held, in the one figure
   they are asked to check a restore against.
3. **`DemoDao.countRowsFor` omitted `goal`, `investment_holding` and `investment_lot`**, and the demo
   wipe deleted none of them. A count that omits a table is not a weaker assertion — it is a false
   one, and it is what let three tables' residue survive an "erase everything".

`CfoArchive.VERSION` stays 1: the three new lists default to empty and `Json` has
`ignoreUnknownKeys`, so neither direction breaks. The gate that refuses an archive this build cannot
restore is `schemaVersion`, which moves on its own.

---

## 2 · What only running it could find

### 2a · The editor was about to double the figure

`GoalsViewModel.openEditor` filled the editor's *Saved so far* field from `goal.saved`. That was
correct until this issue, and from this issue `goal.saved` is the **total** — so opening the editor
on a goal with ₹1,59,800 linked and saving would have written ₹1,69,800 into `saved_minor`, and the
next read would have added the evidenced half again. Nothing in 7.1's code changed; what it *meant*
did.

This is the second instance of that pattern in this feature, after 7.3's `goals_shortfall`. **Adding
a second measurement of an existing quantity makes every sentence about the first one ambiguous,
retroactively, with no edit to the file that holds it.** Fixed to load `savedDeclared`, and verified
on the emulator on a goal holding both halves.

### 2b · A sentinel date rendered at the user

"Count everything in this account" is stored as `0001-01-01` — a bound no `booked_on_iso_date` can
fall below, so the sum needs one comparison instead of a nullable column and two branches. The detail
screen rendered the stored date, so the dedication read back as *"HDFC Savings, counting from
0001-01-01"*: a true statement about the database and a meaningless one about the user's money.

The fix is not a string comparison in the composable. `GoalFundingAccount` gained
`countsWholeHistory`, set by the repository that wrote the sentinel — the layer that knows what the
value means is the layer that should say so.

### 2c · The golden gate was a tautology on the first attempt

The split assertion compared `savedDeclared` against `record.saved - record.evidenced`, which is the
same subtraction the engine performs. Editing a record's `evidenced` changed the input *and* the
expectation together, and the test stayed green — confirmed by trying it. `expect_declared` is now
stated outright in the golden file, and breaking one makes the test fail.

That is the fourth gate in this repository found to read as present and check nothing, and the first
that was caught **before** it shipped rather than issues later.

### 2d · Gates proved red on purpose

| Gate | Broken how | Result |
|---|---|---|
| funding-account dedupe | `NOT EXISTS` narrowed to match nothing | *"a movement that is both dedicated and linked by hand counts once"* FAILED |
| `RULE-PAY-FIRST`'s anchor | `params_json.anchor` → `month_end` | `RulebookDriftTest` FAILED |
| the golden split | one `expect_declared` changed | `GoalGoldenTest` FAILED (after 2c's rewrite; before it, passed) |

---

## 3 · Flow changed this session

New section **§2.7** in `FLOW.md`. In summary:

```
GoalRepository.observeGoals()
└─ combine(
    ├─ goalDao.observeForProfile(profileId)
    ├─ goalDao.observeEvidenced(profileId, today)      ONE UNION ALL, ONE GROUP BY
    │   ├─ goal_contribution ⋈ transactions           → ABS(amount_minor)
    │   ├─ goal_funding_account ⋈ transactions        → SIGNED, from linked_from_iso_date
    │   ├─ AND NOT EXISTS (same txn explicitly linked TO THIS GOAL)      ← the dedupe, per goal
    │   └─ both halves bounded by booked_on_iso_date <= today
    └─ recurringRuleDao.observeIncomeDueDate(profileId)                  ← RULE-PAY-FIRST's day
   ) { rows, evidence, incomeDue ->
       declared  = Money(row.saved_minor)
       evidenced = max(evidence[row.id] ?: 0, −declared)   ← the pair is floored TOGETHER
       → GoalEngine.plan(…, contributionAnchorDay)
           → GoalProjection.savedEvidenced / .savedDeclared    require(they add to saved)
   }
```

```
GoalDetailScreen → GoalDetailViewModel          route CfoRoute.GoalDetail(goalId)
├─ GoalContributionRepository.observeContributions(goalId)
│   └─ goalContributionDao.observeForGoal → flatMapLatest → transactionDao.observeByIds
│          the links are the index; the ledger is the truth
├─ GoalContributionRepository.observeLinkable(goalId)
│   └─ transactions.observeRecent(100) − already linked − the transfer_out leg (ADR-0008)
└─ link / unlink / linkAccount / unlinkAccount
    ├─ link:   findIncludingDeleted → REVIVE, or mint
    └─ unlink: softDelete            reverses to the paise, keeps when it was linked
```

```
GoalDetailEvent.ClearGhostProgress
└─ GoalRepository.save(GoalDraft(…, saved = ZERO), id)   an ordinary goal edit, not a DAO reach
```

Also changed: `DemoModeRepository.exit()` now deletes the goal family and both investment tables;
`ArchiveRepository.export/wipe/restore` carry goals and both link tables.

---

## 4 · Code changed this session

| Path | What it does now |
|---|---|
| `core/database/…/entity/Entities.kt` | `GoalContributionEntity`, `GoalFundingAccountEntity` |
| `core/database/…/dao/Daos.kt` | `GoalDao.observeEvidenced` (the `UNION ALL`), `GoalContributionDao`, `GoalFundingAccountDao`, `GoalEvidenceRow`, `TransactionDao.observeByIds`, `RecurringRuleDao.observeIncomeDueDate`, three `DemoDao` deletes + five new `countRowsFor` terms, three `ArchiveDao` read/insert pairs |
| `core/database/…/CfoDatabase.kt` | VERSION 22, two entities, two DAO accessors |
| `core/database/…/migration/Migrations.kt` | `MIGRATION_21_22` + the two DDL helpers |
| `core/database/schemas/…/22.json` | exported and committed |
| `core/database/src/androidTest/…/MigrationRoundTripTest.kt` | `migrate21To22_…` — index names against `sqlite_master`, and the unique index refusing a duplicate |
| `domain/engines/goals/…/GoalEngine.kt` | `GoalSpec.savedEvidenced`; `GoalPlanInput.contributionAnchorDay`; `GoalProjection.savedEvidenced/savedDeclared/contributionAnchorDay` + the reconciliation `require` |
| `domain/engines/goals/…/DefaultGoalEngine.kt` | carries the split and the anchor; cites `RULE-PAY-FIRST` when the day is known; version 1.1 |
| `domain/engines/goals/…/GoalRules.kt` | `PAY_FIRST` citation (companion, not a field) |
| `domain/engines/goals/ENGINE.md` | the input, the formula line, the assumptions, the rules section, the version log |
| `data/repository/…/GoalRepository.kt` | three flows; `saved` derived; `anchorDay` resolved; `GoalDraft.saved` is now the declared half |
| `data/repository/…/GoalContributionRepository.kt` | **new** — the two link tables, the picker, `GoalContribution`, `GoalFundingAccount` |
| `data/repository/…/RepositoryFactory.kt` | `goalContributions(...)` |
| `data/repository/…/Archive.kt`, `ArchiveRepository.kt` | goals + both link tables exported, wiped and restored; `rowCount()` counts every list |
| `data/repository/…/DemoModeRepository.kt` | the goal family and both investment tables are wiped |
| `app/…/di/RepositoryModule.kt` | `provideGoalContributionRepository` |
| `app/…/navigation/CfoRoute.kt`, `CfoNavHost.kt` | `GoalDetail(goalId)`, wired |
| `feature/goals/…/GoalDetailUiState.kt`, `GoalDetailViewModel.kt`, `GoalDetailScreen.kt` | **new** — the screen |
| `feature/goals/…/GoalsScreen.kt` | `GoalProgressLines`, the ghost note, the way through |
| `feature/goals/…/GoalsViewModel.kt` | the editor loads `savedDeclared` |
| `feature/goals/src/main/res/values/strings.xml` | 28 new strings; the editor's stale hint rewritten |
| `feature/goals/build.gradle.kts` | `GoalDetailFlowTest` excluded from the release variant |
| tests | `GoalContributionRepositoryTest` (17), `GoalDetailViewModelTest` (9), `GoalDetailFlowTest` (12), `FakeGoalContributionRepository`, `FakeGoalAccountRepository`, 8 new engine cases, 3 golden records + `expect_declared`, 2 new drift tests |
| `docs/adr/0036-…`, `DECISIONS.md`, `FLOW.md` §2.7, `VERSION`, `CHANGELOG.md` | the records |

---

## 5 · Not delivered, stated plainly

- **Linking from the transaction side.** The way in is the goal, not the movement — a "link to a
  goal" action on the transaction detail sheet would need `:feature:transactions` to reach the goals
  repository, which is allowed, and a second surface for one action, which is scope. One way in was
  enough to prove the stack.
- **Partial attribution.** A movement funds a goal wholly or not at all. Splitting one across two
  goals is an allocation problem (`Money.allocate` would become the right tool, which the waterfall's
  ENGINE.md currently argues against for its own case) and no requirement asks for it.
- **`RULE-PAY-FIRST`'s notification half.** The row also names `AI-NTF`. Issue 9.6's engine does not
  exist; when it does, the anchor is already resolved and on the projection.
- **A per-account liquidity tier**, still — 7.2's deferral is untouched. A "savings" account and a
  "current" account are dedicated the same way here.
