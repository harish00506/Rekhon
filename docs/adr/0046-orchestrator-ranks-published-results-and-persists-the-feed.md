# ADR-0046 — The orchestrator ranks what the engines already published, persists the feed, and leaves notifying to 9.6

- **Status:** accepted
- **Date:** 2026-09-20
- **Deciders:** Harish G (solo)
- **SRS refs:** §7.1 (AI-ARC-001/003/005/006), §7.2, §20.2 (`insights`), DB-003, DB-005,
  FR-AI-001/002, FR-HOME-001, P-02, P-03, P-08, CLAUDE.md §6; issue 9.5. Builds on ADR-0006 (the
  demo leaves nothing behind), ADR-0017 (typed mirrors), ADR-0043/0044/0045 (the engines it reads)

## Context

§7.2 defines the Insight Orchestrator:
- five triggers — a committed transaction (debounced 5 s), day rollover, external data refresh,
  manual pull-to-refresh, and a weekly deep job;
- a pipeline: build the feature snapshot (L2) → run rules (L3) → run due predictions (L4) →
  decision ranking (L5) → **persist insights** → notify;
- budgets: under 200 ms after a transaction, under 3 s for the weekly job;
- deduplication: one insight per fingerprint (type + subject + period); a dismissed fingerprint is
  suppressed for its snooze window.

The acceptance criteria are narrower: run the engines in layer order with provenance on every
insight, rank deterministically, recompute on new data, and prove it with a golden file.

Most of the pipeline already exists. Stages 1–4 are the engines and repositories built in issues
3.x–9.4, each already publishing a result with provenance. What does not exist is the end: deciding
what is worth saying, in what order, and remembering that the user has already said "not now".

Four questions had no answer in the SRS.

## Decision

**1. The orchestrator ranks published results; it computes nothing.** `:domain:engines:insight`
(AI-ORCH 1.0, L5) takes small signal types declared in its own module and hands back ranked cards.
Every card repeats a figure its source engine published and carries that engine's id, version, rules
and confidence (AI-ARC-003/006). The engine depends on **no other engine module**: one that imported
AI-FCT and AI-FHS could drift into re-deriving what they own.

The stages are run by the repository, in §7.2's order, by reading the repositories that already own
them (AI-ARC-001). No DAO but its own table is touched.

**2. Six insight types in v1.0**, each from an engine that exists: a crunch day (CRITICAL); an
overspent budget, a short emergency fund, a goal behind its plan (WARNING); a month the season makes
dearer, and the health score's biggest lever (INFO). Silence is a claim too — a fund that is funded
and a budget within its plan raise nothing.

**3. The order is a rule, and the tie-breaks are total.** RULE-INS-RANK: severity, then the amount
at stake descending, then the fingerprint. §7.2 states no order, so this is minted; the drift test
pins the rule's own words to the `Severity` enum so the two cannot part. The dashboard shows
`dashboard_max` = 3 (FR-HOME-001's "top 3").

**4. The feed is persisted, with typed columns rather than a JSON blob.**
- A new table, `insight` at schema 23, one row per fingerprint per profile (a unique index, as
  §20.2 asks). AI-ARC-005's reason is that the UI must never await computation; the stronger reason
  is that **a dismissal needs somewhere to live**.
- §20.2 sketches `evidence_json`, and DB-005 then has to version those blobs. Every figure a card
  shows is one of a fixed few — an amount, a supporting amount, a date, a count — so they are
  columns, and DB-005's question does not arise.
- A recomputation **updates** the row: the id, the user's verdict and its suppression are kept and
  only the figures move. Without that, dismissing a card would last until the next refresh.
- A card whose fact no longer holds is deleted, unless the user has ruled on it and the ruling is
  still in force.
- A dismissal or a snooze suppresses the fingerprint for seven days (RULE-INS-DEDUP; §7.2 names no
  length). The suppression is applied **in the DAO's query**, so no caller can forget it.

**5. Triggers: the daily job and opening the dashboard.** `InsightRefreshWorker` runs once a day
(§7.2's `day_rollover`), and the dashboard refreshes when it opens (the manual trigger). The
debounced transaction trigger and the weekly deep job are deferred: both need the same scheduling
machinery the notification engine (9.6) is about to build, and adding a second, differently-shaped
debounce now would be two answers to one question.

**6. Nothing is notified.** §7.2's stage 6 hands off to AI-NTF, which is issue 9.6. Raising a
notification here would be that policy invented in the wrong place.

## Deferred (each with what it needs)

- **The debounced `txn_committed` trigger and the weekly deep job** — 9.6's scheduler.
- **Notifications** (§7.2 stage 6, §17.2) — 9.6.
- **An insight feed screen** with the whole list and an "act" affordance that navigates: the
  dashboard shows the top three today, and the Advisor hub (FR-AI-001) is its own issue.
- **`insight_feedback`** (§20.1) — useful once there is something to learn from; today the status
  column records the verdict.
- **More insight types** — card utilisation and revolving interest (the health lever covers
  utilisation for now, and the card engine does not detect revolving), anomalies (§ANM), market and
  tax insights.
- **The compute budgets** (§7.2: under 200 ms incremental, under 3 s weekly) are not measured. The
  pipeline reads published flows and writes a handful of rows; a benchmark belongs with the
  debounced trigger that makes the number matter.

## Consequences

- The dashboard gains "What needs attention": up to three cards, each with its finding, one
  recommended action, the engine and rules behind it, and two ways to put it away.
- The demo wipe and the archive wipe both clear `insight`, and `countRowsFor` counts it, so the
  residue ADR-0006 forbids cannot accumulate.
- rules-kb moves to 1.18.0, and eight mirrors restate it.

## Alternatives considered

- **Compute the feed on every read, without a table.** Simpler, and it makes dismissal impossible:
  there is nowhere to record it. Rejected — §7.2 says persist, and the reason is this.
- **One row per recomputation, deduplicated at read time.** The table would grow without bound and
  the unique index §20.2 asks for would have to go. Rejected.
- **Let the orchestrator read the DAOs directly.** It would be a second definition of runway,
  budget and forecast. Rejected, as in ADR-0007.
- **`evidence_json` as §20.2 sketches it.** It would need DB-005's versioning to render old
  insights, to store what typed columns hold today. Rejected.
