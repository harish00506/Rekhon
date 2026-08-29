# ADR-0028 — XIRR is solved by bisection over the daily growth factor, not Newton-Raphson over a rate

- **Status:** accepted
- **Date:** 2026-08-24
- **Deciders:** Harish G (solo), implementing issue 6.3
- **Refs:** [ADR-0027](0027-asset-class-is-a-column-on-the-holding.md),
  [ADR-0026](0026-amortisation-schedule-is-derived-not-stored.md),
  CLAUDE.md §1 (P-08), §3 (MNY-001/002, TIM-001/002), SRS §11.2,
  `domain/engines/investment/ENGINE.md`, `domain/engines/loan/Emi.kt`

## Context

§11.2 names XIRR — money-weighted return per holding — and it is the figure that makes an
investment screen worth building. A twelve-instalment SIP has most of its money invested for far
less than a year, so the naive cost-to-value ratio understates the return by roughly half: the
golden fixture's `sip-12` case is **15.67%** where the ratio says 8.3%.

The textbook formulation is `NPV(r) = Σ cf_i · (1+r)^(−d_i/365) = 0`, solved with Newton-Raphson
from a guess. That is what Excel does, and both halves of it are hostile to P-08.

The exponent `d_i/365` is fractional. `BigDecimal` has no exact fractional power, so an
implementation reaches for `Double` — and `Double` is precisely what MNY-001 exists to keep out of
money paths, because a figure the numeric guardrail must verify exactly (AI-ARC-004) cannot be one
that drifts in the last places.

Newton is worse in a subtler way. Its answer depends on the initial guess; it can diverge on a
series with one large early outflow; and it stops on a tolerance test against the derivative. Those
are three independent places where a refactor that looks harmless silently changes what the app said
last year about a holding nobody touched. This project has already decided that a stored insight
must stay reproducible (AI-ARC-006), and an engine whose answer depends on its own starting guess
cannot honour that.

## Decision

**Reparametrise, then bisect a fixed bracket a fixed number of times, with no early exit.**

Substitute the **daily growth factor** `x = (1+r)^(1/365)`. Then `(1+r)^(d/365) = x^d` with `d` a
whole number of days, and multiplying the equation through by `x^dMax` clears every division:

```
F(x) = Σ cf_i · x^(dMax − d_i)          d_i = ACT days from the earliest flow
```

A polynomial in `x` with non-negative integer exponents, sharing NPV's positive roots. The solve
therefore uses only `BigDecimal.pow(Int, MathContext)`, `multiply` and `add` — no fractional power,
no division, no floating point. **That is what makes determinism provable here rather than merely
asserted.**

The root find is bisection over a literal bracket:

```
PRECISION     = 34            MathContext digits, HALF_EVEN  (DECIMAL128)
X_LOW         = 0.98          0.98^365 − 1 ≈ −99.94% a year
X_HIGH        = 1.02          1.02^365 − 1 ≈ +136 000% a year
ITERATIONS    = 128           0.04 · 2⁻¹²⁸ ≈ 1.2e-40
DAYS_PER_YEAR = 365           ACT/365, fixed
```

Slower than Newton, and it does not care: there is no guess to depend on and no tolerance branch to
drift. 128 halvings leave a bracket forty orders of magnitude finer than the basis point the answer
is rounded to, so an early exit could only ever make the result machine-dependent, never faster in a
way anyone could see. **All five constants are part of `ENGINE_VERSION`**, not tuning knobs —
changing any one changes every historical answer, so it is a version bump (AI-ARC-006), the same
discipline `Emi.kt` records for its pinned `MathContext`.

Flows are **sorted and same-day flows summed** before the solve, which is what makes the answer
independent of the order the caller listed them in. Conversion back happens once, at the end:
`r = x^365 − 1`, then `(r × 10 000)` rounded `HALF_EVEN` to integer basis points — one rounding, on
the answer, the `Emi.kt` discipline.

**ACT/365, fixed**, rather than a leap-corrected count: it is what Excel's `XIRR` uses, so a user who
checks the app against a spreadsheet gets the same number. A 366-day span is therefore 366/365 of a
year, deliberately — the `leap-span` golden case pins it at 997 bps where a 365-day equivalent gives
1000.

**Three refusals, not a fallback number:** `flows.tooFew` (fewer than two distinct days),
`flows.sameSign` (purchases with nothing valued or sold), `flows.notBracketed` (a rate outside the
band — in practice a total loss). `InvestmentEngine.holding` reports these as a `XirrUnavailable`
reason beside every other figure rather than failing, because an unpriced holding is the ordinary
state of a new position, not a fault.

## Consequences

**Good.** The answer is byte-identical on every machine and every build, and the golden file proves
it is *right* rather than merely unchanged: its expectations come from an independent
60-significant-digit implementation that solves NPV directly with exp/ln — a different formulation
from this one, so agreement is evidence. A property test additionally recovers a known rate to ±1
bps from a terminal value the test computes itself as `cost × (1+r)^k`, a formula the engine never
evaluates. The engine reads no clock and takes no `asOf`, so a holding's return is a function of
stored rows alone.

**Bad.** The bracket is a real limit, and it shows. A holding that lost more than 99.94% in a year
is refused rather than reported — correct, since "−100%" and "beyond what this engine will say" are
different claims, but it is a blank where a user might expect a number. The same applies at the top:
a position that multiplied 2000-fold in a month is outside the bracket too.

**It is slower than Newton by a constant factor** — 128 polynomial evaluations rather than five or
six. For a twelve-year SIP that is roughly 18 000 `BigDecimal.pow` calls per holding, which is
milliseconds and invisible behind a `Flow` on the IO dispatcher, but it is not free and it scales
with the number of holdings on the accounts screen.

**Multiple sign changes admit multiple roots**, and XIRR is then not uniquely defined
mathematically. This returns the root bisection finds in the fixed bracket: deterministic though not
unique. Excel's answer in the same situation is guess-dependent; ours is at least stable.
`ENGINE.md` states this rather than leaving it to be discovered.

## Alternatives rejected

**Newton-Raphson over `r` with `Double`.** The industry default, and the reason to reject it is the
whole of the Context above: guess-dependent, divergent on plausible inputs, tolerance-terminated,
and floating point on a money path.

**Newton-Raphson over `r` with `BigDecimal`.** Fixes the floating point and none of the rest, and
still needs a fractional power for the NPV it is differentiating.

**Bisection over `r` directly, with `BigDecimal` exp/ln for the fractional exponent.** Deterministic
in principle, but `BigDecimal` has no exp/ln in the JDK — it would mean hand-rolling a series
expansion, which is more code to get right than the substitution and has its own precision contract
to pin.

**A shipped numerics library.** No dependency in the catalog offers XIRR, adding one needs a
`DECISIONS.md` row it could not justify, and `:domain:engines:*` are pure-Kotlin modules that
deliberately carry nothing but `:core:model` and `:core:common`.

**An early exit once the bracket is narrower than a basis point.** Faster, and it reintroduces
exactly the machine-dependence the fixed iteration count removes — the answer would then depend on
where the loop happened to stop.
