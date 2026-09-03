# Changelog

All notable changes to AI Personal CFO are recorded here.
Format: [Keep a Changelog](https://keepachangelog.com). Versioning: [SemVer](https://semver.org).
Single source of truth for the version number is the repo-root [`VERSION`](VERSION) file; keep
`app/build.gradle.kts` `versionName` equal to it. Epics map to the SRS roadmap (§26); every
entry cites its requirement IDs (§28). See [`docs/issues/00-issue-workflow.md`](docs/issues/00-issue-workflow.md).

## [0.7.0] — Epic 7: Goals & Emergency Fund

> The goals engine, the emergency-fund engine, the feasibility waterfall, linked contributions and
> the Financial Order of Operations.

### [0.7.3] — Issue 7.3: Goal feasibility & waterfall  (2026-09-03)

- **Implemented §15.1's feasibility check and priority waterfall** (**§15, §15.1**, **FR-GOAL-003**,
  **FR-GOAL-005**, **AI-GOAL.waterfall**). Until now `GoalEngine` answered each goal as though it
  were the only claim on the month — which is the honest answer to "what would this one take" — and
  **nothing in the app noticed** when three of those honest answers together exceeded everything the
  user earns. `GoalWaterfallEngine` shares one surplus between them: emergency fund first while
  `RULE-EMERG-FIRST` holds, then each goal in the user's own order, reporting the gap and
  FR-GOAL-003's three levers on everything the money did not reach.
- **§15.1 asks for the P50 *forecast* surplus, and there is no forecast.** `:domain:engines:forecast`
  contains only the placeholder issue 1.1 scaffolded — **issue 9.2 was never built**. The
  substitution is the P50 of *observed* surplus: the median of `income − (needs + wants)` across
  closed months, falling back to the onboarding INVEST envelope and reporting **unknown** when
  neither exists. `SurplusBasis` names which on every result, so the card reads "the middle of your
  last 6 closed months" rather than implying a projection (ADR-0035).
- **Safe-to-Spend is deliberately *not* the surplus source.** `RULE-STS` has
  `include_goal_contributions: true` and already subtracts `GoalPlan.totalRequiredMonthly`, so it is
  the surplus *net of* goals; feeding it back would double-count them and make the answer depend on
  itself. Money already **invested** is likewise not subtracted — investing is goal funding, and
  netting it out would hide the money the plan allocates.
- **`RULE-EMERG-FIRST` finally has a reader**, after sitting in the rulebook since day one at
  `severity: fail`, naming `AI-GOAL` in its `consumed_by`, and being enforced by nothing. It is cited
  on **every** plan rather than only when it fires: a clamp that does not bind changed nothing, but a
  gate is evaluated every time and both outcomes decide whether goals are funded at all.
- **No rulebook row minted**, on ADR-0033's precedent — `_meta.version` stays 1.15.0 and the six
  typed mirrors are untouched. Sharper: the engine holds the gate's **citation but not its number**.
  `QuickSetupRules` has mirrored `min_runway_months` since issue 2.3, and ADR-0017's second trigger
  makes a *second* mirror the moment to build the runtime loader instead — so the threshold arrives
  as an input, and a drift test asserts `GoalRules`' instance fields never grow while leaving its
  citations free to.
- **Schema 21 — `goal.sort_order`**, one integer defaulted to zero, the only part of the plan that is
  stored because it is the only part the user decides. Both list queries tie-break on
  `target_date_iso, name`, so an upgraded profile sees exactly the list it saw yesterday until it
  drags something; the round-trip test inserts two goals against their date order and asserts
  precisely that.
- **Shipped the way in**, as 7.1 and 7.2 did: the plan card sits above the goals on
  `:feature:goals`, each goal gains what the surplus can give it and the three levers when it falls
  short, and the waterfall is **draggable** (§15). Because a long-press drag is unusable with
  TalkBack, every card also carries "Move up" / "Move down" as semantic custom actions and merges its
  descendants so those actions land on the goal they move — and the Compose test drives the actions,
  not the gesture, so it proves the accessible path rather than proving a mouse can do it.
- **Running it found two defects a green build could not.** The drag **did nothing at all**:
  `DRAG_ROW_HEIGHT` was a plausible-looking `96.dp`, which is 264px at 440dpi against a ~900px card,
  so a one-place drag computed three rows, fell out of range and was correctly — silently — ignored.
  Every layer behaved as designed, which is why nothing failed. The card now measures itself. And the
  goal card asserted two opposite things at once, *"₹15,000.00 a month short of that"* directly above
  *"Fully covered by this plan"*: 7.1's shortfall compares against the monthly the user typed, 7.3's
  against what the surplus can spare, and adding the second measurement made the first one's wording
  ambiguous **retroactively, without 7.1's code changing**. `goals_shortfall` now names its
  comparison.
- **Three gates proved red before being trusted:** a mis-labelled golden lever, a `RULE-EMERG-FIRST`
  softened from `fail` to `warn`, and a `surplus_lookback_months` minted into the rulebook. All three
  failed **without `--rerun-tasks`**, which confirms 7.2's rulebook-as-test-input fix still holds.

### [0.7.2] — Issue 7.2: Emergency Fund engine (AI-EMF)  (2026-09-02)

- **Implemented:** §10.1's **runway** — how many months the user could live on what they hold liquid
  — and the target it should reach (**§10.1, §15, §36**, **AI-EMF**). `:domain:engines:emergencyfund`
  is pure Kotlin/JVM with an injected `today`, so it reads no clock (TIM-001) and every answer is
  reproducible (P-08). It reports the personal multiplier M, the target, the funded ratio, the
  shortfall, the monthly top-up that closes it, and which of `RULE-EMF-COACH`'s bands to say it in.
- **Two rulebook rows minted**, `RULE-EMF-MULT` and `RULE-EMF-COACH`, both v1.0; `rules-kb.json`
  `_meta.version` **1.14.0 → 1.15.0**, which is why five unrelated typed mirrors restate it.
  `RULE-RUNWAY-M` was **not** touched — its params were already right, and this issue is what finally
  makes its `multiplier_source: AI-EMF` true.
- **Only two of §10.1's five multiplier terms are applied, on purpose.** The spec also bumps M for
  dependents, for no health cover, and for a job-stability self-assessment; **no field in this app
  holds any of the three**. Nothing was minted for them, and `RulebookDriftTest` asserts their
  *absence*, so the issue that adds the fields must add the params in the same breath (ADR-0034).
- **Liquid means savings and cash.** §10.1 would also count a breakable FD and a liquid mutual fund,
  through a per-account liquidity tier this schema does not have. Guessing one from `AccountType`
  would decide for every user at once that every FD is breakable, or that none is. The runway is
  therefore **understated**, which errs safely, and the screen names exactly which accounts counted.
- **Essentials are a median, not a mean** — the middle of the closed months' `NEED` spend, falling
  back to the quick-setup needs envelope, and reported as **unknown** when neither exists. A zero
  target is the dangerous answer: it would tell somebody with nothing saved that they are funded.
- **Shipped the way in**, as 7.1 did: `:feature:emergencyfund` on a typed route (ARC-001) with a
  dashboard entry, carrying §10.1's coach lines and the evidence drill-down it requires.
- **Superseded `QuickSetupEngine`'s stand-in** three-month target as the app's answer to "how big
  should the fund be" — the onboarding seed is left in place, because it runs before any history
  exists.
- **Found and fixed a gate that could be skipped.** `ai/rules/rules-kb.json` was not a declared input
  to any Gradle task, so a threshold edit could leave **every** `RulebookDriftTest` in the repo
  `UP-TO-DATE` and the build green. Discovered by bumping `_meta.version` and watching four of five
  modules go red while `:domain:engines:goals:test` was skipped. `CfoKotlinLibraryConventionPlugin`
  now declares it.
- **Tests:** the engine at **100% line, 99.1% branch**; 16 golden records covering every status,
  every volatility band and all four band edges; 500 seeded property cases; a Room-backed repository
  suite; ViewModel and Compose flow tests. Three gates were deliberately broken and confirmed red.

### [0.7.1] — Issue 7.1: Goals engine (AI-GOAL)  (2026-08-30)

- **Implemented:** a target and a date become a **required monthly contribution** (**§10, §15, §36**,
  **AI-GOAL**). `:domain:engines:goals` is pure Kotlin/JVM with an injected `today`, so it reads no
  clock (TIM-001) and every answer is reproducible (P-08). Per goal: what is left, how many whole
  contributions fit before the date, the largest of those instalments, the date the user's own plan
  actually gets there, the shortfall against it, and which `RULE-HORIZON` funding bucket applies.
- **Shipped the way in, deliberately.** Schema **19 → 20** adds `goal`, `GoalRepository` is the only
  thing that reads it (ARC-005), and `:feature:goals` is a new module on a typed route (ARC-001) with
  a dashboard entry. The acceptance criteria were engine-only; building only the engine would have
  repeated exactly what issue 6.7 found in 6.5 — a complete stack with no field to fill in, and no
  test noticing.
- **`saved_minor` is hand-entered**, and temporary by design: issue 7.4 derives it, as 6.5's fetched
  price replaced 6.3's hand-typed one.
- **No rulebook row was minted, and that is the decision.** `RULE-HORIZON` already named
  `AI-GOAL.funding_buckets` before this engine existed; every other figure is arithmetic. "On track"
  is an **exact** comparison of two figures the user typed, so the card shows the *shortfall* rather
  than a tolerance band (P-02). Minting a row would have bumped `rules-kb.json`'s `_meta.version`,
  forcing all six existing typed mirrors to restate it. `RulebookDriftTest` asserts the **absence**
  as well as the presence, so a future threshold has to arrive deliberately.
- **No rate of return is assumed** — an absence, not a zero. None exists in `ai/rules/`, and inventing
  one would be the hardcoded financial number CLAUDE.md §6 forbids (P-03). The horizon is reported as
  advice, never compounded into the projection.
- **Paid ADR-0021's debt, but not as it asked.** Safe-to-Spend's goal term is now
  `maxOf(INVEST envelope, goals' required monthly)`. The straight replacement that ADR asked for
  would have made the figure jump **upwards** for every existing user without a goal — optimistic in
  the one direction §5.2 exists to prevent — and it **fails four tests, two of them older than this
  issue**, including `saving does not increase what is safe to spend`. The suite had already encoded
  the invariant.
- **Found while proving a gate:** **`runMigrationsAndValidate` does not check index names.** Renaming
  one left all twenty round-trip tests green on Room 2.7.1, so an upgraded database would diverge
  from a fresh one for ever. The comment claiming Room caught this is corrected, and `migrate19To20`
  now asserts the names against `sqlite_master` itself — the third gate in this repo found to read as
  present and check nothing.
- **Decided:** [ADR-0033](docs/adr/0033-goals-mint-no-rulebook-row.md). Also supersedes ADR-0004's
  note that the `goal` table would arrive in 7.2.
- **Not delivered:** feasibility against a surplus (issue 7.3, with `RULE-EMERG-FIRST`), goals linked
  to accounts or transactions (7.4), and `RULE-PAY-FIRST`'s salary-day scheduling (7.4).
- **Found by running it, not by a test:** the goal card's button read "Save" (the editor's string,
  reused) and a fully-funded goal read *"At ₹0.00 a month you get there 2026-08-30"* — true, and
  absurd. The engine was right both times; the sentences were not. Fixed in the screen, with a
  regression test each.
- **Tests:** 24 engine (100% line **and** branch), 10 repository, 14 ViewModel, 10 Robolectric
  Compose, 3 new Safe-to-Spend cases, 1 new migration round-trip. The golden gate and both halves of
  the Safe-to-Spend `maxOf` were each run **red on purpose** before being trusted.

## [0.6.0] — Epic 6: Wealth — Loans & Investments

> Credit cards, loans with amortisation, investment holdings with XIRR, allocation and net-worth
> history — exact paise math throughout.

### [0.6.5] — Issue 6.5: Gold/crypto valuation via market API  (2026-08-29)

- **Implemented:** a stored price now carries its age (**FR-INV-004**, §16.1, §22, API-001/API-002).
  Schema **18 → 19** adds two nullable columns to `investment_holding` — `price_key` (the instrument
  identifier, and the opt-in switch) and `price_fetched_at_utc_millis` (when this device heard it, as
  distinct from `priced_on_iso_date`, the day the market priced it). A fourth engine operation,
  `priceFreshness`, returns `NEVER_PRICED | FRESH | STALE` with an age in days, and the holdings
  screen renders it beneath each value. **Colour is decoration, never the signal** — the word "old"
  and the day count carry the meaning, so it survives greyscale and TalkBack.
- **`:core:network` exists, and ships unconfigured**
  ([ADR-0030](docs/adr/0030-the-market-data-client-ships-unconfigured.md)). It is the only module of
  thirty-five that can open a socket, and holds the only `INTERNET` permission — in its own manifest,
  the convention `data/sms` set with `READ_SMS`. **`NetworkConfig.UNCONFIGURED` has a blank base URL,
  so `MarketDataFactory` constructs no OkHttp client at all**: no connection pool, no DNS, no socket.
  The §22 proxy is specified and unbuilt, and the only alternative was pointing the client at AMFI or
  an exchange directly, which EXT-001 forbids. `NetworkModule` is the whole switch for the day a
  proxy exists. A configured host **cannot be expressed without certificate pins** (§22.1).
- **Revoking MARKET_DATA stops fetching and keeps the cached price** — deliberately unlike the SMS
  drafts issue 3.9 deletes. An SMS draft is an inference about the user drawn from data they withdrew
  permission to read; a gold price is a public fact about gold. What is private is the *request*, and
  that is what the consent gates. There is no `MarketDataConsentWatcher`, and that absence is a
  decision.
- **The rulebook gained `RULE-PRICE-STALE` v1.0** (`_meta.version` **1.13.0 → 1.14.0**, and all four
  `RULEBOOK_VERSION` pins with it). Two thresholds, because they answer different questions:
  `refresh_minutes` (gold 1440, crypto 15) asks *is it worth a network call*, `stale_after_days`
  (gold 3, crypto 1, default 7) asks *should the user be warned*. One value cannot do both — at
  fifteen minutes crypto is permanently stale, at one day it never refreshes.
- **The price columns have a second, narrower writer.** `MarketPriceRepository` owns them;
  `InvestmentRepository` owns the row. `updatePriceByKey` is an `UPDATE` of four named columns that
  **cannot reach `name` or `asset_class`**, so a refresh landing during a rename cannot revert it —
  the guarantee is in the statement, not a lock. `distinctPriceKeys` cannot return anything but a
  price key, so the request payload is identifier-only by construction (**EXT-003**).
- **`MarketPriceWorker` is the app's eighth worker and the first with a constraint**
  (`NetworkType.CONNECTED`). The other seven must not have one: they are pure local computation, and
  gating a net-worth snapshot on connectivity would break airplane mode (P-04). Daily, not
  quarter-hourly — §16.1 gives crypto fifteen minutes *while the app is open*, which is what
  `refreshNow()` covers, once per unlock.
- **Not delivered, stated plainly:** AC-1 ("prices come through our backend proxy") **cannot be
  demonstrated** — there is no proxy. It is made structurally true and provably inert, not live. TLS
  and certificate pinning are **the only untested part** of `:core:network`, because MockWebServer
  serves cleartext and `NetworkConfig` refuses a cleartext host; a real airplane-mode test is
  structurally unmeetable here, and `server.shutdown()` is the labelled stand-in.
- **Fixed — a stale screenshot baseline, and the gate that let it through.** The pre-merge run
  caught `DashboardScreenshotTest.empty_light` failing by 1.15%: the baseline was recorded at issue
  5.4, and the Settings screen then added a button to the dashboard without re-recording it. The
  image was re-recorded and checked; more importantly **`verifyPaparazziDebug` is now part of the
  root `unitTests` task**. Paparazzi verifies only when that task is in the graph, so a plain
  `testDebugUnitTest` asserted nothing — the check existed in `/pre-merge` and `ci.yml` and was run
  by neither. The new gate was broken on purpose and observed to fail before being restored.

- **Tests:** 16 against MockWebServer (200 with paise intact, 500, malformed JSON, a real 5s timeout,
  a dead server, and the three refusals), 13 against in-memory Room with the real engine, 5 on the
  worker, plus the engine's golden fixture and the model's invariants. The consent gate and the TTL
  gate were each broken on purpose and observed to fail the suite (ADR-0005); the fake api throws
  when called, so those gates are proved rather than described.

### [0.6.4] — Issue 6.4: Allocation and diversification  (2026-08-28)

- **Implemented:** what shape the portfolio is in (**FR-INV-002**, §11.2, AI-INV). A new
  `allocation` operation on `:domain:engines:investment` splits the portfolio by `AssetClass` and
  flags what has grown too large, a portfolio-wide **Allocation** screen renders it as a bar, a
  legend and one card per warning, and `InvestmentRepository.observeAllocation` joins accounts,
  holdings and lots into the positions it divides. **No schema change, no migration, no new module,
  no new dependency, no DI change** — schema stays at 18 and the worker count is still seven.
- **The rulebook was read, not written.** `RULE-GOLD-CAP` (10%), `RULE-CRYPTO-CAP` (5%) and
  `RULE-CONC-15-70` (15%/70%) all shipped already naming `AI-INV.diversification` as their consumer,
  so all three are mirrored and cited untouched and `_meta.version` stays **1.13.0** — the same way
  issue 6.1 left `RULE-CC-UTIL` alone, and what keeps this clear of ADR-0017's trigger 3. The
  module gained its first `RulebookDriftTest` and the `inputs.file` wiring its `build.gradle.kts`
  promised in 6.3. **Two AI-INV rows are deliberately not mirrored:** `RULE-AGE-EQUITY` needs the
  user's age and `RULE-5-25` needs a target band, and the app collects neither.
- **The portfolio is the investable accounts, and unpriced holdings are excluded from it**
  ([ADR-0029](docs/adr/0029-the-portfolio-is-the-investable-accounts.md)). Counting savings or a
  house would put nearly every user permanently past the 70% single-class line, and a rule that
  always fires conveys nothing. An unpriced holding is left out of the denominator and reported —
  "Based on 8 of 11 holdings" — rather than counted as ₹0, the same P-03 rule ADR-0027 set on the
  value path.
- **Shares are apportioned, not rounded.** Floors plus largest-remainder distribution, so the slices
  sum to exactly 10 000 bps and a portfolio never adds up to 99.97%. Every expectation in
  `golden/allocation.txt` came from an **independent** Python implementation of the apportionment
  spec, so agreement is evidence the shares are right rather than merely unchanged.
- **A flag names the one row that raised it** (P-02), and the result names all three so a clean
  portfolio can say what it was found clean of. Nothing here tells the user to sell anything —
  §11.1's disclaimer is on the screen and every warning is worded as an observation (P-07).
- **Tests:** 1 341 passed, 0 skipped across the three touched modules (`:domain:engines:investment`
  60, `:data:repository` 1 086, `:feature:accounts` 195). `ktlintCheck`, `detekt`, `lintDebug` and
  `koverVerify` all green. **Six gates were broken on purpose and observed failing**, then reverted
  (ADR-0005): the cap boundary, the remainder distribution, the denominator, the unpriced exclusion,
  a rulebook-only edit, and a deleted golden record.
- **Engine version 1.0 → 1.1**, and `AI-INV` in `engine-registry.yaml` with it. No existing XIRR
  answer moved — the independently-computed golden file still agrees to the basis point — but a
  result stored under 1.0 came from an engine that could not have produced an allocation, and
  AI-ARC-006 needs that to stay tellable.

### [0.6.3] — Issue 6.3: Investment holdings, lots and XIRR  (2026-08-24)

- **Implemented:** what an investment account actually holds, and what it has returned (**§11**,
  AI-INV). Holdings carry a name, an `AssetClass` and the last observed price **per unit** with the
  day it was observed; lots carry each dated purchase, sale and payout. New module
  `:domain:engines:investment`, new tables `investment_holding` and `investment_lot` (schema
  **17 → 18**), a per-account holdings screen, and a value-and-return line on every investment,
  gold and crypto account. **No new worker** — 6.3 is the arithmetic and a screen, not a rebalancing
  alert; the worker count is still seven.
- **XIRR is solved deterministically, without floating point**
  ([ADR-0028](docs/adr/0028-xirr-by-bisection-over-the-daily-growth-factor.md)). The textbook form
  needs a fractional power, so this substitutes the daily growth factor `x = (1+r)^(1/365)` and
  bisects a polynomial in integer powers — 128 halvings of a literal bracket, no early exit, one
  `HALF_EVEN` rounding at the end. The golden file's eight cases were computed by an **independent**
  60-significant-digit implementation, so agreement is evidence the answer is right rather than
  merely unchanged. A twelve-instalment SIP reports **15.67%** where a cost-to-value ratio would
  say 8.3%.
- **Asset class is a column on the holding, not a derivation from the account type**
  ([ADR-0027](docs/adr/0027-asset-class-is-a-column-on-the-holding.md)). One broker account holds an
  equity fund *and* a debt fund, so deriving it would make issue 6.4's allocation structurally wrong.
  `AssetClass.defaultFor` is issue 6.4's groundwork: it is the editor's default and the fallback for
  accounts that hold value without lots. `RULE-GOLD-CAP` and `RULE-CRYPTO-CAP` already name two of
  the stored strings, and a test pins both.
- **Absent is never zero (P-03).** A holding with no price reports no value and no gain, and says
  why it has no return, rather than showing ₹0 — which would report the user's entire cost as a loss.
  A *fully exited* holding is worth exactly zero without needing a price.
- **§11.1's disclaimer ships with the first screen that shows a figure** (P-07): the module analyses
  and flags, does not recommend securities, and is not SEBI-registered advice. A Compose test asserts
  it, including while the editor is open — it is the only enforcement that requirement has.
- **The rulebook is untouched.** A money-weighted return has no threshold to tune, so
  `ai/rules/rules-kb.json` stays at `_meta.version` **1.13.0** and no drift test was added — the
  argument `:domain:engines:loan` already makes for amortisation. The five AI-INV rows remain
  issue 6.4's.
- **Fixed before it shipped:** `BigInteger.longValueExact()` in the unit-count parser requires API 31
  against a minSdk of 26 — it compiled and would have crashed on any older device. Caught by
  `lintDebug`, replaced with a `BigDecimal.setScale(0, HALF_EVEN).longValueExact()`.

### [0.6.2] — Issue 6.2: Loans + amortisation + EMI split  (2026-08-20)

- **Implemented:** a loan's terms and the deterministic schedule they imply — principal, annual rate,
  tenure, first EMI date and an optional lender-stated EMI; the closed-form instalment; and for every
  one of up to 600 instalments an exact principal/interest split (**§5.8**, **§11**, FR-ACC-003). New
  module `:domain:engines:loan`, new table `loan` (schema **16 → 17**), the loan section of the
  account editor, and the next EMI's split on every loan row. **No new worker** — 6.2 is the
  arithmetic, not an alert; the worker count is still seven.
- **The schedule is derived, never stored**
  ([ADR-0026](docs/adr/0026-amortisation-schedule-is-derived-not-stored.md)), contra the
  `loan_amortization_rows` table [ADR-0016](docs/adr/0016-nature-classification-by-account-type.md)
  named. 240 rows per loan would be a cache of a pure function of five columns: correct a mistyped
  rate and the copy silently disagrees with what produced it — the argument
  [ADR-0007](docs/adr/0007-account-balances-derived-not-stored.md) makes for balances and `CreditCard`
  makes for unbilled spend. The 16→17 migration test asserts the table's **absence**.
- **Exact by construction, not by luck.** The closing instalment absorbs the rounding remainder —
  exactly what a lender's final instalment does — so `Σ principal == P` and, on every row,
  `principal + interest == EMI`. Both are enforced in `AmortisationRow.init` and
  `AmortisationSchedule.init`, so a schedule that does not balance cannot be constructed. `Long` paise
  throughout (MNY-001), integer basis points (MNY-002); `BigDecimal` appears only inside the closed
  form, at a **pinned** `MathContext(34, HALF_EVEN)` that is part of the engine's version contract.
- **The user's bank wins.** The EMI is derived, and a typed lender figure replaces it outright —
  banks round the closed form their own way, and a schedule that disagrees with the borrower's own
  statement by ₹2 a month is one they stop trusting. `LoanEmi` carries **both** figures plus its
  `EmiBasis`, so a screen can show the difference rather than hide it (P-02). A lender EMI above the
  derived one closes the loan early, and the walk stops there rather than driving the balance negative.
- **The first engine that reads no rulebook row, and says so.** Amortisation has no tunable threshold
  — the formula is the loan contract's, not the app's — so there is no `LoanRules.kt`, no
  `RulebookDriftTest`, and `provenance.evidence` is empty. `CardStatus` refuses an empty evidence list
  because a card alert is a judgement; a schedule is arithmetic, and citing a rule it never read would
  be a false claim about where the number came from. `ENGINE.md` states this under its own heading.
  `_meta.version` is untouched, so no other engine's `RULEBOOK_VERSION` moved.
- **`Money.percentOf` gained a period divisor.** 850 bps a year is 70.83… bps a month, so truncating
  first drifts over 240 months. One optional parameter keeps a single HALF_EVEN rounding at the end,
  on the paise; every existing call site is unchanged by construction.
- **The rate is typed in percent and stored in basis points** without a `Double` anywhere.
  `MoneyFormatter.parse` already scales two decimals by 100 on text and `BigInteger`, and 1% is 100 bps
  in precisely the way ₹1 is 100 paise — so its minor units *are* the basis points. `8.555` is
  refused rather than rounded (P-03).
- **Tests:** a golden file of five loans and their entire schedules (~500 instalments), produced by an
  **independent** 50-significant-digit decimal implementation rather than captured from this engine;
  500 seeded property loans checked against five identities; `LoanRepositoryTest` at four points in a
  loan's life; and Compose tests asserting the card and loan sections are never on screen together.
  Coverage: the loan engine reports **0 missed** on every counter (gate is ≥ 85%, money 100%).
- **Two gates were broken on purpose and watched go red** before being trusted: removing the closing
  instalment's remainder absorption (5 tests), and widening `showsLoanFields` to `type.isLiability`
  (2 tests). Both green on restore.
- **Three static-analysis failures fixed rather than suppressed.** `CfoMoneyAsFloatingPoint` fired on
  the engine's one deliberate `BigDecimal` amount (renamed, with the reason recorded — this repo has
  had no lint baseline and no suppression since issue 1.5); `EngineModule` and `AccountEditorScreen.kt`
  both hit detekt's function ceiling, so Epic 6's two wealth engines moved to `WealthEngineModule` and
  the loan composables to `AccountEditorLoanFields.kt`.
- **Verified on a device.** ₹30,00,000 · 8.5% · 240 months reads **₹26,034.70** on the accounts row,
  split ₹4,784.70 principal · ₹21,250.00 interest — the golden file's figure, on the emulator. Checked
  in airplane mode (P-04) and through an export → delete → import round trip.

### [0.6.1] — Issue 6.1: Credit card details + alerts  (2026-08-17)

- **Implemented:** a credit card's terms and the two notifications that come out of them — limit,
  statement day, due day, last statement, minimum due and APR; the billing cycle; both utilisation
  figures; a payment reminder three days before the due date and again on the day; and a utilisation
  alert at the rulebook's 30% line (**§5.7**, **§11**, **§17.1**, FR-ACC-002). New module
  `:domain:engines:card`, new tables `credit_card` and `card_alert` (schema **15 → 16**), new daily
  `CardAlertWorker` — the seventh.
- **`RULE-CC-UTIL` was read, not written.** It shipped long before this issue already carrying
  `max_utilisation_pct: 30` and already naming `card_alerts` in its `consumed_by` — the rulebook was
  written expecting this engine. Only the date question needed a row, so `RULE-CC-DUE` v1.0 was
  minted (`remind_days_before: 3`, `remind_on_due_day: true`, `skip_when_nothing_due: true`) rather
  than the shipped row extended, which would have fired ADR-0017's trigger 3 and forced the runtime
  rules loader before this issue could compile
  ([ADR-0025](docs/adr/0025-card-alerts-mint-rule-cc-due-and-claim-per-statement-cycle.md)).
  `_meta.version` → **1.13.0**, so `BudgetRules` and `SafeToSpendRules` restate their pins; neither
  engine's own rows moved.
- **The alert claim is keyed by the statement date, not the month** — the one place this differs
  structurally from `budget_alert`. A card billing on the 25th has a cycle straddling two calendar
  months, so a month-keyed claim would fire twice for one statement:
  `UNIQUE(profile_id, account_id, cycle_start_iso_date, kind)`, claimed before the notification is
  posted, so a retried worker run is silent.
- **Two utilisations, both labelled, because they disagree by design.** The live figure moves with
  every swipe and is what the user feels; the statement figure is what a bureau records and is what
  the alert acts on — a figure that changed hourly would either nag or be claimed once and then be
  wrong for the rest of the cycle. Each carries its `UtilisationBasis`, so neither reaches the user
  as an unlabelled number (P-02).
- **Absence is not zero.** A card with no statement recorded gets `null` utilisation, not 0%, and is
  never alerted about — rendering it as 0% would claim the user owes nothing (P-03).
- **The engine reads no clock and touches no database.** `today` is an argument (TIM-001), so the
  golden file walks one card day by day across a whole billing cycle — statement day, mid-cycle, the
  window opening, the due day, the day after — and a rule that fires a day early is a diff rather
  than a passing test somewhere else. Money is paise and every ratio is integer bps throughout
  (MNY-001/MNY-002).
- **The reminder is guarded like every other LLM-adjacent surface** (AI-ARC-004): the composed text
  is verified against exactly the figures the engine returned, with the card's own name declared as
  text — "HDFC Regalia 4521" is an ordinary thing to call a card, and without that the digits in the
  name would read as an unverifiable count and drop a correct reminder, the failure issue 4.6 hit
  with category names. `usedPercent` lives on `CardAlert` so the message and its allow-list cannot
  round differently. The privacy blur reaches the notification too: blurred, it names the card and
  the fact, never an amount.
- **Found by running it:** the notification quoted a credit limit of **₹2,00,034.82** for a
  ₹2,00,000 card. `CardAlertNotifier` had been recovering the limit as `amount x 10 000 / ratioBps`,
  and `ratioBps` is truncated to a whole basis point, so the inversion was wrong by ₹34.82 — and the
  guardrail passed it, because it had been handed the same reconstruction. `CardAlert` now carries
  `creditLimit` from the engine and the notifier formats it, which is what P-03 has always meant:
  the engine emits every figure, and the words never re-derive one. Locked by
  `the alert carries the real limit, not one recovered from the ratio`.
- **Migration 15 → 16 adds two tables and destroys nothing** (DB-003). No backfill is possible or
  wanted: no card had terms before this version and nobody has been notified about one, so the empty
  tables are the complete and correct history. Both tables join the export archive.
- **Tests:** full `unitTests`, `koverVerify`, `ktlintCheck`, `detekt`, `lintDebug` and
  `verifyPaparazziDebug` green — 41 new cases in `:domain:engines:card` (a golden cycle walk, the
  boundaries either side of every threshold, property tests over a thousand generated cards, a
  rulebook drift test), 15 in `:core:model`, plus repository, worker and editor suites.
  `connectedDebugAndroidTest`: **30 tests, 0 failures** on an emulator **in airplane mode** (P-04),
  including the 15 → 16 migration round trip. Verified by hand on the device: terms entered on the
  demo card, the labelled utilisation on the list, both notifications on their separate channels
  with the right figures, and a second run inside the same cycle posting nothing.

## [0.5.0] — Epic 5: Dashboard, Export & Widget

> The user's daily surfaces: the home dashboard, Safe-to-Spend, privacy blur, local JSON
> export/import, and the home-screen widget.

### [0.5.5] — Issue 5.5: Home-screen widget (Glance)  (2026-08-17)

- **Implemented:** the Glance home-screen widget — Safe-to-Spend and net worth, read from a cache,
  refreshed by WorkManager, honouring the privacy blur, working with no network (§5.2, §35, P-01,
  P-03, P-04). `:widget` was a `ModulePlaceholder` and Glance had been pinned but unused since
  issue 1.1; both are now real.
- **The widget never touches the database, and that is the design, not an optimisation.**
  `CoreModule.provideDatabase` throws while the app is locked (SEC-002), and a home screen is read
  locked far more often than unlocked — so a widget that fetched a figure at draw time would blank
  out or crash exactly when it is most visible. Glance's own preference state is the cache;
  `provideGlance` reads it and nothing else, with no dependency injection in the module at all
  ([ADR-0024](docs/adr/0024-the-widget-renders-from-glance-state-not-the-database.md)).
- **Closes a criterion issue 5.3 left open and said so.** The privacy blur now reaches the launcher.
  It is written by its own watcher, separate from the refresh worker, because hiding amounts must
  work while the app is locked — folding the flag into the refresh would have made it depend on the
  database, and the amounts would have stayed on screen at the moment they were asked to go.
- **Not screenshot-tested, deliberately and on the record.** Paparazzi cannot render Glance — it
  emits `RemoteViews` for an `AppWidgetHost`, not a tree LayoutLib can inflate. A Paparazzi test
  over a plain-Compose mirror would be green against a tree that is not the one shipped, which
  reads as coverage and is worse than none. Instead: `runGlanceAppWidgetUnitTest` on the JVM, and
  `WidgetDeviceTest`, which binds a real widget and inflates its actual RemoteViews on a device.
- **`maskOf` moved from `:core:designsystem` to `MoneyFormatter.mask` in `:core:model`**, so the app
  and the widget mask to one width by construction rather than by inspection (ADR-0022). The
  design-system function delegates; its own tests are unchanged, which is the proof the move was
  behaviour-neutral.
- **Lint gap closed:** `CfoHardcodedUiString` covered `/feature/` and `/designsystem/` but not
  `/widget/`, so the app's most-read surface was outside the strings rule. Two entries fixed it, and
  a seeded literal was confirmed to turn `:widget:lintDebug` red before the fix was kept.
- **Found by running it:** `compose()` without a `GlanceId` composes against *empty* state, so the
  first draft of `WidgetDeviceTest` rendered "Not yet worked out" no matter what had been written —
  and its blur test passed on that, because a widget with no amounts has no digits to leak. The test
  now passes the bound id and asserts both masks are present before sweeping for digits.
- **Tests:** full `unitTests` green (12 new in `:widget`, 10 in `:app`, 4 in `:core:model`, 1 in
  `:lint`); `koverVerify` and `verifyPaparazziDebug` green; `:app:connectedDebugAndroidTest` **9/9**
  on an emulator **in airplane mode** (P-04), including the two new widget device tests. Verified by
  hand on the real launcher in airplane mode: pending state (not ₹0.00) on a fresh profile, figures
  matching the dashboard exactly, blur masking and unmasking, light and dark, and tap-to-open. The
  SEC-002 guard was observed doing its job — the refresh returned `retry` while the session was
  locked at cold start and succeeded on the retry 30 s later.

### [0.5.4] — Issue 5.4: Export/import JSON archive  (2026-08-16)

- **Implemented:** §5.10's local JSON archive — a full export of the user's data and a lossless
  import back (**§34**, **P-01**). Two buttons on the dashboard using the system file picker; no
  cloud, no network path, and none is ever to be added here.
- **This is not Epic 8.** Issue 8.1 builds the *encrypted* backup for disaster recovery; this is the
  *plaintext, readable, portable* copy the user owns. Different artefacts, different threat models
  ([ADR-0023](docs/adr/0023-archive-replaces-and-carries-no-blobs.md)).
- **The Room entities are the archive format**, carrying `@Serializable`. The conventional
  alternative — fourteen DTOs and twenty-eight mappers — has exactly one failure mode and it is
  silent: somebody adds a column, forgets the mapper, and every export from then on drops that data
  with no test able to see it. Here a new column is in the archive the moment it is in the table. The
  cost is that a property rename changes the file format, which `ArchiveFormatTest` pins deliberately.
- **Import replaces, behind a confirmation**, inside one `withTransaction`. The file is parsed and
  version-checked **before anything is deleted**, so a refused archive leaves the device untouched —
  the failure this ordering exists to prevent is a wipe followed by a parse error.
- **The wipe reuses `DemoDao`** rather than writing a second one that could drift from it. That DAO's
  doc comment claimed its safety came from only ever receiving the demo profile id; it now receives
  the real one, so the comment was rewritten rather than quietly invalidated.
- **Receipts and `audit_log` stay behind.** A plaintext file the user may email themselves must never
  carry decrypted receipts (attachment *rows* travel, images do not); `audit_log` has no
  `profile_id`, so the schema cannot scope it to the exported profile.
- **The restore does not go through each table's own `upsertAll`** — `BudgetAlertDao` and
  `BudgetReviewDao` use `IGNORE`, correctly, for their once-per-band and once-per-month claims. A
  restore through them would silently drop rows, so `ArchiveDao` owns `REPLACE` inserts.
- **Fixed before shipping, found by running it:** every refused import showed the generic "something
  went wrong" instead of the specific reason, because `AppError.Validation.code` is the constant
  `"validation"` — the two archive-specific messages were unreachable. The ViewModel test asserted
  only `is Failed`, so it passed. Both the code and the test were fixed.
- **Also fixed by a test:** putting the file pickers in the stateless `DashboardContent` needed an
  `ActivityResultRegistryOwner`, which broke every Paparazzi baseline at once — the composable is
  documented as renderable without Hilt or navigation. The launchers moved to the stateful half.
- **Tests:** full `unitTests` green, `:app:connectedDebugAndroidTest` 7/7 from a clean install. The
  round-trip test seeds **every one of the fourteen tables and every nullable column**, because a
  round trip only covers what its fixture populates; it was watched go red against a single dropped
  table. Plus the empty and 2,000-row cases, the version gate (asserting the data survives the
  refusal), demo isolation, tombstones, and export determinism. Verified on the `CfoTest` emulator in
  **airplane mode**: exported 99 rows to a 65 KB file, confirmed no `audit_log` and no image bytes
  and integer paise; added a ₹7,777 transaction, imported, and watched it vanish; and confirmed a
  doctored `schemaVersion` is refused with the demo data intact.

### [0.5.3] — Issue 5.3: Privacy blur toggle  (2026-08-16)

- **Implemented:** a one-tap blur that hides every amount in the app (**§23**, **FR-PRIV-***,
  **P-01**). An eye icon in the app chrome, reachable from any screen; the state persists in
  DataStore and survives process death.
- **The persistence half already existed** — `privacy_blur_enabled` has been in `cfo_settings.proto`
  since issue 1.9 and `SettingsStore` has implemented it since. Nothing read it. This issue is the
  UI half plus the capture guard.
- **It masks text; it does not blur pixels** ([ADR-0022](docs/adr/0022-privacy-blur-masks-text-and-sets-flag-secure.md)).
  `Modifier.blur` is a **silent no-op below API 31**, does not stop a screenshot, and blurs the
  navigation along with the figures. Amounts become `₹•••••••` through `LocalPrivacyBlur`, read by
  `CfoAmountText` and by a new `maskedAmount(...)` helper.
- **Both paths, not just the component.** Amounts reached the screen two ways: 14 `CfoAmountText`
  call sites and ~24 `MoneyFormatter.format(...)` interpolated into `stringResource(...)` — the
  cash-flow line, the budget totals, the whole budgets screen, and 5.2's own Safe-to-Spend
  breakdown. Blurring only the component would have left most of the app readable.
- **The mask is fixed-width, and that is the point.** Every amount masks to the same string
  regardless of magnitude: a mask that matched the number's own length would hide the digits and
  leak the order of magnitude, which for a salary or a balance is most of what makes it sensitive.
  The sign survives, so direction is never conveyed by colour alone (P-02).
- **`FLAG_SECURE` while the blur is on** — a mask stops someone reading the screen; only this stops
  a screenshot, a screen recording or a shared call. Driven by the same flag, cleared on dispose.
  Issue 11.2 still owns the always-on policy and the recents guard.
- **Notifications too.** A budget alert renders on the lock screen, *without* the app lock, to
  anyone who glances at a phone on a table — the biggest shoulder-surfing surface the app has. With
  the blur on, `BudgetAlertNotifier` sends the category and the band and **no digits at all**, not
  even the percentage.
- **Editable amount fields are exempt** — add-transaction, the account editor, the budget editor and
  the transaction filter's bounds. Masking what someone is typing breaks input, and a field they
  opened themselves is not a shoulder-surfing surface.
- **Accessibility:** a masked amount announces "Amount hidden" instead of reading the figure aloud —
  a blur that leaves the screen reader saying "₹34,600" has moved the leak to the speaker. The
  toggle reports on/off through `stateDescription`, not through a changed icon alone.
- **Tests:** full `unitTests` green. `PrivacyBlurTest` pins the fixed-width property over seven
  orders of magnitude; `DashboardPrivacyBlurTest` (Robolectric Compose UI) sweeps **every** rendered
  string for a rupee-plus-digit and was watched go red against a single reverted call site;
  `MainViewModelTest` covers the persist-and-read-back round trip and the fail-open default;
  `BudgetAlertWorkerTest` covers both notification variants; a `blurred_light` Paparazzi baseline
  shows the masked layout holds. Verified on the `CfoTest` emulator in **airplane mode**: every
  amount masked across dashboard and accounts, `adb screencap` returned **0 bytes** with the blur on
  and 178 KB with it off, and the blur survived a force-stop. `:app:connectedDebugAndroidTest` 7/7.

### [0.5.2] — Issue 5.2: Safe-to-Spend card  (2026-08-16)

- **Implemented:** the Safe-to-Spend engine and card (**§5.2**, **§14**, **AI-STS**, **P-02/P-03**).
  New `:domain:engines:safetospend` (AI-STS, L5), a `SafeToSpendRepository` that assembles its five
  terms, and a dashboard card that renders the figure **with the breakdown that produced it**.
- **The app's home screen no longer shows a number it made up.** `DashboardViewModel` had been
  minting the headline figure as `Money(12_500_00L + today.dayOfMonth)` since issue 1.10 — a literal
  in plain breach of P-03, shipped in every build for three epics. It was the last placeholder on
  the dashboard; nothing on that screen is invented now.
- **`RULE-STS`** (rules-kb.json → **v1.12.0**): `income_basis = budget_envelopes_then_actual`,
  `horizon = month_end`, `buffer_pct = 5`, `include_goal_contributions`, `floor_at_zero = false`.
  The income basis is the declared budget rather than month-to-date actuals because a figure driven
  by the ledger reads deeply negative for twenty-seven days and jumps on payday — it measures the
  salary calendar, not the user's position. `RULE-IDLE-CASH` has named a "Safe-to-Spend needs +
  buffer" since this file was created, so `buffer_pct` gives an existing concept a value rather than
  inventing a term.
- **The breakdown is the result, not decoration.** `SafeToSpend` carries its ordered lines and
  asserts in its constructor that they sum to the headline — so the card cannot show a plausible
  fiction beside a correct number, which is the failure P-02 exists to prevent. The engine builds
  the lines first and folds the figure from them, so both are the same arithmetic. Deductions render
  signed; as magnitudes the column read as six additions that plainly did not add up.
- **The absence is computed, not defaulted.** A profile with no envelopes and no posted income has
  no income basis, so the repository emits `null` and the card says so — a confident ₹0 would be a
  figure the app made up and is indistinguishable from a real month with nothing left (P-03).
- **Four ways the figure could have been quietly wrong, each with a test:** a scheduled bill outside
  the month (`observeUpcoming` reaches 90 days) reducing today's figure; a scheduled *income*
  counted as a commitment; the quick-setup **salary** rule — a recurring rule with a positive
  amount — counted as a bill, which would have subtracted the user's income from their spending
  money; and a bill the user also scheduled counted twice (deduplicated on merchant **and** date, so
  a merchant billed twice in one month still counts twice).
- **"Goal contributions" is a stated stand-in until issue 7.1.** With no goals engine, the term is
  the quick-setup INVEST envelope **in full** — recorded in `ENGINE.md`, not left for a reader to
  discover. Netting it against what has already been saved looks right and is wrong: §8.3's
  `trueSpend` is `NEED + WANT` and already excludes every conversion, so a rupee already in an SIP is
  in no other term and netting would leave it deducted from nothing — the figure would *rise* by the
  amount the user had just saved.
- **Fixed in `:data:repository` as a prerequisite (FR-TXN-010):** `observeNatureBreakdown` ran to the
  month's **last day**, so a payment scheduled for the 28th was reported on the 3rd as money already
  spent — on a card captioned "This month, *actually*" — and was then subtracted a second time by
  Safe-to-Spend's own scheduled-payments term. It is now bounded at `MonthWindow.actualsEndIsoDate`,
  the bound `observeMonthCashFlow` three methods below has always used. This changes the
  "This month, actually" figures for any user with a future-dated row in the current month.
- **Tests:** full `unitTests` green — 30 engine (12-record golden file fixing the breakdown as well as
  the figure, 500 seeded property months, drift), 17 repository (Room in-memory), dashboard ViewModel
  + 4 Paparazzi baselines re-recorded (light/dark/200%), and `connectedDebugAndroidTest` 7/7 on the
  emulator. Both new gates were **watched go red** before being trusted: a mis-labelled golden record
  and an edited `buffer_pct` each fail the build. Verified on the `CfoTest` emulator **in airplane
  mode** (P-04): pending state on a profile with no income; ₹56,421 on adding ₹60,000; ₹55,421 after
  a ₹1,000 expense; and on the demo profile 95,000 − 4,750 buffer − 49,552 spent − 19,000 planned
  savings = **₹21,698**, with the ₹10,000 already invested correctly counted once.
- **Also:** `BudgetRules.RULEBOOK_VERSION` restated to 1.12.0 (the file's `_meta` moved; no
  `RULE-BUD-*` threshold changed), and `RepositoryModule` suppressed `TooManyFunctions` — the count
  there is simply the number of repositories the app has.

### [0.5.1] — The budget engine is allowed to fail  (2026-08-16)

- **Implemented:** issue 4.7 — removed the four unchecked `(x as Ok).value` engine casts in
  `RoomBudgetRepository` (**FR-BUD-003/004**, §21.6). Every `BudgetEngine` method is
  `runCatchingToResult`, so every one can return `Err`; each cast turned that into a
  `ClassCastException` thrown from inside a `combine` transform — precisely what the `Result` return
  type exists to prevent. Found by the 2026-08-16 review of issue 5.1, filed rather than fixed in
  passing because the pattern was established by 4.4 and copied by 4.5 and 4.6.
- **One rule, inherited from the engine's own interface rather than invented.** `suggest`, `alert`
  and `review` each document `Ok(null)` as a legitimate answer and `status` does not, because there
  is no such thing as "no status". So a failure collapses into that same absence at the first three —
  no offer, no band, no card, with the figures beside them untouched — and terminates the stream at
  the fourth, carrying the engine's own `AppError` to the `.catch {}` both ViewModels already have.
  A first framing ("advisory vs load-bearing") produced the same four edits but was a judgement a
  reviewer could not check; this one is findable in `BudgetEngine.kt`.
- **`BudgetEngineFailure` is `internal` and extends plain `Exception`, and both halves matter.**
  `runCatchingToResult` rethrows `IllegalStateException`, so `error(...)` — which is what
  `NetWorthRepository.computeFrom` does for a structurally identical call — would have escaped
  `pendingAlerts`, `acceptSuggestion` and `acceptReviewProposal` into `viewModelScope` and
  `CoroutineWorker`, reopening the §21.6 hole closed the day before. `internal` keeps it from
  becoming an app-wide "throw an `AppError` instead of returning one" hatch beside the `Result` type
  that exists to prevent one.
- **A silent forever-retry is fixed as a side effect, and it is a contract change.**
  `pendingAlerts()` used to return `Err` when the alert engine failed, which `BudgetAlertWorker` maps
  to `Result.retry()` — so it retried a deterministic failure daily for the rest of the month,
  notifying nothing. It now returns an empty list and the worker reports success.
- **The tests were proven to catch the bug, not merely to pass beside it**: all four casts were
  reverted and the new `BudgetEngineFailureTest` went 8 of 9 red, each with a real
  `ClassCastException` at the reverted site.
- **The issue's own acceptance criteria were rewritten, not stretched.** As first written they were
  unsatisfiable — AC1 demanded the sites propagate the error "instead of throwing" when three have
  nowhere to propagate it to and the fourth has nothing to do but throw. The Description's claim that
  an engine `require` can produce `Err` was also wrong, and is corrected in place.

### [0.5.0] — The landing screen stops being four placeholders and a promise  (2026-08-15)

- **Implemented:** issue 5.1 — home dashboard v1 (**FR-DASH-\***, §5.2, P-02/P-03/P-04). The
  dashboard has shown net worth (2.6), the needs/wants/savings plan (2.3) and the true-spend
  breakdown (4.3) since earlier issues; this fills in the three figures its own doc comment named
  as still owed — budget status, this month's cash flow, and recent activity — leaving Safe-to-Spend
  as 5.2's sole remaining placeholder.
- **Budget status reuses `BudgetRepository.observeBudgets()`/`observeAlerts()` exactly as
  `:feature:budgets` already does** — a second consumer of the same mechanism, not a new one. The
  "needs attention" line cites its rule (`RULE-BUD-ALERT`, P-02), the same citation the budgets
  screen's own banner shows for the identical alert. The summary folds only *budgeted* categories'
  totals — an unbudgeted category's spend does not inflate a figure against a plan it was never
  part of.
- **This month's cash flow is one new SQL statement, not a new engine.** `TransactionDao.observeMonthCashFlow`
  sums income and expense with one `CASE WHEN`, no rule or judgment involved, so it carries no
  `EngineProvenance` — the same distinction that already separates `observeDayTotals` from
  `observeNatureBreakdown`. An earlier version combined two `observeDayTotals` calls instead and hit
  a real `kotlinx-coroutines-test` failure — two Room query flows meeting inside `combine()` under
  `UnconfinedTestDispatcher` threw "Detected use of different schedulers" — which is why this
  shipped as one query.
- **Recent activity is bounded by count, not by time — deliberately not the mistake issue 3.6
  fixed.** `TransactionRepository.observeRecent(limit)` is a new, small `LIMIT`-bounded read; the
  time-windowed `observeRecent` 3.6 removed stays removed, and the full ledger is still one tap away
  through "View transactions".
- **A real usability bug was found and fixed by running the app, not by a test.** The dashboard's
  `Column` has never scrolled — issue 1.10's four-row screen never needed to. This issue's three
  added sections pushed the bottom of the screen, including every navigation button, off-screen and
  permanently unreachable; no Compose UI test in this codebase measures a real screen height against
  real content, so nothing caught it until the emulator did. Fixed with `verticalScroll`.
- **Verified on device, fully offline** (P-04, airplane mode confirmed via `adb shell settings get
  global airplane_mode_on` = 1): launched against real, pre-existing profile data, watched every new
  section render its real figures (including the alert citation and all five recent rows), scrolled
  to the previously-unreachable buttons, and navigated to Budgets and confirmed the two screens agree
  on the same category's numbers.
- **Screenshot tests**, `feature/dashboard`'s first — the Paparazzi Gradle plugin was applied to no
  `:feature:*` module before this. Light/dark/200% baselines cover the fully-populated state and the
  all-empty state (no net worth, no budget, no transactions), so an empty-state regression is visible
  even though it has no numbers to eyeball wrong.

## [0.4.0] — Epic 4: Categorisation & Budgets

> What a payment was *for*. The `category` table has existed since issue 1.6 with nothing but the
> demo dataset writing to it; this epic makes the taxonomy the user's, then teaches the app to fill
> it in.

### [0.4.4] — Told once, and only what the maths can back  (2026-08-13)

- **Implemented:** issue 4.5 — budget alerts at 80% and 100% (**FR-BUD-004**, §5.5, AI-ARC-004,
  AI-ARC-006, P-02/P-03/P-04). 4.4 gave the user a number to aim at and a screen that says how the
  month is going; this tells them when it stops going well, without waiting for them to look.
- **The thresholds are a new rule row, not two new params on an existing one.** `RULE-BUD-ALERT` at
  `version: 1.0` in `ai/rules/rules-kb.json` (**v1.10.0**). Adding them to `RULE-BUD-PACE` would have
  bumped a shipped row's version, which is [ADR-0017](docs/adr/0017-budget-thresholds-stay-a-typed-mirror.md)'s
  trigger 3 and would have forced the runtime `ai/` loader — `:core:rules`, an asset pipeline, a
  `rules_knowledge_base` table and seven mirrors retrofitted — before this issue could compile. The
  split is also right on the merits: pace answers "am I on track?", alerting answers "should this
  person be interrupted?" ([ADR-0019](docs/adr/0019-budget-alert-bands-mint-a-new-rule-row.md)).
  4.4's tripwire test is **retargeted, not deleted** — it now guards a permanent boundary.
- **"Tell them once" is enforced by the schema, not by careful code.** New `budget_alert` table at
  **v14** with `UNIQUE(profile_id, budget_id, month_start_iso_date, band)`, so a second warning for
  the same month cannot be inserted whatever the calling code believes — including when two workers
  run at once or one retries after a partial failure. The band is part of the key, so crossing 80%
  and later 100% produces two messages; a boolean on `budget` would have swallowed the second, which
  is the more important one.
- **The notification text is verified before it is posted** (AI-ARC-004). New `NumericGuardrail`
  extracts every rupee amount and percentage from the composed message and requires each to match a
  value the engine actually returned, rendered through `MoneyFormatter`. Fail-closed: the default
  allowed set is empty, so a caller that forgets to declare its values gets silence. A correct sum
  the engine never computed is refused too — being arithmetically true is not the standard (GRD-003).
  This is a **documented subset** of `ai/chat/guardrail.md`; the full L3 gate is issue 9.7, and its
  REGENERATE/REFUSE ladder is inapplicable here because there is no LLM on this path.
- **The band shows in-app whether or not a notification was ever sent** (P-02). A notification is
  sent once and can be missed, denied or swiped away; a crossed band stays true for the rest of the
  month. The permission is asked for **after** a budget is saved, never at launch — Android offers
  that prompt twice in an app's life, and one made before the user has a budget is one they deny
  permanently. A denial is a designed-for state, not a degraded one.
- **Two real defects, both found by tests rather than by review.** A rule row may set `exceeded_pct`
  below 100, at which point the band is reached while the budget still has money in it and
  `spent − budgeted` went negative — the overspend figure is now floored at zero, so the notifier can
  never render a negative "overspend". And the band labels used `%%` in strings that take no format
  arguments, which `stringResource` does not process: the screen would have shown a literal `80%%`.
- **`MigrationSafetyTest` refused the new table for having no tombstone column**, which was a real
  design question rather than a formality. Argued as an exemption instead of adding the column: a row
  records that a person was interrupted, which is not undoable, and the unique index counts
  soft-deleted rows — so a "deleted" alert would still not fire again and the column could only
  mislead. Stated consequence: deleting and recreating a budget inside one month does not re-notify.
- **Every new gate was watched to fail first**, both by editing only `ai/rules/rules-kb.json` and no
  Kotlin — which is also what proves the Gradle `inputs.file` wiring still stops a drift test passing
  against a file it never read.
- **Out of scope, deliberately:** the runtime `ai/` loader (deferred again, recorded in ADR-0019),
  the full L3 guardrail (9.7), notification policy and quiet hours (9.6), and the monthly review
  (4.6).

### [0.4.5] — Told once, and a look back at last month too  (2026-08-15)

- **Implemented:** issue 4.6 — monthly budget review (**FR-BUD-\***, §5.5, P-02/P-03/P-07). 4.5 told
  the user when they crossed a line *during* the month; this tells them what the month actually
  looked like once it closed, and offers a priced adjustment for the one ahead.
- **The engine half already existed.** `BudgetEngine.review`, `BudgetMonthReview`, and
  `RULE-BUD-REVIEW` v1.0 (`ai/rules/rules-kb.json` v1.11.0) shipped in an earlier session with full
  golden/unit/drift coverage. This release closes the gap that was left: there was no database
  table, no repository method, and no UI — the review computed correctly and nobody could see it.
- **`review_once_per_month` is enforced by the schema, the same pattern 4.5 set.** New `budget_review`
  table at **v15**, `UNIQUE(profile_id, month_start_iso_date)` — one level coarser than `budget_alert`'s
  key, because a review is one card for the whole month rather than one status per band
  ([ADR-0020](docs/adr/0020-budget-review-keyed-by-month-not-category.md)). Dismissing it, or
  accepting any one category's proposal, is enough to close the card; it does not reopen until the
  next month closes.
- **A proposal prices the month ahead, not the month reviewed.** Each material finding's replacement
  budget is priced by calling the same `BudgetEngine.suggest` a plain suggestion card calls, targeted
  at the current month — so a reviewed proposal is provably the same number a suggestion would show,
  and accepting it writes to the month the user is standing in, never the closed one.
- **A finding with too little history says so, honestly** (P-03). The engine does not fabricate a
  number it cannot price; the card states that plainly rather than showing nothing or a guess.
- **A real, pre-existing residue gap was found and fixed along the way.** `DemoDao.deleteBudgetAlerts`
  has existed since issue 4.5 but `DemoModeRepository.exit()` never called it — a `budget_alert` row
  written during a demo session survived every wipe since 4.5 shipped, invisible to the "no residue"
  test for the same reason a missing table is invisible to it. Fixed alongside wiring the analogous
  call for the new `budget_review` table.
- **Verified on device**, including the part JVM tests cannot reach: rolled the emulator's clock back
  a month, budgeted and overspent a category through the running app, rolled the clock forward, and
  watched the review card render the correct totals, variance and citation; dismissed it and
  confirmed it stayed gone across a full process restart, proving the claim is a real database row
  and not screen state.

### [0.4.3] — A number to aim at, and the split lines that were being ignored  (2026-08-11)

- **Implemented:** issue 4.4 — budgets CRUD + suggestions (**FR-BUD-001**, **FR-BUD-002**,
  **FR-BUD-003**, §5.5, AI-ARC-003/006, P-02/P-03/P-07). Epic 4 had given the app a taxonomy, a
  category per transaction and a nature per rupee; none of it produced a *plan*. Now a category can
  carry a monthly amount, and the app says where the month is heading against it.
- **The bug this uncovered is older than the feature.** Nothing in the repo could sum spending per
  category, and the one monthly aggregation that existed read the **parent** transaction's
  `category_id` only. A ₹4,000 supermarket run split into ₹3,000 groceries and ₹1,000 wine has a
  parent with no category at all, so the whole ₹4,000 fell past §8.3.1's step 5 to the fallback and
  landed in Wants — the lines the user took the trouble to enter were invisible to every figure on
  screen. **The 50/30/20 rings and true spend will read differently for anyone who has split a
  transaction** ([ADR-0018](docs/adr/0018-split-aware-category-spend.md)).
- **Every spend query is now a `UNION ALL`** of transactions with no live split lines and live split
  lines, so a payment is counted exactly once, by its lines where it has them. The `NOT EXISTS`
  clause is what makes double-counting impossible. ADR-0009 predicted this shape for 4.3 *and* 4.4;
  4.3 did not do it, and no test in 4.3 could tell the difference — there was no split fixture in
  `NatureRepositoryTest` until now.
- **New `:domain:engines:budget`** proposes what a category should cost: the median of the last three
  months, lifted by a seasonal prior when one applies. The prior is the **max** of the matching
  festivals, never their product — multiplying Diwali by Dussehra is nonsense — and it is shrunk
  toward 1 by how many months of history the profile actually has, so a two-month-old profile is not
  told to budget for a festival it has never been through. All in integer basis points (MNY-002),
  rounded to ₹100 so a suggestion reads as a human number rather than ₹4,283.51.
- **The suggestion is never shown as a bare amount** (P-02). The card carries the median it came
  from, the festival that moved it and by how much, and the rule id and version that fired — so a
  user can disagree with the reasoning rather than only with the number. Accepting is a tap and
  nothing else writes a budget row (P-07); the ViewModel test asserts that absence directly.
- **A projection the engine will not make is a sentence, not a gap.** Below three elapsed days a run
  rate says more about one coffee than about the month, so the screen says the month is too early to
  project rather than showing a figure nobody could stand behind (P-03).
- **Rollover carries a surplus and never a deficit.** Unspent money is added to next month; going
  over is not carried forward. Rolling a deficit would silently shrink a budget the user set, turning
  one bad month into two without ever saying so — and the switch's help text states both halves.
- **No schema change.** `BudgetEntity` has shipped since v2 with `category_id`, `rollover_enabled`,
  `source`, `rule_id` and `rule_version` unused; ADR-0004 wrote those columns for this issue. The
  database stays at **v13**.
- **Two rules minted, not two constants** (CLAUDE.md §6): `RULE-BUD-SUGGEST` and `RULE-BUD-PACE` in
  `ai/rules/rules-kb.json` (**v1.9.0**), mirrored as typed Kotlin guarded by drift tests. ADR-0005
  named 4.4 as a possible trigger to build the runtime `ai/` loader; it did not fire, because 4.4
  makes the budget **amount** user-editable, not a rule **threshold**
  ([ADR-0017](docs/adr/0017-budget-thresholds-stay-a-typed-mirror.md)).
- **Every new gate was watched to fail first.** Three by editing only `ai/` files — which also proves
  the Gradle `inputs.file` wiring that stops a drift test passing against a file it never read — and
  the split regression by reintroducing the exact defect and watching
  `a split payment is classified by its lines, not by its empty parent` go red on
  `expected Money(minor=300000) but was Money(minor=0)`.
- **Out of scope, deliberately:** 80%/100% alerts are issue 4.5 and the monthly review is 4.6. No
  alert thresholds were minted here.

### [0.4.2] — What a rupee became, and the right to disagree  (2026-08-10)

- **Implemented:** issue 4.3 — nature classification (**AI-CLS-N**, §8.3, §8.3.1, AI-ARC-003/006,
  P-02/P-03/P-07). Every transaction now answers "what did this money become?" — Need, Want, kept &
  growing, turned into something, or debt — and the user can overrule any of it in one tap.
- **The obvious implementation is wrong, and §8.3.1 has five steps above it saying so.** Reading
  `category.nature` is step 5 of six. An EMI paid into a loan account is debt service because of the
  **account**, whatever category it carries; a transfer into a gold account is a conversion, not
  spending, even when someone tagged it Shopping; and money the user has already called a Want stays
  one. All three failures run the same direction — they **inflate true spend**, the figure
  Safe-to-Spend, the health score and the Purchase Advisor are all calibrated against.
- **New `:domain:engines:nature`** implements steps 1–5 with step 6 as a confidence flag. Steps 1–3
  fire on the account's *type*, because `loan_amortization_rows`, a goals table and a holdings table
  — all three named by §8.3.1 — do not exist
  ([ADR-0016](docs/adr/0016-nature-classification-by-account-type.md)).
- **Schema v12 → v13: one nullable column, and it holds only the correction.** `transactions.nature`
  is what the *user* said; everything else is derived on read. No backfill, no recompute job when a
  rule changes, and no way for a stored value to disagree with the rules that produced it — the shape
  that already bit the net-worth series in 3.10. It also keeps the signal step 4 learns from: an
  engine-written value could not be told apart from a user's decision.
- **The golden file caught a real bug the unit tests could not.** Steps 2–3 originally read only the
  *counterpart* account, so the arriving leg of an SIP fell past every account step to the category —
  half of every conversion labelled a Want. It surfaced because the golden file fixes the **cited
  rule**, not only the nature: four of six steps can produce NEED, so half those records would pass
  under a decision order with two steps swapped.
- **Step 6 raises a question rather than answering it.** §8.3.1's own example is ₹9,400 at a grocery
  whose median is ₹2,000 — festival stock-up (still a Need) or a party (a Want)? The nature is kept
  and the confidence drops below the floor so the sheet says the app is unsure. §8.3 is explicit that
  it never blocks a save.
- **True spend ships understated, and the dashboard says so.** §8.3's formula is
  `NEED + WANT + interest/fees`, and splitting an EMI needs the amortisation row this build has no
  table for — so loan repayments are reported separately and counted as nothing. An understated figure
  the user is *told* about is a different thing from one they are not (P-02).
- **The dashboard now shows a plan and an outcome side by side.** "This month, actually — Needs ₹… ·
  Wants ₹… · Kept ₹…", beneath the budget bar. A dashboard showing only the plan is one that can never
  disagree with the user.
- **Both new gates were watched to fail first**: swapping `CLS-NAT-004` and `CLS-NAT-005` in the
  knowledge base turned the drift test red, and mis-citing one golden record turned
  `every record is decided by the expected rule` red **while the nature assertion still passed**. The
  migration's "the override column must arrive empty" assertion was proved on a device by adding a
  deliberate backfill and watching it fail.
- `classification-kb.json` → **v1.3**: `CLS-NAT-001`…`006` gain ids and versions (AI-ARC-006), plus a
  `stage_nature` block holding the five confidence values, §8.3.1's `3×` multiple and the true-spend
  split.

### [0.4.1] — The app fills the category in, and says which rule it used  (2026-08-10)

- **Implemented:** issue 4.2 — Stage-1 auto-categorisation (**AI-CLS**, §8.1, AI-ARC-003/006,
  P-02/P-03/P-07). Type a merchant on the add screen and the category chip pre-selects itself, with
  a line underneath naming the rule that fired and a way to refuse it.
- **This is the consumer ADR-0014 promised.** Issue 4.1 shipped thirteen `CLS-MER-*` merchant rules
  that *nothing resolved*, and wrote down that it had. They resolve now — new pure-Kotlin module
  `:domain:engines:classification`, one interface, provenance on every suggestion.
- **§8.1 is a precedence chain, not a matcher**, and this ships two of its three tiers:
  **(a)** what the user has filed under this exact merchant before, **(b)** the shipped knowledge
  base, and then §8.1's "Uncategorised" prompt — which here is simply the chip row, untouched. The
  on-device TF-IDF model, §8.1(c), is deferred with its reasons in
  [ADR-0015](docs/adr/0015-stage-1-classification-tiers-and-the-kb-mirror.md). **The interface does
  not pretend it exists**; no empty parameter waits for a producer.
- **The user's own filing outranks anything shipped.** Someone who files Swiggy under Groceries
  because they only order instamart is not out-argued by a rule that says Dining. And a merchant
  they have filed *inconsistently* proposes nothing **and does not fall through to the knowledge
  base** — they have formed an opinion, and a confused opinion is still theirs.
- **Reading the rules for the first time found a wrong one.** `CLS-MER-011` matched the bare literal
  `coin`, which files a laundromat under Investment — money the 50/30/20 view would then count as
  saving. Fixed as a data row (§6): `coin` dropped at **v1.1**, id kept, version bumped, never
  renamed. `zerodha` already covers every real descriptor for Zerodha's Coin.
- **Whole-word matching is the feature, not a refinement.** `CLS-MER-010`'s literal is `lic`. As a
  substring it files every **Licious** order under *Insurance*, where it becomes a NEED, joins the
  emergency-fund essentials, and is the last place anyone would look for a food spend. Four fixtures
  (`LICIOUS`, `DELICIOUS`, `PUBLICIS`, `GARLIC`) hold both boundaries.
- **No new table and no migration** — the DB stays at **v12**. §8.1(a) says "the user's correction
  history", and `transactions` already is that history: one `GROUP BY` over merchant and category.
  A dedicated table would be a second copy of the ledger, able to disagree with it. Splits count for
  nothing (a split is the user saying a merchant is several things at once) and deleted rows count
  for nothing (a decision withdrawn is not a decision).
- **P-02 shows the rule id verbatim** — "Suggested: Dining · rule CLS-MER-001". That is ugly and it
  is the point: it is a citation into `ai/knowledge/classification-kb.json` that a user or reviewer
  can look up, and "we thought it looked like food" is not. **P-07 is the "Not this" beside it**, and
  tapping any other chip refuses it too, permanently for that screen.
- **A threshold that could switch the feature off silently is refused at construction.** Setting
  `min_confidence_bps` above `word_match_bps` would leave the knowledge-base tier firing only on
  merchants typed with no descriptor — almost none of them — while every test scoring it against bare
  names kept passing. `ClassificationRules` will not build that way.
- **Eval gate (§21.5, §8's ≥ 92%):** seventy-five frozen merchant descriptors — fifty-five labelled
  across all thirteen rules, twenty that must be refused. Scores **96% accuracy, 0 wrong categories,
  20/20 refusals**. It is deliberately **not 100%**: `AMAZONPAY` and `BYJUS` are real descriptors this
  engine misses, left in with the reason beside them, because a set curated until it scores perfectly
  measures the curation.
- **All four new gates were watched to fail before they were trusted** — the drift test against a
  renamed category, the accuracy gate against five mislabelled fixtures, the zero-tolerance refusal
  gate against a planted match. The lesson from
  `docs/report/2026-07-25-governance-standards-audit.md`, applied.
- `classification-kb.json` → **v1.2**, gaining a `stage1` block (the confidence a match is worth and
  the floor below which Stage 1 defers) and `CLS-USER-HISTORY@1.0`, the id tier (a) cites.
  `ENGINE.md` documents four known limits, including the two the eval set records as misses.

### [0.4.0] — A taxonomy that exists, and is yours  (2026-08-08)

- **Implemented:** issue 4.1 — the categories editor and the merchant-rule knowledge base
  (**FR-SET-001**, **AI-CLSN-001**, §8.1, AI-ARC-006). A real profile is seeded with fifteen default
  categories on first launch, and every one of them can be renamed, re-natured, nested one level or
  deleted.
- **The gap this closes was invisible and total.** `CategoryEntity`, `CategoryDao` and
  `transactions.category_id` shipped in issue 1.6, the add-transaction screen has offered a category
  chip row since 3.1, and bulk recategorise since 3.6 — **and the only thing in the codebase that
  ever wrote a category row was `DemoDataset`.** `DemoModeRepositoryTest` asserted a real profile had
  exactly zero, and it was right: the chip row was empty and every transaction read "Uncategorised".
  Four issues built on a table nothing could fill.
- **`FR-CAT-*`, which the backlog cited for this issue, does not exist in SRS v1.7** — verified
  against a full-text extraction of all 58 pages. That is **five for five** on generated acceptance
  criteria being more specific than the section they cite. Fixed at source in
  `scripts/gen_issue_docs.py`.
- **The seed is called from `MainViewModel.init`, and the three obvious places all miss a path:**
  `OnboardingWriter` only touches DataStore, `QuickSetupRepository.applySeeds` returns early for a
  user who skipped quick setup, and seeding from the editor leaves the add screen empty for anyone
  who never opens it. One idempotent call at cold start covers all three — plus the profiles
  onboarded before this issue existed. **A profile that deleted every default is not re-seeded**: the
  guard counts soft-deleted rows, so the app does not overrule a decision the user made (P-07).
- **A timestamp is not a uniqueness source.** The first draft derived a created category's id from
  its name and the create stamp; deleting "Fuel" and recreating it inside the same millisecond
  produced the same primary key, so `REPLACE` quietly resurrected the soft-deleted row with its old
  nature. Found by `a name freed by a delete can be used again`, fixed by using the injected
  `IdGenerator` (P-08). Seeded ids stay derived — that is what makes the seed idempotent.
- **A categorised transaction was still calling itself "Uncategorised."** The list row's title fell
  back note → merchant → "Uncategorised" and never consulted the category. Harmless for as long as no
  real profile could have one; a false statement about the row the moment the seed landed. **Found on
  the emulator by saving one**, not by reading the code, and now pinned by a test.
- **`CLS-CAT-001`…`015` and `CLS-MER-001`…`013`** (`ai/knowledge/classification-kb.json` v1.1) carry
  a stable id and a version each. The category defaults are read by `CategorySeed` and guarded by
  `ClassificationKbDriftTest` — **verified to bite against two separate mutations**. The merchant
  rules have **no runtime consumer until issue 4.2**, and gain their ids now anyway because an id
  added later than the row it names is an id that may already be missing from a stored insight
  ([ADR-0014](docs/adr/0014-classification-kb-seed-mirror-and-unconsumed-merchant-rules.md)).
- **One nature, two spellings, one translation.** `category.nature` has stored `invest` since 1.6;
  §8.3 and the knowledge base say `INVESTMENT`. `CategoryNature` carries both — `storedValue` is the
  only thing that reaches a column, `kbValue` the only thing compared against `ai/`.
- **Deleting a category does not delete the money**, and the dialog says so with a number:
  "1 transaction uses this category and will read as Uncategorised. Nothing is deleted and no amount
  changes." A count that cannot be read says so rather than claiming zero (P-02).
- **No schema change** — the DB stays at v12. `category` has had `parent_id`, `nature`, `is_system`
  and `deleted_at_utc_millis` since issue 1.6; nothing here needed a migration.
- New module `:feature:categories` (FR-SET-001 files the editor under Settings, which has no shell
  yet); `CategoryRepository` in `:data:repository`; `Category` widened and moved to its own file in
  `:core:model`.

## [0.3.0] — Epic 3: Transactions & Capture

> The capture path. The `transactions` table has existed since issue 1.6 with nothing the user could
> reach writing to it; this epic makes a transaction theirs to create — by hand first, then by
> transfer, split, receipt and SMS.

### [0.3.11] — Bank alerts, read on the phone and never believed  (2026-08-07)

- **Implemented:** issue 3.9 — opt-in, on-device SMS transaction parsing (§18, §23; P-01). With the
  consent on and `READ_SMS` granted, bank alerts become **drafts the user confirms**; nothing is
  recorded without a tap (P-07). Closes Epic 3.
- **The engine's purpose is to refuse.** An inbox is not a feed of transactions, it is a feed of
  messages *about* money — an OTP quotes the amount it authorises, a mandate reminder quotes what
  *will* be debited, a loan advert quotes a figure larger than anything the user has ever spent, and
  a balance alert quotes the balance. `DefaultSmsEngine.parse` is therefore five gates and one
  constructor: an alphabetic DLT sender, no ignore word, a direction verb, an account marker, and a
  currency-marked figure that is not a balance.
  - **The amount is the first non-balance figure, not the largest.** The receipt parser takes the
    largest candidate; here that reads `Avl Bal Rs.45,320.10` as a ₹45,320 purchase on almost every
    message the app sees. A receipt's largest number usually *is* the total; an alert's largest
    number is usually the balance.
  - **Keywords match as whole words.** `bal` therefore covers `Avl Bal`, `Bal:` and `A/c Bal` in one
    rulebook row without a merchant named `GLOBAL FOODS` losing its amount, and `unpaid` does not
    fire `paid`.
  - Two refusals were found by writing the eval set, not by reasoning: `sent` was missing from the
    debit verbs, so every UPI payment was invisible; and a declined-payment alert parsed as a
    purchase until `declined`/`failed` were added.
- **`RULE-SMS-PARSE`** (`ai/rules/rules-kb.json` v1.8.0) holds every keyword list and threshold,
  mirrored as `SmsRules` under ADR-0005 and guarded by `RulebookDriftTest` — verified to bite against
  five separate mutations.
- **Two independent gates, in a fixed order** (ADR-0013). The in-app consent is checked **before**
  the OS permission is ever requested, so Android's dialog is unreachable from a state the user has
  not opted into. `SmsRepository` is the single chokepoint; `SmsRepositoryTest` proves it with a
  reader that *throws* if called, because an empty result is also what a reader that was called and
  found nothing returns.
- **`sms_draft` at schema v12 has no `body` column.** The row holds a conclusion and the inbox id it
  came from; the text stays in the provider that already owns it. `MigrationSafetyTest` asserts there
  is nowhere to put one, and that the table keeps `profile_id` while deliberately having no
  tombstone — revoking the consent hard-deletes pending drafts rather than keeping a record of what
  was inferred from messages the app was told to stop reading.
- **`READ_SMS` is a Play-policy risk, taken deliberately** — see
  [ADR-0013](docs/adr/0013-read-sms-play-policy-and-the-gated-inbox.md) for the decision, the
  mitigations and the fallback. `RECEIVE_SMS` is **not** requested; new alerts are found by a daily
  `SmsScanWorker` scan from a stored cursor.
- **Fixed, found while building this:** the rulebook drift tests were not connected to the build.
  `ai/rules/rules-kb.json` was not a declared Gradle input, so editing a threshold left every
  `RulebookDriftTest` `UP-TO-DATE` and green — the mechanism ADR-0005's deferral rests on was not
  running. Declared as a test input in `:quicksetup`, `:recurring`, `:receipt` and `:sms`; the same
  edit that produced `BUILD SUCCESSFUL` now produces `BUILD FAILED`.
- **Two defects the mandatory security review caught before merge**, both a new profile-scoped
  table that device-wide machinery did not know about. **The demo wipe did not reach `sms_draft`** —
  and its "no residue" test passed *vacuously*, because the assertion counts rows via the same table
  list the wipe deletes, so a table missing from one is missing from both. **Revocation was scoped to
  the active profile**, so revoking while browsing the demo would have kept every pending draft drawn
  from the real inbox. Both fixed, each with a regression test verified to fail without the fix.
- **Revocation is wired, not just written.** `SmsConsentWatcher` observes the consent and purges on
  a granted → revoked transition, so the loop closes however the consent came to be off — including
  from the consents dashboard that does not exist yet. A watcher rather than a call at each revoke
  site precisely because the site that forgets is the one that leaks; and it fires only on the
  *transition*, so a user who never opted in does not get a delete on every cold start (nor one
  before unlock, where the database provider throws by design).
- **A draft is correctable, not just acceptable or dismissable** (P-07). The amount and the payee are
  editable in place, pre-filled from what was read, so a flagged draft no longer forces the user to
  dismiss it and re-type the whole transaction. **The direction is not editable**: editing changes
  how much moved, never which way, because that came from the alert's own wording.
- **`SmsInboxDeviceTest`** runs the real reader against Android's own SMS provider — the one seam a
  fake provider can never check, since a fake answers whatever it is asked. It documents, from two
  failed attempts, why a stock AVD cannot be seeded with messages.

### [0.3.10] — Back-dating, and a net-worth series that repairs itself  (2026-08-06)

- **Implemented:** any transaction can now be recorded on the day it actually happened — typed,
  transferred, split or scanned. The add screen's date picker offers past days, and the repository
  no longer refuses them (ADR-0012, superseding ADR-0011).
- **Why it was blocked, and what unblocked it.** Issue 3.4 refused a past date for a real reason:
  `net_worth_snapshot` holds one **frozen** row per past day — a trend must not move under the user
  (FR-ACC-005) — and nothing recomputed them, so a row inserted into last week left those days'
  stored figures behind. Issue 3.8 needed back-dating anyway (a receipt is already spent) and
  ADR-0011 opened a narrow provenance-based door while recording the stale history as debt. This
  pays that debt, which removed the reason for the rule, so the rule went too.
- **`NetWorthRepository.repairStaleHistory()`** recomputes exactly the stored days a later write
  invalidated, and nothing else:
  - **The staleness is derived, not tracked.** No `dirty` flag and no repair queue — a flag is state
    a write path can silently fail to set. One query joins snapshots to the transactions booked on or
    before them and compares `updated_at` **and** `deleted_at` against `computed_at`. Both terms are
    needed: `updated_at` catches a row created or edited, `deleted_at` catches one removed, because
    `softDelete` deliberately does not touch `updated_at`. It is the only query in that file that does
    not filter tombstones, because a deleted transaction is precisely the change being looked for.
  - **It never invents history.** The earliest day it can touch is the earliest already stored, so a
    transaction back-dated to before the user had the app corrects today's figure and conjures
    nothing for the years in between (P-03).
  - **A day nobody changed is never rewritten**, which is what keeps FR-ACC-005's freeze intact. The
    normal run rewrites zero days, and a test asserts exactly that.
  - Capped at `MAX_BACKFILL_DAYS` per call; a repaired day stops being reported as stale, so
    successive runs converge rather than repeating themselves.
- **Only `NetWorthSnapshotWorker` calls it**, before its existing backfill — order asserted by a
  test. The alternative, having every write path that can back-date notify the net-worth series,
  would put knowledge of a reporting table into the ledger and be one more thing a future write path
  could forget.
- **Balances were never affected** and still are not: they are derived from the ledger on every read
  (ADR-0007), so a back-dated row is in the dashboard figure the moment it is saved. Verified on the
  emulator — a ₹1,234.50 expense booked three days back landed on its own day and moved net worth
  immediately.
- **Deleted rather than added:** `Clock.stampsFor`'s `allowPast` parameter,
  `TransactionSource.recordsAPastEvent()`, and the date picker's `selectableDates` bound. The
  `AddTransactionUiState` field that carried the bound is renamed `todayInProfileZone`, because it
  now only seeds the picker and a field called `earliestBookableDate` that bounds nothing is a lie
  waiting to be believed.
- **Requirements:** FR-TXN-001, FR-TXN-010, FR-ACC-005, FR-OCR-003; TIM-002, DB-004, ARC-005;
  P-02, P-03, P-08. **ADR-0012** (supersedes ADR-0011).

### [0.3.9] — Issue 3.8: OCR receipt scanning (ML Kit)  (2026-08-06)

- **Implemented:** the app can read. Point it at a receipt — from the camera or the gallery — and it
  extracts the total, the date, the merchant and the GST on-device, pre-fills a review screen, and
  keeps the original image encrypted beside the transaction (FR-OCR-001..006).
  - **Nothing leaves the device, and there is no code path that could carry it** (FR-OCR-002, P-01).
    ML Kit's **bundled** text-recognition model ships inside the APK rather than the thin variant
    that downloads it from Play Services, so recognition also works on first launch in airplane mode
    (P-04). The whole flow was verified with airplane mode on.
  - **No `CAMERA` permission, and no storage permission** — deliberately, and it is why capture uses
    `ActivityResultContracts.TakePicture` and the system photo picker rather than CameraX. The
    camera app owns the camera; this app is handed one scratch file through a `FileProvider` scoped
    to a single cache directory.
  - **A new engine, `:domain:engines:receipt`** (pure Kotlin, ARC-002), implementing §18.1's pipeline
    literally. The load-bearing rule is that **an amount is only a candidate if it is written like
    money** — a currency marker, or a decimal point: without it `GST 18%` above `Bill No 20260406`
    reads as a ₹20,260,406 purchase. Every figure comes out of `MoneyFormatter.parse`, which refuses
    anything not exactly representable in paise; every confidence is integer basis points. **There is
    not a `Double` in the module** (MNY-001, MNY-002). Dates are read **day-first**, because
    `03/04/2026` is 3 April in every shop in India.
  - **The ≥ 95% gate is real** (§18.1, §21.5): 46 frozen anonymised receipts in
    `eval/receipts.txt`, six of them deliberately awkward, asserting total-amount accuracy ≥ 95% and
    field-complete ≥ 80% — plus a counterweight that a correct read is usually *not* flagged, so the
    parser cannot pass by flagging everything. Verified to bite by mis-labelling three fixtures.
    `ENGINE.md` states plainly what the set does **not** prove: it was written alongside the parser,
    so it is regression protection rather than an independent accuracy estimate.
  - **The image is encrypted at rest** (FR-OCR-005, SEC-003): Tink AEAD over an Android Keystore
    keyset **of its own**, so rotating or destroying the attachment key is not the database key.
    EXIF is stripped by decoding and re-encoding — one operation, so it cannot be forgotten — which
    matters because a phone writes the GPS coordinates of where a photo was taken into it. The
    attachment id is the AEAD's associated data, so a blob cannot be moved between rows.
  - **A new table** (schema **v10 → v11**): `attachments`, additive, with **no BLOB column** — the
    bytes live in a separate encrypted file so "delete the image, keep the transaction" is a file
    deletion rather than a row rewrite that leaves the old bytes in SQLite's freelist.
  - **The duplicate guard is FR-OCR-006 in SQL**: a `manual` or `sms` row within **±1% and ±1 day**
    is offered as a merge instead of a second transaction. The band is computed in integer paise
    before the query, never as `amount * 0.99`. "Save a new one" exists because the guard is a
    heuristic and two identical coffees on one afternoon are real (P-07).
  - **Low-confidence fields are flagged in words, not only in colour** (FR-OCR-004), as supporting
    text *and* a content description — the marker is invisible to a screen reader otherwise. Save is
    disabled until there is an amount and a date, and the ViewModel refuses too, because a disabled
    button is a rendering.
- **Fixed — found by running it, not by reading it.** Every unit test passed before the app was put
  on a device, and the device found two things nothing else could have:
  - **Recognised blocks are a receipt's *cells*, not its rows.** `GRAND TOTAL     365.80` came back
    as two blocks side by side, so every line-based heuristic saw a keyword with no amount and fell
    through to an item price — ₹248.00 offered as the total. Single-line blocks within
    `same_row_bps` of each other are now joined into one logical line, which is what §18.1's "near
    keywords" means on a two-column layout and the reason `RecognizedBlock` carries a position.
  - **ML Kit returns `365.8` for a printed `365.80`.** One decimal digit is now accepted: it is
    *tens* of paise, and `MoneyFormatter` pads it exactly. The guards on either side of the pattern
    carry the cost — without them `04.08.2026` would read as ₹4.08, which has its own test.
  - **A receipt could not be saved at all**, because `stampsFor` refuses a past date and a receipt is
    by definition already spent. Back-dating is now decided by **provenance**: a row read off a
    record may be back-dated, a row a person types may not. **ADR-0011** records the trade-off — the
    frozen daily net-worth series does not follow a back-dated row — and names who owes the fix.
  - **A blob was silently not deleted on Windows.** Attachment ids are `att:<uuid>`, and a colon in a
    path is an NTFS alternate-data-stream separator, so the write went to a stream and the delete
    reported success while leaving the data. The file name is now derived from the sanitised id; the
    AEAD binding still uses the full one. A regression test covers it.
- **Requirements:** FR-OCR-001, FR-OCR-002, FR-OCR-003, FR-OCR-004, FR-OCR-005, FR-OCR-006,
  FR-TXN-009; SRS §18.1, §20.1; MNY-001, MNY-002, TIM-001, TIM-002, DB-003, SEC-003, ARC-002/003/005,
  AI-ARC-003/006; P-01, P-02, P-03, P-04, P-07, P-08.
- **Deliberately not in scope:** line items (FR-OCR-003 calls them best-effort — qty–name–price table
  detection is its own project), multi-page stitching (FR-OCR-001 says MAY), a merchant knowledge
  base (issue 4.1 owns `merchant_id`; guessing a name the user never saw is worse than blank), an
  in-app camera preview, and any way to share a receipt out of the app.

### [0.3.8] — Issue 3.7: recurring detection  (2026-08-05)

- **Implemented:** the ledger now notices a pattern in itself. A deterministic detector proposes a
  recurring series when two or more transactions share a merchant, an amount within tolerance and a
  regular cadence; the user confirms or rejects it on the transactions list (FR-TXN-006).
  - **A new engine, `:domain:engines:recurring`** (pure Kotlin, ARC-002). It groups by normalised
    merchant, classifies the **median** gap as weekly/monthly/yearly, and then checks **every** gap
    against the rulebook's tolerance — the second step is the one that matters: classifying on the
    median alone would propose 1 Mar / 31 Mar / 30 Apr / 30 Jun as a monthly bill the user never had.
  - **No floating point anywhere on the money path** (MNY-001). The 5% amount tolerance is checked by
    cross-multiplication rather than by dividing into a ratio, and both medians are *lower* medians,
    so no rounding decision arises and the amount shown for confirmation is one actually paid. The
    next-due date uses `java.time`, not `+30 days`: 31 Jan + one month is 28 Feb, not 2 March.
  - **The thresholds are a rulebook row**, `RULE-RECUR-DETECT` in `ai/rules/rules-kb.json`
    (`min_occurrences: 2`, `amount_tolerance_pct: 5`, `cadence_tolerance_days: 2/4/10`), mirrored as
    an injected `RecurringRules` per **ADR-0005** and guarded by a `RulebookDriftTest` that was
    verified to bite by tampering with the rulebook before it was trusted (CLAUDE.md §6).
  - **A rejection is stored** (schema **v9 → v10**): `recurring_rule.dismissed_at_utc_millis`, one
    nullable `ADD COLUMN`, no backfill. It is deliberately *not* a tombstone — "the user said no" has
    to keep the merchant out of the detector while `deleted_at_utc_millis` means the rule is gone.
    That is what makes the acceptance criterion "decisions feed back as data, not code" literal: the
    exclusion is a row read back on the next emission, not a flag in the UI.
  - **The card shows its working** (P-02): merchant, amount, cadence, and the *dates* of the payments
    that matched — a claim the user can check against their own memory rather than a verdict they
    have to trust. Rule ids are derived (`<profile>:recurring:<merchant>`), so confirming twice
    updates one row rather than minting two.
  - **It proposes only** (P-07). Nothing here posts a transaction or moves money; a confirmed rule
    predicts, and wiring it into the forecast is issue 9.2's.
  - **Income and spending share one tolerance.** The representative amount is the lower median **by
    magnitude**, not by signed value. The band is relative to that figure, so ordering by sign made
    it depend on the direction of the money — two outflows were measured against the larger expense
    and two inflows of the same spread against the smaller, and a mirror-image ledger got a
    different answer.
- **Fixed:** `:app:connectedDebugAndroidTest` — named in the Definition of Done's phase 8 — had **no
  `androidTest` source set at all**, so it ran zero tests and reported success. Every issue that
  ticked that phase ticked a no-op. `CfoSmokeTest` now boots the **real** Hilt graph against the
  **real** SQLCipher database and drives the recurring flow end to end; proven to bite by renaming
  the section heading and watching it go red. Same failure mode as the coverage gate in the
  2026-07-25 governance audit.
- **Verified:** 1 714 unit tests green (debug + release variants); engine coverage 100% line (gate
  85%); ktlint, detekt, `lintDebug` and Paparazzi clean; 16 instrumented tests on device — 14
  migration cases including the 9 → 10 round trip, plus the 2 new app smoke cases. Driven by hand on
  the emulator **in airplane mode** (P-04): five series detected from the demo ledger, one confirmed
  and one rejected, and the screen re-opened to prove both decisions stayed.
- **Not in scope:** auto-posting (P-07; issue 9.2 owns the forecast), a recurring-rules manager
  (FR-SET-001 puts it in Settings), and merchant aliasing — matching is on the merchant string until
  issue 4.1 lands `merchant_id`.

### [0.3.7] — Issue 3.6: search, filters and bulk edit  (2026-08-04)

- **Implemented:** the transaction list stopped being a 30-day window and became the whole ledger —
  searchable, filterable, paged, with multi-select recategorise, retag and delete (FR-TXN-007,
  FR-TXN-008).
  - **Search covers payee, note, tag and amount** behind one field: a user looking for a transaction
    knows *something* about it and should not have to say which kind of something first. The amount
    match is exact via `MoneyFormatter.parse`, not a substring on the stored paise — typing `250`
    finds ₹250, not ₹2.50, ₹1,250 and ₹25,000 as well. Typed `%` and `_` are escaped, so the search
    means what the user typed.
  - **Filters:** account, category, type, source, tag, amount range and date range, as one
    `TransactionFilter` expanded into one nullable-parameter `@Query`. The amount bounds are on the
    **magnitude** (MNY-001) — under a signed comparison "between ₹100 and ₹500" would exclude every
    expense, which is most of a ledger.
  - **Tags are new** (schema **v8 → v9**): `tags` and `transaction_tags`, the two tables SRS §20.1
    names. Additive DDL, no backfill, with a round-trip case that asserts the unique index as well
    as the rows — an index a migration forgot is invisible until a user has two of something.
  - **Paging 3** (`androidx.paging` + `room-paging`, first-party, no network). Two consequences that
    are not obvious from the requirement: a transfer's two legs are now collapsed **in SQL** rather
    than paired within a loaded day, because paging can put them in different pages; and each day's
    total comes from its own `GROUP BY` query rather than a fold over loaded rows, because a page
    boundary can fall inside a day and a folded total would understate it until the user scrolled.
  - **Bulk edit is reversible.** Delete returns the ids it *actually* removed — for a transfer that
    is both legs (FR-TXN-003) — and undo restores exactly those, so the snackbar cannot leave money
    in one account with no counterpart. Recategorise skips transfer legs and split parents in SQL
    rather than trusting the caller to remember (FR-TXN-003, FR-TXN-004).
  - **`observeRecent` was removed, not kept alongside.** Its window was scaffolding whose own doc
    comment named this issue as its removal. The *upper* bound survives, so a scheduled payment
    still stays out of the actuals unless a filter names a future date (FR-TXN-010).
- **Tests:** 1457 passed, 0 skipped. 37 new repository tests (each facet, paging across a page
  boundary, day totals across a page boundary, bulk + undo), 42 Compose tests, 40 ViewModel tests,
  and the 8 → 9 migration round trip. The `LIKE`-escaping guard was proven to fail on purpose before
  being trusted — its first version passed with the escaping removed, because the decoy row had no
  digits in it.

### [0.3.6] — Fix: the add screen was missing two FR-TXN-001 fields  (2026-08-03)

- **Fixed:** the add-transaction screen never captured a **merchant** or a **time of day**, both of
  which FR-TXN-001 lists ("amount, currency, date-time, account, category, subcategory,
  payee/merchant, notes, …"). No schema change — both were already stored.
  - **Merchant was plumbed end to end and unreachable.** `transactions.merchant` has been a column
    since schema v1, `TransactionDraft.merchant` since issue 3.1, the list row falls back to it for
    its title and issue 3.5's detail sheet renders it — but **only `DemoDataset` ever wrote one**.
    The visible symptom: every row on a real profile read "Uncategorised" unless the user happened
    to type a note. **Hidden for a transfer**, which has no payee — which is why `TransferDraft` has
    no field to carry one, the same reason it has no category (FR-TXN-003).
  - **Time of day is now the user's to state.** `occurredAtUtcMillis` was always the app's choice —
    now for today, the start of the day for a future date — so recording this morning's coffee in
    the evening ordered it after everything bought since. `BookingStamps.stampsFor` takes an
    optional `LocalTime` and resolves it through the profile `ZoneId`.
  - **`null` still means "the app's choice"** for both fields, so every existing call site and the
    ≤ 3-tap path are byte-for-byte unchanged (FR-TXN-002).
  - **The time changes ordering, never money.** Balances and budgets bound on `booked_on_iso_date`,
    so the hour decides where a row sits within its day and nothing else — and posting stays a
    property of the day (ADR-0010): a row booked for 09:00 tomorrow is no more posted than one
    booked for tomorrow with no time at all.
- **Tests:** 944 passed, 0 skipped (+24). One failed first and taught something worth keeping:
  **`ZonedDateTime` resolves a DST-gap time forward by the length of the gap, not to the first valid
  instant.** Asking for 00:30 on Chile's 2026-09-06 gives 01:30 local (04:30Z), *not* the 04:00Z
  `startOfDay` produces for the same day — because that asks for 00:00. The obvious expectation is
  wrong, and the test was written asserting it.
- **Verified on a device:** a ₹899 expense saved with merchant "Big Bazaar" at 7:03 AM renders as
  **"Big Bazaar"** rather than "Uncategorised", and sorts below rows stamped at 19:03 — the hour
  visibly driving intra-day order. Airplane mode throughout (P-04).

### [0.3.5] — Issue 3.5: Transaction source tracking  (2026-08-03)

- **Implemented:** every transaction has recorded where it came from since issue 3.1 — this makes the
  app *show* it (**FR-TXN-009**, P-02; ARC-004, ARC-005). **No schema change:** the data has been
  right all along, and only nothing surfaced it.
  - **The row this exists for is the reconciliation adjustment.** Its `note` is deliberately null
    (FR-ACC-006), so it rendered as an anonymous "Uncategorised −₹500.00" with nothing saying the
    *app* had posted it to close a gap against a statement. It now reads **"Balance adjustment"**,
    verified against a live reconciliation on the emulator.
  - **A source label on the row**, worded as provenance rather than mechanism — "From a receipt",
    not "OCR". **Manual rows carry none**: it is the default and the overwhelming majority, and
    tagging every hand-typed row would bury the labels that carry information.
  - **A detail bottom sheet on row tap** — the app's first — showing every FR-TXN-001 field the
    transaction has, source included and spelled out even when it is "Manual". Deliberately **no
    nav route**: issue 3.6 owns editing and will want a real screen.
  - **A source filter chip row** that appears only when the window holds two or more distinct
    sources, so the all-manual profile every real user has today shows no chips and no labels at
    all. Filtered in the ViewModel over rows already loaded — FR-TXN-007's filter list (3.6's) does
    not include source, and keeping this out of SQL leaves that query 3.6's to design.
  - **`RECURRING_AUTO` added**, completing FR-TXN-009's five. Nothing writes it until issue 3.7; it
    exists so a row from a newer build renders rather than being dropped by the mapper — the exact
    failure that omitting `demo` caused in issue 3.1.
- **Corrected the generated backlog for the fourth issue running**, at source in
  `scripts/gen_issue_docs.py`: the criteria cited **AI-ARC-003**, which governs *engine result*
  provenance and has nothing to say about a transaction row (no engine writes one, so "creating
  engine/version where applicable" applied to nothing — and no such column was added); they asked
  for the source "in the detail view" when no detail view existed; and they asked for a backfill
  when `source` has been `TEXT NOT NULL` since schema v1 and every write path sets it. The
  no-op migration was replaced by an assertion that there is nothing to backfill.
- **Tests:** 920 passed, 0 skipped (+30). Two of the new tests failed first and both were real: a
  filtered-to-nothing list rendered *both* empty messages, so `isEmpty` gained a fourth clause;
  and `setContent` was called twice in one test, the mistake issue 3.3 had already recorded.
- **No `connectedDebugAndroidTest`** — the first Epic 3 issue with no schema change, so no migration
  to prove and no upgrade path to build. Emulator run covered the demo profile, a real profile
  onboarded from scratch, and a live reconciliation; whole session in airplane mode (P-04).

### [0.3.4] — Issue 3.4: Future-dated transactions  (2026-08-03)

- **Implemented:** a transaction can be booked on a future day, stays out of every actual until that
  day, and is readable by forecasts before it (**FR-TXN-010**; DB-003, TIM-001, TIM-002, MNY-001,
  ARC-004, ARC-005, SEC-002, P-04, P-08).
  - **Fixed a live defect the tests found before the feature existed.** Net worth has bounded on
    `booked_on_iso_date <= today` since issue 2.6, but the **account-balance** queries
    (`observeWithBalances`, `findWithBalance` — behind the accounts screen, the account editor and
    reconciliation) never did: they summed every live transaction whenever it happened. The first
    scheduled payment would have been subtracted on the accounts screen while net worth showed a
    different figure for the same money. Both are bounded now. `balancesForNetWorth`'s own doc
    comment had predicted this by name eight days earlier.
  - **Schema v7 → v8** — `transactions.posted_at_utc_millis`, nullable, **with a backfill**.
    `ADD COLUMN` alone gives every existing row `NULL`, which is exactly the value meaning "not
    posted", so an upgraded install would have shown the user's whole history in the Scheduled group.
  - **The date decides every figure; the stamp is only a record** (see
    [ADR-0010](docs/adr/0010-future-dated-posting.md)). `ScheduledTransactionWorker` runs daily and
    stamps what is due, idempotently — a second run the same day stamps nothing — but no balance
    depends on it having run. A job can be deferred by Doze, by a powered-off device, or by the app
    being locked (SEC-002); a date cannot.
  - **The add screen gained a Date row**, pre-filled with "Today" so FR-TXN-002's ≤ 3-tap expense is
    untouched. The picker will not offer a past day: back-dating would stale the `net_worth_snapshot`
    rows already written for those days, and issue 3.6 owns editing.
  - **The list gained a Scheduled group** above a Posted one, its day headers deliberately carrying
    no total — a day total is a statement about money that has moved. The two halves come from two
    repository flows whose windows abut at today, so a scheduled row is never in the list day totals
    are computed from and there is no filter for a later screen to forget.
  - **`observeUpcoming()`** is the seam Epic 6's cash-flow forecast and FR-HOME-001's 14-day
    obligations card will read — the "included in forecasts" half of the requirement.
  - **Zone- and DST-correct.** A future row's instant is the start of its own day via
    `Clock.startOfDay`, so it sorts after today's and lands on the first valid instant on a day whose
    local midnight does not exist (Chile, 2026-09-06 — a test case).
- **Corrected the generated backlog for the third issue running**, at source in
  `scripts/gen_issue_docs.py`: the criteria cited no requirement id at all (it is **FR-TXN-010**),
  and "on date rollover they post automatically (WorkManager), idempotently" appears nowhere in the
  SRS — it was authored in the generator. Where those criteria are more specific than the SRS section
  they cite, they are a guess.
- **Tests:** 890 passed, 0 skipped (+62 for this issue) · 12/12 instrumented on a device, incl. the
  7 → 8 backfill · emulator: **v7 installed, real data added, v8 installed over it** — net worth
  unchanged; then a ₹25,000 payment scheduled (net worth unmoved), then the device date advanced one
  day (net worth moved by exactly ₹25,000, **with nothing written**). Whole session in airplane mode.
- **Caught by lint, not by any test:** `LocalDate.EPOCH` requires API 34 and this app's minSdk is 26.
  Every unit test runs on the JVM, where the constant exists, so it compiled, passed 890 tests and
  would have crashed on a real phone.

### [0.3.3] — Issue 3.3: Split transactions across N category lines  (2026-08-02)

- **Implemented:** one purchase attributed across several categories — a ₹1,000 supermarket trip is
  groceries *and* household (**FR-TXN-004**; DB-002, DB-003, DB-004, MNY-001, ARC-004, ARC-005,
  P-03, P-04).
  - **Schema v6 → v7** — the new `transaction_splits` table. Purely additive, so unlike 5 → 6 there
    is nothing to backfill: every existing transaction is simply unsplit, which is the truth about it.
  - **`TransactionRepository.createSplit`** writes the parent and all its lines in one
    `withTransaction` (DB-004). **The exact-sum rule is enforced by refusal, never by adjustment** —
    lines that miss the parent by a single paise are rejected outright, because an app that quietly
    moves a user's figure to make a form balance is worse than one that says no. A single line, a
    zero line, and a line signed against its parent are refused too.
  - **A split moves the balance once.** The parent holds the amount and the lines only attribute it,
    so no balance query changed and no balance code was written. Verified on the emulator: a
    three-line ₹1,000 split moved Cash Wallet by ₹1,000, and deleting it moved it back by ₹1,000.
  - **Deleting a parent takes its lines** in the same transaction — a line whose parent is gone
    attributes an amount that no longer exists.
  - **The add screen gained an opt-in "Split into lines"** with a live **running remainder**: Save
    unlocks exactly when it reaches ₹0.00. **"Split evenly"** goes through `Money.split`, the one
    action that can always divide exactly — ₹1,000 across three lines is 333.34 / 333.33 / 333.33.
    The parent's category row is hidden while splitting; each line carries its own.
  - **The list marks a split parent** with its line count and one unchanged amount, so the money is
    never shown twice.
- **Corrected two stale instructions** in the generated backlog, at source in
  `scripts/gen_issue_docs.py`: `Money.splitExact` does not exist (the API is `split`/`allocate`), and
  **"distribute the remainder via HALF_EVEN" is wrong in principle** — HALF_EVEN rounds one value and
  cannot make N parts sum to a whole (three HALF_EVEN thirds of ₹1,000 give ₹999.99, the exact
  "rounding drift" FR-TXN-004 forbids). The existing largest-remainder rule is what satisfies it.
- **Recorded in [ADR-0009](docs/adr/0009-splits-as-a-child-table.md)** why splits are a child table
  while transfers are linked legs (ADR-0008): a transfer's legs both move money, so a parent row
  would hold no fact; split lines move none, so a child table keeps them out of every balance.
- **Refactors the structure forced, and they were fair:** the split editor became its own file when
  `AddTransactionScreen.kt` passed detekt's function ceiling, the split drafts and rules likewise
  left `TransactionRepository.kt`, and the six split interactions became a nested `SplitEvent` so the
  screen's main event handler stayed inside its complexity budget.
- **Tests:** 62 new, 0 skipped (6 model · 24 repository · 31 feature · 1 instrumented migration).
  The repository property test asserts the sum **on rows read back out of SQLite** rather than
  re-proving `Money` — `MoneySplitPropertyTest` already owns the in-memory guarantee.
  **FR-TXN-002's two-tap expense is untouched**: 3.1's tap-count assertions still pass unmodified.
  Verified by **upgrading, not installing fresh** — the v6 build was installed, given data, and v7
  installed over it, with net worth unchanged at ₹4,16,485 afterwards.
  `:core:database:connectedDebugAndroidTest` — 11/11 on the emulator, in airplane mode throughout.

### [0.3.2] — Issue 3.2: Transfers as a single logical record  (2026-08-02)

- **Implemented:** moving money between two of your own accounts, as **one** record
  (**FR-TXN-003**; DB-003, DB-004, MNY-001, TIM-002, ARC-004, ARC-005, P-02, P-03, P-04).
  - **Schema v5 → v6** — the first schema change since issue 1.6, and the **first migration that
    rewrites existing rows** rather than only adding empty columns. Adds §20.2's
    `transactions.type` and `transactions.transfer_id` + index, then backfills every existing row:
    `source = 'reconciliation'` → `adjustment`, otherwise the sign decides. Without that backfill a
    user's salary credits would all have read as spending.
  - **`TransactionRepository.createTransfer`** writes both legs inside one `withTransaction`
    (DB-004), sharing one `transfer_id`, one instant and one booked day. Neither leg carries a
    category — a transfer is not spending. Cross-currency transfers are **refused**, not converted:
    that needs FX rates no issue has built, and inventing one would be the app making up a number.
  - **`delete` removes both legs atomically** in a single `UPDATE` (FR-TXN-003's second clause), so
    there is no window where one leg is gone and the other is not. The screen passes whichever row
    the user tapped; the repository decides whether a sibling goes with it.
  - **The list collapses a transfer into one row** — "Transfer · HDFC Savings → Cash Wallet" — paired
    by `transfer_id`, never by matching amounts and dates. The day total is unaffected because the
    legs net to zero. A lone leg (its sibling outside the 30-day window) still renders.
  - **The add screen gained a third direction**, Expense · Income · **Transfer**, with a To-account
    picker that excludes the source and hides the category row. Expense remains the default, so
    FR-TXN-002's two-tap common expense is unchanged.
  - **A delete action on every row**, which is what makes the atomic both-legs delete observable.
- **Deviations recorded** in [ADR-0008](docs/adr/0008-transfers-as-linked-legs.md): no `transfers`
  parent table (§20.1) because it would hold no fact the legs don't; `source` carries
  `reconciliation` and `demo`, which §20.2's CHECK list omits; and §20.2's `CHECK(type IN …)` cannot
  exist on an upgraded SQLite table, so the invariant lives in a test instead.
- **Known cost, accepted:** `type` records direction a second time alongside the amount's sign. No
  caller supplies a type — it is derived at one site per write path — and a test walks every path
  asserting the two agree.
- **Fixed:** `TransactionDao.softDelete` had no `AND deleted_at_utc_millis IS NULL` guard, so a
  second delete matched the same row and reported success twice. Harmless until this issue added the
  delete UI that would have exposed it.
- **Tests:** 70 new, 0 skipped (10 model · 27 repository · 32 feature · 1 instrumented migration).
  **Verified on a device**: installed the v5 build, added a transaction, then installed v6 over it —
  every row survived with identical amounts and the salary credit backfilled as `income`, not
  `expense`. Then, in **airplane mode**: a ₹5,000 transfer moved HDFC ₹3,82,800 → ₹3,77,800 and Cash
  −₹4,236 → +₹764, rendered as one row leaving the day total unchanged, and deleting it reverted
  both balances exactly. `:core:database:connectedDebugAndroidTest` — 10/10 on the emulator.

### [0.3.1] — Issue 3.1: Add transaction ≤ 3 taps (FAB)  (2026-08-02)

- **Implemented:** the app's most-used flow — capture a transaction in **two taps** (FAB → Save)
  (**FR-TXN-002**, FR-TXN-001, FR-TXN-009; MNY-001, TIM-001, TIM-002, DB-001, DB-002, ARC-001,
  ARC-003, ARC-004, ARC-005, P-01, P-04, P-08).
  - **`TransactionRepository`** (`:data:repository`) — `create`, a 30-day `observeRecent`, and the
    categories the add screen offers. Amounts are signed `Money` paise and **nothing writes a
    balance**: the row it inserts *is* the balance update (DB-001, ADR-0007). The account is verified
    live before the write, so no orphan row can be stored. `TRANSACTION_ID_PREFIX` moved here from
    `AccountRepository`, unchanged, as that class's comment said it would.
  - **A global FAB** above the nav graph, hidden on onboarding and on the capture screen itself, so
    add-transaction is one tap from anywhere — FR-TXN-002's literal wording, without building the
    bottom nav (issue 5.1 owns that).
  - **The add screen** preselects the first account and autofocuses the amount, which is what makes
    the two taps real; the account picker hides when there is one account and the category row hides
    when the profile has none. The expense/income toggle becomes a **sign** before it reaches the
    store, so direction has exactly one representation below the UI.
  - **A recent-transactions list** replacing issue 1.10's placeholder — day-grouped with daily totals
    (FR-TXN-007's grouping half only; search, filters, paging and bulk edit remain issue 3.6's).
- **Corrected:** this issue's requirement id. The backlog cited **FR-TXN-004**, which is *split
  transactions* (issue 3.3); the ≤ 3-tap rule is **FR-TXN-002**. Fixed at source in
  `scripts/gen_issue_docs.py` and regenerated.
- **Fixed (found on the emulator, not by the build):**
  - Every **demo** transaction was invisible in the new list — `transactions.source` has held
    `"demo"` since issue 2.4, the first `TransactionSource` enum omitted it, and the mapper's
    forward-compatible `mapNotNull` silently dropped all of them. Regression test:
    `the demo's own history is inside the window`.
  - **Save was unreachable behind the keypad** on a profile with several accounts and categories —
    the screen drew edge-to-edge without consuming IME insets, so the form had nothing to scroll.
    Fixed with `imePadding()`.
  - A **double-tap booked the spend twice**: the write finishes fast enough that the second event
    arrives after `isSaving` clears, so `isSaved` is now guarded too.
- **Tests:** 85 passed, 0 skipped (11 model · 27 repository · 45 feature · 2 app) — repository
  (Room in-memory, profile isolation, the derived
  balance moving by exactly the amount, the profile-zone booked day), ViewModels (Turbine), and
  Compose tap-count tests split across `:feature:transactions` and `:app` that pin FR-TXN-002's
  budget at 1 + 1 = 2. Emulator run in **airplane mode** (P-04): ₹250 expense moved the balance
  −₹3,459 → −₹3,709; ₹60,000 income moved net worth +₹4,17,262 → +₹4,77,262.

## [0.2.0] — Epic 2: Onboarding, Security & Accounts

> First-run onboarding, the biometric app lock, and the accounts + net-worth core. Epic 1 built
> foundations; this is the first epic a user can see.

### [0.2.7] — Issue 2.7: Account reconciliation + the DB-001 integrity job  (2026-08-02)

- **Implemented:** a balance the app got wrong can finally be corrected — by *adding* to history,
  never by editing it (**FR-ACC-006**; DB-001, DB-002, MNY-001, TIM-001, TIM-002, SEC-002, ARC-004,
  ARC-005, P-02, P-03, P-04, P-08). **The last issue in Epic 2.**
  - **`AccountRepository.reconcile(accountId, statementBalance)`.** The user states what their bank
    says; the app derives what it holds, subtracts, and posts the difference as one adjustment
    transaction tagged `source = "reconciliation"`. The opening balance and every existing
    transaction are untouched — FR-ACC-006's words are "never silently mutated", and the only way
    to honour that is for the correction to be a new row the user can see and soft-delete like any
    other.
  - **A zero delta writes nothing**, and says so on screen. A zero-amount transaction records no
    fact and would have to be filtered by every engine downstream, so `adjustmentId` is `null` and
    the row is never minted — not even an id is drawn from the generator.
  - **The screen previews; the store decides.** The panel shows a delta computed against the balance
    the list last emitted, but `reconcile` re-derives the balance inside its own transaction and
    computes the delta again there. A transaction landing while the panel is open therefore cannot
    be absorbed into the correction — the same stale-versus-live split 2.6 shipped a defect on.
  - **The adjustment carries the account's own profile, not the active one.** Reconciling inside the
    demo must leave a row `DemoDao.deleteTransactions` can reach; the opposite is the residue
    ADR-0006 forbids, and it is the exact trap 2.6 fell into with `net_worth_snapshot`.
  - **DB-001's integrity job now exists** — `BalanceIntegrityWorker`, daily, the app's **second**
    background work. `account.current_balance_minor` had been a cache written once at create and
    never again, stale on every account with transactions; ADR-0007 said so in as many words —
    *"the two figures can disagree, and until issue 2.7 nothing notices."* One `UPDATE` re-derives
    every cache in the profile, and its trailing `<>` clause is what makes the row count mean
    something: it is how many were wrong, not how many exist. Nothing is written that was already
    correct.
  - **The new worker cannot crash a locked app either.** Same shape as 2.6's: `SessionLock` checked
    before anything injects, repository behind a `Provider`. Observed on hardware, by moving the
    device clock forward a day: `RETRY (locked) → SUCCESS` 30 seconds later.
  - **An inline panel, not a modal dialog.** It began as an `AlertDialog` and was changed for two
    reasons pointing the same way: Robolectric never drives a `Dialog`'s own window to idle, so all
    four rendered tests hung for sixty seconds each and the screen would have been covered only when
    an emulator happened to be attached; and a modal holding a field, five lines of copy and two
    buttons is the shape that gets clipped at 200% font — the defect class 2.5 found on a device and
    could not reproduce. Inline, it is checked on every `unitTests` run.
- **No schema change.** `transactions.source` and `account.current_balance_minor` both already
  existed; the database stays at **v5**. The first issue in Epic 2 needing neither a migration nor a
  migration test.
- **Found by running it, again.** On an untouched form the panel announced *"this adds one
  adjustment transaction"* while Confirm was disabled and nothing could be added — a promise about
  an action the screen was simultaneously refusing. No test caught it because every one had already
  typed an amount. Fixed, and now gated.
- **Verified on a device** (demo household, hand-checked arithmetic): HDFC `+₹3,82,800 → +₹3,83,300`
  and net worth `+₹4,17,262 → +₹4,17,762`, both exactly the ₹500 posted; the same statement a second
  time adjusted nothing; a credit card corrected *downwards* by `−₹1,250`; an overdrawn cash wallet
  corrected `−₹3,459 → −₹3,000` **with the radio off** (P-04). 622 unit tests across 21 modules.

### [0.2.6] — Issue 2.6: Net worth = assets − liabilities + daily snapshot  (2026-08-02)

- **Implemented:** the app's headline figure is computed rather than invented (**FR-ACC-005**;
  DB-001, DB-003, MNY-001, TIM-001, TIM-002, SEC-002, ARC-002, ARC-003, ARC-005, AI-ARC-003,
  AI-ARC-006, P-02, P-03, P-04, P-08).
  - **`:domain:engines:networth`** — the project's **second engine**, pure Kotlin. Partitions
    accounts by `AccountType.isLiability` (`credit_card`, `loan`, `payable` — `receivable` is an
    asset), sums each side, subtracts. The arithmetic never needed the partition: balances are
    already signed, so a plain sum *is* assets − liabilities. It exists so a screen can say "assets
    ₹5,02,800, you owe ₹82,079" instead of one unverifiable total (P-02).
  - **Classification is by type, never by sign** — the one judgement in the engine. An overdrawn
    bank account is an asset with a negative value; a card paid past zero is a liability with a
    positive one. Net worth is *identical* either way, so the error would surface only in the two
    subtotals a user checks against their own accounts. Both cases are pinned by tests, and the
    demo's cash wallet turned out to be overdrawn, so the first case is live.
  - **A daily snapshot that backfills** (schema **v5**, `net_worth_snapshot`). WorkManager job —
    the app's **first background work**. Ids are derived (`<profile>:networth:<date>`), so a second
    run in a day updates one row rather than leaving two figures for one date. Missed days are
    recomputed from the transactions booked on or before each of them, capped at 90 days a run; a
    first ever run writes today only, because inventing a history the user never had the app for
    would be fabricating data (P-03).
  - **The worker cannot crash a locked app.** `CoreModule.provideDatabase` *throws* when the session
    is locked (SEC-002), so the worker checks `SessionLock` first and injects the repository through
    a `Provider` — the graph is not built until that check passes. Observed on hardware:
    `SUCCESS → RETRY (locked) → SUCCESS` 30 seconds later.
  - **A second, date-bounded balance query.** Net worth reads `booked_on_iso_date <= :asOf`, unlike
    2.5's current-balance query — so a future-dated transaction (issue 3.4) will not be subtracted
    from today's figure, and the backfill can reconstruct a past day exactly. It also excludes
    archived (FR-ACC-007) and opted-out accounts.
  - **`account.include_in_networth`** (§20.2) with an editor toggle, for an account that is open and
    transacting but is not the user's to count.
  - **The dashboard's hardcoded ₹4,82,350.00 is gone.** Safe-to-Spend is now the only placeholder
    left. No snapshot yet renders as "not worked out yet", never as ₹0.
- **Tests:** **573** unit passed, 0 skipped, across 21 modules; 10 instrumented on `emulator-5554`.
  Counted from the new `unitTests` task after clearing stale results — earlier drafts of this entry
  said 578 because `build-logic`'s 5 (a separate composite CI runs on its own) were on disk.
  Highlights: a **golden-file test on a fixed account set** (the AC) and a seeded property test
  asserting `netWorth == assets − liabilities` *and* `== Σ signed balances` over generated
  portfolios (money math, 100% coverage); `migrate4To5` against real SQLite, asserting the new
  column defaults to **counting**; the worker's locked path. **Four gates were proven to fail
  before being trusted.**
- **Found by running it, not by a test:** the dashboard read the *stored* snapshot, so deleting a
  ₹1,20,000 account left the total unchanged — correct for a historical record, wrong for a headline
  figure. Split into `observeCurrent()` (live, what the screen shows) and `observeLatest()` (stored,
  what issue 6.6 will chart), both through the same filter so they cannot disagree about which
  accounts count. Two tests added that now red against the old behaviour.
- **Fixed a hole in the test gate itself — the biggest thing this issue found.** `./gradlew
  testDebugUnitTest`, the command CLAUDE.md, the workflow, every issue template **and CI** all named,
  is an Android *variant* task: it does not exist on the pure-Kotlin modules and **never reached
  `:lint` at all**. `:lint`'s fourteen tests are the only check on the five custom detectors that
  make MNY-001, TIM-001, ARC-006, the PII-logging ban and the hardcoded-string ban fail the build —
  so the enforcement layer's own tests **ran in no CI step**. Demonstrated rather than argued:
  disabling `MoneyDoubleDetector` outright left `testDebugUnitTest` reporting BUILD SUCCESSFUL.
  Added a root **`unitTests`** aggregate (matched by task name, so a new module is picked up without
  anyone registering it) and pointed CI, CLAUDE.md, `00-issue-workflow.md`, both issue templates and
  the generator at it. Same shape as audit G-01's vacuous coverage gate.
- **Also fixed:** the demo wipe did not reach `net_worth_snapshot` — a profile-scoped table the wipe
  misses is the residue ADR-0006 forbids, and the test written for it caught the gap immediately.
  `androidx.work`'s own lint rule caught a missing `WorkManagerInitializer` removal. And
  `scripts/gen_issue_docs.py` cited **`FR-NW-*`** for both 2.6 and 6.6 — a requirement ID that
  appears **zero times** in the SRS; the real one is FR-ACC-005. Third citation error in a row
  (2.4 cited §33, 2.5 cited §11), all fixed at the generator.

### [0.2.5] — Issue 2.5: Accounts CRUD (all types)  (2026-08-01)

- **Implemented:** a user can create, read, edit, close and delete an account of any type the SRS
  names, and **FR-ONB-001 is satisfied for the first time** (**FR-ACC-001**, **FR-ACC-007**,
  **FR-ONB-001** step 4; DB-001, DB-002, DB-003, MNY-001, TIM-001, ARC-003, ARC-005, P-02, P-03,
  P-08).
  - **All eleven account types, not six.** `AccountEntity`'s doc comment (issue 1.6) listed
    `bank | cash | wallet | card | loan | investment`; FR-ACC-001 names eleven and includes neither
    `wallet` nor `card`. `AccountType` in `:core:model` is now the single definition, carrying
    §20.2's exact stored strings. The demo dataset had been writing `"card"` — a value no type-aware
    query would ever have matched — and is corrected to `credit_card`; a new test asserts every demo
    account carries a type the enum recognises, which is the check that was missing.
  - **Balances are derived, never stated** ([ADR-0007](docs/adr/0007-account-balances-derived-not-stored.md)).
    DB-001 says the current balance "is derivable from opening balance + transactions" and is "never
    mutated ad hoc", so every read computes `opening + SUM(live transactions)` in one correlated
    subquery. `AccountDraft` deliberately cannot express a balance: correcting one is FR-ACC-006's
    reconciliation flow (issue 2.7), which posts an adjustment transaction. `current_balance_minor`
    stays as the cache DB-001's integrity job will check against.
  - **Archive is not delete (FR-ACC-007).** A closed account keeps every transaction it ever had and
    drops out of the active list; a deleted one is soft-deleted (DB-002) and the row survives. The
    two are independently observable, and the DAO's delete now filters `deleted_at IS NULL` so a
    second delete reports `NotFound` rather than confirming something that never happened.
  - **Onboarding's fourth step, three ADR-0002 updates later.** `OnboardingStep.ACCOUNT` lands
    exactly where that record said it would, and attaches the account to the recurring rules issue
    2.3 wrote with a null `account_id`. Skipping is a blank name — one representation, so it cannot
    disagree with itself. Skipping *quick setup* now has to be remembered rather than meaning
    "finish", because a step follows it.
  - **Schema v3 → v4**, purely additive: `account.institution` and `account.archived_at_utc_millis`.
    The first migration in this app that alters an existing table rather than creating new ones.
  - **`:feature:accounts`** — list and editor, and `CfoRoute.AccountEditor(accountId: String?)`, the
    first typed route in the app to carry an argument.
- **Tests:** 510 unit passed, 0 skipped (+124); 9 instrumented passed on `emulator-5554`.
  Highlights: a **seeded property test** over ~2 000 generated transactions asserting
  `balance == opening + sum` (money math, 100% coverage); `migrate3To4` against real SQLite; the
  full six-step onboarding flow; **an airplane-mode pass** — an account created with the radio off
  (P-04).
- **Found and fixed on the device, not in a test:** a plain `Row` squeezed the Delete action to 10px
  on an active row and to nothing on a closed one, where the label reads "Reopen account". Fixed
  with `FlowRow`. The regression test added alongside it **does not reproduce the defect** —
  Robolectric measures text with a stub, so it stays green against the broken layout; that was
  confirmed by reverting the fix, and the tracker records the device as the gate rather than
  claiming the test bites.
- **Also fixed:** the archived-accounts switch was not part of its own label's tap target;
  `AccountsUiState.isEmpty` rendered a failed read as a cheerful "no accounts yet"; the issue
  generator cited **§11** (the Investment Intelligence Module) for accounts and told every tracker to
  merge to `main`, which CLAUDE.md §7 forbids — both corrected at
  [`scripts/gen_issue_docs.py`](scripts/gen_issue_docs.py) rather than in the generated files.

### [0.2.4] — Issue 2.4: Demo mode with sample data  (2026-07-28)

- **Implemented:** the app can be explored on realistic sample data before any real figure is
  entered — clearly marked throughout, and erased without residue on the way out (**FR-ONB-004**;
  MNY-001, TIM-001, TIM-002, P-01, P-02, P-03, P-04, P-08).
  - **Reachable without creating a profile**, which is what FR-ONB-004 actually asks for. The offer
    sits on the *first* onboarding step, and taking it writes no time zone, no currency, no display
    name, no consent decision and no completion flag — only a `demo_mode_active` setting. A user who
    leaves the demo lands back on first-run onboarding rather than on an empty dashboard belonging to
    a profile they never made. Asserted, not assumed: `starting the demo creates no profile`.
  - **Isolated by profile id, erased by hard delete** ([ADR-0006](docs/adr/0006-demo-mode-profile-isolation-and-hard-delete.md)).
    Sample rows live under a second `demo` profile in the same encrypted database, so the
    per-profile scoping every query already has *is* the isolation. `DemoDao` is the one DAO in the
    app that deletes outright — a soft-delete tombstone would be exactly the residue the acceptance
    criterion forbids. **No schema change:** a DAO adds queries, not tables, so the database stays at
    version 3.
  - **A deterministic, seeded dataset.** Three months of an Indian salaried household — 4 accounts,
    12 categories, ~29 transactions a month, 3 budget envelopes, 2 recurring rules — from a fixed
    backbone of obligations plus discretionary spending jittered by a seeded `Random` (P-08). Fixed
    clock plus fixed seed gives byte-identical rows, which is what makes the golden test possible.
    Every account's closing balance is derived as opening + its own transactions, so the demo adds up.
  - **The demo's budget is computed, not typed** (P-03). It comes from the real `QuickSetupEngine`,
    so the sample dashboard carries the same `RULE-…` citations a real user's does.
  - **One banner labels every screen.** `CfoDemoBanner` is composed above the navigation graph, not
    inside a destination — a per-screen label is one forgotten screen away from showing fabricated
    figures with nothing saying so. It carries the exit action and is announced as a live region.
  - **Readers follow the active profile automatically**: `observeLatestEnvelopes()` resolves it from
    `DemoModeRepository`, so the dashboard swaps to the sample budget and back without importing
    demo mode at all — and every reader added later inherits that.
- **Also fixed:** `OnboardingFlowInstrumentedTest` **had not compiled since issue 2.3** — the
  ViewModel's constructor changed under it and nothing noticed, because `androidTest` is only
  compiled when a device is attached. Repaired and now verified running.
- **Refactored:** `OnboardingWriter` extracts the consent + profile writes (and the order they must
  happen in) out of `OnboardingViewModel`; `RepositoryModule` splits the `:data:repository` bindings
  out of `CoreModule`. Both were detekt limits reached, fixed by splitting along real seams rather
  than by raising a threshold.
- **Tests:** 386 unit passed (35 new), 8 instrumented passed, 0 skipped. **Counting basis, because
  earlier entries got this wrong:** each test once. A project-wide `testDebugUnitTest` also leaves
  `testReleaseUnitTest` results on disk from previous runs, and naively summing every
  `test-results/**` XML double-counts them — which is where the inflated figures in earlier
  changelog entries and `docs/memory.md` came from. Both new gates were **proven to fail before
  being trusted** —
  the golden dataset test reddened on a one-digit seed change, and the residue test reddened when one
  hard delete was swapped for a soft delete.
- **First emulator run in the project's history.** The app was built, installed and driven on an
  Android emulator: onboarding → demo → banner + sample budget → exit → back to onboarding, with the
  same flow repeated in **airplane mode** (P-04). The instrumented suite passes **8/8** on the
  device, which incidentally executed two paths that had never run anywhere: the **v1→v2 and v2→v3
  Room migrations** against real SQLite (DB-003 was previously taken on trust), and **SEC-002's
  Keystore PIN round trip** (every JVM test of it uses a fake `Mac`).

### [0.2.3] — Issue 2.3: Quick-setup seeds (income/rent/savings)  (2026-07-27)

- **Implemented:** the quick-setup step now *does* something. The three figures onboarding has been
  collecting since 2.1 become a budget, an emergency-fund target and the app's first real financial
  rows (FR-ONB-002, FR-BUD-001, FR-TXN-006; MNY-001, MNY-002, TIM-001, TIM-002, DB-003,
  AI-ARC-003, AI-ARC-006, P-02, P-03, P-04, P-08).
  - **The project's first engine.** `:domain:engines:quicksetup` is pure Kotlin (ARC-002) and turns
    income / rent / savings into needs-wants-savings envelopes, a 3-month emergency-fund target and
    an obligation verdict, citing `RULE-50-30-20`, `RULE-EMERG-FIRST`, `RULE-RUNWAY-M` and
    `RULE-EMI-40` by id **and version** in its provenance.
  - **The budget tells the truth when it does not fit.** RULE-50-30-20's metro flex raises the needs
    band to cover a high rent and takes it from *wants* — never from the savings floor. Past the 60%
    ceiling the envelope is left visibly short of the rent, beside a hard-fail verdict and a sentence
    saying so, rather than being balanced on paper by cancelling the user's saving.
  - **Envelopes total the income exactly**, proven over 800 seeded awkward amounts, because the
    split goes through `Money.allocate`'s largest-remainder algorithm rather than three separate
    divisions. There is no `Double` anywhere on the path; rates are integer basis points.
  - **Nothing is fabricated if skipped**, enforced independently at three layers: a blank field
    produces no engine output, Skip never calls the repository, and an empty plan writes no row at
    all — not even a profile.
  - **`budget` and `recurring_rule` arrive as schema v3**, additive only, along with the app's
    **first `profile` row** — every table is `profile_id`-scoped and until now nothing had ever
    written one. Ids are derived rather than generated, so re-running onboarding updates the same
    rows instead of duplicating them (P-08 applied to storage), and the whole write is one
    transaction.
  - **`EngineProvenance` lands in `:core:model`** (AI-ARC-003): engine id, version, instant and the
    rules that fired — the type every future engine result will carry.
  - **The dashboard's spending split is real.** `SAMPLE_NEEDS/WANTS/SAVINGS_MINOR` are gone,
    replaced by the persisted budget observed as a Flow. Safe-to-Spend and net worth remain
    placeholders — they need the engines issues 5.1/5.2 own. Skipping quick setup now shows an
    empty state rather than a bar of zeroes, because a zeroed chart is a number the app invented.
- **Deviations on record:** two, both deliberate.
  [ADR-0004](docs/adr/0004-quick-setup-persists-budgets-and-recurring-rules.md) — issue 2.3 defines
  `budget` and `recurring_rule` columns owned by issues 4.4 and 3.7, with every forward-looking
  foreign key nullable and a list of what each later issue is expected to add.
  [ADR-0005](docs/adr/0005-quick-setup-thresholds-deferred-rulebook-loader.md) — the four rulebook
  thresholds are typed Kotlin constants rather than rows loaded from `ai/`, because nothing in the
  app loads `ai/` yet; a drift test reads the real rulebook and fails the build if they diverge.
- **Tests:** 536 passed, 0 skipped (was 285). +251: the engine's golden, boundary, property and
  determinism suites, the rulebook drift guard, the repository against a real SQLite engine, the
  onboarding derive/persist/skip paths, and the dashboard's budget states.
- **Gates proven to fail before being trusted.** This project has shipped a vacuous gate before
  (audit G-01), so both new ones were made red on purpose first: a one-point threshold change turned
  the drift test red, and temporarily uncovered code turned `koverVerify` red on the new module.
- **Not verified:** nothing has run on a device — `adb` is not installed and no AVD exists, so
  `/run`, `/verify` and `connectedDebugAndroidTest` are **blocked, not skipped**. The 2 → 3 migration
  is proven structurally on the JVM but has never executed against real SQLite, and the repository
  is tested on unencrypted in-memory Room because SQLCipher needs a device.
  [The tracker](docs/issues/2.3-quick-setup-seeds-income-rent-savings-tracker.md) lists every gap.

### [0.2.2] — Issue 2.2: Biometric/PIN app lock (BiometricPrompt)  (2026-07-26)

- **Implemented:** the app lock — BiometricPrompt class 3 with a PIN fallback, gating the whole app
  on cold start and after an idle timeout (SEC-002, SEC-003, §23.1, FR-SET-001, FR-ONB-001 step 3;
  P-01, P-04, P-08, TIM-001, ARC-003, ARC-004, ARC-005, DB-003, §21.6).
  - **Fail-secure by default, not by code path.** The session flag starts closed and the UI state
    starts `CHECKING`, so a wrong PIN, a cancelled prompt, a Keystore that has stopped working and
    an unreadable settings file all leave the app locked without any branch having to say so. 21
    cases in `AppLockViewModelTest` assert the *negative* — that the session did not open.
  - **SEC-002's schedule, exactly.** 5 failures → 30 s, doubling, capped at an hour, pinned attempt
    by attempt. The counter lives in Proto DataStore, so force-stopping the app — the first thing
    anyone with a stolen phone would try — does not clear a lockout.
  - **A PIN cannot be brute-forced offline.** Four to six digits is a million candidates; no hash
    survives that. The credential is `salt || HMAC-SHA256(salt || pin)` under a key generated inside
    the Android Keystore, so the file on disk gives an attacker no oracle to test guesses against.
    Tink only (SEC-003). The PIN is never written to `SavedStateHandle`, which reaches disk.
  - **The lock gates the encrypted store with an assertion, not a promise.** The Hilt provider for
    `CfoDatabase` refuses to hand it to feature code while locked. The audit log is the single
    exemption — it must record *refused* unlocks — and that exemption is a Hilt qualifier
    (`@AuditDatabase`) visible at every injection site rather than a comment.
  - **`audit_log` arrives as schema v2** (§21.6) with the first real migration, additive only. Four
    columns, holding closed-enum codes and a timestamp: there is nowhere in the table to put PII,
    and a test asserts that against the real SQLite columns. First class in `:data:repository`.
  - **Onboarding gains its SECURITY step**, where [ADR-0002](docs/adr/0002-onboarding-step-order.md)
    said it would go — after the profile, and skippable.
- **Deviation on record:** SEC-001's "wrapped by a Keystore key **requiring user authentication**"
  clause is deliberately left open. Binding the key to device auth would permanently destroy the
  database if the user ever removed their lock screen, and there is no server copy.
  [ADR-0003](docs/adr/0003-app-lock-gate-and-deferred-user-auth-key.md) records that, and the
  related limit that the database gate is checked once per process rather than on every access.
  **SEC-001 is not closed by this issue.**
- **Tests:** 285 passed, 0 skipped (was 202). +83: the SEC-002 schedule, the PIN verifier, the
  app-lock store, the audit repository against a real SQLite engine, the fail-secure matrix, and the
  onboarding security step.
- **Not verified:** `BiometricPrompt` and the real Android Keystore have **never been executed** —
  no device or emulator exists on this machine, so `connectedDebugAndroidTest`, `/run` and `/verify`
  are blocked rather than skipped. The v1 → v2 migration is proven structurally on the JVM and its
  DDL diffed by hand against Room's generated SQL, but has not been run.
  [The tracker](docs/issues/2.2-biometric-pin-app-lock-biometricprompt-tracker.md) lists every gap.

### [0.2.1] — Issue 2.1: 4-step onboarding flow  (2026-07-25)

- **Implemented:** the first-run flow — welcome & privacy pledge → SMS-parsing opt-in → profile,
  currency and time zone → optional quick setup (FR-ONB-001, FR-ONB-002, FR-ONB-003; P-01, P-04,
  TIM-001, MNY-001, ARC-001, ARC-004). A new install now opens on onboarding and every launch after
  it on the dashboard.
  - **The profile time zone is finally written.** `ProfileZoneProvider` has been wired to `Clock`
    since issue 1.10, but nothing ever set the setting it reads, so every day boundary and month
    rollover in the app resolved in the *device* zone by fallback. Onboarding closes that seam.
  - **The consent ledger has its first caller** (P-01). The SMS opt-in is its own step, default
    **off**, with FR-ONB-003's required wording — what is read, that parsing is on-device only, and
    that it can be skipped. Declining writes **nothing**: a revocation record for a consent never
    granted would be a false entry in a ledger whose whole purpose is answering "since when?"
    truthfully.
  - **One atomic write.** `SettingsStore.completeOnboarding` writes the profile, the seeds and the
    completion timestamp in a single `updateData`. Writing them separately could mark the app
    onboarded with no time zone — after which every date resolves wrongly and nothing ever asks
    again. Nothing reaches disk until Finish, so an abandoned onboarding leaves no partial profile.
  - **`MoneyFormatter.parse`** (MNY-001) — the quick-setup amounts are the first the user types.
    Exact via `BigInteger`, never `Double`: `"0.07".toDouble() * 100` is `7.000000000000001`.
    Anything it cannot represent exactly — extra precision, out of `Long` range, a Devanagari digit
    — returns `null` rather than an amount that is nearly right.
- **Deviation on record:** the four steps are not FR-ONB-001's literal ordering — its steps 3
  (security) and 4 (first account) belong to issues 2.2 and 2.5, which this issue does not depend
  on. [ADR-0002](docs/adr/0002-onboarding-step-order.md) records it and fixes where those steps
  insert. **FR-ONB-001 is not closed by this issue alone.**
- **Tests:** 202 passed, 0 skipped (was 156). +31 JVM tests in `:feature:onboarding`, +10 money
  parsing, +5 settings-store, +3 `MainViewModel`. Plus one instrumented test driving the flow
  against real DataStore on a device, and an emulator pass covering fresh install, relaunch,
  process death, airplane mode, dark mode and a 200% font setting.

### Fixed
- **Three defects the tests and the device found, not review.** A consent row whose *label* was not
  tappable — only the switch was, which is the most common complaint about settings rows and costs a
  user with a motor impairment several attempts; the row is now `toggleable` with `Role.Switch`, one
  announced control with a full-width target. A duplicated Indian time zone: the emulator (and many
  real devices) report the legacy alias `Asia/Calcutta`, so `distinct()` on ids left `Asia/Calcutta`
  and `Asia/Kolkata` sitting in the list as two apparently different answers — in this app's primary
  market, on the one screen where the choice must be unambiguous; de-duplication is now by
  `ZoneId.rules`. And a device-only race where the instrumented test read the store before the write
  landed: on a real I/O dispatcher the composition goes idle while the file is untouched, which the
  JVM twin's unconfined dispatcher can never reveal.
- `UnusedPrivateMember` now ignores `@Preview` in `detekt.yml` — a preview has no caller by design,
  and the alternative was making it public, which would put it in the module's API.

---

## [Unreleased]

> **Epic 1 (Foundation & Core Platform) is complete** — issues 1.1–1.10. The app builds, has a
> themed shell with typed navigation, an encrypted database, a consent ledger, and five custom lint
> rules plus a coverage gate and screenshot tests that all fail when they should. What it has never
> had is a **CI run** (no git remote) or a **device run** (no emulator).

### Added
- **App shell, typed navigation and the Hilt object graph** (issue 1.10; ARC-001, ARC-003, ARC-004,
  ARC-006). `MainActivity` now hosts a real `NavHost` inside `CfoTheme` and edge-to-edge insets,
  instead of a placeholder `Text`. Routes are `@Serializable` objects in `:app` — the only module
  that knows more than one feature exists — so a destination cannot be reached with a mistyped
  string and features never import each other. `CoreModule` binds dispatchers, an application
  `CoroutineScope` (the injectable alternative that makes `GlobalScope` unnecessary), the `Clock`,
  and the stores from issues 1.6 and 1.9; a missing binding now fails at compile time.
- **The dashboard as the ARC-004 reference screen** — `DashboardUiState` (immutable),
  `DashboardEvent` (sealed), `DashboardViewModel` (one `StateFlow` out, one `onEvent` in), with
  Turbine tests asserting the whole state sequence including loading. Figures are placeholders until
  issues 5.1/5.2, but the shape is what every later screen copies. Plus a minimal transactions
  destination, so cross-feature navigation is exercised rather than asserted.
- **The `Clock` finally reads the user's time zone.** `ProfileZoneProvider` bridges the synchronous
  `Clock.zone()` that every engine depends on to the `Flow` the setting arrives on, closing the seam
  issue 1.3 deliberately left open. Every failure — unset, unreadable, or an unparseable zone id —
  falls back to the device zone, because an exception out of `Clock.zone()` would crash every engine
  at once.

### Fixed
- A test weakness found by deliberately breaking the zone fallback: three tests stayed green because
  a *crashed collector* leaves the same value they assert. Added a case that distinguishes "fell
  back" from "died", which now fails alongside them.
- `RoomDatabase` moved from `implementation` to `api` in `:core:database` — `CfoDatabase` extends
  it, so it is part of that module's public surface.
- **Settings and the per-feature consent ledger** (issue 1.9; SRS §21.3, P-01, TIM-001).
  `:core:datastore` now holds a real **Proto DataStore** — protobuf schema, generated types, no
  SharedPreferences anywhere. `ConsentStore` gates the four opt-in features (SMS parsing, market
  data, cloud LLM, cloud backup): each consent is **default off**, revocable, and carries the
  grant/revoke timestamps P-01 needs to answer "since when?" — a bare boolean cannot. Consents are
  keyed by a stable feature id, so adding one later is not a schema migration. `SettingsStore`
  carries the profile time zone (the seam `SystemClock` was built for in issue 1.3), currency,
  privacy blur and theme. Reads are `Flow` — a consent read once at startup could not be revoked —
  and everything runs on injected dispatchers, returning `Result<_, AppError>` rather than throwing.
  A corrupt file is `Err(Storage)`, never a silent reset to defaults, because resetting a consent
  ledger discards decisions the user made without telling them. 13 JVM tests.

### Fixed
- **Six tests were passing over writes that were failing.** The stores return
  `Result<Unit, AppError>` instead of throwing (§21.6), so a test that ignores the return value
  cannot fail — and the first version of these tests ignored all of them. Every write is now
  asserted with `assertWritten()`, which surfaced the real problem: DataStore's default storage
  cannot replace an existing file on Windows (`Unable to rename …tmp`), so every second write
  errored. Storage switched to `OkioStorage`, whose `atomicMove` works on every host, keeping test
  and production on the same code path. Android was never affected; the silent-green tests were the
  actual defect.

### Changed
- Version catalog gains `protobuf`/`protobuf-javalite`/`protoc`, the `com.google.protobuf` Gradle
  plugin, and `datastore-core-okio`.
- **Design system: M3 theme, tokens, components and chart primitives** (issue 1.8; SRS §24, §21.6,
  ACC-*). `:core:designsystem` now holds the colour/type/dimension tokens from `docs/Design.md`
  (seed `#00696E`, plus the `positive`/`negative`/`warning` roles Material has no slot for),
  `CfoTheme`, five components (`CfoCard`, `CfoButton`, `CfoSecondaryButton`, `CfoListRow`,
  `CfoAmountText`) and two chart primitives (`CfoProportionBar`, `CfoSparkline`). Accessibility is
  built in rather than reviewed in: 48dp is a token every clickable applies, charts **require** a
  `contentDescription`, and `CfoAmountText` always renders the sign so debit/credit never depends on
  colour alone (P-02). Copy stays out of the module — text and descriptions are parameters, because
  the wording belongs in the calling feature's `strings.xml`.
- **Screenshot tests that run without a device** (closes governance audit **G-02**). Paparazzi
  renders light, dark and 200%-font baselines on the JVM — the first visual coverage this project
  has had, and with no emulator the only way anyone sees what the UI looks like. The CI step,
  `/pre-merge` step 3 and the `settings.json` allowlist entries removed in issue 1.5 are restored.
  Proved by overwriting a baseline and watching `verifyPaparazziDebug` go red.
- **WCAG AA contrast asserted in a unit test** (partly closes **G-24**). Every token pair in both
  themes is computed against the 4.5:1 threshold, including amount colours on their own surfaces —
  turning "accessibility scan passes" from a claim in the DoD into arithmetic that runs on every
  build. The suite includes a deliberately failing pair so the formula itself is proved able to fail.

### Fixed
- **Amounts wrapped mid-number at 200% font** — `-₹2,450.00` broke with the final `0` on the next
  line, which reads as a different number. Caught by the new 200%-font screenshot on its first run;
  `CfoAmountText` is now `maxLines = 1, softWrap = false`, and `CfoListRow` lets the label wrap
  instead of the figure.

### Changed
- Paparazzi is pinned to **2.0.0-alpha02**: the 1.3.5 stable hooks a Gradle internal that moved and
  cannot run on Gradle 8.13. It is test-only tooling that ships in no APK; revisit when 2.0 is stable.
- `CfoHardcodedUiString` (issue 1.5) now covers `:core:designsystem` as well as `:feature:*`,
  closing the follow-up recorded in ADR-0001.
- `config/detekt/detekt.yml`: `MagicNumber` is excluded for `**/theme/**` and test sources. A design
  token file is the one place a literal is correct — that is what §21.6 means by "every colour from
  theme tokens" — and flagging it there would only teach contributors to suppress the rule.
- **Migration test harness — DB-003 enforced on every build, with no device** (issue 1.7; §21.5).
  Room's `MigrationTestHelper` needs hardware this project does not have, which would have left
  "destructive migrations are forbidden" enforced by nobody. But the exported schema JSON is data,
  and `androidx.room:room-migration` parses it in plain Kotlin — so `MigrationSafetyTest` now checks
  the structural half of DB-003 in ordinary unit tests: no table or column may be removed, no column
  may change SQL affinity, and no nullable column may become `NOT NULL` (which passes on an empty
  database and fails on real data). Additive changes stay allowed. It also asserts the schema-level
  money/time invariants where the data actually lives — `*_minor` and `*_utc_millis` are INTEGER,
  `*_iso_date` is TEXT, every table has soft delete and profile scoping — and that fixtures are
  contiguous from v1 and include the declared version, so a bump cannot skip its schema silently.
  The row-level half (`MigrationRoundTripTest`, device-only) and the per-version procedure are in
  `core/database/MIGRATIONS.md`.

  Proved by dropping a real column from `AccountEntity` and letting KSP export a genuine v2: the
  guard failed with *"migrating 1 -> 2 would destroy data: account: column 'current_balance_minor'
  was removed"*. Two things surfaced while doing that — a hand-edited schema JSON cannot fake a
  destructive change (KSP regenerates it), and the test task stayed `UP-TO-DATE` when only a schema
  file changed, so `schemas/` is now a declared test input; without that the guard could have
  reported a stale pass.
- **Encrypted persistence core** (issue 1.6; SRS §20/§23, SEC-003, DB-003, P-01/P-04): `:core:database`
  now holds `CfoDatabase` (Room v1) over **SQLCipher**, with the passphrase wrapped by a
  Keystore-backed Tink AEAD — a random passphrase is generated once and only its ciphertext touches
  disk, so the key that unwraps it never leaves the TEE. Base schema is the four tables the issue
  names — `profile`, `account`, `transactions`, `category` — each carrying the invariants that apply
  to every table: amounts as `Long` paise (MNY-001), instants as UTC epoch millis with user-picked
  dates as ISO strings (TIM-001/002), soft delete via `deleted_at_utc_millis`, and per-profile
  scoping on every row and every query. Schema exported to `core/database/schemas/` so issue 1.7's
  migration tests have a fixture. No `fallbackToDestructiveMigration` (DB-003) — with no server
  copy, a missing migration must fail loudly rather than drop tables. 12 unit tests cover the key
  path, including that an unwrap failure surfaces as an error rather than silently minting a new
  passphrase, which would present an unopenable database as an empty one.

### Known gaps
- **The encrypted round-trip is unproven.** SQLCipher and the Keystore exist only on a device, and
  this machine has none (`adb devices` empty, no AVD installed). `EncryptedDatabaseTest` — the
  ciphertext-on-disk check, the read-back and the reopen-with-the-same-key check — is written and
  compiles but **has never executed**. One `connectedDebugAndroidTest` run settles it.
- Database re-key (`PRAGMA rekey`) is intentionally not implemented: `rotateWithPrevious()` supplies
  both keys and is tested, but shipping an untested path that rewrites the whole encrypted file
  would be worse than the gap. It belongs with issue 11.1.
- **Custom lint: five rules that now fail the build** (issue 1.5 / task 1.1.5; SRS §21.3/§21.4/§21.6,
  MNY-001, TIM-001, ARC-006, P-01 — closes governance audit G-03). A new `:lint` module ships
  `CfoMoneyAsFloatingPoint` (a floating-point declaration with a monetary name), `CfoWallClockInDomain`
  (`System.currentTimeMillis()`/`now()` inside `:domain:*` or `:core:model`, with `:core:common`'s
  `SystemClock` exempt as the one sanctioned wall-clock read), `CfoGlobalScope`, `CfoHardcodedUiString`
  (a literal in a `:feature:*` `Text(...)`, `@Preview` exempt) and `CfoPiiInLogs` (a log line naming
  money or personal data). All at severity **error**, wired to every module — Android *and*
  pure-Kotlin, via the standalone `com.android.lint` plugin, because `Money` lives in a `java-library`
  module that lint would otherwise never visit — with **no baseline**, so nothing is grandfathered.
  Each rule was proved by seeding a real violation in a real module and watching the build go red,
  not by the fixture suite alone. 14 fixture tests cover every rule in both directions.
- **ADR-0001** — the repository's first architecture decision record: why `:lint` sits outside the
  §21.2 module graph, and the exact money/PII identifier lists with the false-positive stance behind
  them (partly closes audit G-12).

### Changed
- **`CLAUDE.md` now says "lint-enforced" because it is.** The `GlobalScope`, wall-clock,
  PII-logging and hardcoded-string entries named the rules as review-blocking with enforcement
  "landing in 1.1.5"; each now names the detector that blocks it.
- `config/detekt/detekt.yml`: `style.ReturnCount.excludeGuardClauses: true` — guard clauses are
  idiomatic Kotlin and the lint detectors are built from them; the rule's real target, tangled
  mid-function returns, still counts.

### Fixed
- Four `ExperimentalCoroutinesApi` opt-in warnings in `DispatcherProviderTest` (from issue 1.3),
  which earlier runs had missed because the compile task was up-to-date.
- **`Result<T, AppError>` error model** (issue 1.4 / task 1.1.4; SRS §21.6): the typed return every
  engine and repository will use, so no exception crosses a layer boundary and absence is modelled
  rather than nulled. `sealed interface Result` with `Ok`/`Err` and short-circuiting `map`,
  `flatMap`, `mapError`, `fold`, `getOrElse`, `getOrNull`, `errorOrNull`, `onOk`, `onErr` — a `when`
  over it is exhaustive with no `else`. `AppError` is a sealed hierarchy (`Validation`, `NotFound`,
  `Storage`, `Network(retryable)`, `Crypto`, `Unexpected`) carrying a stable `code` for the UI to
  map to `strings.xml` plus a non-localised fallback message. `runCatchingToResult { }` is the
  single sanctioned catch site: it converts I/O and crypto failures to `Err`, and deliberately
  rethrows `CancellationException` (swallowing it breaks structured concurrency, ARC-006),
  `IllegalState`/`IllegalArgumentException` (a failed `require`/`check` is a bug and §21.6 reserves
  crashes for those), and JVM `Error`s. **No PII by construction (P-01):** the only path from a
  `Throwable` to an `AppError` keeps the exception's class name and discards its message, which
  routinely carries paths, tokens or row data. 29 new tests; `:core:common` holds at 100% coverage.
- **Injected `Clock` + `DispatcherProvider`** (issue 1.3 / task 1.1.3; SRS §21.4 TIM-001/TIM-002,
  §21.2 ARC-006): `:core:common` now owns the time and concurrency seams every engine will inject.
  `Clock` answers `nowUtcMillis()` / `zone()` / `today()` in the **profile time zone** — so a spend
  at 23:30 IST belongs to that day's budget even though UTC has rolled over — with `startOfDay`,
  `endOfDay`, `isSameProfileDay` and `toProfileDate` as extensions, and `SystemClock` as the single
  sanctioned `System.currentTimeMillis()` call site in the codebase (TIM-001). The profile zone is
  read through a provider lambda on every call, which is the seam Proto DataStore settings (issue
  1.9) will plug into. `DispatcherProvider` exposes Main/IO/Default so no call site names
  `Dispatchers.IO` inline and `GlobalScope` is never needed (ARC-006). Uses `java.time` (native at
  minSdk 26, NFR-008) rather than adding `kotlinx-datetime`. `FakeClock` and `TestDispatchers` ship
  from a **`testFixtures`** artifact so later modules reuse one set of doubles. 20 tests covering the
  IST day/month rollover, UTC-midnight straddling, a DST transition, and virtual-time coroutines;
  `:core:common` measures **100%** line coverage.

### Verified
- The **85% coverage floor now bites a second module.** Issue 1.2 could only prove the gate on
  `:core:model`; `:core:common` measured 89.66% before the gaps were closed, so the floor is
  demonstrably measuring real code rather than passing vacuously. Also learned: Kover counts
  `testFixtures` classes, so published fixtures need their own tests.
- **`Money` value class** (issue 1.2 / task 1.1.2; SRS §21.4, MNY-001/MNY-002, NFR-012): the single
  monetary type, `Long` minor units (paise) end-to-end — `@JvmInline value class Money(val minor: Long)`
  in `:core:model` with overflow-checked `plus`/`minus`/`times` (`Math.*Exact`, so a wrong answer
  throws instead of wrapping), `percentOf(bps: Int)` using **HALF_EVEN** banker's rounding on integer
  basis points (MNY-002 — no `Double` rate), and `split(n)`/`allocate(weights)` using the
  largest-remainder method so parts **sum exactly** to the original, for refunds as well as payments.
  Plus `MoneyFormatter` rendering Indian 2,2,3 digit grouping (₹1,23,456.78) with the grouping written
  out rather than delegated, so output does not drift with JDK or Android locale data. 35 tests:
  the T1–T8 table, a seeded property sweep (P-08) over ~41 000 split combinations, and the Long
  extremes. No `Double`/`Float` touches a monetary value anywhere.
- **A coverage gate that actually blocks** (governance audit G-01): `configureCoverage()` in the
  `cfo.kotlin.library` convention plugin gives `koverVerify` its first real rules — line coverage
  ≥ 85% on pure-Kotlin modules and **100% on `:core:model`** (money math). Previously Kover was
  applied with zero rules and passed at any coverage, including 0%. Proved to bite twice before
  merging: an impossible 101% bound failed the build, and deleting one test dropped the measurement
  to 77.5% and failed it again.
- **Gradle multi-module skeleton** (issue 1.1; SRS §21.2/§21.3, ARC-001/ARC-002): the module graph
  made real and building green — `:app`; `:core:{model,common,database,datastore,network,crypto,
  designsystem}`; `:domain:engines:forecast` + `:domain:usecase`; `:data:repository`;
  `:ml:{ocr,llm}`; `:feature:{dashboard,onboarding,transactions}`; `:sync:backup`; `:widget`.
  Dependencies are one-way `feature → domain → data/core` (ARC-001); `:core:model`/`:domain:*` are
  pure Kotlin/JVM with a **Gradle-enforced ARC-002 guard** that fails the build (with a clear
  message) if an Android plugin is applied — proved by a Gradle TestKit test. A single version
  catalog (`gradle/libs.versions.toml`) pins the §21.3 stack (AGP 8.11 / Kotlin 2.1 / Gradle 8.13,
  compileSdk 36); `build-logic/` convention plugins (`cfo.kotlin.library`,
  `cfo.android.{library,application,compose,feature}`, `cfo.hilt`) keep module scripts tiny with
  shared JVM-17 + ktlint/detekt/Kover config. CI (`.github/workflows/ci.yml`) now runs the real
  tasks (convention/ARC-002 tests · ktlint/detekt/lint · unit + coverage · assemble) on
  `dev`/`stage`/`main`.
- **Reference-style backlog** (`docs/superpowers/specs/`): a planning-grade design spec
  (`2026-07-17-ai-personal-cfo-design.md`, 14 §-sections distilling the SRS) and its
  machine-readable index (`2026-07-17-issues.csv`) — **13 epics, 85 issues** mapped to the SRS
  roadmap (§26) and traceability (§28).
- **Full issue backlog** (`docs/issues/`): one rich `<id>-<slug>.md` + `<id>-<slug>-tracker.md`
  per issue (170 files), each with acceptance criteria, a label-driven Skill Rules table, guiding
  principles, three-tier workflow rules, a Definition-of-Done gate, and a Verification-Log tracker.
- **Issue-docs generator** (`scripts/gen_issue_docs.py`): single source of truth holding all 85
  issue records; emits the CSV + every issue/tracker file, idempotently.
- **Reference-format templates**: `_ISSUE_TEMPLATE.md` / `_TRACKER_TEMPLATE.md` rewritten to match
  the generated files.
- **Android use-case dev skills** (19, global `~/.claude/skills/`): `compose-ui`,
  `room-and-migrations`, `hilt-di`, `gradle-modules`, `ml-kit-ocr`, `on-device-llm`,
  `workmanager-jobs`, `datastore-consent`, `keystore-crypto`, `biometric-auth`,
  `retrofit-networking`, `glance-widget`, `paparazzi-screenshot-testing`, `proguard-r8-release`,
  `kotlin-multiplatform`, `compose-navigation`, `compose-performance`, `edge-to-edge`,
  `kotlin-coroutines-flow` — each a project-tailored playbook for the pinned §21.3 stack, citing the
  binding rules (ARC/AI-ARC/MNY/TIM/SEC, P-01…P-08). The last six are grounded in the official
  Google Android (R8 audit, Navigation 3, edge-to-edge, Compose performance / Baseline Profiles) and
  JetBrains/Kotlin (KMP, coroutines/Flow) agent-skill guidance rather than copied from third-party
  registries (skills.sh's mobile catalogue is React-Native/Firebase-centric and its cloud-auth skills
  conflict with P-01/P-04); reconciled line-by-line against those official SKILL.md sources, which
  also surfaced the coroutines/Flow-discipline and Compose-recomposition gaps the last three close.
  Wired into the generator's `LABEL_SKILLS` so each surfaces on the relevant issues — including
  crypto/backup, auth, market-data, integration, widget, designsystem, testing, release, `kmp`,
  `dashboard`/`core`, and the previously-unmapped `app`/`di`/`lint`/`accounts`/`transactions`/
  `notifications` labels. The 7 universal skills (`test-driven-development`, `security-review`,
  `ci-cd-and-automation`, …) were already installed globally — left untouched. `check_issue_docs.py`
  asserts every referenced skill path resolves.
- **`/run` and `/verify` commands** (`.claude/commands/`): the "real gate" (§9) — build + install +
  launch on an emulator, then drive the changed flow (incl. an airplane-mode leg) and confirm it works.
- **Top-level project docs** (`docs/`): `PRD.md`, `Architecture.md`, `Rules.md`, `phase.md`,
  `Design.md`, `memory.md` — thin, cross-referenced views of the SRS / design spec / `CLAUDE.md`
  for fast onboarding (the SRS and `CLAUDE.md` remain the sources of truth). `Design.md` proposes
  the initial Material 3 tokens (seed `#00696E`, Roboto, M3 type scale) pending issue 1.8;
  `memory.md` is the living progress tracker.

### Changed
- **Branch model → GitFlow-lite.** `CLAUDE.md` §7, `docs/issues/00-issue-workflow.md` (steps 8/10),
  and the design spec §9 now specify `feature/* → dev → stage → main` (was trunk-based), with
  `main` (releases) and `stage` (live testing) as **protected**, PR-only, CI-gated branches and
  `dev` as the integration branch.
- **`docs/features/`** repositioned as the deeper **sub-task** layer the issues link down into
  (kept; the 13-epic CSV is now the canonical epic/issue index). `00-issue-workflow.md` and
  `docs/features/README.md` point at the new spec + CSV.
- **Documentation no longer asserts gates that are not wired** (governance audit G-02/G-03/G-04;
  §21.6). `CLAUDE.md` now marks the `GlobalScope` (ARC-006), `System.currentTimeMillis()` (TIM-001)
  and PII/amount-logging bans as **review-blocking today, lint-enforced with task 1.1.5** instead of
  claiming an existing lint rule; detekt now sets `complexity.LongMethod.threshold: 40` so the
  documented 40-line limit is real (detekt's default 60 left it unenforced).

### Removed
- Superseded feature-level `docs/issues/1.1-project-skeleton.md` + tracker (replaced by issues
  1.1–1.5, which link down to the existing `docs/features/1.1-project-skeleton/tasks/` files).
- Dead `verifyPaparazzi*` invocations from `/pre-merge` and `.claude/settings.json` (audit G-02) —
  the task does not exist until Paparazzi lands with issue 1.8, so the DoD command was unrunnable.

## [0.1.0] — Epic 0: Foundations & AI blueprint  (2026-07-17)

### Added
- **AI subsystem files** the app loads at runtime (`ai/`): layered-pipeline architecture,
  Insight Orchestrator workflow + engine registry, RULE-KB rulebook + Financial Order of
  Operations, chat tool registry, LLM system prompt + numeric guardrail, and the
  classification / market-signal / tax / seasonality / vehicle-maintenance knowledge bases.
  (SRS §7, §8, §19, §29, §30, §36, §38.)
- **Agent development config** for writing & maintaining the code: `CLAUDE.md` (binding rules),
  project skills (`new-engine`, `add-rulebook-rule`, `money-time-audit`), slash-command
  workflows (`/new-feature`, `/pre-merge`), CI pipeline, PR template, `ENGINE.md` + ADR
  templates, `.editorconfig`. (SRS §4.2, §21.)
- **Issue workflow** (`docs/issues/`): master workflow + issue/tracker templates for driving
  backlog issues from the SRS.
- `VERSION` and this changelog.

### Notes
- No application (Kotlin/Gradle) code yet — this release is the spec-faithful scaffolding and
  the AI/agent configuration that the build will be written against.
