---
description: Start a new AI Personal CFO feature from its SRS requirement — plan against the spec, respect module boundaries and the layered AI pipeline, write tests first, and land it incrementally behind a flag.
argument-hint: "<requirement id or feature name, e.g. FR-PA-001 or 'purchase advisor timing gate'>"
---

# /new-feature — spec-driven feature workflow

Implement: **$ARGUMENTS**

Follow this order. Do NOT start editing code before step 3 is agreed.

1. **Find the spec.** Locate the requirement in `docs/init/AI_Personal_CFO_SRS_v1.7.pdf`
   (grep the extracted text or read the relevant §). Capture the exact requirement IDs, the
   binding rules, and the acceptance criteria. Quote them back.
2. **Place it in the architecture.** Decide which layer(s) and module(s) it touches
   (`feature → domain → data/core`, §21.2). If it needs a new engine, use the `new-engine`
   skill. If it needs a threshold, it goes in `ai/rules/` via the `add-rulebook-rule` skill —
   never hardcoded.
3. **Plan & confirm.** Produce a short step list: files to add/change, the interface/result
   types, the tests, and the feature flag. Confirm with the user before writing code
   (incremental-implementation: one reviewable slice at a time).
4. **Tests first** (test-driven-development). Write the failing engine/UI tests from the
   acceptance criteria, then implement to green. Money math 100%, engine coverage ≥ 85%.
5. **Respect the golden rules.** P-03 (no LLM-computed numbers), P-01/P-04 (offline, consent),
   provenance on every result (AI-ARC-003), Money/Clock injected (MNY/TIM).
6. **Guard incomplete work with a feature flag** — no long-lived branches.
7. **Document.** Update the engine's `ENGINE.md`; add an ADR if you deviated from the SRS.
8. **Finish with `/pre-merge`.** Commit as `feat(<area>): <summary> (<REQ-ID> §NN)`.

Keep changes small and reviewable. When a decision is genuinely the user's (a product
trade-off the SRS leaves open), ask — don't guess.
