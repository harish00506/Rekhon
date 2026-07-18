# Feature 1.1 — Project Skeleton & Core Foundations

- **Epic:** 1 — Foundation (**SRS Phase 0**, §26)
- **Status:** `todo`  ·  **Priority:** MUST  ·  **Target VERSION:** 0.1.x → 0.2.0
- **Issue / tracker:** [`docs/issues/1.1-project-skeleton.md`](../../issues/1.1-project-skeleton.md)

## Goal
Stand up the Gradle multi-module skeleton and the non-negotiable core primitives every later
feature depends on: the `Money` value type, the injected `Clock`/dispatchers, the
`Result<T, AppError>` error model, and the custom lint rules that make the money/time
"bug-factory" rules (§21.4) enforceable in CI. After this feature, `main` builds green, the CI
pipeline runs for real, and the architecture boundaries (§21.2) are compiler/lint-enforced.

## SRS refs
- §21.2 Module structure · §21.3 Technology stack (pinned) · §21.4 Money & time rules
- §21.5 Testing strategy · §21.6 Coding standards · §7.1 AI-ARC-002 (pure-Kotlin engines)

## Why this feature is first
Everything else — engines, repositories, screens — imports these primitives. Getting `Money`,
`Clock`, and the lint rules wrong here would let `Double`-on-money and `System.currentTimeMillis()`
bugs (the two classic finance-app bug factories) spread before they can be caught. Build the
guardrails before the house.

## Tasks (smallest units)
| Task | Title | Module | Size |
|------|-------|--------|------|
| [1.1.1](tasks/1.1.1-gradle-multimodule-skeleton.md) | Gradle multi-module skeleton + version catalog + CI wiring | build | M |
| [1.1.2](tasks/1.1.2-money-value-class.md) | `Money` value class (Long paise, HALF_EVEN, split remainder) | `:core:model` | S |
| [1.1.3](tasks/1.1.3-clock-and-dispatchers.md) | Injected `Clock` + `DispatcherProvider` time/concurrency core | `:core:common` | S |
| [1.1.4](tasks/1.1.4-result-apperror.md) | `Result<T, AppError>` sealed error model | `:core:common` | S |
| [1.1.5](tasks/1.1.5-custom-lint-rules.md) | Custom lint: Double-on-money, GlobalScope, clock-in-domain, string literals | `:build-logic`/`:lint` | M |

## Exit criteria (Phase 0 gate for this feature)
- `./gradlew projects` lists the module graph; `./gradlew build` is green in CI.
- `:core:model` and `:domain:*` have **no** Android plugin (ARC-002) — enforced by a build check.
- `Money` and `Clock` have 100%-covered unit tests (money math is 100% by rule).
- Custom lint fails a build that puts a `Double` on money or `System.currentTimeMillis()` in `:domain`.
- No feature ships a monetary `Double` or a domain `System.currentTimeMillis()` past this point.

## Out of scope (later features)
- Encrypted DB / SQLCipher setup → Feature 1.2.
- Design system / theme tokens → Feature 1.3.
- Onboarding, accounts, manual transactions → Features 1.4–1.6.
