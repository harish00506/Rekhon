# ADR-0049 — The Purchase Advisor: seven gates, a worst-of verdict urgency may only soften so far, and a trace kept in typed columns

- **Status:** accepted
- **Date:** 2026-09-25
- **Deciders:** Harish G (solo)
- **SRS refs:** §13 (§13.1 pipeline, §13.2 output contract), FR-AI-003, §19.2 (`purchase_check`),
  AI-ARC-001/003/006, P-02, P-03, P-07, P-08, MNY-001/002, TIM-002, DB-003, DB-005, CLAUDE.md §6;
  issue 10.1. Builds on ADR-0017 (typed mirrors), ADR-0046 (an L5 engine imports no other engine),
  ADR-0006 (the demo leaves nothing behind)

## Context

§13.1 gives the pipeline: affordability, cash-flow impact, obligation ratio, goal impact, budget
fit, opportunity cost, timing — and then "VERDICT = worst severity across gates, softened by urgency
flag". §13.2 gives the card: verdict and summary, a gate-by-gate table, an impact strip,
alternatives, one-tap actions, and "the full trace object is persisted so the user can revisit why a
past decision was made".

What §13 does not say is how far urgency may soften, what a goal delay is measured against, which
gates can be satisfied by spending less, or how the trace is stored. Each had to be decided.

## Decision

**1. One pure engine, `:domain:engines:purchase` (AI-PA 1.0, L5).**
It takes the request and a `PurchaseSignals` of figures the engines below already published, and
returns a `PurchaseVerdictCard`. It imports no other engine module, for ADR-0046's reason: an L5
engine that could reach AI-FCT and AI-EMF would start re-deriving what they own. The repository maps
each signal across.

**2. Every gate states its figures as typed values with stable keys, never as sentences.**
A `GateFigure` carries a key and one of an amount, a count, basis points or text. The screen maps
keys to `strings.xml` (§21.6), and AI-GRD can check a figure against the value rather than against a
string the engine chose. **Every gate cites a rule** — a `require` in `GateResult` enforces it,
because a gate that cites nothing cannot answer "why am I seeing this?" (P-02).

**3. Affordability warns where §13 says it warns, and fails only where nothing else can.**
§13.1 writes "fail if liquidAfter < emergencyFloor → verdict ≤ STRETCH", which is a cap, not a
refusal: the money is there, and what it would cost is the safety net. So crossing the emergency
floor is a **warning**; only a purchase the balance cannot cover at all **fails**. For an instalment
the question is the month, not the balance: it fails when the instalment exceeds the monthly surplus
and warns when it exceeds Safe-to-Spend — both lines are figures other engines published, so no new
threshold is minted.

**4. The cash-flow gate reports the crunch days it *adds*, and says how many were already there.**
Blaming a purchase for a crunch that was coming anyway would make every verdict NOT_NOW for someone
already in trouble — which is when the advice matters most.

**5. A goal delay is the price divided by everything going to goals each month** (RULE-PA-GATES,
`days_per_month` 30). Apportioning the price across goals by contribution share works out to the
same delay for every goal, so the card states one number. More than a month is a warning; a few days
is not.

**6. Urgency softens one step, never onto a hard fail, and never to a lie.**
`urgency_softens_one_step` lifts STRETCH to COMFORTABLE — a broken fridge in a tight month is still
the right purchase. `soften_blocked_on_hard_fail` stops it lifting NOT_NOW, and since every FAIL is
a hard fail, in practice urgency only ever lifts a stretch. Both are rulebook params. Telling
someone an unaffordable purchase is merely a stretch because they called it urgent is how an advisor
becomes a rubber stamp.

**7. Opportunity cost is shown and never fails a gate** (RULE-PA-OPPCOST: 11% a year, five and ten
years, §13.1's own defaults). An app that refused purchases because the money could have been
invested would refuse every purchase.

**8. The alternatives answer two honest questions, and refuse to answer a third.**
- `comfortablePrice` is the smallest of the per-gate caps — often the category budget rather than
  the bank balance, which is the honest thing to say.
- `comfortableFrom` assumes only that saving continues at the current rate, and models the
  affordability constraint alone; it does not pretend to know how the budget or the forecast will
  move.
- When a gate objects **whatever the price is** — days already under the buffer, a cheaper month
  ahead, obligations already past the line — there is **no** comfortable price and the card says
  nothing rather than naming one. A property test caught the first version offering a price that
  still came back a stretch, and a second bug where a negative cap was flattened to ₹0.

**9. The trace is kept in typed columns across two tables at schema 25.**
`purchase_trace` holds the request, verdict, impact strip and alternatives; `purchase_trace_gate`
holds one row per figure, carrying its gate and that gate's outcome. No JSON, so DB-005's
blob-versioning question never arises and a figure stays something a query can read. The outcome
repeats across a gate's figure rows, which is denormalised on purpose: the alternative was a third
table, and two keeps the archive, the demo wipe and the restore drill to two places. Both tables
carry tombstones, so neither needs an exemption from the soft-delete invariant.

**The card's own citations are a column.** RULE-COOL-OFF decides the cooling-off suggestion and
belongs to no gate, so provenance rebuilt from the gate rows alone lost it — a read-back test caught
it.

**10. Nothing is executed.** The advisor recommends and keeps its reasoning; the user decides
(P-07). There is no "buy it" action and no money movement.

## Deferred, and why

- **The timing gate's signal.** The gate is built and tested, but `cheaperMonth` is always `null`
  until AI-SEAS's monthly factors are wired through the repository. Inventing a seasonal figure in
  the repository would be exactly the re-derivation ARC-001 forbids.
- **§13.2's one-tap actions** ("create a saving goal for this", "remind me on <date>", "ignore").
  Each is a write into another feature's store — a goal, a notification, a dismissal — and each
  deserves its own issue rather than three half-wired buttons.
- **EMI vs cash with total interest**, which §13.2 lists under alternatives. It is a loan
  calculation, and 10.3's simulators own that engine.
- **The buy list and the adaptive interview** (§13.3) — that is issue 10.2 by name.
- **The category budget for an unnamed category.** The gate reads the budget when the request names
  a category; the screen does not yet offer a category picker, so in practice it passes `null` and
  the gate stays silent. The wiring is there for 10.2's buy list to use.

## Consequences

- The flagship question has a deterministic answer with its working attached, and the working
  survives: a card read in December reproduces what AI-PA 1.0 decided in September.
- Every threshold that decides a verdict is a rulebook row — the lender's lines, the assumed return,
  the softening policy — and a drift test fails the build when a mirror lags.
- The engine is proven at each gate, against an independent Python oracle on five households, and by
  properties that caught two real bugs before release.
- Schema 25 adds two tables to the archive, the demo wipe and the restore drill.
