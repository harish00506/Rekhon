# NatureEngine — what a rupee became (AI-CLS-N)

**SRS:** §8.3, §8.3.1 · **Pipeline layer:** L3 (rules) · **Module:** `:domain:engines:nature`
**Version:** 1.0 · **Status:** active

## Why this engine exists

§8.3 calls nature "the axis the advice layer is built on": the 50/30/20 rings, the true-spend
metric, the emergency-fund essentials and the Purchase Advisor's scrutiny ladder all branch on it.
Issue 4.1 gave every *category* a nature and issue 4.2 gave every transaction a category, which makes
the obvious conclusion tempting — read `category.nature` and stop.

**That is step 5 of six, and the five above it exist because it is wrong on its own.**

- An EMI paid into a loan account is debt service because of the **account**, whatever category it
  carries.
- A transfer into a gold account is a conversion, not spending, even when someone tagged it Shopping.
- Money the user has already told this app is a Want stays a Want, whatever its category says.

All three failures run the same direction: they **inflate true spend** — the figure Safe-to-Spend,
the health score and the Purchase Advisor are calibrated against.

Pure Kotlin (ARC-002) with no database and no clock, so the whole decision order is provable on the
JVM against a frozen golden file.

## Contract

```
interface NatureEngine {
    fun classify(input: NatureInput): Result<NatureVerdict, AppError>
}
```

- **Input** — `NatureInput`: `accountType`, `counterpartAccountType` (`null` unless it is a transfer
  leg), `type`, `amount`, `merchantHistory`, `categoryNature`, `categoryMedian`, `nowUtcMillis`
  (**passed in, never read** — TIM-001), `rules`.
- **Output** — `NatureVerdict`: the `nature`, `provenance` (`engineId = nature-classifier`,
  version, the caller's instant, the `CLS-NAT-*` row(s) that fired, confidence in bps) and
  `isFlagged`, derived from the rules that produced it so no screen invents its own threshold.

**This engine always answers**, which is the opposite of `ClassificationEngine`'s contract and
deliberately so. §8.3 says *every* transaction gets a nature; the 50/30/20 rings cannot render a
hole. Uncertainty is carried as **low confidence**, not as `null` — a verdict below
`min_confidence_bps` is one the review flow should ask about, not one that is missing.

`NatureInput` is a *shape*, not a `Transaction`: six facts, so the engine cannot quietly start
deciding on a note or a date. Assembling it is the repository's job — every one of those facts is a
join only it may make (ARC-005).

## Formula / algorithm

First match wins. Step 6 is a modifier applied on top of whatever won.

| Step | Fires when | Result | Confidence |
|---|---|---|---|
| `CLS-NAT-001` | the transaction's account **or** its counterpart is `LOAN` | `LIABILITY` | 10 000 |
| `CLS-NAT-002` | a transfer leg where either side is an investment account | `INVEST` | 10 000 |
| `CLS-NAT-003` | a transfer leg where either side is `GOLD`/`PROPERTY`/`VEHICLE`/`CRYPTO` | `ASSET` | 10 000 |
| `CLS-NAT-004` | the user's past overrides for this merchant agree | that nature | `10 000 × matching / total` |
| `CLS-NAT-005` | the category has a nature | it | 8 500 |
| `CLS-NAT-005` | nothing above — no category at all | `fallback_nature` | 3 000 (**flagged**) |
| `CLS-NAT-006` | *modifier*: a NEED over `3 ×` the category median | nature **unchanged** | 5 000 (**flagged**) |

**Steps 1–3 are worth full confidence** because an account's type is a fact the user entered on the
accounts screen, not something inferred from a payee string. That is also why they outrank the
learned step: a user who once called a jeweller a WANT did not thereby decide that buying gold is
consumption.

**All three look at both sides of the movement**, and the first draft did not — steps 2–3 read only
the counterpart, so the *arriving* leg of an SIP fell past every account step to the category and
half of every conversion was labelled a Want. The golden file caught it. `counterpartAccountType`
being non-null is already "this is a transfer leg", so a dividend credited into an investment account
is an `INCOME` row with no counterpart and never reaches the branch.

**Step 4 yields rather than imposing.** Two-of-three is 6 666 bps, under the floor, so an unsettled
history lets the category answer — unlike issue 4.2's category tier, where an unsettled history *ends*
the search. A category may be left unset; a nature may not.

**Step 6 keeps the nature and lowers the confidence**, which is the difference between asking a
question and answering it. §8.3.1's own example is ₹9,400 at a grocery: festival stock-up (still a
NEED) or a party (a WANT)? Only the user knows. Bounded three ways — NEED categories only, a median
only when the sample clears `min_history_for_median`, and **strictly** greater than the multiple.

### True spend, and what it honestly omits

`natureBreakdown(rows)` folds classified rows into totals per nature plus §8.3's `trueSpend`.

- **`TRANSFER_IN` never counts.** Both legs of a conversion classify identically — correctly, they
  describe one becoming — so summing both would double every SIP.
- **A `TRANSFER_OUT` counts only when an account step decided it.** A bank-to-bank transfer becomes
  nothing; the engine still answers (§8.3 requires a nature and the sheet must render something), but
  folding that flagged fallback in would add a self-transfer to the month's Wants.
- `INCOME` and `ADJUSTMENT` are excluded: §8.3 asks what money *became*.

**`trueSpend` is understated by exactly the interest on any EMI.** §8.3's formula is
`NEED + WANT + interest/fees`; splitting a repayment needs the amortisation row §8.3.1 step 1 names,
and this build has no such table ([ADR-0016](../../../docs/adr/0016-nature-classification-by-account-type.md)).
`liabilities` is reported in full and contributes nothing — the conservative direction, and the
dashboard says so on screen rather than only here.

## Assumptions & guardrails

- Confidence is integer basis points (MNY-002). **No `Double` in the module**; the one division
  rounds down, toward the safe answer.
- The clock is passed in (TIM-001); `CfoWallClockInDomain` fails the build on a wall-clock read.
- **The rule set cannot be configured into silence.** `fallbackBps` and `unusualAmountBps` are
  *defined* as below the floor — they are the only two ways anything reaches §8.3's review flow — and
  `NatureRules` refuses to be built with either at or above it. Likewise a nature cannot be both true
  spend and a conversion, which would count a rupee twice.
- It classifies, never writes and never orders (P-07). The override is the user's, and it is the only
  thing stored.

### Known limits, stated rather than hidden

- **No principal/interest split** on a LIABILITY — see above.
- **Goal-linked accounts and holdings** are not signals: §8.3.1 names both, neither table exists, so
  the account's *type* stands in (ADR-0016).
- **A cash gold purchase is not an ASSET.** Step 3 needs a transfer into an asset account; buying gold
  with cash and categorising it Shopping is a WANT until the user overrides it or records the asset.
- **The monthly fold skips step 6**, and it costs nothing: the modifier only lowers confidence and a
  breakdown sums natures. Applying it would mean a median query per category to change no figure.

## Rules / knowledge consumed

| ID / file | What it provides |
|---|---|
| `nature_classification.order` (`ai/knowledge/classification-kb.json`) | the six `CLS-NAT-*` steps, in order, each with a version |
| `nature_classification.stage_nature` (same file) | the five confidence values, `unusual_amount_multiple: 3`, `min_history_for_median: 3`, `fallback_nature`, and the true-spend/conversion split |

Mirrored as `NatureRules` per **ADR-0016**, for the reason ADR-0005 first gave: nothing loads `ai/`
at runtime. `NatureKbDriftTest` fails the build when any id, version, **order** or threshold
disagrees. The order is compared as an ordered list because a reordering is the single most
consequential edit anyone can make here — it silently changes which of two conflicting signals wins.

## Evidence shown to the user (P-02)

The transaction detail sheet renders a "What this became" section: the five natures as chips with the
decided one selected, the citation — *"Decided by rule CLS-NAT-005 — tap to change"* — and, when the
verdict is flagged, a line saying the app is unsure. The rule id is verbatim because it is a citation
into the knowledge base that a user or reviewer can look up. Tapping any other nature overrides it;
tapping the selected one **withdraws** the override and hands the transaction back to the rules.

## Tests

- **`NatureEngineTest`** — each step alone, and each adjacent pair *in conflict*: a loan-account EMI
  whose category says NEED, a gold purchase tagged Shopping, a merchant override against a category.
  Plus the fallback, the modifier's three bounds, and the two rule-set invariants.
- **`NatureGoldenTest`** over `golden/nature.txt` — 21 records fixing the nature, **the cited rule**
  and the flag. The citation is the point: four of six steps can produce NEED, so half these records
  would pass under a decision order with two steps swapped.
- **`NatureBreakdownTest`** — money math, a 100 % gate: the two double-count guards, the sign
  handling, exactness to the paise, and the stated EMI shortfall asserted so the day the split lands,
  it fails and says why.
- **`NatureKbDriftTest`** — the mirror against the file, order included.
- **`NatureRepositoryTest`** (`:data:repository`, in-memory Room) — the five joins, the override round
  trip, the median query, and the monthly fold.
- Coverage: engine ≥ 85 % (gate).

**Two gates were watched to fail before they were trusted**: swapping `CLS-NAT-004` and `CLS-NAT-005`
in the knowledge base turned the drift test red, and mis-citing one golden record turned
`every record is decided by the expected rule` red **while the nature assertion still passed** —
which is exactly why the citation is asserted.

## Version log

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-08-10 | Initial implementation for issue 4.3 from SRS §8.3.1, steps 1–5 plus step 6 as a confidence modifier. Steps 2–3 gained the "either side" reading within this version, before release, when the golden file caught the arriving leg of a conversion falling through. |
