<!--
  Definition of Done checklist (SRS §4.2 + §21.6). Every box must be checked or explicitly
  N/A with a reason before merge. main is always releasable.
-->

## What & why

<!-- One-paragraph summary. What behaviour changes and for whom. -->

**Requirement IDs:** <!-- e.g. FR-PA-001, AI-ARC-004 — required; commits must reference these -->

## How

<!-- Approach, key files, any trade-offs. Link the ADR if you deviated from the SRS. -->

## Definition of Done (§4.2)

- [ ] Requirement implemented and **traceable** — commits reference the FR/AI id.
- [ ] Unit tests for domain logic — **≥ 85% engine coverage; 100% money math**.
- [ ] UI state covered by ≥ 1 Compose UI test or Paparazzi screenshot test.
- [ ] **Works offline** — verified with an airplane-mode / backend-absent test case (P-04).
- [ ] Accessibility scan passes; **strings externalised**; **dark mode** verified.
- [ ] No new lint/detekt warnings; **CI green**; reviewed (or solo self-review below).

## Binding-rule self-check

- [ ] **P-03** — no LLM computes a number; all figures come from deterministic engines.
- [ ] **Money/Time** — `Money` (Long paise) & bps only; injected `Clock`, no `System.currentTimeMillis()` in domain (§21.4).
- [ ] **Provenance** — every new engine result carries `engineId/version/inputWindow/computedAt/confidence/evidence` (AI-ARC-003).
- [ ] **Architecture** — dependency direction respected; engines pure Kotlin; repositories are the only DAO/network callers.
- [ ] **AI data** — thresholds changed in `ai/rules/` (versioned, cited), not hardcoded; JSON/YAML parse.
- [ ] **Privacy** — no new off-device data flow without explicit, revocable consent (P-01).
- [ ] **Docs** — `ENGINE.md` updated; ADR added if the SRS was deviated from.

## Screenshots / evidence

<!-- Paparazzi diffs, test output, or before/after where relevant. -->
