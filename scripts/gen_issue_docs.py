#!/usr/bin/env python3
"""Generate the AI Personal CFO issue backlog from a single embedded source of truth.

Why
----
The backlog must mirror the reference project's `docs/superpowers/specs/` + `docs/issues/`
layout: one design-spec `.md`, one `issues.csv`, and — per issue — a rich `<id>-<slug>.md`
plus a `<id>-<slug>-tracker.md`. Hand-writing ~170 cross-linked files would drift. This
generator holds every issue record once and renders all of them uniformly, so the CSV and the
per-issue files can never disagree and the whole set is regenerable when epics change.

What
----
Reads the embedded `EPICS` + `ISSUES` tables and the label -> skill / rule maps, then emits:
  - `docs/superpowers/specs/2026-07-17-issues.csv`  (one row per issue)
  - `docs/issues/<id>-<slug>.md`                    (reference-format issue file)
  - `docs/issues/<id>-<slug>-tracker.md`            (reference-format tracker file)
It is idempotent: re-running overwrites the generated files and prints an epic/issue/file report.

Result
------
A complete, uniformly formatted, cross-linked backlog (13 epics, 85 issues) that plugs into
`docs/issues/00-issue-workflow.md` and the `.claude/` skills.

Changed: 2026-07-18 - initial generator (13 epics / 85 issues).

Input
-----
No CLI args. Data is embedded below. Repo root is derived as the parent of this file's
`scripts/` directory, so it runs from anywhere.

Output
------
Writes UTF-8 files under `docs/` and prints a summary to stdout. Exit code 0 on success.
"""

from __future__ import annotations

import csv
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

# Force UTF-8 so the Rupee sign, >=, and em-dash survive on a cp1252 Windows console.
try:
    sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
except Exception:  # pragma: no cover - older interpreters
    pass

ROOT = Path(__file__).resolve().parent.parent
ISSUES_DIR = ROOT / "docs" / "issues"
SPECS_DIR = ROOT / "docs" / "superpowers" / "specs"
CSV_PATH = SPECS_DIR / "2026-07-17-issues.csv"
DESIGN_SPEC_REL = "../superpowers/specs/2026-07-17-ai-personal-cfo-design.md"
ASSIGNEE = "Harish G"

# --------------------------------------------------------------------------- #
# Skill registry: name -> (path, default "when to use" blurb).                 #
# Project skills live in `.claude/skills/`; global skills in `~/.claude/`.     #
# --------------------------------------------------------------------------- #
SKILL_REGISTRY: dict[str, tuple[str, str]] = {
    "test-driven-development": (
        "~/.claude/skills/test-driven-development/SKILL.md",
        "Write the failing test from the acceptance criteria first, then code to green.",
    ),
    "source-driven-development": (
        "~/.claude/skills/source-driven-development/SKILL.md",
        "Ground framework/library use (Compose, Room, Hilt, ML Kit) in official docs before coding.",
    ),
    "incremental-implementation": (
        "~/.claude/skills/incremental-implementation/SKILL.md",
        "Land in small reviewable slices; feature-flag incomplete work.",
    ),
    "debugging-and-error-recovery": (
        "~/.claude/skills/debugging-and-error-recovery/SKILL.md",
        "Bugs: observe -> reproduce -> hypothesize -> isolate -> fix -> verify.",
    ),
    "api-and-interface-design": (
        "~/.claude/skills/api-and-interface-design/SKILL.md",
        "Design the one public interface + result types before implementing.",
    ),
    "security-review": (
        "~/.claude/skills/security-review/SKILL.md",
        "Mandatory for crypto, keys, SMS, backup, or consent changes (SS23, SEC-*).",
    ),
    "compose-ui": (
        "~/.claude/skills/compose-ui/SKILL.md",
        "Compose screens: immutable UiState/StateFlow (ARC-004), M3 tokens, strings.xml/ICU, a11y, Paparazzi.",
    ),
    "ci-cd-and-automation": (
        "~/.claude/skills/ci-cd-and-automation/SKILL.md",
        "CI pipelines, Gradle config, and the release/build train.",
    ),
    "room-and-migrations": (
        "~/.claude/skills/room-and-migrations/SKILL.md",
        "Room + SQLCipher entity/DAO/query; exported schema + a non-destructive migration test (DB-003).",
    ),
    "hilt-di": (
        "~/.claude/skills/hilt-di/SKILL.md",
        "Wire the DI graph: constructor injection, engine @Binds, injected Clock/dispatchers (ARC-003).",
    ),
    "gradle-modules": (
        "~/.claude/skills/gradle-modules/SKILL.md",
        "Module graph feature->domain->data/core (ARC-001), pure-Kotlin engines (ARC-002), version catalog.",
    ),
    "ml-kit-ocr": (
        "~/.claude/skills/ml-kit-ocr/SKILL.md",
        "On-device receipt OCR (ML Kit v2): parse to Money, review-before-save, on-device only (P-01).",
    ),
    "on-device-llm": (
        "~/.claude/skills/on-device-llm/SKILL.md",
        "Chat/verbalisation behind LlmEngine: tool registry, verbalise-not-compute (P-03), guardrail (AI-ARC-004).",
    ),
    "workmanager-jobs": (
        "~/.claude/skills/workmanager-jobs/SKILL.md",
        "Idempotent background workers on the injected Clock; offline-safe snapshots/alerts/widget refresh.",
    ),
    "datastore-consent": (
        "~/.claude/skills/datastore-consent/SKILL.md",
        "Proto DataStore settings + per-feature revocable consent ledger; enforce on every optional path (P-01).",
    ),
    "keystore-crypto": (
        "~/.claude/skills/keystore-crypto/SKILL.md",
        "Tink/Keystore only (SEC-003): SQLCipher key, E2EE backup (Argon2id+AES-GCM), crypto-shred erase.",
    ),
    "biometric-auth": (
        "~/.claude/skills/biometric-auth/SKILL.md",
        "App lock: BiometricPrompt class 3 + PIN fallback, fail-secure, idle timeout, gates the encrypted store.",
    ),
    "retrofit-networking": (
        "~/.claude/skills/retrofit-networking/SKILL.md",
        "Retrofit/OkHttp via the backend proxy only; cert-pinned, consent-gated, cached + staleness offline (P-04).",
    ),
    "glance-widget": (
        "~/.claude/skills/glance-widget/SKILL.md",
        "Glance home-screen widget from cached engine output; privacy-blur aware, WorkManager refresh, offline.",
    ),
    "paparazzi-screenshot-testing": (
        "~/.claude/skills/paparazzi-screenshot-testing/SKILL.md",
        "Compose screenshot tests in light/dark/200% from a fixed UiState; baselines gate merges (§21.5).",
    ),
    "proguard-r8-release": (
        "~/.claude/skills/proguard-r8-release/SKILL.md",
        "R8 minify + resource shrink; keep rules for Room/serialization/Hilt; strip PII/amount logs (§21.6).",
    ),
    "kotlin-multiplatform": (
        "~/.claude/skills/kotlin-multiplatform/SKILL.md",
        "Keep engines Android-free (ARC-002); share the domain to iOS via KMP (issue 13.7) with expect/actual.",
    ),
    "compose-navigation": (
        "~/.claude/skills/compose-navigation/SKILL.md",
        "One typed nav graph in :app; cross-feature nav via routes only (ARC-001); deep links for widget/notifs.",
    ),
    "compose-performance": (
        "~/.claude/skills/compose-performance/SKILL.md",
        "Keep Compose skippable: stable/immutable UiState (ARC-004), deferred reads, lazy keys, Baseline Profiles.",
    ),
    "edge-to-edge": (
        "~/.claude/skills/edge-to-edge/SKILL.md",
        "Draw edge-to-edge: enableEdgeToEdge + WindowInsets/Scaffold padding so bars/keyboard never clip content.",
    ),
    "kotlin-coroutines-flow": (
        "~/.claude/skills/kotlin-coroutines-flow/SKILL.md",
        "Async right: injected scopes/dispatchers (no GlobalScope, ARC-006), cold Flow vs StateFlow, Turbine tests.",
    ),
    "new-engine": (
        ".claude/skills/new-engine/SKILL.md",
        "Scaffold a :domain:engines:* module (one interface, provenance, Hilt, ENGINE.md, tests).",
    ),
    "add-rulebook-rule": (
        ".claude/skills/add-rulebook-rule/SKILL.md",
        "Add/change any financial threshold as a versioned data row in ai/rules/ (never hardcoded).",
    ),
    "money-time-audit": (
        ".claude/skills/money-time-audit/SKILL.md",
        "Scan the diff for the Money/Clock bug factories before merge.",
    ),
}

# Label -> ordered list of skill names to surface in the Skill Rules table.
LABEL_SKILLS: dict[str, list[str]] = {
    "money": ["test-driven-development", "api-and-interface-design", "money-time-audit"],
    "core": ["api-and-interface-design", "kotlin-coroutines-flow", "test-driven-development"],
    "time": ["test-driven-development", "money-time-audit"],
    "app": ["compose-ui", "compose-navigation", "hilt-di"],
    "di": ["hilt-di", "source-driven-development"],
    "lint": ["gradle-modules", "test-driven-development"],
    "kmp": ["kotlin-multiplatform", "gradle-modules", "source-driven-development"],
    "engine": ["new-engine", "test-driven-development", "source-driven-development"],
    "ai": ["new-engine", "test-driven-development", "source-driven-development"],
    "forecast": ["new-engine", "test-driven-development"],
    "health-score": ["new-engine", "test-driven-development"],
    "orchestrator": ["new-engine", "source-driven-development"],
    "vehicle": ["new-engine", "add-rulebook-rule"],
    "market-signal": ["new-engine", "source-driven-development"],
    "simulator": ["new-engine", "test-driven-development"],
    "purchase-advisor": ["new-engine", "test-driven-development"],
    "guardrail": ["on-device-llm", "source-driven-development", "test-driven-development"],
    "categorisation": ["add-rulebook-rule", "new-engine", "test-driven-development"],
    "budgets": ["add-rulebook-rule", "test-driven-development"],
    "goals": ["new-engine", "add-rulebook-rule"],
    "emergency": ["new-engine", "add-rulebook-rule"],
    "accounts": ["room-and-migrations", "test-driven-development"],
    "transactions": ["room-and-migrations", "test-driven-development"],
    "database": ["room-and-migrations", "test-driven-development", "debugging-and-error-recovery"],
    "datastore": ["datastore-consent", "source-driven-development", "test-driven-development"],
    "security": ["security-review", "source-driven-development"],
    "crypto": ["keystore-crypto", "security-review", "source-driven-development"],
    "privacy": ["security-review", "datastore-consent", "source-driven-development"],
    "compliance": ["security-review", "datastore-consent"],
    "auth": ["biometric-auth", "security-review", "test-driven-development"],
    "backup": ["keystore-crypto", "security-review", "test-driven-development"],
    "sms": ["security-review", "source-driven-development"],
    "market-data": ["retrofit-networking", "test-driven-development"],
    "integration": ["retrofit-networking", "source-driven-development"],
    "notifications": ["workmanager-jobs", "test-driven-development"],
    "ui": ["compose-ui", "source-driven-development"],
    "designsystem": ["compose-ui", "edge-to-edge", "paparazzi-screenshot-testing"],
    "dashboard": ["compose-ui", "compose-performance", "source-driven-development"],
    "onboarding": ["compose-ui", "source-driven-development"],
    "localisation": ["compose-ui", "source-driven-development"],
    "widget": ["glance-widget", "compose-ui", "workmanager-jobs"],
    "ocr": ["ml-kit-ocr", "source-driven-development", "test-driven-development"],
    "ml": ["ml-kit-ocr", "source-driven-development", "test-driven-development"],
    "llm": ["on-device-llm", "source-driven-development"],
    "chat": ["on-device-llm", "source-driven-development"],
    "build": ["gradle-modules", "ci-cd-and-automation"],
    "ci": ["ci-cd-and-automation", "proguard-r8-release"],
    "release": ["ci-cd-and-automation", "proguard-r8-release"],
    "testing": ["test-driven-development", "paparazzi-screenshot-testing"],
    "e2e": ["test-driven-development"],
}
# Appended to every issue (universal skills from 00-issue-workflow.md).
ALWAYS_SKILLS = ["test-driven-development", "incremental-implementation", "debugging-and-error-recovery"]

# Label -> short "label-specific" workflow rule lines.
LABEL_RULES: dict[str, list[str]] = {
    "money": [
        "Money is `Long` paise end-to-end; no `Double`/`Float` on a monetary value (MNY-001).",
        "Divisions/splits use HALF_EVEN with deterministic remainder distribution.",
    ],
    "time": ["Date/calendar logic goes through the injected `Clock`; never `System.currentTimeMillis()` in domain (TIM-001)."],
    "core": ["`:core:model` and `:domain:*` stay pure Kotlin/JVM - no Android imports (ARC-002)."],
    "engine": [
        "Exactly one public interface + result types; the implementation is `internal`, Hilt-injected (ARC-003).",
        "Every result carries provenance: engineId/version/inputWindow/computedAt/confidence/evidence (AI-ARC-003).",
    ],
    "ai": ["The LLM never computes a figure - it verbalises engine output, gated by the guardrail (P-03/AI-ARC-004)."],
    "database": ["Repositories are the only classes touching DAOs (ARC-005); destructive migrations are forbidden (DB-003)."],
    "security": ["No hand-rolled crypto - Tink/Keystore only (SEC-003); no PII/amounts in release logs."],
    "crypto": ["No hand-rolled crypto - Tink/Keystore only (SEC-003); unique nonce/IV per row."],
    "privacy": ["No off-device data flow without explicit, revocable, per-feature consent (P-01)."],
    "compliance": ["Purpose-limited, consent-based, rights-respecting data handling (DPDP 2023 / P-01)."],
    "ui": [
        "Every user-visible string lives in `strings.xml` with ICU plurals; colours/dimensions come from theme tokens (SS21.6).",
        "UiState is one immutable data class exposed as `StateFlow`; events flow up via a sealed interface (ARC-004).",
    ],
    "budgets": ["Thresholds are versioned rule rows in `ai/rules/`, never hardcoded (SS29)."],
    "categorisation": ["Merchant/category rules are versioned data rows cited by ID, not code (SS29/AI-ARC-006)."],
    "goals": ["Advice, never orders (P-07); every allocation is shown with the rule that produced it (P-02)."],
    "emergency": ["The months-of-cover factor is a rule row (SS29); output shows its inputs (P-02)."],
    "backup": ["Fail-secure on a bad passphrase; restore is atomic; off-device writes are consent-gated (P-01)."],
    "testing": ["Golden-file + property + seeded-determinism tests; engine coverage >= 85%, money math 100% (SS21.5)."],
    "ci": ["All CI gates green before merge; no new lint/detekt warnings (SS21.6)."],
    "onboarding": ["Consent is captured before any optional data path is enabled (P-01)."],
    "widget": ["Renders from cached engine output and honours the privacy blur; works with the backend absent (P-04/P-01)."],
    "market-data": ["Market data arrives via the backend proxy only; degrade to cached values with a staleness label (P-04/SS22)."],
}
GENERIC_LABEL_RULE = "Match existing conventions; ship the smallest correct diff (`/ponytail`)."

# --------------------------------------------------------------------------- #
# Epic metadata.                                                              #
# --------------------------------------------------------------------------- #
EPICS: dict[int, dict] = {
    1: {
        "title": "Foundation & Core Platform",
        "phase": "SRS Phase 0 (SS21)",
        "summary": "Stand up the one-way multi-module Gradle project and the portable primitives every feature builds on: Money, Clock, Result, encrypted Room, design system, DataStore, app shell.",
        "rules": [
            "Module dependency is one-way `feature -> domain -> data/core`; feature modules never depend on each other (ARC-001).",
            "`:core:model` and `:domain:*` are pure Kotlin/JVM - no Android imports (ARC-002).",
        ],
    },
    2: {
        "title": "Onboarding, Security & Accounts",
        "phase": "SRS Phase 0 (SS5.1, SS5.7, SS23)",
        "summary": "First-run onboarding, biometric app lock, and the accounts + net-worth core - the entry point and the entities balances are computed from.",
        "rules": ["Everything works offline; consent is captured before any optional feature is enabled (P-01/P-04)."],
    },
    3: {
        "title": "Transactions & Capture",
        "phase": "SRS Phase 0/1 (SS5.3, SS5.4, SS18)",
        "summary": "<=3-tap transaction entry plus automated capture - transfers, splits, recurring detection, OCR receipts, and opt-in SMS parsing - all provenance-tagged.",
        "rules": ["Add-txn stays <= 3 taps; every captured record is provenance-tagged by source (FR-TXN-004/AI-ARC-003)."],
    },
    4: {
        "title": "Categorisation & Budgets",
        "phase": "SRS Phase 1 (SS8, SS5.5)",
        "summary": "The category KB, Stage-1 auto-categorisation, Need/Want/Invest nature classification, and budgets with threshold alerts.",
        "rules": ["Categorisation and budget thresholds are versioned data rows cited by ID - never hardcoded (SS29)."],
    },
    5: {
        "title": "Dashboard, Export & Widget",
        "phase": "SRS Phase 1 (SS5.2, SS5.10)",
        "summary": "The home dashboard, Safe-to-Spend, privacy blur, local JSON export/import, and the home-screen widget - the user's daily surfaces.",
        "rules": ["Every figure on a surface is engine-computed and shown with its breakdown (P-03/P-02)."],
    },
    6: {
        "title": "Wealth: Loans & Investments",
        "phase": "SRS Phase 2 (SS5.7, SS5.8, SS11)",
        "summary": "Credit cards, loans with amortisation, investment holdings with XIRR, allocation, and net-worth history - exact paise math throughout.",
        "rules": ["Wealth math is exact: `Money` paise, rates in bps, 100% money-math coverage (MNY-001/002)."],
    },
    7: {
        "title": "Goals & Emergency Fund",
        "phase": "SRS Phase 2/3 (SS10, SS15, SS36)",
        "summary": "The goals engine, emergency-fund engine, feasibility waterfall, linked contributions, and the Financial Order of Operations.",
        "rules": ["Goals/EMF/FOO are deterministic and advisory; the app recommends, the user decides (P-07/P-08)."],
    },
    8: {
        "title": "Backup & Restore",
        "phase": "SRS Phase 2 (SS23.3, SS34.4)",
        "summary": "End-to-end-encrypted backup, restore on a fresh device, and an automated restore drill so backups are proven, not assumed.",
        "rules": ["Backups are E2E-encrypted with Tink and proven restorable (SEC-003/SS21.5)."],
    },
    9: {
        "title": "AI Core Engines",
        "phase": "SRS Phase 3 (SS7, SS8.2, SS9, SS14, SS17)",
        "summary": "The deterministic engine layer: fixed/variable + nature, cash-flow forecast, seasonality, health score, the Insight Orchestrator, notifications, and the numeric guardrail.",
        "rules": ["Layer N depends only on lower layers; every engine result carries provenance + engine version (AI-ARC-003/006)."],
    },
    10: {
        "title": "Advisor & Chat",
        "phase": "SRS Phase 4 (SS13, SS12, SS19, SS30)",
        "summary": "The Purchase Advisor, buy list, what-if simulators, vehicle prediction, on-device chat + guardrail eval, market signals, and localisation.",
        "rules": ["Advisors simulate and recommend - never execute money movement (P-07); the LLM only verbalises (P-03)."],
    },
    11: {
        "title": "Privacy, Security & Compliance",
        "phase": "Cross-cutting (SS23)",
        "summary": "Key management, screen-capture guard, consents dashboard, crypto-shredding erase, DPDP alignment, dependency scanning, and the no-hand-rolled-crypto audit.",
        "rules": ["Privacy and security are fail-secure and consent-first; no hand-rolled crypto anywhere (P-01/SEC-003)."],
    },
    12: {
        "title": "Quality, Testing & Release",
        "phase": "Cross-cutting (SS21.5)",
        "summary": "The golden-file/property harness, frozen AI-eval datasets, screenshot tests, the instrumented E2E smoke, and the CI gates + SemVer release train.",
        "rules": ["Tests gate merges: coverage, screenshot diffs, AI-eval thresholds, and the airplane-mode E2E (SS21.5)."],
    },
    13: {
        "title": "Expansion",
        "phase": "SRS Phase 5 - design-for (SS27, SS33, SS38, SS39)",
        "summary": "Design-for-later scope: household mode, appliances, insurance, tax v2, business mode, Account Aggregator, and iOS via KMP - foundations and ADRs in v1.",
        "rules": ["Expansion work stays feature-flagged and preserves per-profile isolation + engine portability (P-01/ARC-002)."],
    },
}


@dataclass
class Issue:
    """One backlog issue and everything needed to render its files.

    Why: a single record type keeps the CSV row and the two Markdown files in lock-step.
    What: holds identity (id/epic/title), routing (labels/priority/deps), and the prose
    (srs refs, design-spec section, summary, acceptance, issue-specific rules, optional
    sub-task link) that fills the reference-format template.
    Result: consumed by `render_issue`, `render_tracker`, and `write_csv`.
    Changed: 2026-07-18 - created.
    Input: field values from the ISSUES table below.
    Output: an immutable-ish dataclass instance.
    """

    id: str
    epic: int
    title: str
    labels: list[str]
    pri: str  # "H" | "M" | "L"
    deps: list[str]
    srs: str
    spec: str
    summary: str
    acceptance: list[str]
    rules: list[str] = field(default_factory=list)
    task: str | None = None  # repo-root-relative path to a docs/features task file


def I(id, epic, title, labels, pri, deps, srs, spec, summary, acceptance, rules, task=None):
    """Terse constructor so the ISSUES table below stays readable.

    Why: 85 records are far more legible as positional calls than as dict literals.
    What: forwards to the `Issue` dataclass, splitting the semicolon strings.
    Result: an `Issue`. Changed: 2026-07-18 - created.
    Input: labels/deps as ';'-joined strings; the rest as documented on `Issue`.
    Output: `Issue`.
    """
    return Issue(
        id=id,
        epic=epic,
        title=title,
        labels=[x for x in labels.split(";") if x],
        pri=pri,
        deps=[x for x in deps.split(";") if x],
        srs=srs,
        spec=spec,
        summary=summary,
        acceptance=acceptance,
        rules=rules,
        task=task,
    )


# --------------------------------------------------------------------------- #
# The 85 issues (source of truth for the CSV and every issue/tracker file).    #
# --------------------------------------------------------------------------- #
T = "docs/features/1.1-project-skeleton/tasks/"
ISSUES: list[Issue] = [
    # ---------------- Epic 1 - Foundation & Core Platform ---------------- #
    I("1.1", 1, "Gradle multi-module skeleton + version catalog + CI", "build;ci", "H", "",
      "SS21.2, SS21.3; ARC-001, ARC-002", "SS6 Module Structure, SS9 Build/CI/Release",
      "Stand up the one-way multi-module Gradle project, a single version catalog, and the CI pipeline - the skeleton every later issue lands into.",
      ["Settings + module graph created for the SS21.2 modules; dependency direction enforced `feature -> domain -> data/core` (ARC-001).",
       "`:core:model` and `:domain:*` are pure Kotlin/JVM with no Android imports, enforced by Gradle (ARC-002).",
       "One `libs.versions.toml` pins the SS21.3 stack; `./gradlew help` and `assembleDebug` succeed.",
       "CI (`.github/workflows/ci.yml`) runs build + test + ktlint/detekt on every push."],
      ["Module dependency is strictly one-way; feature modules never depend on each other (ARC-001).",
       "Pin the stack from SS21.3; no library swap without an ADR."],
      T + "1.1.1-gradle-multimodule-skeleton.md"),
    I("1.2", 1, "Money value class (Long paise, HALF_EVEN, exact split)", "core;money", "H", "1.1",
      "MNY-001, MNY-002; SS21.4", "SS7 Money & Time Rules",
      "The `Money` value class - `Long` minor units (paise) end-to-end, HALF_EVEN division with remainder distribution so splits always sum. The single monetary type from DB to UI.",
      ["`Money` wraps `Long` paise; no `Double`/`Float` ever touches a monetary value (review-blocking, MNY-001).",
       "Division uses explicit HALF_EVEN; `splitExact(n)` distributes the remainder so parts sum to the original (property test).",
       "Formatting uses Indian digit grouping (Rs 1,23,456.78); rates are integer basis points (MNY-002).",
       "100% test coverage on money math (golden + property + zero/negative boundaries)."],
      ["Any `Double`/`Float` on a monetary value fails review (MNY-001).",
       "Splits must sum exactly; distribute the remainder deterministically."],
      T + "1.1.2-money-value-class.md"),
    I("1.3", 1, "Injected Clock + DispatcherProvider", "core;time", "H", "1.1",
      "TIM-001, TIM-002; ARC-006, SS21.4", "SS7 Money & Time Rules",
      "An injected `Clock` (profile time zone) and a `DispatcherProvider` for structured concurrency - so domain code never calls `System.currentTimeMillis()` and async work uses injected, seedable scopes.",
      ["Calendar logic (day rollover, month boundary, due dates) resolves through the injected `Clock`; `System.currentTimeMillis()` is banned in domain (TIM-001).",
       "Timestamps are UTC epoch millis; date-only fields are ISO `LocalDate` strings (TIM-002).",
       "`DispatcherProvider` exposes Main/IO/Default; tests inject a `TestDispatcher`; no `GlobalScope` (ARC-006).",
       "Seeded-determinism test: fixed clock -> fixed output."],
      ["Never `System.currentTimeMillis()` in domain - inject `Clock` (TIM-001).",
       "Dispatchers are injected; `GlobalScope` is lint-banned (ARC-006)."],
      T + "1.1.3-clock-and-dispatchers.md"),
    I("1.4", 1, "Result<T, AppError> error model", "core", "H", "1.1",
      "SS21.6 (errors); ARC-003", "SS6 Module Structure",
      "The sealed `Result<T, AppError>` return type - no exceptions cross layer boundaries; repositories and engines return typed errors, and crashes are reserved for programmer bugs.",
      ["`AppError` is a sealed hierarchy; engines/repositories return `Result<T, AppError>`, never throwing across boundaries.",
       "map/flatMap/fold helpers are covered by tests; the success arm and every error arm are exercised.",
       "Domain models are null-hostile - absence is modelled with sealed types, not null."],
      ["No exceptions across layer boundaries; return `Result` (SS21.6)."],
      T + "1.1.4-result-apperror.md"),
    I("1.5", 1, "Custom lint (Money/GlobalScope/Clock/strings)", "lint;quality", "H", "1.1;1.2;1.3",
      "MNY-001, TIM-001, ARC-006; SS21.6", "SS7 Money & Time, SS10 Testing",
      "Custom lint/detekt rules that fail the build on the classic bug factories: `Double`/`Float` on money, `System.currentTimeMillis()` in domain, `GlobalScope`, and hardcoded user-facing strings.",
      ["Lint flags `Double`/`Float` used as money and any `System.currentTimeMillis()` in `:domain:*` (MNY-001/TIM-001).",
       "Lint flags `GlobalScope` (ARC-006) and hardcoded user-visible strings (must live in `strings.xml`).",
       "Each rule has positive + negative unit tests and runs in CI as an error, not a warning."],
      ["Lint rules are errors in CI; no new warnings may merge (SS21.6)."],
      T + "1.1.5-custom-lint-rules.md"),
    I("1.6", 1, "Encrypted Room + SQLCipher DB + Keystore key", "database;security;crypto", "H", "1.1;1.4",
      "SS20 (schema), SS23; DB-003, SEC-003", "SS5 Data Model, SS8 Security & Privacy",
      "The encrypted persistence core: Room over SQLCipher with the passphrase held in the Android Keystore - the on-device, offline-first store all financial data lives in.",
      ["Room is encrypted with SQLCipher; the key is generated/held in the Keystore (StrongBox where available), never in code or prefs (SEC-003).",
       "Base schema (accounts, transactions, categories ... SS20) with soft-delete (`deleted_at`) and per-profile scoping.",
       "Destructive migrations are forbidden (DB-003); the schema is exported for migration tests.",
       "Opens and round-trips in airplane mode (P-04)."],
      ["No hand-rolled crypto - Keystore/Tink only (SEC-003, review-blocking).",
       "Destructive migrations are forbidden (DB-003)."]),
    I("1.7", 1, "Room migration test harness", "database;testing", "H", "1.6",
      "DB-003; SS21.5", "SS5 Data Model, SS10 Testing",
      "A migration test harness: in-memory Room plus a real exported-schema fixture per version bump, proving every migration is non-destructive.",
      ["A migration test runs against a real exported schema fixture for every version bump (SS21.5).",
       "Round-trip test: seed vN, migrate to vN+1, assert data preserved; a destructive migration fails the test (DB-003).",
       "The harness is documented so each new DB version adds a fixture + test."],
      ["Every schema version bump ships a migration test (SS21.5); no destructive migration (DB-003)."]),
    I("1.8", 1, "Design system (M3 theme, tokens, Compose charts)", "designsystem;ui", "H", "1.1",
      "SS24; SS21.6; ACC-*", "SS6 Module Structure, SS10 Testing",
      "The Material 3 design system: theme tokens (colour/dimension/type), core Compose components, and chart primitives - all theme-driven, dark-mode and 200%-font ready.",
      ["M3 theme with colour/dimension/type tokens; no hardcoded colours or dimensions (SS21.6).",
       "Core components + chart primitives built; Paparazzi screenshot tests in light/dark/200% font.",
       "Accessibility: content descriptions, minimum touch targets, and contrast all pass."],
      ["Every colour/dimension comes from a theme token (SS21.6).",
       "Design-system components carry Paparazzi screenshot tests (SS21.5)."]),
    I("1.9", 1, "Proto DataStore settings & consents", "datastore;privacy", "H", "1.1",
      "SS23 (consent), SS5.9; P-01", "SS8 Security & Privacy",
      "Proto DataStore for app settings and the per-feature consent ledger - SharedPreferences is banned; consents are explicit, per-feature, and revocable (P-01).",
      ["Settings + consent are stored in Proto DataStore (SharedPreferences banned, SS21.3).",
       "Each consent is per-feature, default off, revocable, with grant/revoke timestamps (P-01).",
       "Reads/writes are `Flow`/suspend on injected dispatchers and are covered by tests."],
      ["No off-device data flow without an explicit, revocable consent row (P-01)."]),
    I("1.10", 1, "App shell + navigation + Hilt DI graph", "app;di", "H", "1.4;1.8",
      "ARC-001, ARC-003, ARC-004; SS21.2", "SS6 Module Structure",
      "The `:app` shell: single-activity Compose host, a typed nav graph for cross-feature routes, and the Hilt DI graph wiring engines/repositories by constructor injection.",
      ["Single-activity Compose host with a typed nav graph; cross-feature navigation only via typed routes (ARC-001).",
       "The Hilt graph wires modules by constructor injection; no service locators (ARC-003).",
       "UI state is one immutable data class per screen as `StateFlow`; events flow up via a sealed interface (ARC-004)."],
      ["Cross-feature navigation uses typed routes only; feature modules stay independent (ARC-001).",
       "Constructor injection via Hilt; no service locators (ARC-003)."]),

    # ---------------- Epic 2 - Onboarding, Security & Accounts ---------------- #
    I("2.1", 2, "4-step onboarding flow", "onboarding;ui", "H", "1.10;1.9",
      "SS5.1, SS23; FR-ONB-*", "SS6 Module Structure, SS8 Security & Privacy",
      "The 4-step first-run onboarding (welcome -> privacy/consent -> profile & currency -> quick setup) - offline, accessible, and consent-first.",
      ["The four-step flow renders and navigates; state survives process death; it is fully offline (P-04).",
       "The consent screen writes per-feature consent rows (default off) before any optional feature is enabled (P-01).",
       "A Compose UI test drives the full flow; strings externalised; dark mode + 200% font verified."],
      ["No optional data path is enabled without the consent captured here (P-01)."]),
    I("2.2", 2, "Biometric/PIN app lock (BiometricPrompt)", "security;auth", "H", "1.9;1.6",
      "SS23; FR-SEC-*, SEC-*", "SS8 Security & Privacy",
      "App lock via BiometricPrompt (class 3) with a PIN fallback - gates access to the encrypted store on cold start and after an idle timeout.",
      ["BiometricPrompt (class 3) unlocks; a PIN fallback covers no-biometric devices; lock on cold start and a configurable idle timeout.",
       "Failure is fail-secure (no data shown on cancel/error); lockout after repeated failures.",
       "No biometric material leaves the device; auth events go to `audit_log` with no PII."],
      ["Fail-secure on every auth error; no amounts/PII in logs (SS21.6)."]),
    I("2.3", 2, "Quick-setup seeds (income/rent/savings)", "onboarding", "M", "2.1",
      "SS5.1; FR-ONB-*", "SS6 Module Structure",
      "Optional quick-setup that seeds recurring income, rent, and a savings target from onboarding so the dashboard is useful on day one.",
      ["The user can seed income/rent/savings; values create the matching accounts/recurring records deterministically.",
       "It is skippable; nothing is fabricated if skipped.",
       "Amounts are stored as `Money` paise and covered by tests."],
      ["Seeds are user-entered; never invent a financial number (P-03)."]),
    I("2.4", 2, "Demo mode with sample data", "onboarding", "M", "2.1",
      "SS5.1 (FR-ONB-004)", "SS6 Module Structure",
      "A demo mode that loads a labelled sample dataset so the app can be explored without entering real data - clearly marked and fully wipeable.",
      ["Demo loads a fixed sample dataset into an isolated/flagged profile, clearly labelled as demo.",
       "Exiting demo wipes the sample data with no residue in the real profile.",
       "The dataset is deterministic (seeded) and covered by a test."],
      ["Demo data is visibly labelled and isolated from real data (P-02)."]),
    I("2.5", 2, "Accounts CRUD (all types)", "accounts", "H", "1.6",
      "SS5.7, SS11; FR-ACC-*", "SS5 Data Model",
      "Create/read/update/soft-delete for every account type (bank, cash, wallet, card, loan, investment) - the entities balances and net worth are computed from.",
      ["CRUD for every account type in SS11; soft-delete (`deleted_at`) and per-profile scoping.",
       "Balances are `Money` paise; opening + running balance are correct (property test).",
       "The repository is the only DAO toucher (ARC-005); the ViewModel sees domain models only."],
      ["Repositories are the only classes touching DAOs (ARC-005)."]),
    I("2.6", 2, "Net worth = assets - liabilities + daily snapshot", "accounts;engine", "H", "2.5",
      "SS5.7; FR-NW-*, P-03", "SS5 Data Model, SS7 Money & Time",
      "The deterministic net-worth computation (assets - liabilities) plus a daily snapshot job so trend history is exact and reproducible.",
      ["Net worth = sum(assets) - sum(liabilities) in `Money` paise; the number comes from the engine, not the LLM (P-03).",
       "A daily snapshot is written via WorkManager on the injected `Clock`, idempotent per day.",
       "Golden-file test on a fixed account set; airplane-mode safe."],
      ["Numbers from math (P-03); the snapshot uses the injected `Clock` (TIM-001)."]),
    I("2.7", 2, "Account reconciliation flow", "accounts", "M", "2.5",
      "SS5.7; FR-ACC-*", "SS5 Data Model",
      "A reconciliation flow to align an account's app balance with a real statement balance, recording an explicit adjustment transaction with provenance.",
      ["The user sets the actual balance; the app computes the delta and records a labelled, source-tagged adjustment - never silently editing history.",
       "The adjustment is `Money` paise and shows before/after and the rule that fired (P-02).",
       "Covered by tests including zero-delta and negative-delta."],
      ["Show the work: reconciliation records an explicit, sourced adjustment (P-02)."]),

    # ---------------- Epic 3 - Transactions & Capture ---------------- #
    I("3.1", 3, "Add transaction <= 3 taps (FAB)", "transactions;ui", "H", "2.5",
      "SS5.3, SS18; FR-TXN-004", "SS6 Module Structure",
      "The core capture path: add a transaction in <= 3 taps from the FAB - amount, category, account - the app's most-used flow.",
      ["Add-txn is reachable and completable in <= 3 taps (a Compose UI test asserts the tap count).",
       "Amount is `Money` paise; date via the injected `Clock`; the write goes through the repository.",
       "Validation for empty/invalid amount; fully offline."],
      ["<= 3 taps is a hard requirement (FR-TXN-004); amounts are `Money` (MNY-001)."]),
    I("3.2", 3, "Transfers (single logical record)", "transactions", "H", "3.1",
      "SS5.3; FR-TXN-*", "SS5 Data Model",
      "Account-to-account transfers modelled as one logical record (not two unlinked transactions) so net worth and category totals stay correct.",
      ["A transfer debits source and credits destination as one linked record, excluded from income/expense totals.",
       "The amounts balance exactly in `Money` paise (property test).",
       "Soft-deleting a transfer reverses both legs atomically."],
      ["A transfer is one logical record; it is not spending (P-03 correctness)."]),
    I("3.3", 3, "Split transactions (N lines)", "transactions;money", "H", "3.1;1.2",
      "SS5.3; FR-TXN-*, MNY-001", "SS7 Money & Time Rules",
      "Split a single transaction across N category lines whose parts always sum to the parent amount, using `Money.splitExact` remainder distribution.",
      ["N split lines sum exactly to the parent over random n/amount (property test, MNY-001).",
       "The remainder is distributed via HALF_EVEN; each line carries its own category/nature.",
       "Editing a line re-balances; the UI shows the running remainder."],
      ["Splits always sum to the parent; use `Money.splitExact` (MNY-001)."]),
    I("3.4", 3, "Future-dated transactions", "transactions", "M", "3.1",
      "SS5.3; FR-TXN-*, TIM-001", "SS7 Money & Time Rules",
      "Future-dated (scheduled) transactions that stay out of current balances until their date, using the injected `Clock` for rollover.",
      ["Future-dated txns are excluded from current balance/spend until their date passes (injected `Clock`, TIM-001).",
       "On date rollover they post automatically (WorkManager), idempotently.",
       "Covered by boundary tests (today vs tomorrow, zone/DST-safe)."],
      ["Date logic via the injected `Clock`, never `System.currentTimeMillis()` (TIM-001)."]),
    I("3.5", 3, "Transaction source tracking", "transactions", "H", "3.1",
      "SS5.3, SS18; AI-ARC-003", "SS5 Data Model",
      "Every transaction records its source (manual, OCR, SMS, import, recurring) so categorisation confidence and audits can reason about provenance.",
      ["Each txn carries a source enum + provenance (creating engine/version where applicable, AI-ARC-003).",
       "Source is filterable and shown in the detail view (P-02).",
       "A default is backfilled for manual entries; covered by tests."],
      ["Provenance on every record (AI-ARC-003); show the source (P-02)."]),
    I("3.6", 3, "Search, filters, bulk edit", "transactions", "H", "3.1",
      "SS5.3; FR-TXN-*", "SS5 Data Model, SS6 Module Structure",
      "Full-text search, faceted filters (date/account/category/amount range), and safe bulk edit/recategorise over the transaction ledger.",
      ["Search + filters return correct sets (repository queries, tested on a fixture).",
       "Bulk edit/recategorise applies atomically with undo; soft-delete is respected.",
       "Large-list performance is acceptable via a paged `Flow`; fully offline."],
      ["Repositories own the queries (ARC-005); bulk operations are reversible."]),
    I("3.7", 3, "Recurring detection", "transactions;engine", "M", "3.1",
      "SS5.4, SS17; P-08", "SS4 AI Architecture",
      "A deterministic recurring-transaction detector (merchant + cadence + amount tolerance) that surfaces subscriptions and bills with evidence.",
      ["The detector is deterministic (fixed input -> fixed output, P-08) and emits provenance + evidence.",
       "It detects fixed and near-fixed cadences within a tolerance and does not false-positive on one-offs (golden-file test).",
       "The user can confirm/reject; decisions feed back as data, not code."],
      ["Deterministic engine, seeded only via an injected source (P-08); evidence attached (AI-ARC-003)."]),
    I("3.8", 3, "OCR receipt scanning (ML Kit)", "ocr;ml", "H", "3.1;1.6",
      "SS18; FR-RCP-*, receipts >= 95%", "SS11 External Data (on-device ML)",
      "On-device receipt capture with ML Kit Text Recognition v2 - extract merchant, amount, date into a pre-filled transaction; nothing leaves the device.",
      ["ML Kit v2 runs on-device (no network, P-01) and extracts amount/date/merchant to a review screen.",
       "Amounts are parsed to `Money` paise; the user confirms before save; source = OCR (3.5).",
       "AI-eval dataset: receipt field accuracy >= 95%, regression-gated (SS21.5)."],
      ["On-device only; no image or extracted text leaves the device (P-01)."]),
    I("3.9", 3, "SMS parsing (opt-in, on-device)", "sms;security", "M", "3.1",
      "SS18, SS23; P-01", "SS8 Security & Privacy",
      "Opt-in, on-device SMS transaction parsing (bank alerts -> draft transactions) behind an explicit, revocable consent - no message content ever leaves the device.",
      ["SMS read is opt-in via explicit consent (default off, revocable, P-01); disabled means zero SMS access.",
       "Parsing runs on-device to draft txns for review (source = SMS); no raw SMS stored beyond what is needed.",
       "The parser is deterministic and tested on a fixture of anonymised templates."],
      ["Explicit, revocable consent; content stays on-device (P-01)."]),

    # ---------------- Epic 4 - Categorisation & Budgets ---------------- #
    I("4.1", 4, "Categories editor + merchant-rule KB", "categorisation", "H", "3.5",
      "SS8, SS29; FR-CAT-*", "SS4 AI Architecture",
      "The category taxonomy editor plus the merchant->category rule KB stored as versioned data rows (`ai/knowledge/classification-kb.json`), not hardcoded logic.",
      ["Categories are user-editable; merchant rules live as versioned rows cited by ID (SS29).",
       "Adding/editing a rule uses the data-not-code path (`add-rulebook-rule`); each row is versioned (AI-ARC-006).",
       "Repository-backed and covered by tests."],
      ["Thresholds/rules are data rows, never hardcoded (CLAUDE SS6, AI-ARC-006)."]),
    I("4.2", 4, "Auto-categorisation v1 (AI-CLS Stage 1)", "categorisation;ai", "H", "4.1",
      "SS8.2; AI-CLS, categorisation >= 92%", "SS4 AI Architecture",
      "Stage-1 auto-categorisation: a deterministic merchant/rule match assigns a category with a confidence and evidence; low confidence defers to the user.",
      ["A rule/merchant match assigns a category deterministically with confidence + evidence (AI-ARC-003).",
       "Below a confidence threshold it defers instead of guessing (P-03 correctness).",
       "AI-eval dataset: categorisation accuracy >= 92%, regression-gated (SS21.5)."],
      ["Numbers/scores come from the engine with evidence; no LLM invention (P-03)."]),
    I("4.3", 4, "Nature classification (Need/Want/Invest/Asset/Liability)", "categorisation;ai", "H", "4.2",
      "SS8, SS14; AI-CLS", "SS4 AI Architecture",
      "Classify each transaction's financial nature (Need / Want / Invest / Asset / Liability) - the signal Safe-to-Spend, the health score, and advice all build on.",
      ["Deterministic nature assignment with evidence; user-overridable (the override is stored as data).",
       "Feeds Safe-to-Spend and health-score inputs with a consistent taxonomy (SS14).",
       "Golden-file test over a labelled fixture."],
      ["Deterministic + explainable (P-08/P-02); overrides are data."]),
    I("4.4", 4, "Budgets CRUD + suggestions", "budgets", "H", "4.1",
      "SS5.5, SS29; FR-BUD-*", "SS4 AI Architecture, SS5 Data Model",
      "Per-category budgets with deterministic, evidence-backed suggestions derived from history - the user always decides the final number (P-07).",
      ["CRUD budgets per category/period in `Money` paise; suggestions are computed from history with the rule shown (P-02).",
       "A suggestion is advice, not applied automatically (P-07).",
       "Golden-file test on the suggestion math."],
      ["Advice not orders (P-07); the suggestion shows its inputs + rule (P-02)."]),
    I("4.5", 4, "Budget alerts (80%/100%)", "budgets;notifications", "H", "4.4",
      "SS5.5, SS30; thresholds SS29", "SS4 AI Architecture",
      "Threshold alerts at 80% and 100% of a budget, with the thresholds as versioned rule rows and the notification gated by the guardrail.",
      ["Alerts fire at the configured thresholds (rows in `ai/rules/`, not hardcoded), deterministic on the injected `Clock`.",
       "The notification text passes the numeric guardrail before display (AI-ARC-004).",
       "Covered by tests at the 79/80/100/101% boundaries."],
      ["Thresholds are data rows (SS29); guardrail every message (AI-ARC-004)."]),
    I("4.6", 4, "Monthly budget review", "budgets", "M", "4.4",
      "SS5.5; FR-BUD-*", "SS4 AI Architecture",
      "A monthly review that summarises budget vs actual per category and proposes next-month adjustments with evidence.",
      ["A month-end summary (budget vs actual, `Money` paise) is computed deterministically on the injected `Clock`.",
       "Proposed adjustments show inputs + rule (P-02); the user accepts/edits (P-07).",
       "Golden-file test on a fixed month."],
      ["Deterministic, show-the-work, advice-only (P-02/P-07)."]),

    # ---------------- Epic 5 - Dashboard, Export & Widget ---------------- #
    I("5.1", 5, "Home dashboard v1", "dashboard;ui", "H", "2.6;4.4",
      "SS5.2; FR-DASH-*", "SS6 Module Structure",
      "The home dashboard: net worth, this-month cash flow, budgets, and recent activity - the app's landing surface, offline and accessible.",
      ["Renders net worth, month cash-flow, budget status, and recent txns from engine outputs (numbers from math, P-03).",
       "One immutable `UiState` as `StateFlow`; loading/error states covered by Turbine tests (ARC-004).",
       "Screenshot tests light/dark/200%; fully offline."],
      ["Numbers from math (P-03); immutable state via `StateFlow` (ARC-004)."]),
    I("5.2", 5, "Safe-to-Spend card", "dashboard;engine", "H", "5.1;4.3",
      "SS5.2, SS14; AI-STS", "SS4 AI Architecture, SS6 Module Structure",
      "The Safe-to-Spend figure - income minus commitments, bills, and goal contributions across the period - computed by the engine and shown with its breakdown.",
      ["Safe-to-Spend is computed deterministically from nature + upcoming commitments (P-03), in `Money` paise.",
       "The card shows the breakdown/rule that produced it (P-02); never a black-box number.",
       "Golden-file test; airplane-mode safe."],
      ["Deterministic number + shown breakdown (P-03/P-02)."]),
    I("5.3", 5, "Privacy blur toggle", "dashboard;privacy", "H", "5.1",
      "SS23; FR-PRIV-*, P-01", "SS8 Security & Privacy",
      "A one-tap privacy blur that hides all amounts on screen (and in the widget/screenshots) for shoulder-surfing and screen-sharing safety.",
      ["The toggle blurs every monetary value app-wide; state persists in DataStore.",
       "Honoured by the widget and combined with FLAG_SECURE (11.2) so amounts are not captured.",
       "A Compose UI test asserts amounts are hidden; an accessible label is present."],
      ["Privacy-first surface (P-01); no amount is visible when blurred."]),
    I("5.4", 5, "Export/import JSON archive", "export;privacy", "H", "2.5;3.1",
      "SS5.10, SS34; P-01", "SS5 Data Model, SS11 External Data & Backend",
      "A full local JSON export/import archive of the user's data (user-owned portability) with a lossless round-trip - no cloud involved.",
      ["Export writes a versioned JSON archive locally; import restores it losslessly (round-trip test).",
       "`Money` stays paise; the schema is versioned; there is no network path (P-01/P-04).",
       "Handles empty and large datasets; covered by tests."],
      ["Local, user-owned, offline export (P-01/P-04)."]),
    I("5.5", 5, "Home-screen widget (Glance)", "widget", "M", "5.2",
      "SS5.2, SS35 (widget)", "SS6 Module Structure",
      "A Glance home-screen widget showing net worth / Safe-to-Spend, honouring the privacy blur and updating from cached engine output (offline).",
      ["The Glance widget renders Safe-to-Spend/net worth from cached values and respects the privacy blur (5.3).",
       "It updates via WorkManager and works with the backend absent (P-04).",
       "Screenshot test light/dark."],
      ["Offline + privacy-aware (P-04/P-01)."]),

    # ---------------- Epic 6 - Wealth: Loans & Investments ---------------- #
    I("6.1", 6, "Credit card details + alerts", "wealth;accounts", "H", "2.5",
      "SS5.7, SS11; FR-CARD-*", "SS5 Data Model",
      "Credit-card accounts with statement/due dates, utilisation, and minimum-due - plus due-date and utilisation alerts (thresholds as data rows).",
      ["The card entity tracks limit, statement/due date, utilisation, and min-due (`Money` paise; bps for rates).",
       "Due-date and utilisation alerts fire on the injected `Clock`; thresholds are rule rows (SS29).",
       "Golden-file test on a billing cycle; alert text is guarded (AI-ARC-004)."],
      ["Rates in bps (MNY-002); thresholds are data (SS29)."]),
    I("6.2", 6, "Loans + amortisation + EMI split", "wealth;loans;money", "H", "2.5;3.3",
      "SS5.8, SS11; MNY-001, MNY-002", "SS7 Money & Time Rules",
      "Loan accounts with a deterministic amortisation schedule and an EMI principal/interest split - exact paise math, rates in basis points.",
      ["The amortisation schedule is computed deterministically; each EMI splits into principal/interest exactly (sum = EMI, property test).",
       "Rates are bps and amounts are `Money` paise; HALF_EVEN rounding with remainder handling.",
       "Golden-file test vs a known schedule; 100% money-math coverage."],
      ["100% money-math coverage; rates bps, amounts paise (MNY-001/002)."]),
    I("6.3", 6, "Investments holdings/lots + XIRR", "wealth;investments;engine", "H", "2.5",
      "SS5.8, SS11; XIRR", "SS5 Data Model, SS7 Money & Time",
      "Investment holdings tracked by lots, with a deterministic XIRR/return engine over dated cash flows - the basis for allocation and net worth.",
      ["Holdings/lots are stored; XIRR is computed deterministically over dated flows (converges, tested vs known cases).",
       "Values are `Money` paise; the injected `Clock` drives date maths; provenance is attached to results.",
       "Golden-file + property tests (empty, single-flow, loss cases)."],
      ["Deterministic engine with provenance (P-08/AI-ARC-003)."]),
    I("6.4", 6, "Allocation & diversification (AI-INV)", "wealth;investments;ai", "M", "6.3",
      "SS5.8; AI-INV", "SS4 AI Architecture",
      "An allocation/diversification engine that classifies holdings into asset classes and flags concentration - advice with evidence, never an order.",
      ["Allocation % by asset class is computed from holdings (numbers from math, P-03); concentration is flagged against rule rows.",
       "Recommendations show inputs + rule (P-02) and are advisory (P-07).",
       "Golden-file test."],
      ["Advice not orders (P-07); thresholds are data (SS29)."]),
    I("6.5", 6, "Gold/crypto valuation via market API", "wealth;market-data", "M", "6.3",
      "SS16, SS22; P-01, P-04", "SS11 External Data & Backend",
      "Valuation of gold/crypto holdings from market prices fetched through the backend proxy - cached, staleness-labelled, and fully degradable offline.",
      ["Prices come through our backend proxy (never scraped on-device, SS16/SS22), cached with a fetched-at timestamp.",
       "Offline shows the last cached value with a staleness label (P-04); no core path blocks on the network.",
       "The network is consent-gated; covered by tests with the backend absent."],
      ["Market data via the backend proxy only; degrade to cached + staleness (P-04)."]),
    I("6.6", 6, "Net-worth snapshots history", "wealth", "M", "2.6",
      "SS5.7; FR-NW-*", "SS5 Data Model",
      "Persisted net-worth snapshot history with trend queries - an exact, reproducible time series from the daily snapshots (2.6).",
      ["Snapshot history is queryable by range; the trend derives from stored snapshots (no recompute drift).",
       "Values are `Money` paise; charts come from the design system; offline.",
       "Golden-file test on a seeded series."],
      ["Reproducible history from stored snapshots (P-08)."]),

    # ---------------- Epic 7 - Goals & Emergency Fund ---------------- #
    I("7.1", 7, "Goals engine (AI-GOAL)", "goals;engine", "H", "3.1;5.1",
      "SS10, SS15, SS36; AI-GOAL", "SS4 AI Architecture",
      "The goals engine: target amount + date -> required monthly contribution and projected completion, deterministic and evidence-backed.",
      ["Required monthly contribution and projection are computed deterministically (P-03), in `Money` paise on the injected `Clock`.",
       "The result carries provenance + evidence and shows the rule/formula (P-02).",
       "Golden-file + property tests (past-due, zero-target, over-funded)."],
      ["Numbers from math with provenance (P-03/AI-ARC-003)."]),
    I("7.2", 7, "Emergency Fund engine (AI-EMF)", "emergency;engine", "H", "4.3",
      "SS15, SS36; AI-EMF", "SS4 AI Architecture",
      "The emergency-fund engine: target months x essential (Need) spend -> funded ratio and top-up plan, driven by nature classification.",
      ["Target = configurable months x essential monthly spend (from nature, 4.3); funded ratio + shortfall computed deterministically.",
       "The months factor is a rule row (SS29); the result shows inputs + rule (P-02).",
       "Golden-file test."],
      ["Thresholds as data (SS29); explainable output (P-02)."]),
    I("7.3", 7, "Goal feasibility & waterfall", "goals", "H", "7.1;9.2",
      "SS10, SS15; AI-GOAL", "SS4 AI Architecture",
      "Feasibility of concurrent goals against the forecast, plus a contribution waterfall that prioritises goals when surplus is limited.",
      ["Feasibility uses the cash-flow forecast (9.2) to flag under-funded goals deterministically.",
       "The waterfall allocates limited surplus by priority; each allocation is `Money` paise and shown (P-02).",
       "Golden-file test on competing goals."],
      ["Deterministic allocation, shown work, advice-only (P-02/P-07)."]),
    I("7.4", 7, "Linked contributions", "goals", "M", "7.1",
      "SS10, SS15", "SS4 AI Architecture, SS5 Data Model",
      "Link transactions/accounts to goals so contributions update goal progress automatically with correct paise accounting.",
      ["Linking a txn/account updates goal progress exactly (sum of contributions, `Money` paise).",
       "Unlinking reverses it; provenance is retained; per-profile scoped.",
       "Covered by tests including reversal."],
      ["Exact paise accounting; reversible (MNY-001)."]),
    I("7.5", 7, "Financial Order of Operations (AI-FOO)", "goals;engine", "M", "7.2;9.2",
      "SS36; `ai/rules/financial-order-of-operations.json`", "SS4 AI Architecture",
      "The Financial Order of Operations engine - sequences surplus across emergency fund, high-interest debt, goals, and investing - from the versioned FOO rule set.",
      ["The next best action is derived from the FOO rule rows (data, not code) given the user's state; deterministic.",
       "The output shows which rule fired and why (P-02); advisory only (P-07).",
       "Golden-file test across representative states."],
      ["Sequence from `financial-order-of-operations.json` (data); advice-only (P-07)."]),

    # ---------------- Epic 8 - Backup & Restore ---------------- #
    I("8.1", 8, "E2EE backup (Argon2id + AES-256-GCM)", "backup;crypto", "H", "1.6",
      "SS23.3, SS34.4; SEC-003", "SS8 Security & Privacy",
      "End-to-end-encrypted backup: passphrase -> Argon2id KDF -> AES-256-GCM (via Tink) over the exported archive - the platform never sees plaintext or key.",
      ["Backup encrypts the archive with AES-256-GCM keyed by an Argon2id-derived key (Tink; no hand-rolled crypto, SEC-003).",
       "A unique IV/nonce is used per backup; the passphrase is never stored; off-device writes are consent-gated (P-01).",
       "Round-trip decrypt test; a tampered tag is rejected."],
      ["Tink/Keystore only, no hand-rolled crypto (SEC-003); unique nonce per row."]),
    I("8.2", 8, "Restore on fresh device", "backup", "H", "8.1",
      "SS23.3, SS34.4", "SS8 Security & Privacy",
      "Restore the encrypted backup onto a fresh install/device - the passphrase decrypts and rehydrates the full store, verified by an integrity check.",
      ["A fresh-device restore rebuilds the DB from the backup; integrity/version is checked before apply.",
       "A wrong passphrase fails safely with no partial write; `Money` values are exact after restore.",
       "Instrumented round-trip test (backup -> wipe -> restore)."],
      ["Fail-secure on a bad passphrase; atomic restore (P-01)."]),
    I("8.3", 8, "Backup restore drill", "backup;testing", "M", "8.2",
      "SS21.5, SS34.4", "SS10 Testing",
      "An automated restore drill in the instrumented tests proving backups are actually restorable (guards against silent backup rot).",
      ["The drill generates a backup on a seeded DB, restores on a clean instance, and asserts row parity.",
       "It runs as an instrumented test; failure blocks release.",
       "It is documented as a recurring release-gate step."],
      ["Backups are proven restorable, not assumed (SS21.5)."]),

    # ---------------- Epic 9 - AI Core Engines ---------------- #
    I("9.1", 9, "Fixed/Variable + Nature engine (AI-CLS Stage 2)", "engine;ai", "H", "4.3",
      "SS8.2, SS14; AI-CLS", "SS4 AI Architecture",
      "Stage-2 classification: label each expense fixed vs variable and finalise nature - the structured signal forecasting and health scoring consume.",
      ["Deterministic fixed/variable + nature labels with evidence/provenance (AI-ARC-003); the engine is versioned.",
       "Consistent with Stage-1 (4.2) and nature (4.3); the override path is data.",
       "Golden-file test on a labelled fixture; engine coverage >= 85%."],
      ["Deterministic + provenance + versioned (P-08/AI-ARC-003/006)."]),
    I("9.2", 9, "Cash-flow forecast (AI-FCT)", "engine;forecast", "H", "9.1;3.7",
      "SS9, SS17; AI-FCT, forecast backtests", "SS4 AI Architecture",
      "The cash-flow forecast engine: project balances forward from recurring detection + fixed/variable classification, with confidence and backtest-gated accuracy.",
      ["The forecast projects period balances deterministically from recurring (3.7) + classification (9.1), carrying confidence + evidence.",
       "Monotonic identities hold (property tests); backtests meet the frozen accuracy threshold (SS21.5).",
       "`Money` paise; the injected `Clock`; airplane-mode safe."],
      ["Deterministic, evidence-backed, backtest-gated (P-08/SS21.5)."]),
    I("9.3", 9, "Seasonality (AI-SEAS)", "engine;forecast", "M", "9.2",
      "SS17, SS38; AI-SEAS, `ai/knowledge/calendar-seasonality.json`", "SS4 AI Architecture",
      "A seasonality adjustment (festivals, school fees, insurance renewals from the seasonality KB) layered onto the base forecast.",
      ["Seasonal factors come from the versioned seasonality KB (data, not code) and are applied deterministically to the forecast.",
       "The adjustment is shown separately with the rule that fired (P-02).",
       "Golden-file test across seasonal months."],
      ["Factors are data rows (SS29); show the adjustment (P-02)."]),
    I("9.4", 9, "Financial Health Score (AI-FHS)", "engine;health-score", "H", "9.1;7.2",
      "SS14; AI-FHS", "SS4 AI Architecture",
      "The Financial Health Score: a deterministic composite (savings rate, emergency-fund ratio, debt load, etc.) with a full component breakdown.",
      ["The score is computed deterministically from weighted components (weights = rule rows); the number is from math (P-03).",
       "Every component and its contribution are shown (P-02); provenance + version attached.",
       "Golden-file test; engine coverage >= 85%."],
      ["Score from math with shown components (P-03/P-02); weights are data."]),
    I("9.5", 9, "Insight Orchestrator + feed", "engine;orchestrator", "H", "9.2;9.4",
      "SS7; `ai/orchestrator/insight-orchestrator.yaml`", "SS4 AI Architecture",
      "The Insight Orchestrator: runs the engine registry in dependency order (L1->L6) and assembles the ranked insight feed with provenance on every card.",
      ["The orchestrator executes engines per the registry/workflow YAML in layer order; each insight carries engineId/version/evidence (AI-ARC-003/006).",
       "Feed ranking is deterministic; stale insights are recomputed on new data.",
       "Golden-file test on a fixed engine set."],
      ["Layered pipeline; provenance + engine version on every result (AI-ARC-003/006)."]),
    I("9.6", 9, "Notification engine + policy", "notifications;engine", "H", "9.5",
      "SS30; notification policy", "SS4 AI Architecture",
      "A notification engine with a rate/priority policy so insights and alerts reach the user without spam - every message guardrailed.",
      ["The policy (frequency caps, priority, quiet hours) is applied deterministically on the injected `Clock`; config is data rows.",
       "Every outgoing message passes the numeric guardrail (AI-ARC-004) before display.",
       "Covered by tests at cap/quiet-hour boundaries."],
      ["Guardrail every message (AI-ARC-004); the policy is data."]),
    I("9.7", 9, "Guardrail (AI-ARC-004)", "ai;guardrail", "H", "9.5",
      "`ai/chat/guardrail.md`; AI-ARC-004, P-03", "SS4 AI Architecture",
      "The numeric guardrail every LLM-verbalised output must pass: figures in the text must match engine outputs exactly, or the message is blocked/repaired.",
      ["The guardrail parses figures from LLM text and verifies each against engine provenance; a mismatch is blocked/repaired (AI-ARC-004).",
       "No unverified number can reach the UI (P-03); violations are logged (no PII/amounts in release logs).",
       "Unit tests for pass, mismatch, missing-evidence, and formatting cases."],
      ["Numbers from math; guardrail before display (P-03/AI-ARC-004)."]),

    # ---------------- Epic 10 - Advisor & Chat ---------------- #
    I("10.1", 10, "Purchase Advisor (AI-PA) + trace card", "purchase-advisor;engine", "H", "9.2;7.1",
      "SS13; AI-PA", "SS4 AI Architecture",
      "The Purchase Advisor: can-I-afford-this? runs affordability against forecast, goals, and Safe-to-Spend and returns a verdict with a full trace card.",
      ["The verdict (yes/wait/no) is computed deterministically from forecast (9.2), goals (7.1), and Safe-to-Spend; advisory only (P-07).",
       "The trace card shows every input + the rule/model that fired + a plain-language reason (P-02).",
       "Golden-file test across affordable/borderline/unaffordable cases."],
      ["Advice not orders (P-07); show the full trace (P-02)."]),
    I("10.2", 10, "Buy List & adaptive interview (AI-PA-INT)", "purchase-advisor", "M", "10.1",
      "SS13; AI-PA-INT", "SS4 AI Architecture",
      "A saved buy-list plus an adaptive interview that gathers only the missing facts needed to advise on a purchase.",
      ["Buy-list CRUD; each item is re-evaluated by the advisor (10.1) as finances change.",
       "The interview asks only for missing inputs with deterministic branching; answers are stored as data.",
       "Covered by tests."],
      ["Ask only what is needed; advice-only (P-07)."]),
    I("10.3", 10, "Simulators (prepay-vs-invest, payoff)", "engine;simulator", "M", "6.2;7.5",
      "SS36; simulators", "SS4 AI Architecture",
      "What-if simulators (loan prepay vs invest, debt-payoff strategies) that compute exact outcomes side-by-side - simulate, never execute (P-07).",
      ["Each simulator computes deterministic outcomes in `Money` paise / bps and compares scenarios with the math shown (P-02).",
       "No money is moved - simulation only (P-07).",
       "Golden-file tests vs known scenarios."],
      ["Simulate, don't execute (P-07); exact money math (MNY-001/002)."]),
    I("10.4", 10, "Vehicle & maintenance prediction (AI-VEH)", "engine;vehicle", "M", "9.2",
      "SS38; AI-VEH, `ai/knowledge/vehicle-maintenance-kb.json`", "SS4 AI Architecture",
      "A vehicle running-cost and maintenance-due predictor driven by the vehicle-maintenance KB and usage, folded into the forecast.",
      ["Service-due and running-cost predictions come from the versioned vehicle KB (data) + usage; deterministic with evidence.",
       "Predicted costs flow into the forecast (9.2) and are shown with the rule that fired (P-02).",
       "Golden-file test."],
      ["KB-driven (data, SS29); explainable (P-02)."]),
    I("10.5", 10, "Chat assistant (on-device LLM) + tool registry", "chat;llm;ai", "H", "9.5;9.7",
      "SS19, SS30; `ai/skills/tool-registry.json`, `ai/chat/system-prompt.md`", "SS4 AI Architecture",
      "The on-device chat assistant: an LLM (AICore/MediaPipe behind `LlmEngine`) that answers via the tool registry and only ever verbalises engine numbers.",
      ["The LLM runs on-device behind the `LlmEngine` interface; cloud is opt-in and receives only the structured context pack (SS19.4), never raw transactions (P-01).",
       "Answers are produced by calling registered tools; every figure comes from an engine and passes the guardrail (P-03/AI-ARC-004).",
       "Covered by tests with a stubbed LLM; the offline path works."],
      ["On-device by default; the LLM verbalises, never computes (P-01/P-03)."]),
    I("10.6", 10, "Chat guardrail eval", "chat;ai;testing", "H", "10.5;9.7",
      "SS21.5; AI-ARC-004", "SS10 Testing",
      "A frozen evaluation set for the chat guardrail - proves the assistant never emits an unverified or hallucinated number (regression-gated).",
      ["An eval dataset of prompts/answers asserts zero unverified figures pass the guardrail (AI-ARC-004).",
       "The regression threshold blocks merges (SS21.5); adversarial cases are included.",
       "It runs in CI."],
      ["The guardrail eval gates merges (SS21.5)."]),
    I("10.7", 10, "Market Signal Engine (AI-MKT) + Opportunity screen", "engine;market-signal", "M", "6.5;9.2",
      "SS16, SS39; AI-MKT, `ai/knowledge/market-signals.json`", "SS4 AI Architecture, SS11 External Data & Backend",
      "A market-signal engine that turns proxy-fetched market data + the signals KB into opportunity insights - advisory, offline-degradable, never auto-acting.",
      ["Signals are derived from proxy data (6.5) + the versioned KB; deterministic with provenance; degrade to cached + staleness offline (P-04).",
       "The Opportunity screen shows each signal's inputs + rule (P-02); advisory only (P-07).",
       "Golden-file test."],
      ["Advice not orders (P-07); offline-degradable (P-04)."]),
    I("10.8", 10, "Localisation (Hindi + 2)", "localisation;ui", "M", "5.1",
      "SS21.6, SS31 (i18n); ACC-*", "SS6 Module Structure",
      "Localise the app into Hindi plus two more Indian languages, with ICU plurals and Indian number/date formatting throughout.",
      ["All user-visible strings are externalised with ICU plurals; Hindi + 2 locales complete (no hardcoded strings, SS21.6).",
       "Indian digit grouping and locale date formatting; RTL-safe layouts.",
       "Screenshot tests per locale; lint blocks hardcoded strings."],
      ["Every string in `strings.xml` with ICU plurals (SS21.6)."]),

    # ---------------- Epic 11 - Privacy, Security & Compliance ---------------- #
    I("11.1", 11, "SQLCipher key management (StrongBox)", "security;crypto", "H", "1.6",
      "SS23; SEC-003", "SS8 Security & Privacy",
      "Harden the DB key lifecycle: generate/store in Keystore StrongBox where available, with rotation and no key material ever leaving the TEE.",
      ["The key is generated in the Keystore (StrongBox when present) and is never exported or logged (SEC-003).",
       "A rotation path re-keys the DB without data loss (tested); the StrongBox-absent fallback is documented.",
       "Security-review sign-off recorded."],
      ["Keystore/Tink only; the key never leaves the TEE (SEC-003)."]),
    I("11.2", 11, "FLAG_SECURE + screen-capture guard", "security;privacy", "H", "1.10",
      "SS23; FR-PRIV-*", "SS8 Security & Privacy",
      "Set FLAG_SECURE on sensitive screens to block screenshots and screen recording, and hide amounts in the app switcher.",
      ["FLAG_SECURE is applied to financial screens; screenshots/recording are blocked there (manual + instrumented check).",
       "The app-switcher preview hides amounts and integrates with the privacy blur (5.3).",
       "Covered by an instrumented test where feasible."],
      ["Sensitive surfaces are capture-guarded (P-01)."]),
    I("11.3", 11, "Consents dashboard + one-tap revoke", "privacy;compliance", "H", "1.9",
      "SS23; DPDP, P-01", "SS8 Security & Privacy",
      "A consents dashboard listing every per-feature consent with grant time and one-tap revoke - the user's control surface over all optional data flows.",
      ["Every consent (SMS, network, backup, cloud LLM ...) is listed with state + timestamp; one-tap revoke takes effect immediately (P-01).",
       "Revoking disables the dependent feature and stops its data flow at once.",
       "Covered by tests; strings externalised."],
      ["Consent is explicit, per-feature, and revocable (P-01)."]),
    I("11.4", 11, "Erase-all (crypto-shredding)", "security;privacy", "H", "1.6;11.1",
      "SS23, SS34; SEC-003", "SS8 Security & Privacy",
      "Erase-all that crypto-shreds the data by destroying the Keystore key (rendering ciphertext unrecoverable) then wiping the store - irreversible by design.",
      ["Erase destroys the DB key in the Keystore and wipes DB/backups/caches; the data is cryptographically unrecoverable (SEC-003).",
       "It requires explicit confirmation + auth; an audit event is recorded (no PII).",
       "An instrumented test verifies no readable data remains."],
      ["Crypto-shred by key destruction; irreversible and confirmed (P-01/SEC-003)."]),
    I("11.5", 11, "DPDP Act 2023 alignment", "compliance", "H", "11.3",
      "SS23, SS32; DPDP", "SS8 Security & Privacy",
      "Align data handling with India's DPDP Act 2023: purpose limitation, consent records, and data-principal rights (access/erase/portability) - documented and evidenced.",
      ["Consent records, purpose strings, and rights flows (access via export 5.4, erase 11.4) are mapped to DPDP requirements.",
       "A compliance doc/ADR traces each requirement to its implementation.",
       "No off-device processing without a lawful consent basis (P-01)."],
      ["Purpose-limited, consent-based, rights-respecting (DPDP/P-01)."]),
    I("11.6", 11, "Dependency scan (OSV) + minified release", "security;ci", "H", "1.1",
      "SS21.3; SEC-007", "SS9 Build/CI/Release",
      "CI dependency scanning (OSV) and a minified/shrunk (R8) release build with resource shrinking and log stripping - supply-chain and release hardening.",
      ["OSV (or equivalent) scans dependencies in CI; new criticals block the build (SEC-007).",
       "The release build is R8-minified with PII/amount log stripping verified (SS21.6).",
       "New third-party deps are justified in the issue."],
      ["Deps are scanned; the release strips PII/amount logs (SEC-007/SS21.6)."]),
    I("11.7", 11, "No-hand-rolled-crypto (Tink) audit", "security;crypto", "H", "1.6;8.1",
      "SEC-003", "SS8 Security & Privacy",
      "An audit + lint pass ensuring all cryptography goes through Tink/Keystore - no custom crypto anywhere in the codebase (review-blocking).",
      ["The audit confirms every crypto operation uses Tink/Keystore; a lint/CI check flags raw `javax.crypto` misuse (SEC-003).",
       "Findings are resolved and a security-review sign-off is recorded.",
       "A test/CI rule prevents regressions."],
      ["No hand-rolled crypto anywhere (SEC-003, review-blocking)."]),

    # ---------------- Epic 12 - Quality, Testing & Release ---------------- #
    I("12.1", 12, "Engine golden-file + property test harness", "testing", "H", "1.4",
      "SS21.5; P-08", "SS10 Testing",
      "A shared harness for engine testing: golden-file snapshots (fixed input JSON -> expected result JSON), property tests, and seeded-determinism helpers.",
      ["The reusable harness supports golden-file, property, and seeded-determinism tests for any engine (SS21.5).",
       "The snapshot-update workflow is documented; determinism is enforced via an injected seed source (P-08).",
       "At least one engine adopts it as a reference."],
      ["Every engine: fixed input -> fixed output, seeded only via injection (P-08)."]),
    I("12.2", 12, "AI evaluation datasets (categorisation/OCR/forecast)", "testing;ai", "H", "4.2;3.8;9.2",
      "SS21.5; cat >= 92% / receipts >= 95% / forecast backtests", "SS10 Testing",
      "Frozen, labelled evaluation datasets for categorisation, OCR receipts, and forecast backtests, with regression thresholds that block merges.",
      ["Datasets are frozen and versioned; runners report accuracy vs thresholds (cat >= 92%, receipts >= 95%, forecast backtest).",
       "Regressions block merges in CI (SS21.5).",
       "Extending a dataset is documented."],
      ["Frozen datasets; regression thresholds gate merges (SS21.5)."]),
    I("12.3", 12, "Paparazzi screenshot tests", "testing;ui", "M", "1.8",
      "SS21.5", "SS10 Testing",
      "Paparazzi screenshot coverage for design-system components and key screens in light/dark/200% font, with diffs gating merges.",
      ["Screenshot tests cover design-system components + critical screens (light/dark/200%).",
       "Diff failures block merges; baselines are checked in.",
       "The update flow is documented."],
      ["DS components have screenshot tests; diffs gate merges (SS21.5)."]),
    I("12.4", 12, "Instrumented E2E smoke + airplane-mode", "testing;e2e", "H", "5.1;5.4",
      "SS21.5; P-04", "SS10 Testing",
      "An instrumented end-to-end smoke: onboard -> add data -> verify dashboard/forecast -> export/import round-trip -> airplane-mode pass - the offline-first release gate.",
      ["The E2E test runs the full core flow on an emulator including an airplane-mode leg (P-04).",
       "The export/import round-trip is asserted; failure blocks release.",
       "It runs via `connectedDebugAndroidTest` in CI where a device is available."],
      ["The core flow must pass with the backend absent (P-04)."]),
    I("12.5", 12, "CI gates + release train", "ci;release", "H", "1.1;12.1",
      "SS21.6, SS26; SemVer", "SS9 Build/CI/Release",
      "The full CI gate set (build, unit+coverage, lint/detekt, screenshot, E2E, dep scan) and the SemVer release train wired to VERSION/CHANGELOG.",
      ["CI enforces all gates; coverage (engine >= 85%, money 100%) and AI-eval thresholds block merges.",
       "The release train bumps `VERSION`/`versionName`/`versionCode` and updates `CHANGELOG.md` per SemVer (SS26).",
       "A tagged release build is produced and documented."],
      ["All gates green before release; `VERSION` is the single source (SS21.6)."]),

    # ---------------- Epic 13 - Expansion (design-for) ---------------- #
    I("13.1", 13, "Household mode", "expansion", "L", "2.5",
      "SS27, SS33", "SS12 Greenfield note, SS14 Out of Scope",
      "Design-for household mode: multiple profiles under one household with per-profile scoping and shared/aggregated views (foundations only in v1).",
      ["The data model supports household -> profiles with strict per-profile scoping (no cross-leak).",
       "Aggregation views are specified; v1 ships the scoping foundation behind a flag.",
       "An ADR captures the design."],
      ["Per-profile isolation from day one (P-01); feature-flagged."]),
    I("13.2", 13, "Appliances maintenance", "expansion;vehicle", "L", "10.4",
      "SS38", "SS4 AI Architecture",
      "Extend the vehicle-predictor pattern to household appliances (warranty/service due, running cost), reusing the KB-driven engine.",
      ["Appliance entities + KB rows reuse the 10.4 engine pattern; predictions are deterministic with evidence.",
       "Feature-flagged; golden-file test."],
      ["KB-driven, explainable, flagged (SS29/P-02)."]),
    I("13.3", 13, "Insurance/protection suite (AI-INS)", "expansion;ai", "L", "9.4",
      "SS33; AI-INS", "SS4 AI Architecture",
      "Design-for an insurance/protection-gap engine (life/health/vehicle coverage adequacy) as advisory insights.",
      ["Coverage-gap logic is specified against rule rows; deterministic + advisory (P-07).",
       "Feature-flagged; an ADR + golden-file skeleton exist."],
      ["Advice not orders (P-07); thresholds are data (SS29)."]),
    I("13.4", 13, "Tax engine v2 (AI-TAX)", "expansion;ai;tax", "L", "6.3",
      "SS33; `ai/knowledge/tax-kb-fy2025-26.json`, AI-TAX", "SS4 AI Architecture, SS11 External Data & Backend",
      "Design-for a fuller tax engine (regime comparison, capital-gains, deductions) driven by the versioned tax KB - estimates, not filing.",
      ["Tax computations come from versioned tax-KB rows (data); deterministic; clearly labelled estimates (P-07).",
       "Regime comparison shows the math (P-02); feature-flagged.",
       "An ADR + golden-file skeleton exist."],
      ["Tax params are data rows (SS29); estimates only (P-07)."]),
    I("13.5", 13, "Business mode", "expansion", "L", "2.5",
      "SS27, SS33", "SS12 Greenfield note",
      "Design-for a lightweight business/freelancer mode (separate books, GST-aware categories) built on the same engines.",
      ["A separate business profile/book is specified with isolation; a GST-aware category design is captured.",
       "Feature-flagged; ADR only in v1."],
      ["Isolated books; per-profile scoping (P-01), flagged."]),
    I("13.6", 13, "Account Aggregator integration", "expansion;integration", "L", "3.5",
      "SS16, SS22; AA framework", "SS11 External Data & Backend",
      "Design-for India's Account Aggregator consented data ingestion through the backend - strictly consent-gated, staleness-labelled, and offline-degradable.",
      ["AA ingestion is designed via the backend with explicit, revocable consent (P-01); imported txns are tagged source = AA (3.5).",
       "It degrades to cached + staleness offline (P-04); an ADR + interface stub exist in v1."],
      ["Consent-gated, provenance-tagged, offline-degradable (P-01/P-04)."]),
    I("13.7", 13, "iOS via KMP", "expansion;kmp", "L", "1.1",
      "SS27, SS39", "SS6 Module Structure, SS12 Greenfield note",
      "Design-for sharing the pure-Kotlin domain/engine layer to iOS via Kotlin Multiplatform - validated by keeping engines Android-free (ARC-002).",
      ["Confirm `:core:model` + `:domain:*` stay Android-free so they are KMP-portable (ARC-002).",
       "A KMP feasibility ADR + a spike sharing one engine exist."],
      ["Engines stay pure Kotlin/JVM (ARC-002) to preserve portability."]),
]

PRIORITY_WORD = {"H": "High", "M": "Medium", "L": "Low"}


def polish(text: str) -> str:
    """Upgrade ASCII stand-ins in rendered markdown to the repo's Unicode conventions.

    Why: the ISSUES table is authored in plain ASCII (`SS`, `>=`, `->`, `Rs`) so it is easy to
    edit and diff, but the rest of the repo (CLAUDE.md, 00-issue-workflow.md) uses the section
    sign, math comparators, arrows, and the Rupee sign. This keeps the generated files
    visually consistent with those hand-written docs.
    What: substitutes the section token `SS<digit>` -> `§`, `>=`/`<=` -> `≥`/`≤`, `->` -> `→`,
    ` x ` -> ` × `, and `Rs ` -> `₹`.
    Result: the same markdown with the polished glyphs; written as UTF-8.
    Changed: 2026-07-18 - created.
    Input: rendered markdown (str). Output: polished markdown (str).
    """
    text = re.sub(r"SS(?=\d)", "§", text)
    text = text.replace(">=", "≥").replace("<=", "≤")
    text = text.replace("->", "→")
    text = text.replace(" x ", " × ")
    text = text.replace("Rs ", "₹")
    return text


def slugify(title: str, max_len: int = 50) -> str:
    """Turn an issue title into a filesystem-safe slug, truncated like the reference.

    Why: filenames must be stable, lowercase, and bounded so links never break.
    What: lowercases, replaces every run of non-alphanumerics with a single dash, trims,
    and caps the length (mirroring the reference's ~50-char truncation).
    Result: a slug such as `gradle-multi-module-skeleton-version-catalog-ci`.
    Changed: 2026-07-18 - created.
    Input: `title` (str), optional `max_len` (int).
    Output: slug (str), no leading/trailing dash.
    """
    slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    if len(slug) > max_len:
        slug = slug[:max_len].rstrip("-")
    return slug


def skills_for(issue: Issue) -> list[str]:
    """Resolve the ordered, de-duplicated skill list for an issue from its labels.

    Why: each issue's Skill Rules table must reflect the label -> skill map plus the
    universal skills, with no duplicates and a stable order.
    What: walks the labels in order, appends each label's mapped skills, then the always-on
    skills, keeping first occurrence.
    Result: a list of skill names present in SKILL_REGISTRY.
    Changed: 2026-07-18 - created.
    Input: an `Issue`. Output: list[str] of skill names.
    """
    ordered: list[str] = []
    for label in issue.labels:
        for skill in LABEL_SKILLS.get(label, []):
            if skill not in ordered:
                ordered.append(skill)
    for skill in ALWAYS_SKILLS:
        if skill not in ordered:
            ordered.append(skill)
    return ordered


def guiding_principles(issue: Issue) -> list[str]:
    """Select the guiding-principle lines relevant to an issue.

    Why: the issue file mirrors the reference's "Guiding Principles" block, but the relevant
    golden rules depend on what the issue touches (money, time, security, engines, UI).
    What: starts from the always-binding principles and adds label-conditional ones.
    Result: a list of markdown bullet strings.
    Changed: 2026-07-18 - created.
    Input: an `Issue`. Output: list[str].
    """
    lines = [
        "**P-03 Numbers from math, words from AI** - engines compute every figure; the LLM only verbalises.",
        "**P-01 Privacy first** - no financial data leaves the device without explicit, revocable, per-feature consent.",
        "**P-02 Show the work** - every output shows its inputs + the rule/model that fired + a plain-language reason.",
        "**P-08 Deterministic & testable** - fixed input -> fixed output; randomness only via an injected, seedable source.",
    ]
    labels = set(issue.labels)
    if labels & {"money", "core"}:
        lines.append("**MNY-001/002** - Money is `Long` paise; rates are integer basis points; no `Double`/`Float` on money.")
    if "time" in labels:
        lines.append("**TIM-001/002** - injected `Clock`, UTC epoch millis, ISO `LocalDate`; never `System.currentTimeMillis()` in domain.")
    if labels & {"security", "crypto", "privacy", "compliance", "backup", "auth", "sms"}:
        lines.append("**SEC-003 / P-01** - Tink/Keystore only (no hand-rolled crypto); every off-device path is consent-gated.")
    if labels & {"engine", "ai", "forecast", "health-score", "orchestrator", "vehicle", "market-signal", "guardrail", "purchase-advisor", "simulator"}:
        lines.append("**AI-ARC-003/006** - every engine result carries provenance (engineId/version/inputWindow/computedAt/confidence/evidence).")
    if labels & {"ui", "designsystem", "dashboard", "onboarding", "localisation", "widget"}:
        lines.append("**SS21.6 theming/i18n** - strings in `strings.xml` (ICU plurals); colours/dimensions from theme tokens.")
    if labels & {"goals", "budgets", "purchase-advisor", "simulator", "market-signal", "emergency"}:
        lines.append("**P-07 Advice, never orders** - the app recommends and simulates; the user decides. No auto-executed money movement.")
    return lines


def label_specific_rules(issue: Issue) -> list[str]:
    """Collect the label-specific workflow rule lines for an issue.

    Why: the "While Solving -> Label-specific" block must reflect the issue's labels.
    What: gathers unique lines from LABEL_RULES for each label; falls back to a generic line.
    Result: a non-empty list of rule strings.
    Changed: 2026-07-18 - created.
    Input: an `Issue`. Output: list[str].
    """
    lines: list[str] = []
    for label in issue.labels:
        for rule in LABEL_RULES.get(label, []):
            if rule not in lines:
                lines.append(rule)
    if not lines:
        lines.append(GENERIC_LABEL_RULE)
    return lines


def deps_display(issue: Issue) -> str:
    """Render an issue's dependency list for the issue header.

    Why: the header shows human-readable dependencies or 'None'.
    What: joins dep IDs with commas, or returns 'None'.
    Result: a string. Changed: 2026-07-18 - created.
    Input: an `Issue`. Output: str.
    """
    return ", ".join(issue.deps) if issue.deps else "None"


def branch_name(issue: Issue, slug: str) -> str:
    """Compute the working branch name for an issue.

    Why: the workflow uses `feature/<id-with-dashes>-<slug>` branches.
    What: replaces dots in the id with dashes and appends the slug.
    Result: e.g. `feature/1-2-money-value-class...`. Changed: 2026-07-18 - created.
    Input: an `Issue` and its slug. Output: str.
    """
    return f"feature/{issue.id.replace('.', '-')}-{slug}"


def tracker_phases(issue: Issue) -> list[tuple[str, str]]:
    """Build the phase checklist rows for an issue's tracker, tailored to its labels.

    Why: a database issue needs a migration phase, a UI issue a screenshot phase, a core-flow
    issue an E2E phase - the checklist should match the work.
    What: assembles the always-present phases and inserts conditional ones based on labels.
    Result: an ordered list of (phase, verification-hint) tuples.
    Changed: 2026-07-18 - created.
    Input: an `Issue`. Output: list[tuple[str, str]].
    """
    labels = set(issue.labels)
    phases: list[tuple[str, str]] = [
        ("Dependencies clear or waived", f"deps: {deps_display(issue)}"),
        ("Branch created", "`git branch --show-current` -> feature/..."),
        ("Failing tests written (TDD)", "new tests are red before implementation (`./gradlew testDebugUnitTest`)"),
        ("Implementation to acceptance criteria", "all acceptance criteria above are met"),
        ("Static analysis clean", "`./gradlew ktlintCheck detekt lintDebug` - no new warnings"),
        ("Unit + coverage gate", "`./gradlew testDebugUnitTest koverVerify` - engine >= 85%, money 100%"),
    ]
    if labels & {"database"}:
        phases.append(("Migration test green", "schema migration test passes; no destructive migration (DB-003)"))
    if labels & {"ui", "designsystem", "dashboard", "onboarding", "localisation", "widget"}:
        phases.append(("Screenshot tests", "`./gradlew verifyPaparazziDebug` - light/dark/200%"))
    if labels & {"ai", "forecast", "ocr", "ml", "categorisation"}:
        phases.append(("AI evaluation datasets", "frozen datasets meet thresholds (SS21.5)"))
    phases.append(("Run on emulator (real gate)", "`/run` + `/verify` - changed behaviour observed on a device"))
    if labels & {"transactions", "dashboard", "onboarding", "accounts", "app", "e2e", "export", "widget", "backup"}:
        phases.append(("E2E smoke + airplane-mode", "`./gradlew connectedDebugAndroidTest` incl. airplane-mode (P-04)"))
    phases.append(("Docs + VERSION + CHANGELOG", "ENGINE.md/ADR as needed; `VERSION` + `CHANGELOG.md` bumped"))
    phases.append(("Merge to `main` + push", "merged to `main`; Verification Log complete; pushed"))
    return phases


def render_issue(issue: Issue, slug: str) -> str:
    """Render a single issue's `<id>-<slug>.md` in the reference format.

    Why: every issue file must share the exact section layout captured from the reference
    project so the workflow and tooling can rely on it.
    What: assembles the header, description, acceptance criteria, Skill Rules table, guiding
    principles, the three-tier workflow rules, DoD, verification placeholder, files-changed
    placeholder, and the sub-task link.
    Result: a complete markdown string.
    Changed: 2026-07-18 - created.
    Input: an `Issue` and its slug. Output: markdown (str).
    """
    epic = EPICS[issue.epic]
    labels_md = ", ".join(f"`{x}`" for x in issue.labels)
    tracker_file = f"{issue.id}-{slug}-tracker.md"

    lines: list[str] = []
    lines.append(f"# [{issue.id}] {issue.title}")
    lines.append("")
    lines.append(f"**Status:** Todo  ")
    lines.append(f"**Epic:** Epic {issue.epic} — {epic['title']}  ")
    lines.append(f"**Priority:** {PRIORITY_WORD[issue.pri]}  ")
    lines.append(f"**Depends on:** {deps_display(issue)}  ")
    lines.append(f"**Assignee:** {ASSIGNEE}  ")
    lines.append(f"**Labels:** {labels_md}")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Description")
    lines.append("")
    lines.append(issue.summary)
    lines.append("")
    lines.append("Master blueprint (source of truth): `docs/init/AI_Personal_CFO_SRS_v1.7.pdf` - cite the requirement")
    lines.append(f"IDs in code and commits. Planning design spec: [`2026-07-17-ai-personal-cfo-design.md`]({DESIGN_SPEC_REL}).")
    lines.append("Master workflow: [`00-issue-workflow.md`](00-issue-workflow.md). Binding rules: `CLAUDE.md`.")
    lines.append("")
    lines.append(f"**SRS sections:** {issue.srs}  ")
    lines.append(f"**Design spec section:** {issue.spec}  ")
    lines.append(f"**Epic summary:** {epic['summary']}")
    lines.append("")
    lines.append(f"**Branch:** `{branch_name(issue, slug)}`")
    lines.append("")
    lines.append("## Tracker")
    lines.append("")
    lines.append(f"Progress and phase checklist: [{tracker_file}]({tracker_file})")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Acceptance Criteria")
    lines.append("")
    for ac in issue.acceptance:
        lines.append(f"- [ ] {ac}")
    lines.append("- [ ] Golden rules (P-01...P-08) respected; money/time rules hold wherever amounts or dates are touched.")
    lines.append("- [ ] All Skill Rules and label-specific rules below are satisfied.")
    lines.append("- [ ] Definition of Done met (`00-issue-workflow.md` steps 9-11; `/pre-merge`).")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Skill Rules (load before coding)")
    lines.append("")
    lines.append("Load and follow these skills **before** implementing:")
    lines.append("")
    lines.append("| Skill | When to use | Path |")
    lines.append("|-------|-------------|------|")
    for skill in skills_for(issue):
        path, when = SKILL_REGISTRY[skill]
        lines.append(f"| `{skill}` | {when} | `{path}` |")
    lines.append("")
    lines.append("Commands: **`/ponytail`** (laziest correct diff) - **`/run`** + **`/verify`** (drive the app) -")
    lines.append("**`/pre-merge`** (Definition-of-Done gate). Read each skill file before coding the matching area.")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Guiding Principles (CLAUDE.md SS1 / design spec SS2)")
    lines.append("")
    for gp in guiding_principles(issue):
        lines.append(f"- {gp}")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Workflow Rules — Before Starting")
    lines.append("")
    lines.append(f"- [ ] Dependencies ({deps_display(issue)}) are complete or explicitly waived by the user")
    lines.append("- [ ] Read `docs/issues/00-issue-workflow.md` and `CLAUDE.md`")
    lines.append(f"- [ ] Read the SRS sections cited above ({issue.srs}) and quote the requirement IDs back")
    lines.append(f"- [ ] Read the design spec section: **{issue.spec}**")
    lines.append("- [ ] Load the skill files listed above for this issue's labels")
    lines.append("- [ ] List assumptions and challenge each one before coding")
    lines.append("")
    lines.append("## Workflow Rules — While Solving")
    lines.append("")
    lines.append("### Universal (apply to every issue)")
    lines.append("")
    lines.append("1. Define expected vs actual behaviour with evidence (logs, tests).")
    lines.append("2. Reproduce before fixing; binary-search to isolate the root cause.")
    lines.append("3. Form testable hypotheses; switch to formal debugging after 10 min of guessing.")
    lines.append("4. Smallest correct diff; match the surrounding code style.")
    lines.append("5. Verify the fix with the original reproduction case.")
    lines.append("6. **Respect the boundaries** - `feature -> domain -> data/core` (ARC-001); engines pure-Kotlin (ARC-002); repositories are the only DAO touchers (ARC-005).")
    lines.append("")
    lines.append("### Issue-specific (from the SRS)")
    lines.append("")
    for i, rule in enumerate(issue.rules, 1):
        lines.append(f"{i}. {rule}")
    lines.append("")
    lines.append("### Label-specific")
    lines.append("")
    for i, rule in enumerate(label_specific_rules(issue), 1):
        lines.append(f"{i}. {rule}")
    lines.append("")
    lines.append("### Epic-specific")
    lines.append("")
    for i, rule in enumerate(epic["rules"], 1):
        lines.append(f"{i}. {rule}")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Workflow Rules — Definition of Done")
    lines.append("")
    lines.append("- [ ] Acceptance criteria checked off above")
    lines.append("- [ ] Every new/changed function has a doc comment (why/what/result/changelog/inputs/outputs) and a test")
    lines.append("- [ ] Tests pass for every touched suite; coverage gate met (engine >= 85%, money 100%)")
    lines.append("- [ ] Works offline - verified with an airplane-mode case where a data path is touched (P-04)")
    lines.append("- [ ] No secrets/PII/amounts in logs or committed files")
    lines.append("- [ ] No new lint/detekt warnings; strings externalised; dark mode + accessibility verified (if UI)")
    lines.append("- [ ] App run and observed working on an emulator/device (`/run` + `/verify`) - a green build does not close the issue")
    lines.append("- [ ] Tracker updated with the Verification Log; `VERSION` + `CHANGELOG.md` bumped at ship")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Verification")
    lines.append("")
    lines.append("_Not started._ On completion, record the commands + results here and in the tracker's Verification Log:")
    lines.append("")
    lines.append("```text")
    lines.append("./gradlew ktlintCheck detekt lintDebug")
    lines.append("./gradlew testDebugUnitTest koverVerify")
    lines.append("# /run + /verify on an emulator; connectedDebugAndroidTest for core flows (incl. airplane-mode)")
    lines.append("```")
    lines.append("")
    lines.append(f"Full timestamped log: [{tracker_file}]({tracker_file}#verification-log).")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Files Changed")
    lines.append("")
    lines.append("| Path | Action |")
    lines.append("|------|--------|")
    lines.append("| _TBD_ | Filled in as the issue is implemented |")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Sub-tasks")
    lines.append("")
    if issue.task:
        rel = "../" + issue.task[len("docs/"):]
        name = Path(issue.task).name
        lines.append(f"Atomic task breakdown: [`{name}`]({rel}).")
    else:
        lines.append(f"No sub-tasks yet. Break into `docs/features/{issue.id}-{slug}/tasks/` when this issue is too")
        lines.append("large for one PR (use `docs/features/_TASK_TEMPLATE.md`).")
    lines.append("")
    return "\n".join(lines)


def render_tracker(issue: Issue, slug: str) -> str:
    """Render a single issue's `<id>-<slug>-tracker.md` in the reference format.

    Why: each issue needs a companion tracker holding the phase checklist and the timestamped
    Verification Log the workflow requires before an issue is Done.
    What: assembles the header, Status Summary, tailored Phase Checklist, and an empty
    Verification Log table ready to be appended to.
    Result: a complete markdown string.
    Changed: 2026-07-18 - created.
    Input: an `Issue` and its slug. Output: markdown (str).
    """
    issue_file = f"{issue.id}-{slug}.md"
    phases = tracker_phases(issue)

    lines: list[str] = []
    lines.append(f"# Issue {issue.id} — {issue.title} Tracker")
    lines.append("")
    lines.append(f"**Parent issue:** [{issue_file}]({issue_file})  ")
    lines.append(f"**Strategy:** {issue.summary}  ")
    lines.append("**Branch (integration):** `main`  ")
    lines.append(f"**Branch (implementation):** `{branch_name(issue, slug)}` -> merged to `main`")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Status Summary")
    lines.append("")
    lines.append("| Field | Value |")
    lines.append("|-------|-------|")
    lines.append("| **Status** | Todo (not started) |")
    lines.append(f"| **Progress** | 0 / {len(phases)} phases |")
    lines.append("| **Last verified** | - |")
    lines.append("| **VERSION at start** | see repo-root `VERSION` |")
    lines.append(f"| **Depends on** | {deps_display(issue)} |")
    lines.append(f"| **Branch** | `{branch_name(issue, slug)}` |")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Phase Checklist")
    lines.append("")
    lines.append("| # | Phase | Status | Verification |")
    lines.append("|---|-------|--------|--------------|")
    for i, (phase, hint) in enumerate(phases, 1):
        lines.append(f"| {i} | {phase} | [ ] | {hint} |")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Verification Log")
    lines.append("")
    lines.append("Append one row per verify step (lint, unit, coverage, screenshot, emulator run, E2E, git push).")
    lines.append("Use `OK`, `SKIP - <reason>`, or `FAIL - <reason>`. `SKIP` must name why (never to go green).")
    lines.append("")
    lines.append("| Date & time (UTC+5:30) | Phase | Command | Result |")
    lines.append("|------------------------|-------|---------|--------|")
    lines.append("| - | - | (not started) | - |")
    lines.append("")
    return "\n".join(lines)


def write_csv(issues: list[Issue]) -> None:
    """Write `2026-07-17-issues.csv` - one row per issue, matching the reference columns.

    Why: the CSV is the flat epic->issue index the design spec's SS13 points at.
    What: writes the exact reference header and a row per issue (labels/deps ';'-joined).
    Result: a UTF-8 CSV at CSV_PATH.
    Changed: 2026-07-18 - created.
    Input: the issue list. Output: none (writes a file).
    """
    SPECS_DIR.mkdir(parents=True, exist_ok=True)
    with CSV_PATH.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["Issue ID", "Epic", "Epic Title", "Issue Title", "Assignee", "Labels", "Priority", "Depends On"])
        for issue in issues:
            writer.writerow([
                issue.id,
                str(issue.epic),
                polish(EPICS[issue.epic]["title"]),
                polish(issue.title),
                ASSIGNEE,
                ";".join(issue.labels),
                PRIORITY_WORD[issue.pri],
                ";".join(issue.deps),
            ])


def main() -> int:
    """Generate the CSV and every issue/tracker file, then print a report.

    Why: single entry point so the whole backlog regenerates with one command.
    What: validates ids/deps, writes the CSV, renders both files per issue, and prints counts.
    Result: files under docs/; returns 0 on success, non-zero if a dependency id is unknown.
    Changed: 2026-07-18 - created.
    Input: none. Output: process exit code (int).
    """
    ISSUES_DIR.mkdir(parents=True, exist_ok=True)

    ids = {i.id for i in ISSUES}
    if len(ids) != len(ISSUES):
        print("ERROR: duplicate issue ids detected", file=sys.stderr)
        return 1
    bad_deps = {d for i in ISSUES for d in i.deps if d not in ids}
    if bad_deps:
        print(f"ERROR: dependencies reference unknown issue ids: {sorted(bad_deps)}", file=sys.stderr)
        return 1

    write_csv(ISSUES)

    file_count = 0
    for issue in ISSUES:
        slug = slugify(issue.title)
        (ISSUES_DIR / f"{issue.id}-{slug}.md").write_text(polish(render_issue(issue, slug)), encoding="utf-8")
        (ISSUES_DIR / f"{issue.id}-{slug}-tracker.md").write_text(polish(render_tracker(issue, slug)), encoding="utf-8")
        file_count += 2

    epic_counts: dict[int, int] = {}
    for issue in ISSUES:
        epic_counts[issue.epic] = epic_counts.get(issue.epic, 0) + 1

    print("AI Personal CFO - issue backlog generated")
    print(f"  CSV:    {CSV_PATH.relative_to(ROOT)}")
    print(f"  Epics:  {len(EPICS)}")
    print(f"  Issues: {len(ISSUES)}")
    print(f"  Files:  {file_count} issue/tracker .md ({file_count // 2} issues x 2)")
    for epic in sorted(EPICS):
        print(f"    Epic {epic:>2} - {EPICS[epic]['title']:<34} {epic_counts.get(epic, 0)} issues")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
