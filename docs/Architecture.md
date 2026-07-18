<!--
  Why:  A single map of how the app is structured and what it's built from, so a new reader
        can orient in one screen. The binding detail lives in CLAUDE.md §2 and the design
        spec §4/§6/§9 — this indexes them.
  What: Architecture — app flow, folder/file structure, tech stack.
  Result: A reader knows the module graph, the AI pipeline, where files live, and the stack.
  Changelog:
    2026-07-18 — Created (thin view of CLAUDE.md §2 + design spec §4/§6/§9).
-->

# AI Personal CFO — Architecture

> **Binding:** [`../CLAUDE.md` §2](../CLAUDE.md). **Source of truth:** the
> [SRS](init/AI_Personal_CFO_SRS_v1.7.pdf) (§21.2, §7). **Detail:**
> [design spec §4/§6/§9](superpowers/specs/2026-07-17-ai-personal-cfo-design.md) and
> [`../ai/architecture/ai-architecture.md`](../ai/architecture/ai-architecture.md).

## App flow & architecture

**Module dependency is one-way** — `feature → domain → data/core` (ARC-001). Feature modules
never depend on each other; cross-feature navigation goes through the nav graph with typed
routes. `:core:model` and `:domain:*` are **pure Kotlin/JVM, no Android imports** (ARC-002).
Repositories are the only classes that touch DAOs or network (ARC-005). UI state is one
immutable `StateFlow` data class per screen; events flow up via a sealed interface (ARC-004).

**The AI is a layered pipeline, not one model** (§7). Layer N depends only on layers below;
every engine result carries provenance (`engineId, engineVersion, inputWindow, computedAt,
confidence, evidence`), and engine versions are stored with results so old insights stay
reproducible (AI-ARC-003/006).

```
L1 data        raw ledger (accounts, transactions) — the ONLY source of numbers
L2 analytics   deterministic aggregates (spend by category, cash position, ratios)
L3 rules       versioned rulebook rows (ai/rules/*) — thresholds, order-of-operations
L4 predictions recurring detection, cash-flow forecast, seasonality, vehicle costs
L5 decisions   health score, Safe-to-Spend, goal feasibility, purchase advisor
L6 LLM         verbalises L2–L5 output; never computes — guardrailed (AI-ARC-004)
```

## Folder & file structure

**Target module tree (to be built — design spec §6):**

```
:app                      single-activity Compose host, nav graph, Hilt graph
:core:model :core:common  pure Kotlin/JVM — Money, Clock, Result, DispatcherProvider (ARC-002)
:core:database            Room + SQLCipher, DAOs, migrations
:domain:engines:*         pure-Kotlin engines (one interface + result types each, ARC-003)
:data:repository          the ONLY classes that touch DAOs or network (ARC-005)
:feature:*                Compose screens + ViewModels (StateFlow UiState, ARC-004)
:ml:ocr :ml:llm           ML Kit OCR, on-device LLM behind interfaces
:sync:backup              E2EE backup/restore
:widget                   Glance home-screen widget
```

**Current repo layout (what exists today — no Kotlin code yet, v0.1.0):**

```
CLAUDE.md · VERSION · CHANGELOG.md · .editorconfig
ai/            runtime AI files the app LOADS (rules, workflow, tools, knowledge) — ai/README.md
docs/init/     the SRS (master blueprint)      docs/templates/  ENGINE.md, ADR templates
docs/adr/      Architecture Decision Records    docs/features/   feature/task breakdown
docs/issues/   85 issue + tracker files         docs/superpowers/specs/  design spec + CSV
docs/PRD.md · Architecture.md · Rules.md · phase.md · Design.md · memory.md  (these docs)
.claude/       project skills + slash commands  .github/  CI + PR template
scripts/       issue-doc generator + checker
```

## Tech stack

**Pinned (§21.3 — do not swap without an [ADR](adr/)):** one Gradle version catalog
(`libs.versions.toml`) pins the stack; `:core:model`/`:domain:*` are pure-Kotlin/JVM.

| Concern | Choice |
|---------|--------|
| UI | Jetpack Compose + Material 3 |
| Persistence | Room over **SQLCipher** (encrypted), key in Android Keystore/StrongBox |
| Settings & consent | **Proto DataStore** (SharedPreferences banned) |
| DI | Hilt (constructor injection only) |
| Background work | WorkManager |
| Network (optional) | Retrofit + OkHttp + kotlinx.serialization, cert-pinned to our backend |
| OCR | ML Kit Text Recognition v2 (on-device) |
| On-device LLM | AICore / MediaPipe behind an `LlmEngine` interface |
| Crypto | Google **Tink** / platform Keystore (no hand-rolled crypto — SEC-003) |
| Auth | BiometricPrompt (class 3) + PIN fallback |
| CI | GitHub Actions — build · test + coverage · ktlint/detekt · Paparazzi diff · dep scan |
