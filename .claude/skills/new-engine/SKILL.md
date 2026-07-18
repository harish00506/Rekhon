---
name: new-engine
description: Scaffold a new pure-Kotlin domain engine for AI Personal CFO the correct way — one public interface + result types, provenance, Hilt injection, ENGINE.md, and golden-file/property/determinism tests. Use when adding any :domain:engines:* module (classification, forecast, purchase advisor, health score, a new AI capability), or when the user says "new engine", "add an engine", "scaffold engine".
---

# Skill: New Engine

Create a new engine under `:domain:engines:<name>` that obeys the binding architecture
(§7.1, §21.2, §21.6). Engines are the deterministic heart of the app — this is where the
"numbers come from math" guarantee (P-03) is kept.

## Before you start
- Find the engine's SRS section and its ID (e.g. AI-EMF §10). Read the formula there.
- Check `ai/orchestrator/engine-registry.yaml` — add or reconcile the engine's row.
- If the engine reads thresholds, they belong in `ai/rules/rules-kb.json` (data, not code).

## Rules this scaffold MUST satisfy
1. **Pure Kotlin, no Android imports** (ARC-002). Module has no `android` Gradle plugin.
2. **Exactly one public interface + result data classes**; the impl is `internal`,
   `@Inject constructor(...)` (ARC-003).
3. **Injected clock, locale, randomness** — never `System.currentTimeMillis()`,
   `Math.random()`, or `Random()` directly (TIM-001, P-08).
4. **Money is `Money` (Long paise); rates are bps** — no `Double`/`Float` on money (MNY-001/002).
5. **Result carries provenance**: `engineId, engineVersion, inputWindow, computedAt,
   confidence, evidence` (AI-ARC-003).
6. **Errors as `Result<T, AppError>`**, never exceptions across boundaries.

## Steps
1. Create the module `:domain:engines:<name>` (pure Kotlin) and register it in settings.gradle.
2. Define the contract:
   ```kotlin
   /**
    * Why:  <the problem this engine solves, cite SRS id + section>
    * What: <its role in the pipeline layer L?>
    * Result: <what it decides / outputs>
    * Changelog: <YYYY-MM-DD — created>
    * Input:  <each field, meaning, format>
    * Output: <result type, meaning, format>
    */
   interface <Name>Engine {
       fun compute(input: <Name>Input): Result<<Name>Result, AppError>
   }

   data class <Name>Result(
       // domain fields ...
       override val provenance: Provenance,   // engineId, engineVersion, inputWindow, computedAt, confidence, evidence
   ) : EngineResult
   ```
3. Implement it `internal`, inject `Clock`, `DispatcherProvider`, and any KB loaders. Bind in
   a Hilt module.
4. Write `ENGINE.md` from `docs/templates/ENGINE.md` (contract, formula, assumptions, version log).
5. **Tests (required — §21.5):**
   - Golden-file: a fixed input snapshot → expected result JSON. Add at least one realistic,
     one edge (empty/cold-start), one boundary case.
   - Property test for any math identity (sums balance, monotonicity, clamps hold).
   - Seeded-determinism test: same seed → identical output.
   - Money math coverage must be **100%**.
6. Add/curate the engine in `ai/orchestrator/engine-registry.yaml` with its version.
7. Commit as `feat(engine): add <Name>Engine (AI-XXX §NN)` referencing the requirement id.

## Definition of done for this skill
Interface + result + impl + Hilt binding + ENGINE.md + the three test kinds, CI green,
registry updated. No Android import in the module. No raw `Double` on money.
