# ADR-0017 — Budget thresholds stay a typed mirror; the `ai/` loader is deferred again

- **Status:** accepted, with the trigger list narrowed
- **Date:** 2026-08-11
- **Deciders:** Harish G (solo), implementing issue 4.4
- **Refs:** [ADR-0005](0005-quick-setup-thresholds-deferred-rulebook-loader.md), CLAUDE.md §6,
  SRS §29 (AI-ARC-006), `ai/README.md`, `00-issue-workflow.md` step 5

## Context

[ADR-0005](0005-quick-setup-thresholds-deferred-rulebook-loader.md) deferred building a runtime
loader for `ai/`, shipped `QuickSetupRules` as a typed mirror guarded by a drift test, and listed
three triggers that would force the loader to be built. It named this issue explicitly:

> **A user-editable threshold is required.** §29's audit_log who/when/why governance cannot be
> satisfied by a Kotlin constant at all. **Realistically issue 4.4 (budgets) or 9.4 (financial
> health score).**

So 4.4 has to answer that call, or say why it does not apply.

Since ADR-0005 the mirror pattern has spread to five places — `QuickSetupRules`, `SmsRules`,
`ReceiptRules`, `RecurringRules` and `ClassificationRules`/`CategorySeed` — each with its own
`*DriftTest`. Issue 4.4 adds two more mirrors (`BudgetRules`, `SeasonalityPriors`), which makes this
the seventh. That is worth stating plainly rather than letting it accrete silently.

## Decision

**Ship `BudgetRules` and `SeasonalityPriors` as typed mirrors, guarded by `RulebookDriftTest` and
`SeasonalityKbDriftTest`, and do not build the loader in issue 4.4.**

The reason ADR-0005's first trigger has **not** fired is a distinction worth being precise about:

> Issue 4.4 lets the user edit the **budget amount**. It does not let the user edit a **rule
> threshold**.

A budget amount is a *user's decision*, and it already has a home — a row in the `budget` table,
with `source`, `rule_id` and `rule_version` columns recording whether the app suggested it and from
which rule. That is the audit trail §29 asks for, and it exists today. What §29's governance clause
actually requires a loader for is a user moving `lookback_months` from 3 to 6, or switching
`seasonality_enabled` off — and **no screen in 4.4 offers either**. FR-BUD-001/002/003 do not ask
for one, and inventing that screen here would be building §29's hardest requirement as a side effect
of a budgets feature, which is precisely the mistake ADR-0005 declined to make for onboarding.

## Consequences

**Accepted, and unchanged from ADR-0005:** CLAUDE.md §6 is, strictly, still violated. The rulebook
is the source of truth for a *test*, not for the running app, which is weaker.

**New, and worth watching:** ADR-0005's triggers 2 and 3 are now closer than its author expected.

- **Trigger 2 — "a second engine needs the same rules."** `RULE-50-30-20` is already mirrored by
  `QuickSetupRules`, and its `consumed_by` names `budget_suggester`. This engine deliberately does
  **not** read it — the 50/30/20 bands are a nature-level frame, not a per-category suggestion — so
  the trigger has not fired. But `AI-CLSN-002`'s 50/30/20 rings and 9.4's health score will both
  want it, and engine-versus-engine drift is **not** covered by any test here: each drift test
  compares its own mirror against the file, and two mirrors that disagree with each other while both
  agreeing with the file is a state these tests cannot detect.
- **Trigger 3 — "any cited rule gains a version bump."** Still not fired. `_meta.version` has moved
  1.7.0 → 1.9.0, but every individual rule row is still at `"version": "1.0"`, and the mirrors pin
  the row versions, not the file version.

**Narrowed trigger list, superseding ADR-0005's:**

1. **A screen that edits a rule threshold** (not a budget amount) is specified — realistically 9.4,
   or a settings screen for §29's user overrides.
2. **Two mirrors need the same rule row.** The first engine to mirror `RULE-50-30-20` alongside
   `QuickSetupRules` should build the loader instead, because that is the point at which the drift
   tests stop being sufficient.
3. **Any individual rule row's `version` is bumped**, which means an insight stored under the old
   version has to stay reproducible (AI-ARC-006) — a mirror cannot hold two versions at once.

## What was actually verified

Both new gates were **broken on purpose and watched to fail** before being trusted (2026-08-11) —
this project has shipped one vacuously-passing gate already (governance audit G-01, the 0%-coverage
`koverVerify`), so a guard does not count here until it has been seen to bite:

| Edit made | File touched | Test that went red |
|-----------|--------------|--------------------|
| `lookback_months` 3 → 4 | `ai/rules/rules-kb.json` only | `RulebookDriftTest > the suggestion thresholds match RULE-BUD-SUGGEST` |
| Diwali `prior_multiplier` 1.38 → 1.42 | `ai/knowledge/calendar-seasonality.json` only | `SeasonalityKbDriftTest > every multiplier matches, converted to basis points` |
| `wedding_season` window `Nov-Feb` → `Nov-Jan` | `ai/knowledge/calendar-seasonality.json` only | `SeasonalityKbDriftTest > every window matches, parsed back from the knowledge base` |

The first three columns matter together: **each edit touched an `ai/` file and no Kotlin**, which is
what proves the `inputs.file` wiring in `domain/engines/budget/build.gradle.kts`. Without it Gradle
does not know the tests read those files, leaves them `UP-TO-DATE`, and the gate reports green
against a file it never opened — the failure found and fixed in `:domain:engines:sms` on 2026-08-07
and `:domain:engines:classification` on 2026-08-10.

## Alternatives rejected

**Build the loader now, as ADR-0005 suggested.** Rejected on the distinction above: the trigger it
named was user-editable *thresholds*, and 4.4 delivers user-editable *amounts*. Building a
`:core:rules` module, an asset pipeline, a `rules_knowledge_base` table with an override layer and a
migration, then retrofitting five shipped engines onto it, is a larger and more consequential change
than the feature it would serve — and it would still be designed against no real user-override
screen, because none is specified yet.

**Mirror `RULE-50-30-20` here too, for consistency with the other suggestion inputs.** Rejected: it
would fire trigger 2 immediately and for no benefit, since a per-category median has nothing to say
about a needs/wants/savings split. `AI-CLSN-002`'s rings are the right consumer, and they are not
this issue.

**Hardcode without the drift tests.** Rejected outright, as in ADR-0005 — that is the version of
this decision that rots.
