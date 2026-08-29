# ADR-0015 — Stage 1 ships two of its three tiers, and the knowledge base is still a Kotlin mirror

- **Status:** accepted, with named triggers to revisit
- **Date:** 2026-08-10
- **Deciders:** Harish G (solo), implementing issue 4.2
- **Refs:** CLAUDE.md §6, SRS §8.1, §21.5, §29 (AI-ARC-006),
  [ADR-0005](0005-quick-setup-thresholds-deferred-rulebook-loader.md),
  [ADR-0014](0014-classification-kb-seed-mirror-and-unconsumed-merchant-rules.md), `ai/README.md`

## Context

ADR-0014 named **issue 4.2** as the trigger to revisit two things: the Kotlin mirror of
`ai/knowledge/classification-kb.json`, and the thirteen `merchant_rules` rows shipped with no
runtime consumer. This issue is that consumer, so both questions come due, and a third arrives with
them.

**SRS §8.1 states Stage 1 as three tiers behind a precedence chain:**

> hybrid of (a) exact/normalised merchant-rule lookup from the user's correction history, (b)
> keyword/regex knowledge base for Indian merchants, (c) on-device TF-IDF + logistic model over
> payee/note/amount/time features trained from the user's corrections. Precedence: user rule >
> learned model (if confidence ≥ 0.85) > knowledge base > "Uncategorised" prompt.

Tier (c) is a trained model with its own persistence, its own versioning obligation (AI-ARC-006),
its own retraining trigger and its own evaluation. Tiers (a) and (b) are a query and a matcher.

## Decision

### 1. Ship tiers (a) and (b); defer (c) to its own issue

The acceptance criteria for 4.2 are "a rule/merchant match assigns a category deterministically with
confidence + evidence", "below a confidence threshold it defers", and the ≥ 92% eval gate. All three
are met by (a) and (b), and the eval set demonstrates it at 96%.

Tier (c) is a `:ml:*` module, a feature extractor, a model format, a training path, a retrain
trigger and a second frozen dataset — a body of work whose acceptance criteria this issue does not
contain, designed against a Stage-1 interface that now exists and can be built to.

**The interface does not pretend (c) exists.** There is no empty `learnedModel` parameter and no
`ModelSuggestion` type waiting for a producer. That is the failure this repo keeps re-learning — the
`merchant` column in 3.1, the `category` table until 4.1, and the `merchant_rules` rows ADR-0014
itself shipped unread: a field plumbed end to end with nothing able to fill it. When (c) lands it
adds a tier between the two that exist, which is a change to `DefaultClassificationEngine`'s `when`
and to `ClassificationInput`, and neither is made easier by guessing at it now.

### 2. Tier (a) reads the ledger; there is no `user_merchant_rule` table

§8.1(a) says "from the user's **correction history**", and `transactions` already *is* that history:
every categorised transaction is a decision the user made or accepted. A dedicated table would be a
second copy of the same fact, able to disagree with the ledger it was derived from, and would need a
schema migration to hold nothing new. `TransactionDao.categoryCountsForMerchant` is one `GROUP BY`,
and **the schema is unchanged at v12**.

Two clauses of that query are decisions rather than details:

- **Split transactions contribute nothing.** The parent carries the merchant and the lines carry the
  categories, so one split is the user saying "this merchant is several things at once" — evidence
  *against* a single suggestion. Joining the lines in would dilute the share and suppress the tier.
  This is the opposite of what `countForCategory` (issue 4.1) does with splits, and deliberately: it
  is counting usage, this is counting agreement.
- **A deleted transaction teaches nothing.** A decision withdrawn is not a decision.

### 3. The knowledge base stays a Kotlin mirror — ADR-0014's trigger, answered "not yet"

ADR-0005's trigger #2 was "two consumers copy overlapping rows", and that has now happened:
`CategorySeed` in `:core:model` holds `category_defaults`, and `ClassificationRules` in
`:domain:engines:classification` holds `merchant_rules` and `stage1`. **They do not overlap** — two
disjoint sections of one file, each mirrored once, each drift-tested against the file. The
duplication ADR-0005 feared is a third copy of the *same* rows, which does not exist.

The wall has not moved either: nothing loads `ai/` at runtime, there is no asset pipeline and no
`rules_knowledge_base` table, and `:core:model` and `:domain:*` are pure Kotlin with no
serialisation dependency by design (ARC-002) — so a loader belongs in a module that still does not
exist. Building it here would be a new module, an asset pipeline and a parser, designed against two
consumers, in an issue whose acceptance criteria say nothing about it.

What this issue *does* add is a drift check the earlier ones could not make: **every merchant rule
must name a category `category_defaults` actually defines.** A rule pointing at `Food` where the
defaults say `Dining` would match merchants perfectly, resolve to nothing, and pass every test
written against the rule while classifying none of them on a device.

### 4. `min_confidence_bps` is bounded by construction, not by comment

A floor above `word_match_bps` would leave tier (b) firing only on merchants typed with no
descriptor — almost none of them — while every test that scores it against bare names kept passing.
`ClassificationRules` refuses to be built that way. A rule set that can be configured into silence
is the same shape as a gate that passes vacuously, and this project has shipped one of those already
(governance audit G-01).

### 5. `CLS-MER-011` is corrected as data, at a new version

Read as a rule for the first time, its bare literal `coin` files a laundromat under Investment. The
fix is the row (`coin` dropped; `zerodha` already covers every real Coin descriptor), the id is kept
and the version bumped to 1.1 per the file's own `id_policy` — never renamed, because a stored
suggestion may already cite it.

## Triggers to revisit

- **Tier (c)** — its own issue. It is also the point at which "how confident is a suggestion" stops
  being two constants and becomes a distribution, which is when `stage1`'s thresholds want revisiting
  as a set.
- **A user-editable threshold**, which a Kotlin constant cannot satisfy at all. ADR-0005's trigger #1,
  unaffected by this decision, still pointing at issue 4.4.
- **A third consumer of `classification-kb.json`, or a second copy of the same section.** That is the
  overlap ADR-0005 actually warned about, and it would make the loader cheaper than the mirrors.

## Consequences

**Good.** The `CLS-MER-*` ids ADR-0014 shipped unread now resolve to something a user can see and a
reviewer can look up. A merchant the user has categorised once is categorised for them thereafter,
with no new table and no migration. Reading the rules for the first time found a wrong one, and
fixing it as data is CLAUDE.md §6 working as intended.

**Bad.** CLAUDE.md §6 is still, strictly, violated: classification data lives in Kotlin, and `ai/` is
the source of truth for a *test* rather than at runtime. The mirror is now two files rather than one,
so the loader's eventual arrival has two call sites to replace. And §8.1 is two-thirds implemented,
which anyone reading the SRS beside the code will notice — which is what this record is for.

**Neutral, and worth stating plainly.** Tier (a)'s exactness means a card descriptor carrying an
order number teaches nothing about the next one. That is §8.1's own word ("exact/normalised"), the
real fix is tier (c), and it is pinned by a test rather than left to be rediscovered.

## Alternatives considered

**Build tier (c) too.** Rejected on scope: see decision 1.

**Add a `user_merchant_rule` table.** Rejected — see decision 2. It is a copy of the ledger that can
disagree with the ledger.

**Match the history with `LIKE` so descriptors with order numbers collapse.** Rejected: `LIKE
'swiggy%'` is a guess about where a merchant name ends, and it fails in both directions (`SWIGGY` vs
`SWIGGYS DINER`, `UPI/` prefixes). §8.1 puts fuzzy matching in tier (c), where it is a model that
can be evaluated, not a wildcard that cannot.

**Special-case `coin` and `uber eats` in the engine.** Rejected — CLAUDE.md §6 exists for exactly
this, and a merchant list maintained in Kotlin is one nobody outside the codebase can read or audit.

**Build the loader now, since ADR-0014 said to revisit here.** Revisited, and answered: see
decision 3. "Revisit" is not "reverse" — the conditions that made the deferral correct are unchanged,
and the specific overlap ADR-0005 warned about has not occurred.
