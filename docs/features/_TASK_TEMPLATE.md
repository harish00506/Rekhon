<!--
  TEMPLATE — copy to docs/features/<feature-id>-<slug>/tasks/<task-id>-<slug>.md
  A TASK is the smallest shippable unit of work: one focused change, independently
  testable and verifiable, landable in a single reviewable slice. If a task cannot be
  done in one incremental slice with its own tests, split it. Delete this comment when filled.
-->

# Task <epic>.<feature>.<task> — <title>

> One-line objective: <what this task delivers, in a single sentence>

## 1. Meta
| Field | Value |
|-------|-------|
| **Task ID** | `<E.F.T>` (e.g. `1.1.2`) |
| **Feature** | [`<E.F> <feature name>`](../feature.md) |
| **Epic** | `<E>` — <name> (SRS Phase <n>) |
| **Status** | `todo` · `in-progress` · `blocked` · `in-review` · `done` |
| **Priority** | `MUST` · `SHOULD` · `MAY` (from the SRS requirement) |
| **Size** | `XS` · `S` · `M` · `L` (L should usually be split) |
| **Module(s)** | `:core:model`, `:domain:engines:…`, `:feature:…` (which Gradle module) |
| **Branch** | `feature/<E-F-T>-<short-slug>` |

## 2. Traceability (SRS — read before coding)
| SRS ref | Requirement ID(s) | Acceptance-bearing text |
|---------|-------------------|--------------------------|
| §<NN>   | `<FR-… / AI-… / MNY-… / TIM-… / RULE-…>` | <quote the binding line> |

**AI files touched:** <e.g. `ai/rules/rules-kb.json` row RULE-…, or "none">

## 3. Objective — what this task will do
<2–4 sentences. The concrete behaviour or artifact produced. Precise enough that "done"
is unambiguous.>

**Why now / rationale:** <the problem it solves; what it unblocks downstream.>

## 4. Scope
**In scope**
- <bullet — exactly what is built here>

**Out of scope (non-goals — YAGNI, `/ponytail`)**
- <bullet — explicitly deferred, with the task/feature that will do it>

## 5. Dependencies
- **Blocked by:** `<task IDs that must land first>` — stop if unmet.
- **Blocks:** `<task IDs that depend on this>`.

## 6. Skills & commands needed (step through in this order)
| Phase | Skill / command | Why |
|-------|-----------------|-----|
| Plan | `planning-and-task-breakdown` / **Plan** agent | design before coding |
| Ground | `source-driven-development` | official Compose/Room/Hilt/ML-Kit docs, not stale patterns |
| Build | `test-driven-development`, `incremental-implementation` | failing test → code → green, small slices |
| <special> | `new-engine` / `add-rulebook-rule` | <only if the task adds an engine or a rule row> |
| Guard | `money-time-audit` | if the task touches amounts or dates |
| Simplify | `/ponytail` | keep it the laziest thing that works |
| Verify | `/run`, `/verify` | run the app and observe the change |
| Review | `code-review`, `security-review` | before merge (security if crypto/keys/consent) |
| Ship | `/pre-merge` | Definition-of-Done gate |

## 7. Rules to follow (binding — from `CLAUDE.md` / SRS)
List only the rules this task must actively honour, with the concrete implication:
- **P-03** numbers from math — <implication, or "n/a">
- **MNY-001/002** `Money` = Long paise, rates bps; no `Double`/`Float` on money — <implication>
- **TIM-001/002** injected `Clock`; no `System.currentTimeMillis()` in domain — <implication>
- **ARC-002/003/005** pure-Kotlin engine; provenance on results; repos own DAO/network — <implication>
- **P-01/P-04** privacy & offline — <implication>
- <other IDs as relevant>

## 8. Implementation notes
- **Files to add/change:** `<paths>`.
- **Interfaces / signatures:** <the public shape, e.g. `interface X { fun y(...): Result<Z, AppError> }`>.
- **Approach:** <the boring, testable path. Note the standard-library/first-party choice used.>
- **Doc comments (global rule):** every new class/function gets why / what / result / changelog / inputs / outputs.

## 9. Acceptance criteria (Given / When / Then — testable)
- [ ] **AC1** — Given <state>, when <action>, then <observable result>.
- [ ] **AC2** — …
- [ ] Works offline where a core flow is affected (P-04).

## 10. Test cases to write (§21.5 — required)
Every new/changed function ships with a doc comment **and** tests covering normal + edge +
boundary + empty + error paths. Money math coverage = **100%**; engine coverage ≥ **85%**.

| # | Kind | Given | Expect | Notes |
|---|------|-------|--------|-------|
| T1 | normal | <input> | <output> | golden-file if engine |
| T2 | edge | <empty / cold-start> | <labelled estimate / safe default> | |
| T3 | boundary | <threshold ± 1> | <pass/warn/fail flips correctly> | |
| T4 | error | <invalid input> | `Result.Err(AppError.…)`, no throw across boundary | |
| T5 | property/determinism | <seeded / identity> | <sum balances / same seed → same output> | if applicable |

**How to run:** `./gradlew <module>:test` (+ `koverVerify`); screenshot `verifyPaparazziDebug`;
E2E `connectedDebugAndroidTest` if a user flow changes.

## 11. Definition of Done (§4.2)
- [ ] Requirement implemented and traceable (commits reference the ID).
- [ ] Tests written & green (engine ≥ 85%, money math 100%); touched suites pass.
- [ ] UI covered by ≥ 1 Compose UI / screenshot test (if UI).
- [ ] **Run on emulator, changed behaviour exercised** (logged in tracker Verification Log).
- [ ] Lint/detekt clean; no new warnings.
- [ ] Docs updated (`ENGINE.md` if engine; ADR if SRS deviation); `VERSION` + `CHANGELOG.md` bumped at ship.

## 12. Risks & edge cases
- <the sharp edges: overflow, rounding, timezone boundary, empty data, migration safety…>

## 13. Artifacts to update on completion
- Tracker: `docs/issues/<id>-<slug>-tracker.md` (Verification Log).
- `CHANGELOG.md` entry under the current epic heading; `VERSION` + `versionName`/`versionCode`.
- `ENGINE.md` / ADR as applicable.
