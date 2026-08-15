# ClassificationEngine — Stage-1 auto-categorisation (AI-CLS)

**SRS:** §8.1 · **Pipeline layer:** L3 (rules) · **Module:** `:domain:engines:classification`
**Version:** 1.0 · **Status:** active

## Why this engine exists

Issue 4.1 shipped the merchant→category knowledge base and, deliberately, no consumer for it —
[ADR-0014](../../../docs/adr/0014-classification-kb-seed-mirror-and-unconsumed-merchant-rules.md)
records that and names this issue as the consumer. Until it existed, a transaction was categorised
only if the user tapped a chip, so `transactions.category_id` was mostly null on a real profile and
every engine built on top of categories — budgets, the 50/30/20 rings, Safe-to-Spend — was reading
a column almost nobody had filled in.

§8.1 does not describe a matcher. It describes a **precedence chain**:

```
user rule  >  learned model (≥ 0.85)  >  knowledge base  >  "Uncategorised" prompt
```

and the order is the design. A user who files Swiggy under Groceries because they only ever order
instamart must not be out-argued by a shipped rule that says Dining with high confidence. **The
earlier tier is allowed to decide, including to decide nothing.**

The asymmetry that sets the floor: **a merchant the engine declines to classify costs one tap** on
a chip row that is already on screen. **A merchant it classifies wrongly files money under a
category the user never chose** — quietly, into a budget they will later read as fact, on a chip
that looks exactly like one they picked themselves. One is an inconvenience, the other is a
falsehood, so every ambiguous case resolves to `null`.

Pure Kotlin (ARC-002) with no database and no clock, so the whole precedence chain and the accuracy
gate are provable on the JVM.

## Contract

```
interface ClassificationEngine {
    fun suggest(input: ClassificationInput): Result<CategorySuggestion?, AppError>
}
```

- **Input** — `ClassificationInput`:
  - `merchant: String` — the payee as captured, in any case, with whatever descriptor noise the bank
    or the user wrote (`SWIGGY*ORDER 7781`). Normalised here, so no caller has to.
  - `categories: List<Category>` — the profile's **live** taxonomy. This is what makes "a suggestion
    always names a row that exists" a property of the type rather than a convention.
  - `history: List<MerchantHistoryRow>` — `(categoryId, count)` for this same normalised merchant,
    aggregated by the caller because only a repository may touch a DAO (ARC-005).
  - `nowUtcMillis: Long` — stamped into provenance; **passed in, never read** (TIM-001).
  - `rules: ClassificationRules` — the knowledge base's rows and thresholds, **injected** (ADR-0015).
- **Output** — `CategorySuggestion?`: `categoryId` plus `provenance` (`engineId = auto-categoriser`,
  `engineVersion`, `computedAtUtcMillis`, `evidence = [CLS-MER-0NN@v]` or `[CLS-USER-HISTORY@1.0]`,
  `inputWindow`, `confidenceBps`).

**`Ok(null)` is the ordinary answer**, not a failure: §8.1's fourth precedence step *is* the
"Uncategorised" prompt, which on screen is simply the chip row with nothing selected.

There is no `confidence` or `ruleId` field on `CategorySuggestion`, and no category **name**. The
first two live in `provenance` — the one shape every engine result in this codebase carries
(AI-ARC-003) — and duplicating them would let the displayed reason and the stored reason drift. The
name is the caller's to look up from `categories`, so a rename cannot falsify a copy.

`normaliseMerchant(raw)` is **public**, and public for one reason: the caller looks the correction
history up by merchant, and if it normalised differently from this engine the two tiers would
disagree about what "the same merchant" is — the user's own correction would silently stop being
found while the knowledge base kept matching. One function, used by both sides.

## Formula / algorithm

Two tiers, in §8.1's order. `merchant` is trimmed and lower-cased first; an empty result proposes
nothing.

### Tier (a) — the user's correction history (§8.1(a))

Runs when **any** history row names a category that is still live. Rows naming a deleted category
are dropped *before* the share is taken: a deleted category is not a dissenting vote, it is an
absent one, and counting it would let a tidy-up in the categories editor silently mute every rule
the user had taught.

```
settled      = the surviving row with the highest count
confidence   = 10 000 × settled.count / total        (integer division, MNY-002)
propose iff  settled.count ≥ history_min_occurrences AND confidence ≥ min_confidence_bps
```

Integer division rounds **down**, so 2 of 3 is 6 666 bps and falls below the 7 000 floor rather than
above it. A merchant filed inconsistently therefore proposes nothing — **and does not fall through
to the knowledge base.** The user has demonstrably formed their own opinion about that merchant;
the shipped rules have no standing to overrule an opinion just because it is a confused one.

If *no* row resolves, there is no opinion left to overrule anything with, and Stage 1 carries on.

### Tier (b) — the knowledge base (§8.1(b))

Each `merchant_rules` row holds one or more literals (`swiggy`, or `uber|ola|rapido`). For every
row, the best of its literals scores:

| Match | Confidence |
|-------|-----------|
| the merchant **is** the literal (`swiggy`) | `exact_match_bps` = 9 500 |
| the literal is a **whole word** inside it (`SWIGGY*ORDER 7781`) | `word_match_bps` = 8 500 |
| otherwise | no match |

The **ambiguity check runs before the best match is taken**: if the matching rows name more than one
category, nothing is proposed. `AMAZON PAY NETFLIX` matches Shopping and Subscriptions at identical
confidence, and "take the best" would resolve it by whichever row the file happens to list first —
the knowledge base's *ordering* deciding where the user's money goes.

The winning row's `category` is then resolved **by name** against the live taxonomy.

### Whole-word matching is load-bearing, not a nicety

`CLS-MER-010`'s literal is `lic`. Matched as a substring it files every **Licious** order — a
meat-delivery service most Indian users of this app will have in their ledger — under *Insurance*,
where it becomes a NEED, joins the emergency-fund essentials, and is the last place anyone would
look for a food spend. `publicis` and `delicious` are the same bug. A word here ends at anything
that is not a letter, which is looser than `\b` on purpose: `hp petrol` and `et money` are single
literals containing a space, and digits are a boundary so `iocl` matches `IOCL1234`.

The rule is kept identical to `:domain:engines:sms`'s rather than shared with it — the two modules
share no code by design (ARC-002), and a common text utility would be the first thing to drag one
engine into the other's dependency graph.

## Assumptions & guardrails

- Confidence is integer basis points (MNY-002). **There is not a `Double` in the module**, and the
  one division is integer arithmetic that rounds toward the safe answer.
- The clock is passed in (TIM-001); `CfoWallClockInDomain` fails the build on a wall-clock read here.
- **It reads `category`, not `default_nature`.** The rows carry a nature and reading it would be the
  natural next line of code, but nature classification is §8.3 and **issue 4.3**, whose decision
  order puts a category's default nature at step 5 behind three account-level overrides this engine
  cannot see. A nature guessed from the merchant alone would be right often enough to be trusted and
  wrong exactly where it matters — an EMI, a gold purchase, a goal transfer.
- **`regex` rows are treated as alternations of literals**, not as regular expressions. Enforced,
  not merely documented: `MerchantRule`'s constructor refuses a literal containing regex
  metacharacters, so a knowledge base that ever writes `^amazon` fails the build instead of silently
  matching nothing.
- **§8.1(c), the on-device TF-IDF + logistic model, is not built.** See
  [ADR-0015](../../../docs/adr/0015-stage-1-classification-tiers-and-the-kb-mirror.md).
- It proposes, never writes and never orders (P-07). The tap that overrides a suggestion is also
  what teaches tier (a), because that tap becomes a categorised transaction.

### Known limits, stated rather than hidden

- **An exact history lookup misses a varying descriptor.** §8.1(a) asks for an *exact* (normalised)
  merchant match, so `SWIGGY*ORDER 7781` and `SWIGGY*ORDER 9902` are different merchants and filing
  one teaches the other nothing. It matters least where it would hurt most — a typed merchant and an
  SMS counterparty both repeat verbatim — and the real fix is the fuzzy matching §8.1(c) describes,
  which is a model, not a `LIKE`. Pinned by a test so it stays a decision.
- **A renamed seeded category stops matching tier (b).** The knowledge base resolves by name, so a
  user who renames "Dining" before ever categorising a Swiggy order gets no suggestion until they
  file one by hand — after which tier (a) covers it permanently. Resolving through the seed key
  instead would mean teaching this module the repository's `"$profileId:category:$key"` id scheme,
  a storage detail leaking into a pure engine to buy back one tap.
- **`UBER EATS` is classified as Transport**, because `CLS-MER-007` matches `uber`. It is a food
  delivery service. The fix is a knowledge-base row, not a special case here, and it is deliberately
  *not* in the eval set — a set that quietly omits the cases an engine fails measures the curation.
- **`AMAZONPAY` and `BYJUS` are missed**, because the glued suffix closes the word boundary. Both
  *are* in the eval set, labelled, and are the two misses that keep the score under 100%.

## Rules / knowledge consumed

| ID / file | What it provides |
|-----------|------------------|
| `merchant_rules` (`ai/knowledge/classification-kb.json`) | thirteen `CLS-MER-*` rows: `match`, `type`, `category` |
| `stage1` (same file) | `min_confidence_bps: 7000`, `exact_match_bps: 9500`, `word_match_bps: 8500`, `history_min_occurrences: 1`, `CLS-USER-HISTORY@1.0` |
| `category_defaults` (same file) | via `CategorySeed` in `:core:model` — the names tier (b) resolves against |

Mirrored as `ClassificationRules` per **ADR-0015**, for the reason ADR-0005 first gave: nothing in
the app loads `ai/` at runtime. `ClassificationKbDriftTest` fails the build when any id, version,
match string, type, category or threshold disagrees — including a check that every merchant rule
points at a category `category_defaults` actually defines, which is the failure mode that would
otherwise look like a working tier that classifies nothing.

**`min_confidence_bps` cannot be set above `word_match_bps`.** `ClassificationRules` refuses to be
built that way, because such a floor would leave tier (b) firing only on merchants typed with no
descriptor at all — almost none of them — while every test that scores it against bare names kept
passing. The tier would look alive and classify nothing.

### A rule this issue had to fix

`CLS-MER-011` shipped in 4.1 matching the bare literal `coin`. Read as a rule for the first time
here, it files a laundromat, a coin dealer and a coin collector under **Investment** — money the
50/30/20 view would then count as saving. Fixed as a data row (`coin` dropped at version 1.1, since
`zerodha` already covers every real descriptor for Zerodha's Coin), never as a special case in code.
The id was kept and the version bumped, per the file's own `id_policy`.

## Evidence shown to the user (P-02)

The add-transaction screen pre-selects the proposed chip and prints, under the chip row:

> Suggested: Dining · rule CLS-MER-001   **Not this**

The rule id is shown **verbatim**. That is ugly and it is the point: it is a citation into
`ai/knowledge/classification-kb.json` that a user or a reviewer can look up, and "we thought it
looked like food" is not. The dismiss action is the P-07 half — a suggestion the user cannot refuse
is a decision — and tapping any other chip refuses it too, permanently for that screen.

## Tests

- **Each tier in isolation**, their precedence, and the two ways a tier yields: an inconsistent
  history proposes nothing *and does not fall through*; an unusable one *does*.
- **The substring traps**, first in the file because they are the tests that matter: `LICIOUS`,
  `PUBLICIS`, `DELICIOUS` (literal starting a longer word) and `GARLIC` (literal ending one), scored
  apart because a one-sided boundary check passes one and fails the other.
- **The `coin` regression**, so the knowledge-base fix cannot be undone silently.
- **Refusals:** two rules naming different categories; a category the profile does not have; a blank
  merchant; a floor above the word rate refused at construction.
- **Provenance and determinism:** the engine names itself and its version, stamps the caller's
  instant, and gives a byte-identical result twice (P-08).
- **The data seam** (`CategorySuggestionTest`, `:data:repository`, in-memory Room): the history query
  normalises case and whitespace on both sides, ignores deleted transactions, ignores splits, and is
  scoped to the active profile so the demo teaches a real ledger nothing.
- **The eval gate** (`ClassificationEvalTest` over `src/test/resources/eval/categorisation.txt`):
  seventy-five merchant descriptors — fifty-five labelled across all thirteen rules, twenty that must
  be refused. Current scores: **accuracy 96%** (two known misses), **wrong categories 0**,
  **refusals 20/20**.
- Coverage: engine ≥ 85% (gate).

### The threshold is the SRS's

§8 sets categorisation at **≥ 92%**, and that is the number the gate uses. The refusal half is
scored at **100%** with no budget, stated as a count rather than a percentage precisely because a
percentage invites one.

### What the eval set does and does not prove

It scores 96%, and that is **not** an independent estimate of real-world accuracy: the fixtures were
written alongside the engine, so they are the shapes it was built to handle. Twice during that
writing the set drove a change rather than confirming one — it found `coin`'s false positive, and it
found that a substring match would file Licious under Insurance — which is the set doing its job and
also a demonstration that the descriptors it does not contain are the ones that will be wrong.

What the gate provides is **regression protection**: a rule edit that starts misfiling money, or
stops classifying Indian merchants, fails the build. The independent measurements are the emulator
run and, in the end, the user's own correction — which is why tier (a) exists and why nothing here
is ever saved without a tap (P-07).

## Version log

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-08-10 | Initial implementation for issue 4.2 from SRS §8.1, tiers (a) and (b). `CLS-MER-011` lost its `coin` literal within this version, before release, when the eval set was written against real descriptors. |
