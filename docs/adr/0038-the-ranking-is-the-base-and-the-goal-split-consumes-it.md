# ADR-0038 — AI-FOO is the base; the goal waterfall splits what it leaves

- **Status:** accepted
- **Date:** 2026-09-18
- **Deciders:** Harish G (solo)
- **SRS refs:** §36 (AI-FOO), §15.1 (FR-GOAL-003, FR-GOAL-005), §10.1, AI-ARC-003, P-02, P-03;
  supersedes the divergence recorded in ADR-0037, builds on ADR-0035

## Context

§36 says AI-FOO "supersedes the simple waterfall in §15". Issue 7.5 shipped it composing 7.3 rather
than replacing it, and recorded the cost as its first follow-up:

> Past the gate, AI-FOO gives the emergency fund its monthly pace ahead of the goals, while 7.3's
> waterfall gives it nothing and pours the whole surplus into goals.

So a household with three months of cover and an unfinished fund saw two different answers to one
question. The dashboard said the fund takes ₹10,000 first and the goals get ₹50,000; the goals screen
said the goals get ₹60,000. Each screen was right about its own rule, and a user reading both had no
way to tell which was true.

The obvious fix — have the goal waterfall pour AI-FOO's remainder — was blocked by the direction of
the dependency: `OrderOfOperationsRepository` read the surplus, the goals' need and the goal count
**out of** `GoalWaterfallRepository`. Reversing the flow without moving anything would have been a
cycle.

## Decision

**1. The surplus derivation moves out of both into `SurplusRepository`.** The observed-P50 median with
the declared-envelope fallback (ADR-0035) is unchanged, line for line; it simply no longer belongs to
whichever repository happened to need it first. Both read it.

**2. AI-FOO is the base.** `OrderOfOperationsRepository` now takes `SurplusRepository` and
`GoalRepository` — it sums the projections' `requiredMonthly` itself rather than reading a waterfall's
totals — plus the emergency fund and its own debt read. It no longer knows the goal waterfall exists.

**3. The goal waterfall consumes the ranking.** `GoalWaterfallRepository` takes
`OrderOfOperationsRepository` and pours **what §36 leaves**: the distributable surplus less every
stage above `GOAL_INVESTING`. It passes `emergencyTopUpMonthly = ZERO`, because Stage 3 already
claimed that pace and claiming it twice would hide a month of it. It still passes the runway and the
gate, so `blockedByEmergencyFund` still explains a held goal in the words the card has always used.

**4. The plan carries two echoes so the smaller figure explains itself.** `GoalWaterfall` gains
`claimedBeforeGoals` and `grossSurplus`. They are **echoes, not terms**: the allocation is already net
of them and nothing subtracts them again. The goals card names the month's own surplus, then says
what went to the buffer, high-interest debt and the emergency fund first, and what is left. Without
that line the card would show a figure smaller than the dashboard's with no explanation — money
apparently lost between two screens.

## Consequences

- **Positive:** the two screens cannot disagree; the goals' share is now a single figure computed
  once. `GoalWaterfallRepository` also gets simpler — four sources become three, and it no longer
  derives anything.
- **Positive:** §36's supersession is real rather than asserted. A goal plan is now feasible only if
  the goals fit in what is left *after* the buffer, high-interest debt and the emergency fund — which
  is what "the next best rupee" means.
- **Negative / cost:** `GoalWaterfall.monthlySurplus` changed meaning — it is what the goals may have,
  not the month's surplus. Anything reading it as the month's figure must read `grossSurplus`. Two
  repository tests asserted the old meaning and were updated; the card was too.
- **Negative / cost:** a plan can now read INFEASIBLE because a credit card is being paid off. That is
  the intended answer and the card says why, but it is a visible change for an existing user.
- **Negative / cost:** one more link in the chain behind the goals screen. A failure in the debt read
  now reaches the goals card, where before it could not. The ranking's own fallback keeps that to a
  degraded figure rather than an error.
- **Follow-ups:** none from this decision. ADR-0037's remaining follow-ups (FOO-001 reordering,
  one-tap goal creation, the Advisor hub, Stages 1/4/7) are untouched.

## Alternatives considered

- **Make AI-FOO match 7.3 instead** — have Stage 3 claim nothing once the gate is clear. Rejected: it
  contradicts §36's strict order, and it would mean the emergency fund is never funded on purpose
  between three months of cover and its target.
- **Let the goals screen call the ranking directly from its ViewModel.** Rejected: the split would
  then live in a feature module, and two ViewModels would each own a copy of "what is left" (ARC-005).
- **Keep both and label them** — "goals-first view" and "order-of-operations view". Rejected: two
  answers with labels is still two answers, and the app's whole claim (P-02) is that a figure can be
  traced to one rule.
- **Have AI-FOO read the waterfall's remainder** (the reverse composition). Rejected: it is the cycle
  this decision exists to break, and it puts §15's simple waterfall above §36's order, which is
  backwards.

## Compliance with golden rules

- **P-02:** the card names the month's surplus, what the earlier stages took and what is left; every
  stage still cites its rule.
- **P-03:** every figure is still an engine's; the repositories resolve inputs and the feature module
  words them. Nothing new is computed on a screen.
- **P-07:** advice only; nothing moves.
- **P-08 / MNY / TIM:** `Long` paise throughout, no clock read outside the repositories, and the
  engine's no-paise-lost invariant is unchanged — it now simply holds over a smaller pour.
