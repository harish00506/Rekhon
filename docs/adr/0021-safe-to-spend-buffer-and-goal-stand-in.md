# ADR-0021 — Safe-to-Spend withholds a buffer §5.2 does not name, and stands in for goal contributions until issue 7.1

- **Status:** accepted
- **Date:** 2026-08-16
- **Deciders:** Harish G
- **SRS refs:** §5.2, §14, AI-STS; `RULE-STS`, `RULE-IDLE-CASH` (§29); P-02, P-03

## Context

§5.2 defines Safe-to-Spend as *"income minus commitments, bills, and goal contributions across the
period"*. Issue 5.2 implements it, and the definition leaves two things unresolved that a reader of
the shipped code will otherwise have to reverse-engineer.

**First, "income" is ambiguous and the two readings behave very differently.** The ledger's
month-to-date income is the truthful one, but a user paid on the 28th sees ₹0 income and a deeply
negative Safe-to-Spend for twenty-seven days, then a jump on payday. That figure measures the salary
calendar, not the user's position, and it is unusable for the one thing the number exists for —
deciding whether to spend something today.

**Second, there is no goals engine.** Goal contributions are AI-GOAL's (issue 7.1), which is two
epics away. §5.2 names the term anyway, so 5.2 either ships without it — reporting a figure it knows
to be too high, by exactly the amount the user intends to save — or substitutes something.

**Third, and the actual deviation:** the deductions are only the commitments the app has been told
about. The ones that ruin a month are the ones it has not. Nothing in §5.2 provides for that, and a
Safe-to-Spend that spends to the last rupee of its own arithmetic is optimistic in precisely the way
the figure exists to prevent.

## Decision

`RULE-STS` v1.0 fixes all three as **data**, not code:

1. `income_basis = "budget_envelopes_then_actual"` — the quick-setup envelope total (which *is* the
   user's declared monthly income, split three ways), falling back to the ledger's month-to-date
   income only for a profile that declared none. Neither being positive is an **absence**: the card
   shows its pending state rather than a ₹0 (P-03).
2. `include_goal_contributions = true`, and until issue 7.1 the repository supplies **the whole
   quick-setup INVEST envelope** — the user's own declared monthly saving. When AI-GOAL lands, the
   term becomes the sum of the month's linked contributions and **the engine's contract does not
   change**; only what the repository puts in the field.

   *In full, not netted against what has already been saved.* Netting looks obviously right and is
   wrong: §8.3's `trueSpend` is `NEED + WANT` and already excludes every conversion, so a rupee
   already put into an SIP is in no other term — netting would leave it deducted from nothing, and
   the figure would **rise** by exactly the amount the user had just saved.
3. `buffer_pct = 5` — a twentieth of income withheld, shown on the card as its own line
   ("Held back as a buffer"), never hidden inside another term.

`floor_at_zero = false`: an overcommitted month reports its shortfall rather than a clamped ₹0.

## Consequences

- **Positive:** the figure is stable across the month, defensible line by line on the card, and every
  parameter is a rulebook row a user or reviewer can change without touching Kotlin. The buffer
  absorbs one ordinary surprise without making the headline useless.
- **Negative / cost:** the app deliberately reports a figure **lower** than §5.2's literal formula,
  by `buffer_pct` of income. That is a real deviation and the reason this ADR exists. It is
  disclosed on the card rather than buried — the buffer is a labelled line, not a silent haircut —
  so a user who disagrees can see exactly what it costs them and change the row.
- **Negative / cost:** the goal-contribution stand-in is wrong for anyone whose real goals differ
  from their quick-setup INVEST envelope, which is everyone once issue 7.1 ships. It is recorded as
  a stated limitation in `ENGINE.md` and in `RULE-STS.source_note`, not left to be discovered. A
  user who saves *more* than their envelope is under-deducted by the excess.
- **Consequence for issue 4.3:** the terms only stay disjoint if `observeNatureBreakdown` is bounded
  at today, so this issue bounded it (FR-TXN-010 — it feeds a card captioned "This month,
  **actually**"). That is a correctness fix to 4.3's read in its own right; it changes the
  "This month, actually" figures for any user with a future-dated row in the current month.
- **Follow-ups:** issue 7.1 replaces the goal term in `SafeToSpendRepository` only. Issue 10.1
  (Purchase Advisor) and §14 (health score) both read this figure and inherit the buffer; if either
  needs the unbuffered number, the breakdown already carries it as the `BUFFER` line rather than
  needing a second engine call.

## Alternatives considered

- **Month-to-date actual income only** — rejected: a headline that reads −₹30,000 for most of the
  month and flips on payday trains the user to ignore it, which is worse than a slightly
  conservative number.
- **`max(envelopes, actual)`** — rejected: the figure jumps mid-month when a bonus posts, and the
  jump is upward, which is the direction that causes overspending.
- **No buffer** — rejected on the merits by the decider: the deductions cover known commitments
  only, and the unknown ones are what end a month short.
- **Omitting goal contributions until 7.1** — rejected: §5.2 names the term, and reporting a figure
  known to be too high by the user's own stated savings is a worse error than approximating it from
  the envelope they set.
- **Putting `buffer_pct` on `RULE-50-30-20`** — rejected for ADR-0019's reason: that is a shipped row
  at v1.0 cited in stored provenance, and a params change bumps its version, which is ADR-0017's
  trigger 3. `RulebookDriftTest` asserts the parameters stay off it.

## Compliance with golden rules

- **P-03:** every rupee is computed by `SafeToSpendEngine` from amounts the repository resolved; the
  ViewModel and the composable compute nothing. The absence case exists precisely so no figure is
  invented from an income nobody supplied.
- **P-02:** the buffer and every other term are shown as their own labelled, signed lines with the
  `RULE-STS v1.0` citation. `SafeToSpend`'s constructor asserts the lines sum to the headline, so the
  shown work and the number cannot disagree.
- **P-04:** no network on this path; verified in airplane mode on the `CfoTest` emulator.
- **P-07:** advisory only — nothing here blocks a spend or moves a rupee.
- **P-08 / MNY-001 / MNY-002 / TIM-001:** `Long` paise end to end, the buffer applied as basis points
  with `HALF_EVEN` via `Money.percentOf`, no clock and no randomness in the engine — the instant is
  passed in and stamped into provenance only.
