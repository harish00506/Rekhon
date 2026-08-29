# ADR-0029 — The portfolio is the investable accounts, and unpriced holdings are excluded from it

- **Status:** Accepted
- **Date:** 2026-08-28
- **Deciders:** Harish G
- **Refs:** issue 6.4, FR-INV-002, SRS §11.1/§11.2, `RULE-GOLD-CAP`, `RULE-CRYPTO-CAP`,
  `RULE-CONC-15-70`, P-02, P-03, P-07, [ADR-0027](0027-asset-class-is-a-column-on-the-holding.md)

## Context

FR-INV-002 (MUST) says "Portfolio view MUST show allocation by asset class, diversification
analysis, and concentration warnings". Every percentage in that sentence is a fraction, and a
fraction needs a denominator that the requirement does not name.

Two questions had to be answered before any arithmetic could be written, and both change what the
user is told rather than merely how it is computed.

**What is "the portfolio"?** The rulebook rows this issue consumes are worded in terms of it —
"Gold <= 10% of portfolio", "single asset class <= 70%" — but the app stores accounts, not a
portfolio. Eleven account types exist, from a savings account to a vehicle.

**What about holdings with no price?** Issue 6.3 shipped `currentValue` as `Money?`, null when a
holding has units but has never been priced, deliberately never ₹0 (P-03) — because reporting zero
would show the user their entire cost as a loss. Those holdings cannot be placed in a split at all.

ADR-0027 anticipated the first question and guessed the other way: `AssetClass.defaultFor` was
written as "issue 6.4's groundwork ... the fallback for accounts that hold value without lots", and
a comment on `InvestmentRepository.INVESTABLE` said 6.4 would count property and vehicle through
their account balances. This ADR supersedes that expectation.

## Decision

**The portfolio is the investment, gold and crypto accounts** — the `INVESTABLE` set issue 6.3
already defined — that are not archived and that the user counts towards net worth. Holdings where
an account has them, one position per holding; the account balance via `AssetClass.defaultFor`
where an account has none. Bank, cash, property, vehicle and receivable accounts are outside it.
Liabilities were never a candidate: `AssetClass.defaultFor` returns null for them, and
`AssetClass`'s own doc already required that a debt "must be excluded from the allocation
denominator rather than filed under `OTHER`, which would understate every other class's share".

**Allocation is computed over the priced positions only.** Unpriced positions are excluded from the
denominator, counted in `unvaluedCount`, and reported: the screen renders "Based on 8 of 11
holdings", and `provenance.confidenceBps` carries the coverage as a rate. A portfolio where nothing
is priced reports `NOTHING_PRICED` rather than a split of zeroes.

Both decisions live in `InvestmentRepository.observeAllocation`, not in the engine. The engine
divides whatever positions it is handed, which is what keeps it a pure function over a literal list
and testable without a database.

## Consequences

- `RULE-CONC-15-70`'s 70% single-class line stays meaningful. Counting an emergency fund would put
  nearly every Indian user permanently over it, and a warning that fires for everyone on day one is
  noise the user learns to dismiss — which then costs them the message that mattered.
- `RULE-GOLD-CAP` fires for the users most likely to trip it. A gold account tracked as one number
  has no holdings to price, so a denominator built only from holdings would have omitted it
  entirely.
- **A user's house is not in this screen.** Someone with a ₹1 crore flat and ₹2 lakh of equity is
  told their portfolio is 100% equity, which is true of their *portfolio* and startling next to
  their net worth. Issue 6.6's net-worth history is where the whole balance sheet belongs, and the
  two screens will disagree by design. This is the sharpest edge of the decision.
- A partly-unpriced portfolio shows shares that are correct about the part it can see and silent
  about the rest — mitigated by the coverage line, not eliminated by it. A user with one very large
  unpriced holding gets a split that is technically true and practically misleading, and the
  coverage sentence is the only thing standing between them and that.
- The stale `INVESTABLE` comment from 6.3 was corrected in the same commit rather than left to
  contradict this.

## What was actually verified

| Edit made | File touched | Test that went red |
|---|---|---|
| Removed the `type in INVESTABLE` filter, so savings counted | `InvestmentRepository.positions` | `a savings balance is not part of the portfolio` |
| `currentValue` → `currentValue ?: Money.ZERO` | `InvestmentRepository.positions` | `an unpriced holding is reported as unvalued rather than counted at zero` |
| `<=` → `<` on the cap comparison, flagging at the line | `Allocation.classCapFlags` | `a class sitting exactly on its cap is not flagged` |
| `order.take(shortfall)` → `order.take(0)`, dropping the leftover basis points | `Allocation.distribute` | five at once: the allocation golden test and all four property tests — the shares stopped summing to 10 000 |
| `cap_pct` 10 → 11 in the rulebook, **no Kotlin change** | `ai/rules/rules-kb.json` | `gold's ceiling matches RULE-GOLD-CAP` — which also proves the `inputs.file` wiring, since Gradle re-ran a task whose only changed input was the JSON |
| Deleted the last golden record | `golden/allocation.txt` | `the fixture still covers the paths it says it does` |

Every row above was actually executed and the named test actually observed failing, then reverted —
ADR-0005's standing requirement that a new gate be broken on purpose before it is trusted, and the
reason the G-01 vacuous-`koverVerify` precedent exists.

## Alternatives rejected

**Every asset account, matching net worth.** The most complete picture of where wealth sits, and
what ADR-0027's comment expected. Rejected because it makes both `warn`-level rules fire almost
permanently: cash and real estate dominate an ordinary Indian balance sheet, so "one asset class is
over 70%" would be a standing condition rather than an observation. A rule that always fires
conveys nothing.

**Holdings only, the issue's literal wording** ("classifies holdings into asset classes").
Rejected because a gold or crypto account tracked as a single balance — the common case for exactly
the two classes the rulebook caps — has no holding rows and would silently vanish from the split,
under-reporting the classes most likely to breach.

**Refuse to report until every holding is priced**, like `XirrUnavailable` refuses a rate.
Rejected because it is the wrong strength of claim: a rate over a series with a missing terminal
value is genuinely undefined, whereas a split over the priced part is a real and useful fact about
a real subset. One unpriced holding blanking the whole screen would punish the user for incomplete
data instead of telling them what is missing.

**Suppress the flags below a coverage floor.** Attractive — a warning computed over 40% of a
portfolio is thin evidence. Rejected because the floor would be a new financial threshold, which
CLAUDE.md §6 says must be a rulebook row, and minting one to paper over a presentation problem is
the wrong instinct. The coverage line tells the user what the split was computed from and lets them
judge it, which is what P-02 asks for.
