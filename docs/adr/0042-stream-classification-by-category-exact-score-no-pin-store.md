# ADR-0042 — Stage 2 classifies by category, scores exactly, and ships without a pin store

- **Status:** accepted
- **Date:** 2026-09-19
- **Deciders:** Harish G (solo)
- **SRS refs:** §8.1, §8.2, §8.3, AI-CLS, AI-ARC-003, AI-ARC-006, MNY-002, P-02, P-03, P-08; issue 9.1.
  Builds on ADR-0015 (Stage 1), ADR-0016 (nature), ADR-0017 (the typed mirror), ADR-0018 (splits)

## Context

§8.2 gives a formula and a decision table, and leaves these open:

1. **What a stream is:** "merchant-series or category".
2. **What cv is taken over:** all six months, or the months with activity.
3. **Precision:** the formula is in fractions and needs a square root. Engines here compute in
   integer bps (MNY-002, P-08).
4. **The overrides:** "transactions linked to loan EMIs, recurring rules, or insurance premia are
   FIXED". "User can pin any stream's class; pins beat the model."
5. **The outputs:** "stored in `monthly_profile` (DB) and exposed as evidence".
6. **Cold start** with "< 2 months of data".

The issue also says Stage 2 must "finalise nature" and be "consistent with Stage-1 (4.2) and nature
(4.3)".

## Decision

**1. A stream is a Stage-1 category.** It is the unit the user already chose and corrects, and
it's what the rest of the app budgets by. Classifying by merchant would split one landlord paid
through two apps into two streams, and would merge a supermarket's groceries with its electronics.
Split payments count by their lines, through the existing split-aware read (ADR-0018).
Uncategorised expenses form one stream with no prior. Consistency with Stage 1 is by construction:
Stage 2 classifies Stage 1's output and never re-files anything.

**2. Nature stays AI-CLS-N's.** "Finalise nature" is read as *do not reopen it*. The per-transaction
verdict from 4.3 (only the override is stored, ADR-0016) is the only writer of nature. A stream's
class is a statement about **recurrence**, not about what the money became. So a WANT can be FIXED
(a subscription), and a NEED can be VARIABLE (a doctor's visit). A second writer would make the two
disagree the first time either changed.

**3. cv is over months with activity**, as §8.2's `n` is. A stream seen in three months has three
totals. Zero months would make every non-monthly stream look volatile. Cadence and day-lock already
penalise irregularity.

**4. Exact arithmetic, class decided on the exact score.** Every quantity is a 34-digit `BigDecimal`
(`MathContext.DECIMAL128`, fully specified, so identical on every JVM). Only the numbers shown are
rounded to bps, HALF_EVEN. Rounding each term first moved a test stream by 0.1 bp, enough to change
its class at a threshold set exactly there. The thresholds themselves stay integer bps in the
mirror, and the file's decimals × 10 000 are drift-checked.

**5. Degenerate inputs score the worst, never the best.** Fewer than two gaps, or a median gap of 0
days, gives cadence 1. A stream seen twice has no rhythm to reward. Day-lock distance is circular
over 31 days, and a tie for the mode goes to the earliest day.

**6. Known obligations are matched at transaction level, as §8.2 says ("*transactions* linked to …
recurring rules").** A confirmed, live, outflow recurring rule links transactions in one of two ways:
- A **detected** rule (3.7) names a merchant and stores no category. Payments to that merchant
  (trimmed, lower-cased, the detector's own key) become their own stream, `recurring:<merchant>`,
  and are FIXED. The rest of the category stays a separate stream, scored on its own rows.
- A **quick-setup** rule names a category, so that whole category stream is FIXED.

The first version joined on category only. The device run showed it never fired: after confirming
the demo's "Landlord −₹28,000" series, Fixed still read ₹0.00, because the detected rule carries no
`categoryId`. Loan EMIs and insurance premia have no such link in this schema. Their categories'
FIXED priors cover cold start, and the detector proposes them once they repeat.

**7. Pins are an engine input with no store yet.** The precedence (pin > obligation > cold start >
score) is implemented and tested, so "the override path is data" holds in the engine: an override
arrives as input, not as a code branch. Persisting a pin needs a column or table, a migration, the
archive, the drill fixture (8.3) and a UI. That is a feature of its own, and the repository passes
no pins until it lands.

**8. No `monthly_profile` table.** The three totals are derived on read, like balances (ADR-0007)
and amortisation (ADR-0026). A stored copy of a pure function of the ledger drifts the moment a
transaction is edited. They are "exposed as evidence" on every verdict's provenance and on the
dashboard.

**9. Cold start: n < 2 → the category's `typical_stream`, labelled as an estimate. No prior →
VARIABLE.** The priors already existed in `category_defaults`. They are now read and cited by their
`CLS-CAT-*` rows.

**10. The rows are data.** `classification-kb.json` v1.4 adds `CLS-STR-001..004` with versions and
confidences, plus the three parameters §8.2 states in prose. Weights and thresholds are unchanged.
The three existing mirrors restate the file version and nothing else.

## Consequences

- **The dashboard gains one line**, "Every month, typically — Fixed · Semi-fixed · Flexible", with
  the estimate note and the rules that fired. All five Paparazzi baselines were re-recorded.
- **Nothing consumes the totals yet except that line.** The forecast (9.2) and health score (9.4)
  are the named consumers. The engine id `AI-CLS.stream` is in the engine registry, versioned
  separately from Stage 1.
- **A quarterly fee reports its full amount as the month's typical.** Amortising it is not in §8.2.
- **Pinning is not reachable by the user.** It is recorded here, not implied by the tests.

## Alternatives considered

- **Merchant series.** Rejected in Decision 1. It could be added later as a finer stream inside a
  category if the eval data shows categories are too coarse.
- **Integer bps throughout, rounding each term.** Rejected in Decision 4: the class would depend on
  the order of rounding.
- **Storing a `monthly_profile`.** Rejected in Decision 8.
- **A migration now for pins.** It is a feature with its own UI, and the issue's criteria don't need
  the store to prove the precedence.
