<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 9.5 — AI-ORCH, §7.2's Insight Orchestrator, and the persisted feed it writes.
  Result: a reader can see which of §7.2's pipeline this issue built, why the feed is a table rather
          than a computation, and what the orchestrator deliberately does not do.
  Changelog: 2026-09-20 — Created.
-->

# 2026-09-20 — The Insight Orchestrator (issue 9.5, ADR-0046)

**Branch:** `feature/9-5-insight-orchestrator-feed` off `dev` (`df815da`) · **VERSION** 0.9.4 →
**0.9.5** · **versionCode** 40 → 41 · **Schema 22 → 23** (`insight`) · **rules-kb.json** 1.17.0 →
**1.18.0** · `AI-ORCH` 1.0 (new)

---

## 1 · Decisions this session

The full argument for each is in ADR-0046.

- **The orchestrator ranks what the engines already published; it computes nothing.** Every card
  repeats a figure and carries that engine's id, version, rules and confidence. The engine imports
  **no other engine module** — one that could reach AI-FCT and AI-FHS could start re-deriving them.
- **Six insight kinds in v1.0**, one per engine that exists: a crunch day (critical); an overspent
  budget, a short emergency fund, a goal behind its plan (warnings); a dearer month and the health
  score's biggest lever (info). Silence is a claim too: a funded fund raises nothing.
- **The order is a rule with total tie-breaks** (RULE-INS-RANK: severity, amount, fingerprint), so
  the same facts always give the same feed. The dashboard shows three (FR-HOME-001).
- **The feed is a table, not a computation.** AI-ARC-005 says the UI must not wait for a pipeline;
  the stronger reason is that a dismissal needs somewhere to live. Schema 23's `insight` holds one
  row per `type+subject+period` per profile, and a recomputation **updates** it — keeping the id,
  the user's verdict and its suppression, and moving only the figures.
- **No `evidence_json`.** §20.2 sketches a blob and DB-005 then has to version it. Every figure a
  card shows is one of a fixed few, so they are typed columns and the question does not arise.
- **`insight` is exempt from the soft-delete invariant**, argued in `MigrationSafetyTest` like the
  four before it: the unique index counts tombstones, so a soft-deleted card could never be raised
  again — and an overspend corrected in March must be able to return in April.
- **Triggers: the daily worker and opening the dashboard.** The debounced transaction trigger and
  the weekly deep job wait for 9.6's scheduler rather than growing a second, differently-shaped
  debounce here.
- **Nothing is notified.** §7.2's last stage hands off to AI-NTF, which is issue 9.6.
- **Deferred with triggers:** notifications; the full feed screen and the Advisor hub;
  `insight_feedback`; more insight kinds (card utilisation, anomalies, market, tax); the §7.2
  compute-budget benchmarks, which belong with the trigger that makes them matter.

## 2 · What the tests caught

The engine matched its independent oracle on the first run, so each gate was checked to fail:
- removing the severity order failed the golden, behaviour **and** property tests;
- raising cheaper seasonal months failed the golden and behaviour tests;
- editing the KB's `dashboard_max` alone failed the drift test;
- in the repository, a recomputation that forgot the stored verdict failed the dismissal test, and
  one that minted a new id failed both the dedup and dismissal tests.

All were reverted. Two of my own expectations were wrong before their first run: a warning order
(₹2,500 a month outranks ₹1,250 over) and a snooze/act pair I had the wrong way round.

**The full gate found two real gaps the unit tests could not.** `MigrationSafetyTest` refused the
new table for having no tombstone — which forced the exemption to be *argued*, and the argument is
what produced the dismissal-as-status design in the first place. Then `BackupRestoreDrillTest`
refused a fixture that did not fill every table, which is how `insight` reached the archive, the
restore and the demo wipe in the same commit rather than three issues later.

## 3 · Flow changed this session

New `FLOW.md` §2.10 — **a new shape: a read that writes.**

```
InsightRefreshWorker (daily) ┐
DashboardViewModel.init      ┘→ InsightRepository.refresh()
   → read each published stage in §7.2's order (forecast · health · fund · budgets · goals)
   → InsightSignals.* → InsightEngine.insights()     rank, fingerprint
   → upsert by fingerprint, keeping the verdict; delete the stale
DashboardViewModel.observeInsights() → insightDao().observeFeed()   suppression is in the query
  ⇣ InsightFeedSection → InsightDismissed / InsightSnoozed → status + suppressed_until
```

## 4 · Code changed this session

| Path | What it does now |
|------|------------------|
| `domain/engines/insight/**` (new) | `InsightEngine` + signal types, `InsightRules`, `RankedInsightEngine`, `ENGINE.md`; tests: behaviour (15), property (6 × 300), golden with `insight_oracle.py`, drift (7) |
| `ai/rules/rules-kb.json`, `ai/rules/rulebook.md` | `RULE-INS-RANK`, `RULE-INS-DEDUP`; 1.18.0 |
| eight `*Rules.kt` mirrors | `RULEBOOK_VERSION` 1.18.0 (no mirrored row changed) |
| `core/database/**` | `InsightEntity`, `InsightDao`, migration 22→23, the demo wipe, `countRowsFor`, the archive select/insert; round-trip and safety tests |
| `data/repository/.../InsightRepository.kt` (+ test, 14) | `InsightSignals`, `RoomInsightRepository` — refresh, feed, verdicts |
| `data/repository/.../Archive.kt`, `ArchiveRepository.kt`, `DrillFixture.kt` | the feed is backed up, restored and wiped with the profile |
| `app/.../work/InsightRefreshWorker.kt` (+ test, 3), `CfoApplication.kt` | the daily trigger |
| `app/.../di/RepositoryModule.kt`, `RepositoryFactory.kt` | the engine and the repository |
| `feature/dashboard/.../InsightFeedSection.kt` (new, + test, 6), `DashboardScreen/UiState/ViewModel`, `strings.xml` | "What needs attention", with Later and Dismiss |
| `settings.gradle.kts`, `ai/orchestrator/engine-registry.yaml` | the module; AI-ORCH's row |
| `docs/adr/0046-…`, `DECISIONS.md`, `FLOW.md` | the decision, its row, §2.10 |

## 5 · Quiz

**Outcome: not yet taken.** The answers in italics are the author's. Record the developer's own pass
here, fails included.

1. Why is the feed a table rather than something computed on every read? *(AI-ARC-005, and because a
   dismissal needs somewhere to live — a recomputed feed has nowhere to remember "not now".)*
2. Why does `insight` have no `deleted_at_utc_millis` when almost every other table does? *(The
   unique fingerprint index counts tombstones, so a soft-deleted card could never be raised again.)*
3. A user dismisses an overspent-budget card and the budget gets worse. What do they see tomorrow,
   and in a fortnight? *(Nothing tomorrow — the dismissal holds for seven days and the recomputation
   keeps it — then the card again, with the newer figure, on the same row.)*
4. Why does the health-lever card sort below every budget card? *(It carries no amount, and the
   rule puts "no amount" last within a severity.)*
5. Why does this issue send no notification, when §7.2's pipeline ends in one? *(Stage 6 hands off
   to AI-NTF; notification policy is §17.2's and issue 9.6's.)*
