<!--
  TEMPLATE — the reference-format issue file. Two ways to create one:
    1. PREFERRED — add the issue to scripts/gen_issue_docs.py (the ISSUES table) and run
       `python scripts/gen_issue_docs.py`. This regenerates this file's shape for every issue
       and keeps docs/superpowers/specs/2026-07-17-issues.csv in sync. Do this for backlog issues.
    2. AD-HOC — copy this file to docs/issues/<id>-<slug>.md and fill every <placeholder> by hand
       (e.g. 2.3-quick-setup-seeds.md). Use only for a one-off not in the CSV.
  The master workflow (00-issue-workflow.md) reads the Skill Rules table + SRS refs before any
  code is written. Delete this comment block when filled.
-->

# [<id>] <Title>

**Status:** Todo  
**Epic:** Epic <N> — <Epic Title>  
**Priority:** <High | Medium | Low>  
**Depends on:** <comma-separated issue IDs, or None>  
**Assignee:** Harish G  
**Labels:** `<label>`, `<label>`

---

## Description

<One-paragraph summary of what this issue delivers and why.>

Master blueprint (source of truth): `docs/init/AI_Personal_CFO_SRS_v1.7.pdf` — cite the requirement
IDs in code and commits. Planning design spec: [`2026-07-17-ai-personal-cfo-design.md`](../superpowers/specs/2026-07-17-ai-personal-cfo-design.md).
Master workflow: [`00-issue-workflow.md`](00-issue-workflow.md). Binding rules: `CLAUDE.md`.

**SRS sections:** <§NN, requirement IDs, and any `ai/…` file touched>  
**Design spec section:** <§N Title>  
**Epic summary:** <one line describing the epic this issue belongs to>

**Branch:** `feature/<id-with-dashes>-<slug>`

## Tracker

Progress and phase checklist: [<id>-<slug>-tracker.md](<id>-<slug>-tracker.md)

---

## Acceptance Criteria

- [ ] <observable, testable outcome — cite the requirement ID>
- [ ] <observable, testable outcome>
- [ ] <observable, testable outcome>
- [ ] Golden rules (P-01…P-08) respected; money/time rules hold wherever amounts or dates are touched.
- [ ] All Skill Rules and label-specific rules below are satisfied.
- [ ] Definition of Done met (`00-issue-workflow.md` steps 9–11; `/pre-merge`).

---

## Skill Rules (load before coding)

Load and follow these skills **before** implementing (see the label → skill map in
`scripts/gen_issue_docs.py`):

| Skill | When to use | Path |
|-------|-------------|------|
| `<skill>` | <when> | `<path to SKILL.md>` |
| `test-driven-development` | Write the failing test from the acceptance criteria first, then code to green. | `~/.claude/skills/test-driven-development/SKILL.md` |
| `incremental-implementation` | Land in small reviewable slices; feature-flag incomplete work. | `~/.claude/skills/incremental-implementation/SKILL.md` |
| `debugging-and-error-recovery` | Bugs: observe → reproduce → hypothesize → isolate → fix → verify. | `~/.claude/skills/debugging-and-error-recovery/SKILL.md` |

Commands: **`/ponytail`** (laziest correct diff) · **`/run`** + **`/verify`** (drive the app) ·
**`/pre-merge`** (Definition-of-Done gate). Read each skill file before coding the matching area.

---

## Guiding Principles (CLAUDE.md §1 / design spec §2)

- **P-03 Numbers from math, words from AI** — engines compute every figure; the LLM only verbalises.
- **P-01 Privacy first** — no financial data leaves the device without explicit, revocable, per-feature consent.
- **P-02 Show the work** — every output shows its inputs + the rule/model that fired + a plain-language reason.
- **P-08 Deterministic & testable** — fixed input → fixed output; randomness only via an injected, seedable source.
- <add the label-conditional principles: MNY-001/002, TIM-001/002, SEC-003, AI-ARC-003/006, §21.6 theming, P-07>

---

## Workflow Rules — Before Starting

- [ ] Dependencies (<deps>) are complete or explicitly waived by the user
- [ ] Read `docs/issues/00-issue-workflow.md` and `CLAUDE.md`
- [ ] Read the SRS sections cited above and quote the requirement IDs back
- [ ] Read the design spec section: **<§N Title>**
- [ ] Load the skill files listed above for this issue's labels
- [ ] List assumptions and challenge each one before coding

## Workflow Rules — While Solving

### Universal (apply to every issue)

1. Define expected vs actual behaviour with evidence (logs, tests).
2. Reproduce before fixing; binary-search to isolate the root cause.
3. Form testable hypotheses; switch to formal debugging after 10 min of guessing.
4. Smallest correct diff; match the surrounding code style.
5. Verify the fix with the original reproduction case.
6. **Respect the boundaries** — `feature → domain → data/core` (ARC-001); engines pure-Kotlin (ARC-002); repositories are the only DAO touchers (ARC-005).

### Issue-specific (from the SRS)

1. <rule derived from the requirement>

### Label-specific

1. <rule derived from the label — see LABEL_RULES in scripts/gen_issue_docs.py>

### Epic-specific

1. <the epic's binding rule>

---

## Workflow Rules — Definition of Done

- [ ] Acceptance criteria checked off above
- [ ] Every new/changed function has a doc comment (why/what/result/changelog/inputs/outputs) and a test
- [ ] Tests pass for every touched suite; coverage gate met (engine ≥ 85%, money 100%)
- [ ] Works offline — verified with an airplane-mode case where a data path is touched (P-04)
- [ ] No secrets/PII/amounts in logs or committed files
- [ ] No new lint/detekt warnings; strings externalised; dark mode + accessibility verified (if UI)
- [ ] App run and observed working on an emulator/device (`/run` + `/verify`) — a green build does not close the issue
- [ ] Tracker updated with the Verification Log; `VERSION` + `CHANGELOG.md` bumped at ship

---

## Verification

_Not started._ On completion, record the commands + results here and in the tracker's Verification Log:

```text
./gradlew ktlintCheck detekt lintDebug
./gradlew testDebugUnitTest koverVerify
# /run + /verify on an emulator; connectedDebugAndroidTest for core flows (incl. airplane-mode)
```

Full timestamped log: [<id>-<slug>-tracker.md](<id>-<slug>-tracker.md#verification-log).

---

## Files Changed

| Path | Action |
|------|--------|
| _TBD_ | Filled in as the issue is implemented |

---

## Sub-tasks

<Link to the atomic task breakdown under docs/features/<id>/tasks/ if one exists, or note
"No sub-tasks yet. Break into docs/features/<id>-<slug>/tasks/ when too large for one PR
(use docs/features/_TASK_TEMPLATE.md)".>
