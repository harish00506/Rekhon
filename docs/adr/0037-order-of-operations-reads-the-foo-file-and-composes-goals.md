# ADR-0037 — The order of operations reads the FOO file, mints nothing, and composes the goal waterfall rather than replacing it

- **Status:** accepted
- **Date:** 2026-09-17
- **Deciders:** Harish G (solo), implementing issue 7.5
- **SRS refs:** §36 (AI-FOO, FOO-001, FOO-002, FOO-003), §15.1, §10.1, §29, AI-ARC-003, AI-ARC-006,
  P-02, P-03, P-07; ADR-0017, ADR-0034, ADR-0035

## Context

§36 defines AI-FOO: an eight-stage, India-adapted waterfall that "consumes the forecast surplus and
outputs a ranked action list with rupee amounts", "supersedes the simple waterfall in §15", and
"operationalises RULE-EMERG-FIRST as a stage gate". Three rules sit under it: strict order with
user reordering that shows the cost of deviating (FOO-001), the single top action on Home with the
full list in the Advisor hub (FOO-002), and stage thresholds as versioned, cited rulebook rows
(FOO-003).

Seven things in the repository do not line up with that text as written:

1. **The thresholds are not in `rules-kb.json`.** They live in
   `ai/rules/financial-order-of-operations.json` — stage ids, `params_json`, a file version — which
   nothing read before this issue. One of them is a decimal percentage (`apr_threshold_pct: 13.5`).
2. **The forecast does not exist.** Issue 9.2 was never built; `:domain:engines:forecast` is still
   issue 1.1's placeholder. 7.5's issue file lists 9.2 as a dependency.
3. **Two stages cannot be judged.** Stage 1 needs EPF contribution data the app does not hold; Stage 4
   needs §38's regime comparator (issue 13.4).
4. **Stage 7's simulator does not exist** (issue 10.3).
5. **The grey band has a gap.** Stage 2 fires at ≥ 13.5%; Stage 6 is written "APR ~10–12%" with
   `apr_range_pct: [10, 12]`. Read literally, a 12.5% loan belongs to neither.
6. **`RULE-EMERG-FIRST` is already mirrored** — by `QuickSetupRules` — and 7.3 already routed around a
   second mirror (ADR-0035). ADR-0017 trigger 2 fires the moment another engine copies the number.
7. **The registry names a module that would mislead.** `engine-registry.yaml` put AI-FOO in
   `:domain:engines:orchestrator`, but "orchestrator" already names AI-ORCH, the §7.2 insight
   pipeline in `ai/orchestrator/insight-orchestrator.yaml`.

And one design tension §36 creates on purpose: it *supersedes* 7.3's waterfall, which shipped two
weeks earlier and which the goals screen still renders.

## Decision

**1. The FOO file is the rule set, mirrored, not re-minted.** `OrderOfOperationsRules` is a typed
mirror of the file's stage parameters, converted to paise and basis points, held to the file by
`OrderOfOperationsRulesDriftTest` — the same deferral every engine here makes (ADR-0005, ADR-0017).
Each stage is cited as `FOO.<STAGE_ID>` at the file's version, which is FOO-003's "cited" without a
second copy of the rows in `rules-kb.json`. **No rulebook row is minted**; `_meta.version` stays 1.15.0.
Only parameters the engine applies are mirrored (ADR-0034); `nps_1b_inr` and `apr_range_pct[1]` are
asserted by the drift test but not copied.

**2. `RULE-EMERG-FIRST` is cited, not mirrored** — ADR-0035's pattern. Its number arrives as
`OrderOfOperationsInput.emergencyGateMonths`, resolved by the repository from `QuickSetupRules`. A
reflection guard asserts the mirror's field list never grows.

**3. Dependencies are waived, with substitutes named on screen.**
- The surplus is **the goal waterfall's own figure** — 7.3's observed-P50 stand-in (ADR-0035) — read
  from `GoalWaterfall`, not derived a second time. `surplusBasis` carries which source it was, and the
  full-order screen says it in words.
- Stages 1 and 4 are **always reported, as `SKIPPED`, with the reason**. §36 says "every skipped stage
  shows why"; omitting them would hide that the ranking is incomplete.
- Stage 7 is `DEFER_TO_SIMULATOR` with **no amount**.

**4. The grey band runs from `apr_range_pct[0]` up to, not including, the fire threshold.** The tilde
in "~10–12%" marks 12 as a typical top, not a boundary, and a literal reading would drop a 12.5% loan
into Stage 7's "genuine toss-up" by accident. The drift test asserts `apr_range_pct[1]` is still 12
**and still below** the fire threshold — if it ever rose past 13.5%, this reading would stop following
from the file and the build would say so.

**5. While the gate holds, Stage 3 may take its whole shortfall.** Past the gate it takes AI-EMF's
monthly pace (`topUpMonthly`), the plan the emergency-fund screen already shows. Below the gate,
nothing beneath Stage 3 may be funded, so capping Stage 3 at the pace would leave money idle behind a
rule whose purpose is to build that fund.

**6. An unrated card is fire debt; an unrated loan is not sent.** A card account with no terms or no
APR is ranked in Stage 2 with reason `FIRE_DEBT_CARD_RATE_UNKNOWN` — §36 itself names cards as 36–42% —
and the screen says the rate was assumed. **It does not offer to add the rate**: the device run found
that issue 6.1's card editor has no rate field (`credit_card.apr_bps` is in the schema and nothing in
the UI writes it), so a button built for that sent the user to a screen that could not keep its
promise, and was removed. In practice every card is therefore fire debt until that field exists — the
right default for Indian cards, and said on screen. A loan account with no terms has no rate to place
it by, and guessing a band would be an invented number (P-03). Informal payables carry no rate and are
not ranked.

**7. The module is `:domain:engines:orderofoperations`**, and the registry row is corrected. It depends
on `:domain:engines:goals` for `SurplusBasis` only — L5 over L4, the direction CLAUDE.md §2 allows —
so the two screens name the same enum rather than two copies of it.

**8. 7.3 is composed, not replaced.** Stage 5 is one aggregate line (Σ goals' required monthly)
linking to the goals screen, which keeps 7.3's per-goal split unchanged.

**9. UI: a dashboard card and a full-order screen in `:feature:dashboard`.** The card is FOO-002's Home
half. The Advisor hub is Epic 10's and does not exist, so the list gets its own route
(`CfoRoute.OrderOfOperations`) behind the card; feature modules may not depend on each other
(ARC-001), so it lives beside the card that opens it.

## Consequences

- **Positive:** every stage boundary traces to a row a reviewer can open; the ranking is complete even
  where the app cannot judge a stage; the two surplus-consuming screens pour the same number; no
  rulebook churn, no schema change, no new dependency.
- **Negative / cost:** a **known divergence with 7.3**. Past the gate, AI-FOO gives the emergency fund
  its monthly pace ahead of the goals, while 7.3's waterfall gives it nothing and pours the whole
  surplus into goals. Both screens are right about their own rule; they disagree about the goals'
  share. Recorded, not hidden.
- **Negative / cost:** the "next best rupee" is poured from an *observed* surplus. When 9.2 lands, the
  substitution changes in one place — `GoalWaterfallRepository` — and this engine follows.
- **Negative / cost:** CLAUDE.md §6 is, strictly, still violated — the FOO file is the source of truth
  for a test, not for the running app. ADR-0017's triggers are unchanged: this issue adds a mirror of a
  *different* file, not a second mirror of a shared row, and bumps no row version.
- **Negative / cost:** until the card editor has a rate field, **no card can reach Stage 6** — every
  card is ranked as fire debt. For Indian card rates that is the right answer, but it is an assumption
  the user cannot yet correct.
- **Follow-ups:**
  - Add an APR field to the card editor (issue 6.1's screen). Found on the 7.5 device run.
  - Re-point `GoalWaterfallRepository` at what AI-FOO leaves after Stages 0–3, so the goals screen and
    the ranking agree (closes the divergence above).
  - FOO-001 reordering and its "cost of deviation" — needs an interest projection per deviation.
  - §36's "one-tap goal/contribution creation" from a card.
  - Stage 1 when EPF data exists; Stage 4 when 13.4's comparator exists; Stage 7 when 10.3's simulator
    exists; the list's move into the Advisor hub (Epic 10).

## Alternatives considered

- **Mint `RULE-FOO-*` rows in `rules-kb.json`** — the literal reading of FOO-003. Rejected with the
  user: the FOO file already *is* a versioned, cited rule set for these stages, and copying its numbers
  into a second file would create two sources for one threshold and bump the rulebook's
  `_meta.version`, forcing every typed mirror to restate it.
- **Stop until 9.2 exists.** Rejected with the user: 7.3 already established a named substitute, and
  nothing in the ranking's *order* depends on the surplus — only the amounts do.
- **Leave Stages 1 and 4 out of the list.** Rejected: §36 requires skipped stages to show why.
- **Honour `apr_range_pct[1] = 12` as a hard ceiling.** Rejected: it opens a 12–13.5% hole and ranks
  those loans as low-rate.
- **Re-point 7.3's goal card at FOO now.** Rejected with the user for this issue: it reopens 7.3's
  shipped code, tests and golden file. Listed as the first follow-up.
- **Name the module `orchestrator`, as the registry did.** Rejected: that name is AI-ORCH's.
- **A new `:feature:advisor` module now.** Rejected: Epic 10 will define the Advisor hub; a module
  created ahead of it would be named and shaped by a guess.

## Compliance with golden rules

- **P-01 / P-04:** no network, no consent path; the ranking is computed on-device from the encrypted
  database and works offline. Every amount on both surfaces goes through the privacy blur.
- **P-02:** every stage — including skipped and held ones — shows its reason and the rule ids and
  versions that placed it; the surplus basis is stated in words.
- **P-03:** every figure is the engine's; the feature module words enums and computes nothing. No
  rule's number is written into a string.
- **P-07:** amounts are "suggested"; Stage 6 is a `CHOICE`; Stage 7 proposes nothing; nothing moves.
- **P-08 / MNY / TIM:** `Long` paise, `Int` basis points, no clock read, no randomness; property-tested
  for determinism and the no-paise-lost invariant.
