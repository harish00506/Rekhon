<!--
  Why:  CLAUDE.md §5 — a decision record for closing the registry drift ADR-0070 opened, and for
        the `provenance_id` field this introduced.
  What: the 2026-10-10 reconciliation — nine missing entries written, three stamped ids made
        resolvable, and a drift test so neither can recur.
  Result: a reader can see why the ids came from the engines rather than being minted, and why a
          correction to ADR-0070's own figure is recorded here rather than edited into it.
  Changelog: 2026-10-10 — Created.
-->

# ADR-0076 — The engine registry is reconciled, and now checked

**Status:** Accepted · **Date:** 2026-10-10 · **Supersedes the open finding in:** [ADR-0070](0070-appliances-are-a-sibling-of-ai-veh-and-the-running-cost-is-the-new-number.md) · **Rules:** AI-ARC-003, AI-ARC-006, P-02

## Context

`ai/orchestrator/engine-registry.yaml` describes itself plainly:

> **What:** One row per engine (module `:domain:engines:*`), with its layer, one-line input→output
> contract, the rulebook/KB files it reads, and its version.
> **Result:** The orchestrator and provenance stamping resolve engine metadata from here.

Issue 13.2 measured it while adding AI-APP and found two drifts: engine modules with no entry, and
an entry naming `:domain:engines:growth`, a module that has never existed. ADR-0070 recorded both
and fixed neither, on the grounds that writing accurate contract lines is its own issue. Issue 13.4
removed the ghost in passing, when it discovered that entry was AI-TAX's.

**Nothing has ever checked this file.** That is the actual defect; the missing rows are its symptom.

## Decision

### 1 · The ids were not minted — the engines already had them

The obstacle looked like naming. Most of the missing engines are deterministic feature engines that
the SRS gives an `FR-` id rather than an `AI-*` name: net worth, nature, receipt, SMS, recurring,
budget, quick setup, loan, card. Inventing nine ids would have been a decision with consequences,
because an engine id is cited in stored provenance and **must never be renamed** (AI-ARC-006).

Checking what each module actually stamps settled it. **All nine have been writing a stable
provenance id into real results since they were built** — `budget-planner`, `card-planner`,
`loan-amortiser`, `nature-classifier`, `net-worth`, `quick-setup`, `receipt-parser`,
`recurring-detector`, `sms-parser`.

So the ids already existed in the data. The catalogue simply did not know them, which means a stored
result citing `budget-planner` resolved to nothing — exactly the failure AI-ARC-006 exists to
prevent. Each entry's `id` is now the string its engine stamps.

### 2 · `provenance_id`, because neither name can move

Three *registered* engines stamp something other than their registry id:

| Registry `id` | Stamps | Module |
|---|---|---|
| `AI-CLS` | `auto-categoriser` | `classification` |
| `AI-INV` | `investment-xirr` | `investment` |
| `AI-STS` | `safe-to-spend` | `safetospend` |

Neither name can be changed. The `AI-*` ids are cited by `rules-kb.json`'s `consumed_by` fields, so
renaming them breaks those citations. The stamped ids are in results already written, so renaming
those orphans history — the same rule the rulebook applies to its own rule ids.

So a `provenance_id` field sits beside `id`, and both resolve. Nothing moves.

### 3 · The test is the actual fix

`EngineRegistryDriftTest` asserts four things: every engine module has an entry; no entry names a
module that does not exist; every id an engine stamps is resolvable from the registry; and the
module count is pinned, so a new engine must be registered in the same change that creates it.

Without the last one, nine could accumulate again exactly as they did.

The registry lives under `ai/`, which issue 11.5 already declared a test input, so a registry edit
cannot leave this UP-TO-DATE.

## A correction to ADR-0070's own figure

ADR-0070 reports **"10 of 28 engine modules have no entry"** and lists `chat` among them. The count
was wrong: it was **nine**.

`chat` *was* registered. Its `module:` field reads
`":domain:engines:chat + :ml:llm + :feature:chat"` — a compound string, because AI-CHAT spans three
modules — and the regex used to measure it looked for a module path in quotes on its own. The same
bug would have hidden any future compound entry.

ADR-0070 and the records of issue 13.2 are **left as written**. They are the account of what was
believed on 2026-10-03, and this project keeps its history rather than retouching it — the same
decision ADR-0068 made about the two versioning drifts it found. The correction lives here, and
`EngineRegistryDriftTest` handles compound `module:` fields so the measurement cannot be wrong the
same way twice.

**The lesson is about the measurement, not the arithmetic:** a drift audit written as a one-off
regex is itself undrifted code. Had the check been a test from the start, its blind spot would have
surfaced the first time somebody added an entry it could not parse.

## Consequences

- The registry has 32 entries across 30 engine modules (`goals` and `purchase` have two each).
- `provenance_id` is a new field. Only the three entries in §2 carry one; an engine whose stamped id
  equals its registry id needs none.
- Nine contract lines were written from each engine's own interface and KDoc rather than guessed,
  which is what made this a separate piece of work rather than a line in another issue.

## Alternatives considered

- **Rename the three stamped ids to match the registry.** Rejected, §2: stored results carry them.
- **Rename the three registry ids to match the engines.** Rejected, §2: `rules-kb.json` cites them.
- **Mint `AI-*` ids for the nine.** Rejected, §1: they already have ids, in data, and a second name
  for the same engine is how a catalogue stops being authoritative.
- **Rewrite ADR-0070's figure in place.** Rejected: it would make the record disagree with the
  session file and changelog entry written beside it, and the error is more useful visible than
  tidied away.
