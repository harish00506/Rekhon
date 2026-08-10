# ADR-0014 — The classification KB is mirrored in Kotlin, and its merchant rules ship without a consumer

- **Status:** accepted, with a named trigger to revisit
- **Date:** 2026-08-08
- **Deciders:** Harish G (solo), implementing issue 4.1
- **Refs:** CLAUDE.md §6, SRS §8.1, §29 (AI-ARC-006), [ADR-0005](0005-quick-setup-thresholds-deferred-rulebook-loader.md), `ai/README.md`

## Context

Issue 4.1 is "categories editor + merchant-rule KB". Two of its pieces touch
`ai/knowledge/classification-kb.json`, and they are in different situations.

**The category defaults have a consumer as of this issue.** `category_defaults` is the taxonomy a
real profile starts with, and it needed to start with one: `CategoryEntity`, `CategoryDao` and
`transactions.category_id` have existed since issue 1.6, but **the only thing that ever wrote a
category row was `DemoDataset`** — `DemoModeRepositoryTest` asserts a real profile has exactly zero.
The add-transaction screen offered an empty chip row, and every transaction on a real profile read
"Uncategorised".

**The merchant rules do not.** §8.1's Stage 1 is a hybrid of a user-correction lookup, this keyword
knowledge base, and an on-device TF-IDF model, with a defined precedence between them. That whole
mechanism is **issue 4.2** (AI-CLS Stage 1). Nothing in 4.1 matches a merchant to a category.

Both run into the same wall ADR-0005 hit for `QuickSetupRules`, and it has not moved: **nothing in
the app loads `ai/` at runtime.** There is no asset packaging step, no JSON reader, no
`rules_knowledge_base` table, and `:core:model` and `:domain:*` are pure Kotlin with no
serialisation dependency (ARC-002), so a loader belongs in a module that does not exist yet.

## Decision

**1. `CategorySeed` is a typed Kotlin mirror of `category_defaults`,** in `:core:model`, each row
carrying the `rule_id` and `version` it was copied from. The duplication is guarded by
`ClassificationKbDriftTest`, which reads the knowledge base out of the repository and fails the build
when any id, version, key, name or nature disagrees — the same bargain, and the same mechanism,
ADR-0005 struck.

That test was **verified to fail before it was trusted**: flipping `CLS-CAT-009`'s `default_nature`
from `WANT` to `NEED` was confirmed to turn `the seed rows match category_defaults` red, and deleting
a row was confirmed to turn `every category_defaults row is seeded, and no others` red as well. This
project has already shipped one gate that passed vacuously (governance audit G-01's 0%-coverage
`koverVerify`), so a guard is not counted as a guard here until it has been seen to bite.

**2. The merchant rules ship as data with no runtime consumer,** and gain their `rule_id` and
`version` **now** rather than when 4.2 reads them. An id added later than the row it names is an id
that may already be missing from a stored insight, which is precisely what AI-ARC-006 exists to
prevent. `ClassificationKbDriftTest` asserts every merchant row has both and that no id repeats —
what can be checked about rows nothing reads is that they are citable and unambiguous.

**3. Two vocabularies for one nature, translated in one place.** `category.nature` has stored
`invest` since issue 1.6 (`DemoDataset.CATEGORY_SPECS` wrote the first rows); the knowledge base says
`INVESTMENT`, because §8.3's table does. `CategoryNature` carries both — `storedValue` is the only
thing that may reach a column, `kbValue` the only thing that may be compared against `ai/`.
Rewriting the column would need a migration for a cosmetic gain; rewriting the knowledge base would
put it out of step with the SRS it was transcribed from.

## Trigger to revisit

**Issue 4.2**, which is the first code that reads `merchant_rules`, is where the loader question is
asked properly — it is also the point at which two consumers copy overlapping rows, which is
ADR-0005's own trigger #2. Until then the drift test is what makes the copy safe.

> **Revisited 2026-08-10 in issue 4.2 — see [ADR-0015](0015-stage-1-classification-tiers-and-the-kb-mirror.md).**
> The answer was *not yet*, on a narrower reading than this record anticipated: the two consumers
> mirror **disjoint** sections of the file (`category_defaults` here, `merchant_rules` + `stage1`
> there), so the overlapping-copies case ADR-0005 warned about has not occurred. The merchant rules
> gained their consumer, `CLS-MER-011` gained a version because reading it for the first time
> revealed it was wrong, and the drift test gained the check this record could not make: every
> merchant rule must name a category `category_defaults` actually defines.

ADR-0005's trigger #1 (a **user-editable** threshold, which a Kotlin constant cannot satisfy at all)
is unaffected by this decision and still points at issue 4.4.

## Consequences

**Good.** A real profile has a taxonomy on first launch, every seeded row is attributable to a
`CLS-CAT-*` id and version, and the merchant rules are governed from the moment they exist rather
than from the moment they are read. The seam for a real loader is a list of typed rows, so replacing
where they come from changes nothing above it.

**Bad.** CLAUDE.md §6 is, strictly, violated twice over: there is classification data in Kotlin, and
`ai/` is still the source of truth only for a *test*, which is weaker than being the source of truth
at runtime. Anyone grepping for compliance will find `CategorySeed`, which is why this record exists
and why that object's doc comment points straight at it.

**Neutral, and worth stating plainly.** Thirteen `merchant_rules` rows now carry ids that nothing
resolves. That is the exact shape of the failure this project keeps re-learning — a field plumbed end
to end with nothing able to produce a value for it (the `merchant` column in 3.1; the `category`
table until this issue). The difference here is that it is written down, has a named consumer one
issue away, and is not claimed to work: no screen, no engine and no test asserts anything about what
those rules would classify.

## Alternatives considered

**Build the loader now.** Rejected on scope, for the reason ADR-0005 gave and this issue does not
change: it is a new module, an asset pipeline and a parser, designed against one consumer, in an
issue whose acceptance criteria say nothing about it.

**Ship the merchant matcher in 4.1 too**, so the rules have a consumer. Rejected as scope: §8.1's
Stage 1 is a precedence chain over three sources, and building one third of it here would mean 4.2
inherits an interface designed without the other two in view.

**Leave the merchant rows without ids until 4.2 needs them.** Rejected: see decision 2. Retro-fitting
an id is how a citation becomes unreproducible.

**Seed the categories from Kotlin without referencing `ai/` at all.** Rejected — it is the version of
this decision that rots. The whole basis for accepting a copy is that divergence cannot happen
quietly.
