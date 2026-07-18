---
name: money-time-audit
description: Audit a diff or module for the two classic bug factories in AI Personal CFO — money represented as Double/Float instead of Long paise, and time read via System.currentTimeMillis() instead of an injected Clock — plus related review-blocking rules (GlobalScope, PII/amount logging, raw randomness). Use before merging any engine/repository change, when the user says "money-time audit", "check money handling", "review for money/time bugs", or after writing code that touches amounts, dates, or forecasts.
---

# Skill: Money & Time Audit

These two categories cause the most expensive, hardest-to-see bugs in a finance app.
§21.4 makes them **review-blocking**. Run this on the changed files (or a module) and report
findings as `file:line — problem → fix`.

## What to flag (and the fix)

**Money (MNY-001/002 — review-blocking)**
- `Double` / `Float` / `BigDecimal` on any monetary value → use the `Money` value class (Long
  paise) end-to-end. Grep: `Double|Float` near `amount|price|balance|minor|cost|paise|rupee`.
- Division on money without explicit `HALF_EVEN` rounding + remainder distribution for splits.
- Percentages/rates as `Double` in engines → integer **basis points (bps)**.
- Formatting money anywhere but the UI edge → only `MoneyFormatter` at the UI layer.

**Time (TIM-001/002)**
- `System.currentTimeMillis()`, `Instant.now()`, `LocalDate.now()`, `new Date()` in `:domain:*`
  or `:core:model` → inject `Clock`; compute calendar logic in the profile time zone.
- Date-only fields stored as midnight timestamps → ISO `LocalDate` strings.

**Determinism & concurrency (P-08, ARC-006)**
- `Math.random()` / `Random()` without an injected, seeded source → injected `Random(seed)`.
- `GlobalScope` → injected structured-concurrency scope (lint-banned).
- `runBlocking` outside tests → suspend/Flow.

**Privacy (P-01, logging)**
- Logging amounts or PII on a path that reaches release builds → structured logger that lint
  strips; security events to `audit_log`.
- New network call on a core/offline path → must be optional, cached, consent-gated (P-01/P-04).

## How to run
1. Scope: `git diff --name-only` (or the module the user names).
2. Grep each pattern above across the scoped files.
3. For every hit, decide if it truly touches money/time/determinism (ignore unrelated
   `Double`s like animation fractions) and report `file:line — problem → concrete fix`.
4. If clean, say so explicitly and name what you checked. Do not invent findings.

Prefer running the project's custom lint (`./gradlew lintDebug detekt`) as the source of
truth when the build exists; this skill is the fast human-reviewable pass on top.
