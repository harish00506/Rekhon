<!--
  Why:  CLAUDE.md §10 — every session that changes code leaves one of these, so the reasoning
        behind a change survives the conversation that produced it.
  What: Session record — decisions, flow delta, code changed, quiz.
  Result: A reader six months from now can reconstruct why this session did what it did.
  Changelog:
    2026-08-14 — Created. First session record; §10 was written in this same session.
-->

# Session — 2026-08-14 · Budget alerts (4.5) and the session-record rules

**Branch:** `feature/4-5-budget-alerts-80-100`
**Commits:** `9debbd2` → `6ffccc5` (issue 4.5), plus this session-record change
**Issue:** [4.5 Budget alerts (80%/100%)](../issues/4.5-budget-alerts-80-100.md) — FR-BUD-004

> The 4.5 code carries `2026-08-13` stamps in its own doc comments and changelog entry; this record
> is filed under the date the session closed.

---

## 1 · Decisions this session

### 1.1 The alert bands mint a new rule row instead of bumping a shipped one

Full argument in **[ADR-0019](../adr/0019-budget-alert-bands-mint-a-new-rule-row.md)**; indexed in
[`DECISIONS.md`](../../DECISIONS.md). In short: adding `warn_pct` to `RULE-BUD-PACE` is a params
change, a params change bumps that row's `version`, and ADR-0017's trigger 3 makes a version bump
force the runtime `ai/` loader into existence — `:core:rules`, an asset pipeline, a
`rules_knowledge_base` table with a migration, and seven mirrors retrofitted — before
`:domain:engines:budget` could compile again.

The split is also right on the merits, which is the part worth keeping: `RULE-BUD-PACE` answers
*"am I on track?"*, a question about arithmetic whose consumer is a screen the user chose to open.
`RULE-BUD-ALERT` answers *"should this person be interrupted?"*, a question about attention whose
consumer is a notification they did not ask for. Only the second can hold
`notify_once_per_band_per_month`, which is not a threshold at all.

### 1.2 Once-per-band is a database constraint, not application logic

`UNIQUE(profile_id, budget_id, month_start_iso_date, band)` plus `OnConflictStrategy.IGNORE`, with
the claim taken **before** the notification is posted. The alternative — a flag checked in the
worker — cannot survive two concurrent runs or a crash between checking and posting. The rule row's
`notify_once_per_band_per_month` documents the intent and is read by no production code; the index
is the mechanism. (This distinction is what the quiz below caught.)

### 1.3 The in-app band derives from budgets, not from the alert table

`observeAlerts()` is `observeBudgets().map { rows -> rows.mapNotNull(::alertFor) }`. Reading
`budget_alert` instead would have been the obvious implementation and would have been wrong: that
table records *what was sent*, so on a device where notification permission was never granted it is
empty, and the banner would show nothing while the user sat at 140% of their grocery budget. Denied
permission is a supported state, not a degraded one.

### 1.4 The numeric guardrail shipped here is a deliberate subset

`NumericGuardrail` in `:core:model` does deterministic extraction of ₹ amounts and percentages and
resolves them against exactly the values the engine returned. It is fail-closed: the default allowed
set is empty, so a caller that forgets to declare its figures gets a refusal. The full L3 gate in
`ai/chat/guardrail.md` — date/count extraction, lakh/crore transforms, the REGENERATE-then-REFUSE
ladder — is issue 9.7's. REGENERATE is *inapplicable* here rather than unbuilt: there is no LLM on
this path, the text is templated from engine output, so there is nothing to regenerate.

### 1.5 Session records, a flow map, and a comprehension quiz (this change)

Three gaps, none of which any existing document covered:

- **Why a library was chosen was written nowhere.** ADRs are triggered only by deviations from the
  SRS. This session added `androidx.activity.compose` to `:feature:budgets` with no record at all.
- **How execution travels was written nowhere.** `docs/Architecture.md` covers module *dependency
  direction*; nothing covered the entry point or what calls what. 198 Kotlin files, 33 modules.
- **Every gate in §8 checks the code; none checks the coder.**

Resolved with two root documents ([`DECISIONS.md`](../../DECISIONS.md), [`FLOW.md`](../../FLOW.md)),
this per-session file, and the quiz protocol in `CLAUDE.md` §10.

**`DECISIONS.md` is an index, not an archive.** The 19 existing ADRs already answer "why this
approach" properly; restating them would create a second copy to keep in step, and the copy would
lose. Each gets one line and a link. Only decisions with no ADR — every library choice — carry
their reasoning there.

**`FLOW.md` maps the spine and three shapes, not 198 files.** Screen, worker, engine: everything
else in the codebase is one of those three with different nouns. A per-file call graph would be
stale within a week and read by nobody. Ceiling named in the file itself.

**Stated in §10's own text: these rules are agent-followed, not build-enforced.** Nothing in Gradle
or CI fails because a session file is missing. Saying so is deliberate — this repo has already
shipped a governance gate that nothing ever ran, and `scripts/check_issue_docs.py` exists today
wired into no workflow. The cheap enforcement, if wanted: extend that script and add it to `ci.yml`.

**Two findings fell out of writing `DECISIONS.md`**, which is the argument for having written it:

- **MockK is in the version catalog and used by zero modules and zero files.** Every suite so far
  uses a hand-written fake or recorder instead. Candidate for deletion.
- **Retrofit, OkHttp and Glance are pinned but consumed by nothing** — expected, since `:widget` and
  the backend are not built, but now visible rather than assumed.

---

## 2 · Flow changed this session

One new entry point (a fifth worker) and one new path through it. Now in
[`FLOW.md` §3](../../FLOW.md) as the worked example for the worker shape.

```
CfoApplication.onCreate()
└─ BudgetAlertWorker.schedule(this)                        NEW — fifth scheduled worker
    └─ WorkManager.enqueueUniquePeriodicWork("budget-threshold-alerts", KEEP, 1 day)

BudgetAlertWorker.doWork()                                 NEW
├─ sessionLock.isUnlocked == false → Result.retry()        before repository.get()
├─ repository.get().pendingAlerts()
│   ├─ budgetDao().observeCategoryBudgets(profileId, monthStart)
│   ├─ engine.alert(BudgetAlertInput(...))                 NEW — third engine method
│   └─ minus rows already in budget_alert this month
└─ per alert:  markNotified(alert)  →  budgetAlertDao().insertIfNew()   CLAIM
               └─ if it returned true:  notifier.notify(alert)
                   ├─ compose(alert)
                   ├─ NumericGuardrail.verify(...)         NEW — AI-ARC-004, fail-closed
                   └─ NotificationManagerCompat.notify(...)
```

Also changed, in the screen shape:

```
BudgetsViewModel.init
└─ observeAlerts()                                         NEW third collector
    └─ repository.observeAlerts()
        └─ observeBudgets().map { it.mapNotNull(::alertFor) }   derived, not read from the table
            ⇣
        uiState.alerts → BudgetAlertBanner + per-row BudgetBandChip
```

---

## 3 · Code changed this session

Full path-by-path table in the issue's
[Files Changed](../issues/4.5-budget-alerts-80-100.md#files-changed) section. Summary by layer:

| Layer | What changed |
|-------|--------------|
| `ai/rules/` | New `RULE-BUD-ALERT` v1.0; `_meta.version` → 1.10.0; `RULE-BUD-PACE.source_note` repointed (prose only) |
| `:domain:engines:budget` | Third contract method `alert()`; new `BudgetAlertBands`; `BudgetPace` extracted; `BudgetRules` mirrors the new row |
| `:core:model` | New `NumericGuardrail` + `GuardrailResult` |
| `:core:database` | `BudgetAlertEntity`, `BudgetAlertDao`, `MIGRATION_13_14`, schema `14.json` |
| `:data:repository` | `CategoryBudgetAlert`; `observeAlerts` / `pendingAlerts` / `markNotified` |
| `:app` | `CfoNotifications`, `BudgetAlertNotifier` (+ Hilt binding), `BudgetAlertWorker`, `POST_NOTIFICATIONS` |
| `:feature:budgets` | Alert banner + band chip, contextual permission request, new dependency `androidx.activity.compose` |
| Docs | ADR-0019, `ENGINE.md`, issue + tracker, `VERSION` 0.4.4, `CHANGELOG.md` |
| Governance (this change) | `DECISIONS.md`, `FLOW.md`, `CLAUDE.md` §10 + §5/§8/§9, `/pre-merge` check 10, this file |

**Not done, and recorded as not done:** `:core:database:connectedDebugAndroidTest` and the
`/run` + `/verify` device script. The 13 → 14 migration round-trip is written and never executed.

---

## 4 · Quiz

Run against 4.5's own work, which hit three triggers: a rule row, a database migration, and a new
dependency. **Result: passed, on the third attempt for one topic.** Recorded as it happened.

| # | Topic | Question | Answer given | Verdict |
|---|-------|----------|--------------|---------|
| 1 | Migration | What stops the second daily run re-sending the same 80% alert? | "The rule row's flag" | ❌ |
| 1b | Migration | What happens if you delete the flag from `rules-kb.json` and change no Kotlin? | "A drift test fails, nothing else" | ✅ |
| 2 | Rule row | Why were the bands not added to `RULE-BUD-PACE`? | "The row would get too large" | ❌ |
| 2b | Rule row | Why is adding params and leaving the version at 1.0 rejected outright? | "The drift test would catch it" | ❌ |
| 2c | Rule row | A July insight cites v1.0; you edit what v1.0 means. What does the user see in September? | "Shown them today's reasoning as history" | ✅ |
| 3 | Worker | What goes wrong if the repository injects before the lock is checked? | "The process crashes" | ✅ |

**What the failures were actually about**, since that is the only part worth keeping:

- **Q1** — mistaking the *documentation* of a guarantee for its *mechanism*.
  `notify_once_per_band_per_month` appears exactly once in the entire Kotlin codebase, in an
  assertion in `RulebookDriftTest.kt:94`. No production code reads it. A boolean in a JSON row
  cannot refuse a second write; a unique index can. Q1b confirmed the point landed: deleting the
  flag breaks a drift test and changes no behaviour whatsoever.
- **Q2** — mistaking a *mechanical* consequence for the *reason*. Both wrong answers were true
  statements: a large row is awkward, and the drift test would indeed go red. But a drift test you
  can satisfy in thirty seconds by editing the mirror is not why ADR-0019 rejects the idea in its
  strongest language. Q2c reframed it as what the user experiences — a stored citation that
  silently means something different than it did when it was written — and that is what AI-ARC-006
  exists to prevent.
- **Q3** passed first time: `CoreModule.provideDatabase` throws while locked, so injecting before
  checking would take the process down from a job the user never started.

The two failures were both the same species of error — reading the *stated* intent as the
*enforced* one. Worth noting given that §10, written in this same session, says in its own text
that it is agent-followed and not build-enforced.
