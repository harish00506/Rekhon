# ADR-0001 — A `:lint` module outside the §21.2 graph, and name-based money detection

- **Status:** accepted
- **Date:** 2026-07-25
- **Deciders:** Harish G (solo), implementing issue 1.5 / task 1.1.5
- **SRS refs:** §21.2 (module graph), §21.3 (custom lint in the pinned stack), §21.4 (MNY-001,
  TIM-001), §21.6 (strings, style), ARC-006, P-01

## Context

`CLAUDE.md` §3 and §5 state four bans in the present tense — `Double` on money, wall-clock reads in
domain code, `GlobalScope`, and hardcoded user-visible strings — plus a ban on logging PII or
amounts. The 2026-07-25 governance audit found that **none of them was enforced by anything**: no
`lint.xml`, no custom-lint module, no `lint {}` block. `lintDebug` ran with AGP defaults, which
know nothing about this project's rules. The audit called that its systemic finding, because a rule
documented as a gate but implemented as nothing produces false confidence in both human reviewers
and AI agents, who treat `CLAUDE.md` as ground truth.

Two decisions inside the fix need recording, because neither is obvious and both will look
arbitrary to someone reading the code later.

**1. Where the detectors live.** §21.2 enumerates the module graph, and `:lint` is not in it.
`CLAUDE.md` §5 requires an ADR for any deviation from the SRS. §21.3 does list "custom lint (Money,
GlobalScope, string literals)" in the pinned stack, so the *capability* is specified — only its
home is not.

**2. How a monetary value is recognised.** MNY-001 says money is `Long` minor units in the `Money`
value class. But a detector cannot ask "is this `Double` money?" from the type system — if the type
already told us, `Money` would not need to exist. Recognition has to come from somewhere else.

## Decision

**Put the detectors in a new top-level `:lint` module**, included in `settings.gradle.kts` and
applied to every module through the `configureCustomLint()` helper in the convention plugins. It is
a plain `java-library` — deliberately *not* on the `cfo.kotlin.library` convention plugin, because
the ARC-002 guard and the 85% coverage floor exist for product code, and neither is meaningful for
build tooling that ships in no APK. It does get ktlint and detekt: §21.6 applies to every module,
and the enforcement layer does not get to skip the style gate.

`com.android.lint` is also applied to pure-Kotlin modules. Without it lint never runs on them at
all, and `:core:model` — where `Money` lives — would be the one module MNY-001 was not checked in.

**Detect money by name, not by type.** A declaration is flagged when its type is floating point
(`Double`, `Float`, or `BigDecimal`) **and** its identifier contains a monetary word. Both halves
are required. Matching is on whole words split out of camelCase, never substrings, so `expand` does
not match `pan` and `minorVersion` does not match `minor` in isolation.

The money word list (`MoneyDoubleDetector.MONEY_WORDS`):

> amount(s), price(s), balance(s), cost(s), paise, rupee(s), money, fee(s), salary, income,
> expense, spend(ing), budget, emi, premium, minor, payment(s), principal, interest, cashflow,
> networth

The PII/logging word list (`PiiLoggingDetector.SENSITIVE_WORDS`) adds the personal identifiers:

> account, merchant, payee, beneficiary, customer, email, phone, mobile, upi, vpa, iban, aadhaar,
> address, pin, otp, token

**Scope by package as well as path.** `DomainClockDetector` and `HardcodedUiStringDetector` apply
only to certain modules. They check the file path *or* the declared package. Path alone was tried
first and proved brittle — a lint test harness relocates fixture files, and a rule that silently
stops firing when a directory moves is worse than no rule, because it still reads as enforced.

**`:core:common` is exempt from TIM-001.** `SystemClock.nowUtcMillis()` is the single sanctioned
wall-clock read in the codebase. A rule that banned its own implementation would be suppressed, and
a suppressed rule teaches everyone that suppressing rules is normal.

## Consequences

- **Positive:** five rules that were honour-system now fail `./gradlew lint`. Each was proved by
  seeding a real violation in a real module and watching the build go red — not by the existence of
  the detector files. The `money-time-audit` skill becomes a fast confirmation rather than the last
  line of defence.
- **Negative / cost:** the name heuristic will miss money in a badly named variable, and can flag a
  `Double` that merely sounds monetary. Task 1.1.5 §12 sets that trade-off explicitly — prefer
  missing an exotic case over blocking valid code — because a rule that blocks valid work gets
  disabled, and a disabled rule protects nothing. The escape hatch for a genuine false positive is
  to rename the variable (usually the right fix anyway) or `@Suppress` it with a comment saying why.
- **Negative / cost:** `lintVersion` in the catalog must move in lockstep with `agp` (lint version
  = AGP version + 23) or the detectors compile against a different API than the one running them.
- **Follow-ups:** `contentDescription` literals are not covered yet — that needs parameter
  resolution against Compose, which is not on the analysis classpath until the design system lands
  (issue 1.8), at which point `:core:designsystem` should also join `HardcodedUiStringDetector`'s
  scope. Quick-fixes (auto-replace with `stringResource`) are deliberately out of scope.

## Alternatives considered

- **Put the detectors in `build-logic`** — rejected: `build-logic` is an included *composite* build
  whose output is Gradle plugins, not a lint-checks artifact consumable via `lintChecks(...)`.
- **Detect money by type only (flag every `Double`)** — rejected: the codebase legitimately needs
  floating point for animation fractions, ratios and progress. A blanket ban is the rule that gets
  turned off in week two.
- **Detect money by annotation (`@MonetaryAmount`)** — rejected as YAGNI and self-defeating: it only
  fires when a developer remembers to annotate, which is precisely the moment they would also have
  remembered to use `Money`.
- **Use a third-party rule set** (e.g. Slack's compose-lints for the strings rule) — rejected: it
  covers none of the four project-specific rules, and adding a dependency to the catalog would need
  its own ADR for less than it gives.
- **Leave the strings rule to AGP's built-in `HardcodedText`** — rejected: that check only inspects
  XML layouts, and this app's UI is Compose.

## Compliance with golden rules

Strengthens rather than weakens them. **P-01** gains its first mechanical enforcement (the PII
logging detector); **MNY-001** and **TIM-001** become build-blocking; **ARC-006** likewise. Nothing
here touches runtime behaviour — the `:lint` module ships in no APK — so **P-03**, **P-04** and
**P-07** are unaffected. No network access is added; lint runs entirely offline.
