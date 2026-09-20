# ADR-0045 — The health score scores six signals the app can measure, re-weights what it cannot, and defers the weekly cadence

- **Status:** accepted
- **Date:** 2026-09-19
- **Deciders:** Harish G (solo)
- **SRS refs:** §14 (AI-FHS), FR-AI-001, P-02, P-03, P-08, MNY-002, AI-ARC-003/006, CLAUDE.md §6;
  issue 9.4. Builds on ADR-0007 (derived figures from their owners), ADR-0017 (typed mirrors),
  ADR-0034 (no insurance or dependant fields), ADR-0042 (streams)

## Context

§14.1 defines the score:
- five pillars, weighted 25/20/20/20/15 and each scored 0–100, give a total from 0 to 1000;
- the pillars list sixteen scoring bases between them;
- five bands;
- anti-anxiety rules: weekly updates, movement with a cause list, the single highest-leverage
  action, and no shame;
- a drill-down with a what-if slider;
- pillars with less than one month of signal render as "—" and are re-weighted, never guessed.

The issue asks for a deterministic, weighted composite with the weights as rule rows, every
component shown, provenance, and a golden file.

Most of the sixteen bases have a source in the app. Six are measured by an engine that already
exists. The rest have either no data anywhere in the app (insurance, dependants, retirement flags —
see ADR-0034) or no number in §14 at all (how many alerts is "frequent", what volatility scores
zero).

## Decision

**1. v1.0 scores the six signals the app measures, each on a straight line between stated
anchors.**

| Signal | Pillar | Source | Curve |
|---|---|---|---|
| Runway vs M | Liquidity | AI-EMF's plan | linear to M; at least 25 once a month is covered |
| Obligations / income | Debt | AI-CLS FIXED streams + loans' next instalments; median closed-month income | 100 at ≤ 30%, 0 at ≥ 55% |
| Card utilisation | Debt | card engine, **statement** balance | 100 at ≤ 30% (RULE-CC-UTIL), 0 at 100% |
| Savings rate | Discipline | the monthly ledger: income − needs − wants − liabilities | 0 at ≤ 0, 100 at ≥ 30% (RULE-SAVE-RATE) |
| Budget adherence | Discipline | this month's budgets not overspent | the share |
| Goals on track | Goals | goals with a target that are on track or funded | the share |

- The two anchors §14 does not state — the runway floor's size (25) and where utilisation reaches
  zero (100%) — are minted in RULE-FHS-SIGNALS, and its source note says so.
- The two anchors another row already owns (RULE-CC-UTIL's 30, RULE-SAVE-RATE's 30) are **read,
  not restated**. The drift test asserts both halves.

**2. Missing is never zero.** A signal is absent without data:
- runway: no essentials yet;
- obligations: no month of income;
- utilisation: no statement;
- savings: no month of income;
- budgets and goals: none set.

A pillar with no signal is "—", and its weight goes to the pillars that have data (§14). The
Protection pillar has no signal in v1.0, so it is always "—" and 15% is always shared out. Signals
inside a pillar are weighted equally, because §14 names them but gives no sub-weights.
Confidence is the weight that rests on data.

**3. The join decisions** (in `HealthSignals`):
- **EMIs are not counted twice.** When loan accounts exist, a FIXED stream in a LIABILITY category
  is dropped, because its payments *are* those loans' instalments. With no loan account, that
  stream is the only record of the EMI, so it stays.
- **Nothing fixed and no loan means unknown, not zero** (found on the device). AI-CLS cannot call a
  stream FIXED until three closed months (§8.2), so the demo — which pays ₹28,000 of rent — scored
  "obligations 0% of income, full marks" and reached 992. That is a claim about money the data does
  not support, so the signal is absent until something is measured, and the pillar says "—". A user
  with genuinely no fixed costs is scored on their cards instead.
- **Rent comes from AI-CLS** (FIXED streams, including a confirmed-obligation merchant), not from a
  category name.
- **Utilisation uses the statement balance**, which is what RULE-CC-UTIL's rationale and a credit
  bureau use. The live balance moves with every swipe, against §14's weekly cadence. A card with
  no statement yet is left out, limit and all.
- **The savings rate is Σ kept / Σ income** over three closed months. A month with no income still
  counts its spending.

**4. The score is exact, and its parts add up.**
- Every rounding is half-even, from an exact fraction, taken once per step (signal, pillar, total).
- Signals are scored from the rounded measure the screen shows, so a user can reproduce them.
- Contributions and effective weights are apportioned (floors, then the spare points to the
  largest remainders), so they sum to the total and to 100% exactly.

**5. The lever** is the signal with the most points of the total still to gain:
`w_p × (100 − pts) / n_p` over the effective weights. It is §14's "single highest-leverage action"
reduced to arithmetic already on screen.

**6. The engine is L3 and pure; the repository composes owners.** `HealthScoreRepository` reads
eight flows (the emergency fund, the ledger, streams, loans, cards, budgets, goals and categories)
from the repositories that own them. It touches no DAO, so the score cannot disagree with the
screens it summarises.

## Deferred (each with what it needs)

- **Weekly cadence, movement with a cause list, and a drop paired with its action** (§14
  anti-anxiety). These need stored score snapshots (a table and a migration). The score is live
  today; the lever is the action.
- **The what-if slider:** a UI on the same arithmetic, and a separate screen issue.
- **The Protection pillar** (insurance flags, diversification, the idle-cash and single-income
  penalties). Insurance and dependants need fields (ADR-0034, and later §39's module).
  Diversification and idle cash need a scoring curve §14 does not give; idle cash also needs
  RULE-IDLE-CASH's 60-day balance history.
- **Revolving interest and the no-debt bonus.** The card engine does not yet detect revolving, and
  §14 gives the bonus no size.
- **Overspend-alert frequency, spend volatility, the retirement flag and the funding streak.** No
  curve is given (frequency, volatility) or no field exists (retirement).
- **The Advisor hub** (FR-AI-001). The card sits on the dashboard until the hub exists.

## Consequences

- A young profile sees fewer pillars, and says so: the demo reads "Based on 2 of 5 pillars" until a
  repeat is confirmed, then three.
- The dashboard gains a "Financial health" card: the total and its band; each pillar's points,
  contribution and share; "—" with the weight it gave away; each signal against its full-marks
  anchor; the lever; "Based on N of 5 pillars"; and the rules.
- A profile with data in one pillar gets a score from that pillar alone, and the card says it
  rests on 1 of 5 pillars. That is §14's re-weighting, stated rather than hidden.
- rules-kb moves to 1.17.0, and seven mirrors restate it.

## Alternatives considered

- **Score a missing pillar as 0.** Forbidden by §14, and it would tell a new user they are At Risk
  for not having typed in their insurance. Rejected.
- **Invent curves for the unstated signals** (alert frequency, volatility, diversification). Each
  would be a financial number with no source. Rejected, in favour of rows added when a source
  exists.
- **Live card utilisation.** It is always current, but twitchy, and it is not what RULE-CC-UTIL
  describes. Rejected.
- **Read the database directly.** A second definition of runway, obligations and budgets.
  Rejected, as in ADR-0007.
