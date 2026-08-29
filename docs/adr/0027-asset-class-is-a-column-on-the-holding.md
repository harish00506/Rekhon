# ADR-0027 — Asset class is a column on the holding, and the unit price is the one observation we store

- **Status:** accepted
- **Date:** 2026-08-24
- **Deciders:** Harish G (solo), implementing issue 6.3
- **Refs:** [ADR-0007](0007-account-balances-derived-not-stored.md),
  [ADR-0016](0016-nature-classification-by-account-type.md),
  [ADR-0026](0026-amortisation-schedule-is-derived-not-stored.md),
  [ADR-0028](0028-xirr-by-bisection-over-the-daily-growth-factor.md),
  CLAUDE.md §3 (MNY-001/002, TIM-002), SRS §11.2, §20.1, `ai/rules/rules-kb.json`,
  `domain/engines/investment/ENGINE.md`

## Context

Issue 6.3 gives an investment account the things inside it: holdings, their dated cash movements,
and what they have returned. Two questions had to be answered before a column could be written, and
the repo answered neither.

**First, where the asset class lives.** §11.2 measures allocation "across equity / debt / gold /
real-estate / cash / crypto", and issue 6.4 will consume five rulebook rows that need those
distinctions — `RULE-AGE-EQUITY` compares an equity share against a band, `RULE-CONC-15-70` caps a
single class at 70%. `AccountType` already has `INVESTMENT`, `GOLD` and `CRYPTO`, so deriving the
class from the account looks free.

It is not. One `INVESTMENT` account — a broker, an MF folio — legitimately holds an equity fund *and*
a liquid debt fund. Derived from the account, both land in one bucket, and 6.4's headline number is
not imprecise but structurally wrong: the equity share it reports is the whole portfolio.

**Second, where a holding's value comes from.** This project has refused to store a derived figure
twice: [ADR-0007](0007-account-balances-derived-not-stored.md) for account balances,
[ADR-0026](0026-amortisation-schedule-is-derived-not-stored.md) for amortisation schedules, both for
the same reason — a stored copy and the rows that produced it can disagree, and only one is true. A
holding's value looks like a third instance of that pattern, and mostly it is. But a *market price*
is not derivable from anything the device holds. Issue 6.5 will fetch it; until then somebody has to
type it, and refusing to store it means refusing to compute a return at all — which fails 6.3's
acceptance criteria.

## Decision

**The asset class is an `AssetClass` enum in `:core:model`, stored as `investment_holding.asset_class`.
The unit price is stored with the day it was observed. Everything else is derived.**

`AssetClass` has seven members with explicit `storedValue` strings, plus
`AssetClass.defaultFor(AccountType): AssetClass?` — the editor's default *and* issue 6.4's fallback
for accounts that hold value without lots (a savings balance is `CASH`, a property is
`REAL_ESTATE`). Liabilities map to `null`, so 6.4 excludes them from the allocation denominator
rather than filing them under `OTHER` and understating every other class.

An enum rather than a free-text column, for the reason `AccountType`'s own doc comment already
gives: the column has no CHECK constraint in Room, so nothing but a closed set stops a typo becoming
a row every later query silently misses.

`storedValue` for two members is **already load-bearing**: `ai/rules/rules-kb.json` shipped
`RULE-GOLD-CAP` with `params_json.asset_class == "gold"` and `RULE-CRYPTO-CAP` with `"crypto"`
before any Kotlin consumed them. `AssetClassTest` pins both strings so 6.4 cannot discover a
mismatch at the point it tries to cap a class that matches no holding.

On value: `investment_holding` stores `unit_price_minor` (paise **per unit**) and
`priced_on_iso_date`, both nullable and **both-or-neither**. The holding's worth is
`netQuantity × unitPrice`, computed on every read; `netQuantity` is a sum over the lots. Nothing
stores a total value, a total quantity or a cost basis.

The pair is both-or-neither because the date is not decoration: it is the terminal cash flow's date
for XIRR. A price with no date would leave the engine reaching for "today", and the same untouched
holding would report a different return tomorrow — a P-08 violation invisible to any single run,
because each run looks self-consistent.

## Consequences

**Good.** Issue 6.4 inherits a taxonomy instead of inventing one, and inherits it in a place both
the editor and the allocation engine read, so the two cannot drift. Issue 6.5 replaces *only* where
`unit_price_minor` comes from — no schema change, no second source of truth, and
`priced_on_iso_date` is already the staleness anchor its labels need. The absence of a stored value
means a corrected lot immediately corrects the value, the gain and the return, with nothing to
invalidate.

**Bad.** `defaultFor` is a guess presented as a default, and a user who never revisits it leaves
every holding in an `INVESTMENT` account classed as equity — which would overstate the equity share
6.4 reports. The field is on the editor precisely so it can be overruled, but a default nobody
changes is a default that becomes data.

**The price is a stored observation, and it will go stale silently** until 6.5 ships. The date is
recorded and rendered, so the staleness is visible rather than hidden, but nothing yet warns about
it. A holding priced eight months ago reports a return computed against an eight-month-old
valuation, correctly labelled and still misleading at a glance.

**A holding is the first Epic-6 table not keyed on `account_id`.** `credit_card` and `loan` are 1:1
with their account; holdings are 1:N, so they carry a surrogate `id` and an index on `account_id`.
The lots below them denormalise `profile_id` rather than reaching it through the holding, because
the demo wipe and the export both address every table by profile alone (ADR-0006), and a table
reachable only by a join is one of them will eventually miss.

## Alternatives rejected

**Derive the class from `AccountType`.** Cannot express an equity fund and a debt fund in one broker
account, which is the ordinary case, not an edge one.

**A free-text `asset_class` column.** The exact trap `AccountType`'s doc describes — an unconstrained
TEXT column where a typo is invisible until a query returns nothing.

**A seven-row `asset_class` lookup table.** An enum with extra failure modes: a migration to seed it,
a join on every read, and the possibility of a row being edited into something no `when` handles.

**Store the computed value instead of the price.** Discards the units, so issue 6.5's per-unit market
prices could not be slotted in without a migration, and per-unit gain would be unavailable. It also
makes the value a figure with two sources — the lots and itself.

**Store nothing and wait for 6.5.** Fails 6.3's acceptance criterion that XIRR is computed and
tested, and leaves `:domain:engines:investment` with no terminal cash flow to solve against.
