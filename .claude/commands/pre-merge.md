---
description: Run the AI Personal CFO Definition of Done (§4.2) against the current change before opening a PR or merging.
argument-hint: "[optional: feature or requirement id, e.g. FR-TXN-004]"
---

# /pre-merge — Definition of Done gate

Verify the current working change against the binding Definition of Done (§4.2) and the
coding standards (§21.6). Target: **`main` is always releasable.** Do not merge if any
required box is unchecked.

Scope of this run: $ARGUMENTS (if empty, use `git diff` against the base branch).

Work through this and report each item as ✅ / ⚠️ / ❌ with the evidence (command output,
file:line), then give a one-line **MERGE / DO NOT MERGE** verdict.

1. **Traceability** — every commit message references its FR/AI requirement id.
   `!git log --oneline origin/main..HEAD`
2. **Tests exist and pass** — domain logic covered; **≥ 85% engine coverage, 100% money math**.
   `!./gradlew test`  (report coverage from the JaCoCo/Kover summary if present)
3. **UI covered** — ≥ 1 Compose UI test or Paparazzi screenshot test for changed UI state.
   `!./gradlew verifyPaparazziDebug` (if UI changed) — runs on the JVM, no device needed.
   A new or changed design-system component needs its baseline re-recorded and committed.
4. **Offline** — a changed core flow has an airplane-mode / backend-absent test case (P-04).
5. **Accessibility & i18n** — strings externalised (no new hardcoded user-facing strings),
   dark mode verified, accessibility scan clean.
6. **Lint clean** — no new lint/detekt warnings.
   `!./gradlew lintDebug detekt ktlintCheck`
7. **Money & time** — run the `money-time-audit` skill on the diff; must be clean.
8. **Docs** — changed engine has an up-to-date `ENGINE.md`; any SRS deviation has an ADR.
9. **AI data integrity** — if `ai/**` changed, JSON/YAML parse and IDs/versions were bumped
   correctly (use the `add-rulebook-rule` checklist).
10. **Session records & the quiz** (CLAUDE.md §10) — a `docs/sessions/YYYY-MM-DD-*.md` exists for
    this session; every dependency change in the diff has a `DECISIONS.md` row; every changed
    runtime call path is in `FLOW.md`; and **every major change in the diff has a recorded quiz
    pass**. A major change with no quiz is ❌ — go and run it now, before the merge, rather than
    noting it as missing. Report the quiz outcomes as recorded, fails included; a log with no
    fails in it is not evidence of a gate working.

If the Gradle build does not exist yet (greenfield), say so and check the items you can
(traceability, string externalisation, money/time audit, ai/ integrity) rather than
inventing green checks.
