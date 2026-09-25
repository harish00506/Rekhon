<!--
  Why:  CLAUDE.md §10 — one file per working session that changes code.
  What: issue 10.1 — AI-PA, §13's Purchase Advisor, its kept trace, and the screen that shows the
        working.
  Result: a reader can see why a warning is not a refusal, what urgency is not allowed to do, and
          why the trace is two typed tables rather than a JSON blob.
  Changelog: 2026-09-25 — Created.
-->

# 2026-09-25 — The Purchase Advisor (issue 10.1, ADR-0049)

**Branch:** `feature/10-1-purchase-advisor-ai-pa-trace-card` off `dev` (`052de43`)
**Versions:**
- **VERSION** 0.9.7 → **0.10.0** (Epic 10 opens)
- **versionCode** 43 → 44
- **Schema** 24 → 25 (`purchase_trace`, `purchase_trace_gate`)
- **rules-kb.json** 1.20.0 → **1.21.0**
- `AI-PA` 1.0 (new)

---

## 1 · Decisions this session

The full argument for each is in ADR-0049.

- **Seven gates over published signals, and no engine imported.** AI-PA takes a `PurchaseSignals`
  the repository fills from AI-EMF, AI-STS, AI-FCT, the obligations the health score already
  computes, AI-GOAL and AI-BUD. Importing those engines would have let an L5 engine re-derive what
  they own — ADR-0046's rule, kept.
- **A warning is not a refusal.** §13.1 writes "fail if liquidAfter < emergencyFloor → verdict ≤
  STRETCH", which is a cap: the money is there, and what it costs is the safety net. So crossing the
  emergency fund **warns**; only a price the balance cannot cover **fails**. The user decides (P-07).
- **Attribution is honest.** Gate 2 reports the crunch days the purchase *adds* and states
  separately how many were already there. Blaming a purchase for a crunch that was coming anyway
  would answer "not now" to everyone already in trouble — precisely when the advice matters.
- **Urgency lifts one step and never a hard fail.** In practice that means it lifts a stretch to
  comfortable and nothing else, because every failure is a hard failure. Both switches are rulebook
  params.
- **Opportunity cost is shown, never decisive.** An app that refused purchases because the money
  could have been invested would refuse every purchase.
- **A goal delay is the price over everything going to goals each month** — apportioning by
  contribution share gives every goal the same delay, so the card states one number.
- **The alternatives refuse to invent.** When a gate objects whatever the price is, there is no
  comfortable price and the card says nothing rather than naming one.
- **The trace is two typed tables, not a blob.** `purchase_trace` and `purchase_trace_gate` at
  schema 25; no JSON, so DB-005's versioning question never arises. The outcome repeats across a
  gate's figure rows — denormalised on purpose, to keep the archive, demo wipe and restore drill to
  two places instead of three. Both tables carry tombstones, so neither needs an invariant exemption.
- **Deferred:** the timing gate's seasonal signal (AI-SEAS is not wired; inventing one here would be
  the re-derivation ARC-001 forbids), §13.2's one-tap actions, EMI-vs-cash interest (10.3), and
  §13.3's buy list and interview (10.2).

**What the tests found, which is the part worth keeping:**
- The **property tests** caught two real bugs: the card naming a "comfortable price" that still came
  back a stretch (a gate can object at any price), and a negative cap rendered as ₹0.
- The **read-back test** caught provenance losing RULE-COOL-OFF, which belongs to no gate — the
  card's own citations are now a column.
- The first **golden run** caught an incoherent fixture, not a bug: a household with ₹1,00,000 liquid
  but a ₹12,000 forecast low point, which made every large purchase overdraw the forecast.

## 2 · Flow changed this session

```
DashboardScreen "Can I afford this?" → CfoRoute.PurchaseAdvisor → AdvisorScreen
└─ AdvisorViewModel (rupees → paise, nothing else computed)
   → PurchaseAdvisorRepository.advise()
       ├─ one consistent read of the six sources
       ├─ PurchaseAdvisorEngine.advise()  — seven gates, worst-of verdict, urgency within limits
       └─ purchase_trace + purchase_trace_gate in one transaction
   ⇣ verdict · gate table · impact strip · alternatives · "From AI-PA v1.0 · <rules>"
```

`FLOW.md` §2.13 holds the full chain.

## 3 · Code changed this session

| Path | What it does now |
|------|------------------|
| `ai/rules/rules-kb.json`, `rulebook.md`, eleven `*Rules.kt` mirrors | 1.21.0: RULE-PA-GATES, RULE-PA-OPPCOST |
| `domain/engines/purchase/` (new) | AI-PA: the gates, the verdict, the card, the mirror, `ENGINE.md`, 51 tests with a golden file and its oracle |
| `core/database/**` | schema 25: two trace tables, `PurchaseTraceDao`, `MIGRATION_24_25` |
| `data/repository/PurchaseAdvisorRepository.kt` (new) | one consistent read, the engine, and the kept card |
| `data/repository/{Archive,ArchiveRepository,DemoModeRepository,RepositoryFactory}.kt`, `DrillFixture.kt` | both tables backed up, restored, wiped and drilled |
| `feature/advisor/` (new) | the question, the card with its working, and the history |
| `feature/dashboard/**` | a "Can I afford this?" destination |
| `app/.../navigation/*`, `di/RepositoryModule.kt`, `app/build.gradle.kts` | the typed route and the DI wiring |
| `docs/adr/0049-…`, `DECISIONS.md`, `FLOW.md` §2.13, `ai/orchestrator/engine-registry.yaml` | the records |
