# ADR-0019 — FR-BUD-004's alert bands mint a new rule row rather than bump a shipped one

- **Status:** accepted
- **Date:** 2026-08-13
- **Deciders:** Harish G (solo), implementing issue 4.5
- **Refs:** [ADR-0017](0017-budget-thresholds-stay-a-typed-mirror.md),
  [ADR-0005](0005-quick-setup-thresholds-deferred-rulebook-loader.md), CLAUDE.md §6,
  SRS §5.5 (FR-BUD-004), §29 (AI-ARC-006), `ai/chat/guardrail.md` (AI-ARC-004)

## Context

Issue 4.5 needs two thresholds — 80% and 100% of a category budget — and CLAUDE.md §6 says a
financial threshold is a **data row in `ai/`, never a hardcoded number**. `RULE-BUD-PACE` already
exists, already concerns budget progress, and already has a `params_json`. Adding `warn_pct` and
`exceeded_pct` to it is the obvious move, and it is the one this issue must not make.

[ADR-0017](0017-budget-thresholds-stay-a-typed-mirror.md) narrowed ADR-0005's trigger list to three
conditions that force the runtime `ai/` loader to be built. The third is:

> **Any individual rule row's `version` is bumped**, which means an insight stored under the old
> version has to stay reproducible (AI-ARC-006) — a mirror cannot hold two versions at once.

`RULE-BUD-PACE` shipped in 4.4 at `"version": "1.0"`, is cited by `BudgetStatus`'s provenance, and
that citation is stored on every `budget` row the app suggested. Adding a parameter to it is a params
change, which §29's schema note says bumps the version. That fires trigger 3 — and trigger 3 is not
a formality. Honouring it means building `:core:rules`, an `ai/`-to-assets pipeline, a
`rules_knowledge_base` table with a migration, and retrofitting seven shipped mirrors onto it, before
this issue could compile again.

The rulebook's own `_meta.version` is a different thing and moves freely; the mirrors pin **row**
versions, which is what ADR-0017 says.

## Decision

**Mint a new row, `RULE-BUD-ALERT`, at `version: "1.0"`. Do not touch `RULE-BUD-PACE`'s params.**

Nothing is bumped, so trigger 3 does not fire and the typed-mirror pattern continues unchanged for
one more issue. `BudgetRules` gains `warnPct` and `exceededPct`, `RulebookDriftTest` gains a case,
and `_meta.version` moves 1.9.0 → 1.10.0.

**This is not a workaround, and the ADR would say so if it were.** The split is right on the merits:

- `RULE-BUD-PACE` answers *"am I on track?"* — a question about arithmetic, whose consumer is a
  screen the user chose to open.
- `RULE-BUD-ALERT` answers *"should this person be interrupted?"* — a question about attention,
  whose consumer is a notification the user did not ask for.

They are edited by different people for different reasons, and the second has a parameter the first
could never hold: `notify_once_per_band_per_month`, which is not a threshold at all but a promise
about frequency. Had they shared a row, a user who wanted fewer notifications would have had to
change a row that also governs the projection on the screen.

**4.4's tripwire test is retargeted, not deleted.** `RulebookDriftTest` had a test asserting the
alert bands were absent from `RULE-BUD-PACE`, written to hold them back until the screen that
explains them existed. 4.5 built that screen and kept the separation, so the test now reads *"the
alert bands live on RULE-BUD-ALERT, not RULE-BUD-PACE"* and guards a permanent boundary rather than
a schedule.

## The guardrail this issue ships is a subset, deliberately

AI-ARC-004 requires that LLM output pass a numeric guardrail before display, and 4.5's acceptance
criteria apply that to the notification text. The full L3 gate described in `ai/chat/guardrail.md` —
date/count/score extraction, the lakh/crore and FX display transforms, and the
REGENERATE-then-REFUSE ladder — is **issue 9.7's**.

`NumericGuardrail` in `:core:model` implements the part that applies here: deterministic extraction
of rupee amounts and percentages from the composed text, resolved against exactly the values the
engine returned, rendered through `MoneyFormatter`. It is fail-closed — the default allowed set is
empty, so a caller that forgets to declare its values gets a refusal rather than a pass.

Two parts of the contract are **inapplicable** here rather than unbuilt, which is worth stating so a
later reader does not mistake the gap for an oversight: there is no LLM anywhere on this path — the
text is templated from engine output — so there is nothing to regenerate and no fallback to compose.
What survives is the part that matters: GRD-001's deterministic matching, never the model judging
itself.

The known narrowing is recorded in the class's own KDoc: only currency and percentage claims are
extracted, because those are the only figures this path emits. A caller that templates a bare count
would not be checked.

## Consequences

**Trigger 3 remains unfired, and is now closer.** Every rule row in `ai/rules/rules-kb.json` is still
at `1.0`. The first genuine params change to any *shipped* row still forces the loader — and this
decision makes that moment more likely to arrive as a real edit rather than as a new feature, since
new features can keep minting new rows.

**A new escape hatch exists, and it should be used honestly.** "Mint a new row instead of bumping"
works whenever the new thresholds are genuinely a different question. It would be an abuse if used to
add a parameter that belongs on an existing row — the giveaway is a new row whose `name` restates the
old one's. `RULE-BUD-ALERT`'s name and rationale are about interruption, not pace, and its
`consumed_by` names `AI-NTF`, which `RULE-BUD-PACE` does not.

**`RULE-BUD-PACE`'s `source_note` changed, and that is not a params change.** It previously said the
alert thresholds "belong to issue 4.5"; it now points at `RULE-BUD-ALERT`. Prose, not a threshold —
no engine reads it, no result cites it, and the row's `version` correctly stays at `1.0`.

**The once-per-band promise is enforced by the schema, not by this row.** `params_json` carries
`notify_once_per_band_per_month: true`, but the thing that makes it true is
`UNIQUE(profile_id, budget_id, month_start_iso_date, band)` on `budget_alert`. The flag documents the
intent; the index is the mechanism. The row's `source_note` says so, so nobody later reads the flag
as the guarantee.

## What was actually verified

Both gates were **broken on purpose and watched to fail** before being trusted (2026-08-13) — this
project has shipped one vacuously-passing gate already (governance audit G-01), so a guard does not
count here until it has been seen to bite:

| Edit made | File touched | Test that went red |
|-----------|--------------|--------------------|
| `warn_pct` 80 → 75 | `ai/rules/rules-kb.json` only | `RulebookDriftTest > the alert thresholds match RULE-BUD-ALERT` |
| `"warn_pct": 80` added to `RULE-BUD-PACE.params_json` | `ai/rules/rules-kb.json` only | `RulebookDriftTest > the alert bands live on RULE-BUD-ALERT, not RULE-BUD-PACE` |

**Each edit touched an `ai/` file and no Kotlin**, which is what proves the `inputs.file` wiring in
`domain/engines/budget/build.gradle.kts` is still live. Without it Gradle does not know the tests
read that file, leaves them `UP-TO-DATE`, and the gate reports green against a file it never opened —
the failure found in `:domain:engines:sms` on 2026-08-07 and `:domain:engines:classification` on
2026-08-10.

A third defect was found by a test rather than by a gate, and is worth recording because it was a
real bug in a decision this ADR ratifies: with `exceeded_pct` set below 100 — which the rule row
permits — the EXCEEDED band is reached while the budget still has money in it, and `spent - budgeted`
went negative. The overspend figure is now floored at zero, so the notifier can never be handed a
negative "overspend" to render.

## Alternatives rejected

**Add the params to `RULE-BUD-PACE` and build the loader.** Rejected on proportion, the same argument
ADR-0017 made: `:core:rules`, an asset pipeline, a `rules_knowledge_base` table and migration, and
seven mirrors retrofitted, is a far larger and more consequential change than the feature it would
serve — and it would still be designed against no user-override screen, because none is specified
yet. When trigger 1 or 2 fires, the loader gets built against a real requirement.

**Add the params to `RULE-BUD-PACE` and leave the version at `1.0`.** Rejected outright. That is the
version of this decision that rots: it keeps the appearance of the versioning discipline while
quietly making a stored citation mean two different things depending on when it was written, which
is exactly what AI-ARC-006 exists to prevent.

**Hardcode 80 and 100 in the engine, since they are "obvious".** Rejected — §6 admits no such
exception, and the numbers are not obvious. 80% is a judgement about when a warning is early enough
to act on and late enough not to be noise, and it is precisely the kind of thing a user should be
able to move once §29's override screen exists.

**Put the bands in `BudgetStatus` and have the screen decide when to alert.** Rejected: it would put
the decision to interrupt someone in the UI layer, where it is untestable without a rendered screen
and unreachable from the background worker that actually sends the notification.
