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
  (`docs/adr/`). **Adding, removing or swapping a dependency needs a row in `DECISIONS.md`**
  naming what it was chosen over — no exceptions, including test-only libraries. **Changing a
  runtime call path needs `FLOW.md` updated in the same commit** (§10).
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
- [ ] Unit tests for domain logic (≥ 85% engine coverage; **100% money math**) — run them with
      **`./gradlew unitTests`**, never `testDebugUnitTest`, which is an Android variant task and
      silently skips the pure-Kotlin modules and `:lint` entirely (issue 2.6).
- [ ] UI state covered by ≥ 1 Compose UI test or screenshot test.
- [ ] Works offline; verified with an airplane-mode test case.
- [ ] Accessibility scan passes; strings externalised; dark mode verified.
- [ ] No new lint/detekt warnings; CI green; reviewed (or solo self-review checklist).
- [ ] **Session file written** in `docs/sessions/`, and any new decision indexed in `DECISIONS.md` /
      any changed call path reflected in `FLOW.md` (§10).
- [ ] **Every major change in this session was quizzed and passed** (§10) — recorded honestly,
      including the ones that needed a second attempt.

## 9. Where things live

```
docs/init/*.pdf         the SRS (master blueprint, source of truth)
docs/templates/         ENGINE.md and other doc templates
docs/adr/               Architecture Decision Records
DECISIONS.md            project-wide decision index — why this approach, why this library (§10)
FLOW.md                 how execution travels — entry points and call paths (§10)
docs/sessions/          one file per working session — decisions, flow delta, quiz (§10)
ai/                     runtime AI files the app LOADS (rules, workflow, tools, knowledge)
.claude/skills/         project skills for recurring dev tasks (new-engine, add-rule, audit)
.claude/commands/       slash-command dev workflows (/pre-merge, /new-feature)
.github/workflows/      CI (build · test · lint · screenshot diff)
:app :core:* :domain:* :data:* :ml:* :feature:* :sync:* :widget   (to be built, §21.2)
```

---

## 10. Session records and the quiz gate

> **Agent-followed, not build-enforced.** Nothing in Gradle or CI fails because a session file is
> missing or a quiz was skipped. This section says so plainly rather than implying a gate that does
> not exist — this repo has already shipped one governance gate that nothing ever ran. If you want
> it enforced, extend `scripts/check_issue_docs.py` and wire it into `ci.yml`.

### 10.1 The three records

| File | Scope | When it changes |
|------|-------|-----------------|
| **`DECISIONS.md`** (root) | The whole project | A new approach decision, or **any** dependency added/removed/swapped |
| **`FLOW.md`** (root) | The whole project | A runtime call path changes — new entry point, new worker, reordered spine |
| **`docs/sessions/YYYY-MM-DD-<slug>.md`** | One working session | Every session that changes code |

`DECISIONS.md` is an **index**: when an ADR exists it gets one line and a link, never a restatement.
The session file holds the full reasoning; the root file holds the pointer.

**The session file has four sections, in this order:**

1. **Decisions this session** — each decision with its full reasoning. The one-liners go up to
   `DECISIONS.md`.
2. **Flow changed this session** — the call chains added or altered, as arrow chains matching
   `FLOW.md`'s style.
3. **Code changed this session** — path → what it does now, one row each.
4. **Quiz** — every question asked, the answer given, and whether it passed first time.

Write it at the end of the session and commit it with the other docs. **No separate commit.**

### 10.2 What counts as major

A change is **major** — and therefore quizzed — if it does any of these:

- adds, removes or swaps a **dependency**;
- adds or alters a **database migration** or schema;
- adds or changes a **rule row in `ai/`**;
- adds a **new engine, or a new method on an engine's public interface**;
- touches a **money, crypto, or privacy** path (`Money` arithmetic, Keystore/Tink, consent, PII).

Not major: tests, docs, string resources, renames, formatting, and any change that only follows a
pattern already established elsewhere in the same module.

### 10.3 The quiz

**Purpose.** Every other gate in §8 checks the code. This one checks that the person shipping it
understands it. Code the coder cannot explain is code nobody can maintain, and an AI that writes
faster than its user can read is how a codebase becomes someone else's.

**When.** Immediately before committing a major change — not batched to the end of the session.
The point is to gate the commit, and a quiz about code written three hours ago tests memory, not
comprehension.

**Form.** Three multiple-choice questions via `AskUserQuestion`, about the change just written.

**What makes a fair question.** Each one must be about **mechanism or consequence** — *what stops
this from firing twice*, *what breaks if this line is deleted*, *why does this live here and not
one layer up* — and answerable by reading the diff. **Never trivia**: not a name, not a line count,
not syntax. If the answer is a fact rather than an understanding, the question is bad and must be
replaced. A plausible-but-wrong option must be genuinely plausible — the one a careful reader would
pick if they had missed exactly one thing.

**On a wrong answer.** Explain the specific point that was missed, then ask a **fresh** question on
that same point. Repeat until it is passed. **The commit does not happen until then.** The change is
not quietly simplified to dodge the question either — the code stays, the understanding catches up.

**Recorded honestly** in the session file, fails included. A quiz log with no failures in it is
either a lucky month or a gate being marked green, and §8's whole point is that the second one is
worse than having no gate at all.
