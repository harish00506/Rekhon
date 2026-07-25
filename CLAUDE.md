# AI Personal CFO — Project Rules for AI Agents

> **This file is loaded every session. It is binding.** It tells any AI agent (and any
> human) how to write and maintain code in this repository. When this file and a chat
> instruction disagree, ask — do not silently override a rule here.

**What this is:** an Android-first, AI-driven personal-finance app for India that acts as
a full-time CFO — it classifies, forecasts, advises, and *explains*, on-device and
privacy-first. It is greenfield: the spec exists, the code is being built to it.

**Sources of truth (read before changing behaviour):**
- `docs/init/AI_Personal_CFO_SRS_v1.7.pdf` — the master blueprint. Every requirement has
  an ID (e.g. `FR-TXN-004`, `AI-ARC-004`). **Cite the ID** in code comments and commits.
- `ai/` — the AI subsystem's runtime files (rules, workflow, skills-as-tools, knowledge).
  See `ai/README.md`. These are **data the app loads**, not app code.

---

## 1. Golden rules (never violate — these override feature requests, §1.3)

| ID | Rule | What it means for your code |
|----|------|------------------------------|
| **P-03** | Numbers from math, words from AI | LLMs never compute amounts/scores/forecasts. Deterministic engines produce every number; the LLM only verbalises. Any code that lets the model output an unverified figure is wrong. |
| **AI-ARC-004** | Guardrail every LLM output | LLM text passes the numeric guardrail (`ai/chat/guardrail.md`) before display. |
| **P-01** | Privacy first | No financial data leaves the device without explicit, revocable, per-feature consent. Default fully offline. Never add a network call on a core path. |
| **P-07** | Advice, never orders | The app recommends and simulates; the user decides. No auto-executed money movement in v1. |
| **P-04** | Offline-first | Every core feature works in airplane mode. The app must build and pass all tests with the backend absent. Network features degrade to cached data + staleness labels. |
| **P-08** | Deterministic & testable | Every engine: fixed input → fixed output. Randomness only via an injected, seedable source. |
| **P-02** | Show the work | Every recommendation shows inputs + the rule/model that fired + a plain-language reason. No black-box verdicts. |

---

## 2. Architecture (binding — §21.2, §7.1)

**Module dependency is one-way:** `feature → domain → data/core`. Feature modules never
depend on each other; cross-feature navigation goes through the nav graph with typed routes (ARC-001).

- `:core:model` and `:domain:*` are **pure Kotlin (JVM), no Android imports** — enforced by
  Gradle (ARC-002). Keeps engines portable and unit-testable.
- **Every engine exposes exactly one public interface + result types**; implementations are
  `internal`, injected via Hilt constructor injection (ARC-003, no service locators).
- Repositories are the **only** classes that touch DAOs or network. ViewModels see domain
  models only — never Room/Retrofit types (ARC-005).
- UI state = one immutable data class per screen as `StateFlow`; events flow up via a sealed
  interface. No LiveData, no leaking mutable state (ARC-004).
- All async work uses structured concurrency (injected scopes). `GlobalScope` is banned (ARC-006)
  — **lint-enforced**: `CfoGlobalScope` fails the build (`:lint`, issue 1.5).

**The AI is a layered pipeline, not one model (§7).** Layer N depends only on layers below.
`L1 data → L2 analytics → L3 rules → L4 predictions → L5 decisions → L6 LLM`.
Every engine result carries **provenance**: `engineId, engineVersion, inputWindow, computedAt,
confidence, evidence` (AI-ARC-003). Engine versions are stored with every result so old
insights stay reproducible (AI-ARC-006). See `ai/architecture/ai-architecture.md`.

---

## 3. Money & time — the two classic bug factories (§21.4, review-blocking)

- **MNY-001:** Money is `Long` minor units (paise) end-to-end (DB → engine → UI). Use the
  `Money` value class. **Any `Double`/`Float` touching a monetary value fails the build** —
  `CfoMoneyAsFloatingPoint` (`:lint`, issue 1.5) flags a floating-point declaration with a
  monetary name; the word list lives in [ADR-0001](docs/adr/0001-custom-lint-module-and-money-heuristic.md).
  Division uses explicit `HALF_EVEN` rounding + remainder distribution for splits.
- **MNY-002:** Percentages/rates are integer **basis points (bps)** in engines.
- **TIM-001:** Timestamps are UTC epoch millis. All calendar logic (day rollover, month
  boundaries, due dates) uses the profile time zone via an **injected `Clock`** — never
  `System.currentTimeMillis()` in domain code — **lint-enforced**: `CfoWallClockInDomain` fails the
  build for `:domain:*` and `:core:model` (`SystemClock` in `:core:common` is the one sanctioned
  wall-clock read).
- **TIM-002:** Date-only fields are ISO `LocalDate` strings, never midnight timestamps.

---

## 4. Testing (§21.5) — no feature is done without it

- **Engines:** golden-file tests (fixed input snapshot → expected result JSON), property
  tests for math (splits always sum, forecast monotonic identities), seeded-determinism
  tests. **Coverage ≥ 85%; money math 100%.**
- **Repositories:** Room in-memory + a migration test against a real schema fixture for
  *every* version bump. Destructive migrations are forbidden (DB-003).
- **ViewModels:** Turbine on `StateFlow`; assert the full `UiState` sequence incl. loading/error.
- **UI:** Compose tests for critical flows (add txn ≤ 3 taps, onboarding, purchase advisor);
  Paparazzi screenshot tests for design-system components in light/dark/200% font.
- **AI evaluation:** frozen labelled datasets (categorisation ≥ 92%, receipts ≥ 95%, forecast
  backtests). Regression thresholds block merges.

This aligns with test-driven-development: write the failing test first, then the code.

---

## 5. Coding standards (§21.6, binding checklist)

- **Style:** ktlint official; functions ≈ ≤ 40 lines (detekt); no wildcard imports; organise
  by feature, not by type. `.editorconfig` holds the shared baseline.
- **Nullability:** domain models are null-hostile — model absence with sealed types, not null.
  Platform types isolated at the network/DB edge.
- **Errors:** no exceptions across layer boundaries. Repositories/engines return
  `Result<T, AppError>` (sealed). Crashes only for programmer errors.
- **Immutability:** `data class` with `val` everywhere; collections exposed read-only.
- **Concurrency:** dispatchers injected (`DispatcherProvider`); DAOs are `suspend`/`Flow`;
  no `runBlocking` outside tests.
- **Logging:** structured logger; **PII/amount logging is banned** — **lint-enforced**:
  `CfoPiiInLogs` fails the build on a `Log.*`/`println` whose arguments name money or personal
  data. Security events go to `audit_log`.
- **Strings & resources:** every user-visible string in `strings.xml` with ICU plurals — in
  `:feature:*`, **lint-enforced** by `CfoHardcodedUiString` (`@Preview` sample data exempt); every
  colour/dimension from theme tokens. Indian digit grouping (₹1,23,456.78) via `MoneyFormatter`.
- **Docs:** every engine module has an `ENGINE.md` (contract, formula, assumptions, version
  log) — template in `docs/templates/ENGINE.md`. Any deviation from the SRS needs an **ADR**
  (`docs/adr/`).
- **Doc comments (global rule):** every class and function gets a doc comment covering
  *why / what / result / changelog / inputs / outputs*. Match the surrounding code's density.

> Rule of thumb for anything not covered here: **prefer the boring, testable, explainable
> option. Cleverness is a cost.**

---

## 6. Changing the AI's behaviour → change data, not code

The rulebook, classification, market signals, and tax parameters are **data rows** in `ai/`,
not hardcoded logic. To change a threshold or add a rule, edit the relevant file in `ai/`
(the `add-rulebook-rule` skill walks this) — **never** hardcode a financial number in an
engine. Every row is versioned and cited by ID in evidence (AI-ARC-006). See `ai/README.md`.

---

## 7. Git & workflow

- **Branch model (GitFlow-lite):** `feature/<id-dashes>-<slug>` → **`dev`** (integration) →
  **`stage`** (live testing) → **`main`** (releases). **`main` and `stage` are protected** — no
  direct pushes or force-pushes; changes land only via reviewed, CI-passing PRs. Day-to-day work
  is committed on `dev` or a feature branch — **never commit directly to `main`/`stage`.**
- **Conventional commits** (`feat:`, `fix:`, `refactor:`…); **each commit references its
  requirement ID(s).** Feature-flag incomplete work — keep feature branches short-lived.
- `main` and `stage` are always releasable/deployable. Do not commit or push unless asked; if
  asked while on `main` or `stage`, branch to `dev`/a feature branch first.
- **Promote by PR:** `feature → dev` (per issue) → `dev → stage` (live testing) → `stage → main`
  (release). Before opening a PR, run the **Definition of Done** (`/pre-merge`, and the PR template).

## 8. Definition of Done (§4.2 — applies to every feature)

- [ ] Requirement implemented and traceable (commit references the FR/AI id).
- [ ] Unit tests for domain logic (≥ 85% engine coverage; **100% money math**).
- [ ] UI state covered by ≥ 1 Compose UI test or screenshot test.
- [ ] Works offline; verified with an airplane-mode test case.
- [ ] Accessibility scan passes; strings externalised; dark mode verified.
- [ ] No new lint/detekt warnings; CI green; reviewed (or solo self-review checklist).

## 9. Where things live

```
docs/init/*.pdf         the SRS (master blueprint, source of truth)
docs/templates/         ENGINE.md and other doc templates
docs/adr/               Architecture Decision Records
ai/                     runtime AI files the app LOADS (rules, workflow, tools, knowledge)
.claude/skills/         project skills for recurring dev tasks (new-engine, add-rule, audit)
.claude/commands/       slash-command dev workflows (/pre-merge, /new-feature)
.github/workflows/      CI (build · test · lint · screenshot diff)
:app :core:* :domain:* :data:* :ml:* :feature:* :sync:* :widget   (to be built, §21.2)
```
