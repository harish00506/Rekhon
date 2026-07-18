<!--
  Why:  A fast checklist of what to reach for and what never to use, plus the error-handling
        boundaries the AI must respect. The binding version is CLAUDE.md; this is the scannable
        index into it so a reviewer can spot a violation without reading the whole rulebook.
  What: Rules — what to use, what to avoid, AI error-handling boundaries.
  Result: A reader knows the mandated patterns, the banned ones, and where the LLM must stop.
  Changelog:
    2026-07-18 — Created (index into CLAUDE.md §1/§2/§3/§5 + ai/chat/guardrail.md).
-->

# AI Personal CFO — Rules

> **Binding source:** [`../CLAUDE.md`](../CLAUDE.md). **When this and `CLAUDE.md` disagree,
> `CLAUDE.md` wins — fix this doc.** This is a summary + index, not a new rulebook.

## Skills

When writing Kotlin, the global **`android-kotlin`** skill
(`~/.claude/skills/android-kotlin/SKILL.md`, `user-invocable: false`) **auto-applies** to every
`*.kt` / `*.kts` / `build.gradle.kts` — use it for idiomatic Coroutines / Compose / Hilt / MockK
patterns. Finer-grained, project-tailored skills (`compose-ui`, `hilt-di`,
`kotlin-coroutines-flow`, `room-and-migrations`, …) are wired onto each issue's labels via
[`../scripts/gen_issue_docs.py`](../scripts/gen_issue_docs.py) and surface in the issue docs.

**`CLAUDE.md` overrides every skill.** Where `android-kotlin` (a generic skill) disagrees with a
rule here, the rule wins — notably: `Result<T, AppError>` (not `Result<out T>` with `Throwable`);
**no network on a core path** (P-04); injected `Clock` / `DispatcherProvider` (not hardcoded
`Dispatchers.IO`); `Money` as `Long` paise; and the strict multi-module graph (not a single
`app/` module).

## What to use

| Area | Use | Rule |
|------|-----|------|
| Money | The `Money` value class — `Long` **paise** end-to-end (DB → engine → UI); `HALF_EVEN` + remainder distribution for splits | MNY-001 |
| Rates/percentages | Integer **basis points (bps)** in engines | MNY-002 |
| Time | Injected `Clock` + profile time zone for all calendar logic; ISO `LocalDate` for date-only | TIM-001/002 |
| Errors | `Result<T, AppError>` (sealed) across layer boundaries — no exceptions crossing layers | §5 |
| UI state | One immutable `data class` per screen as `StateFlow`; events up via a sealed interface | ARC-004 |
| DI | Hilt **constructor injection**; each engine = one public interface + result types, impl `internal` | ARC-003 |
| Concurrency | Injected scopes + `DispatcherProvider`; DAOs `suspend`/`Flow`; structured concurrency | ARC-006 |
| Stack | Only the pinned §21.3 libraries (see [Architecture.md](Architecture.md)); swaps need an [ADR](adr/) | §21.3 |
| Strings/theme | Every user-visible string in `strings.xml` (ICU plurals); colours/dimensions from theme tokens | §21.6 |
| AI behaviour | Change thresholds/rules as **data rows in [`../ai/`](../ai/)**, never hardcode a financial number | §6 |

## What to avoid (banned — fails review / lint)

- `Double` / `Float` touching a monetary value (MNY-001, review-blocking).
- `System.currentTimeMillis()` in domain code (TIM-001, lint-banned) — inject `Clock`.
- `GlobalScope` (ARC-006, lint-banned); `runBlocking` outside tests.
- `SharedPreferences` — use Proto DataStore.
- **Hand-rolled crypto** — Google Tink / platform Keystore only (SEC-003, review-blocking).
- `LiveData`; leaking mutable UI state; wildcard imports.
- **PII / amount logging in release** (lint strips it); security events go to `audit_log`.
- A **network call on a core path** — every core feature works in airplane mode (P-04).
- A feature module depending on another feature module (ARC-001).
- Destructive Room migrations (DB-003) — every version bump ships a migration test.

## AI error-handling boundaries (the hard line for the LLM)

- **P-03 — the LLM never computes a number.** Every amount/score/forecast comes from a
  deterministic engine; the LLM only *verbalises* figures it is handed. Any code path that lets
  the model emit an unverified figure is wrong by construction.
- **AI-ARC-004 — guardrail every LLM output.** LLM text passes the numeric guardrail
  ([`../ai/chat/guardrail.md`](../ai/chat/guardrail.md)) before display; on a figure that
  doesn't match engine provenance, the guardrail **blocks or repairs** it — never shows it.
- **Chat touches data only through the tool registry** ([`../ai/skills/tool-registry.json`](../ai/skills/tool-registry.json)) —
  no ad-hoc DB access from the LLM layer.
- **P-02 — show the work.** Every recommendation surfaces its inputs + the rule/model that
  fired + a plain-language reason. No black-box verdicts.
- **Engines return `Result<T, AppError>`**, never throw across a boundary; the LLM layer
  degrades gracefully (e.g. "I can't compute that right now") rather than inventing an answer.
- **Cloud LLM (opt-in only)** receives the structured context pack (§19.4), never raw
  transactions (P-01).
