# ADR-0016 — Nature fires on account *type*, only the override is stored, and true spend ships understated

- **Status:** accepted, with named triggers to revisit
- **Date:** 2026-08-10
- **Deciders:** Harish G (solo), implementing issue 4.3
- **Refs:** CLAUDE.md §6, SRS §8.3, §8.3.1, §14, §21.5, §29 (AI-ARC-006),
  [ADR-0005](0005-quick-setup-thresholds-deferred-rulebook-loader.md),
  [ADR-0012](0012-future-dated-posting.md),
  [ADR-0015](0015-stage-1-classification-tiers-and-the-kb-mirror.md)

## Context

§8.3.1 is a six-step decision order, and **three of its steps name data this codebase does not have**:

| Step | §8.3.1 says | This build has |
|---|---|---|
| 1 | loan account → LIABILITY, principal/interest split from `loan_amortization_rows` | an `AccountType.LOAN`; no amortisation table |
| 2 | transfer to an "investment/**goal-linked**" account | `AccountType.INVESTMENT`; no goals table |
| 3 | "creates/updates an **asset record** (gold, vehicle, property, holdings)" | `AccountType.GOLD`/`PROPERTY`/`VEHICLE`/`CRYPTO`; no holdings table |

The remaining three are buildable today: past overrides (step 4), the category's nature (step 5, which
issue 4.1 seeded), and the median comparison (step 6).

Two further questions come with them. §8.3 says nature is "auto-assigned, user-correctable, learned",
so a correction has to be **stored** — and the natural next question is whether the *derived* value
should be stored too. And §8.3 defines `trueSpend = NEED + WANT + interest/fees`, whose third term
needs the split step 1 cannot make.

## Decision

### 1. Steps 1–3 fire on the account's *type*

An account's type is a fact the user entered on the accounts screen. It is a weaker signal than an
amortisation row (it cannot split principal from interest) and a stronger one than any inference from
a payee string — which is why steps 1–3 are worth full confidence and outrank everything below them.

**Step 1 checks both sides of the movement; steps 2–3 check both sides of a transfer.** An EMI is two
rows (issue 3.2's two-leg transfer), and labelling only the loan-side leg would leave the leg the user
actually looks at — the one leaving their bank — reading as ordinary spending. The first draft had
steps 2–3 read only the counterpart, on the reasoning that money *arriving* in an investment account
is a conversion only when it came from the user's own account. The reasoning was right and the place
was wrong: `counterpartAccountType != null` **is** "this is a transfer leg", so a dividend is an
`INCOME` row that never reaches the branch. The golden file caught it, because it fixes the cited rule
and not only the answer.

**What this gives up, written down:** buying gold with cash and categorising it Shopping is a WANT
until the user overrides it. There is no asset record to notice.

### 2. Only the user's override is stored; everything else is derived on read

`transactions.nature` (schema v13) holds **the correction and nothing else**. `NULL` means "whatever
§8.3.1 currently says", not "unknown".

Storing the resolved nature instead would need a backfill of every existing row through the engine in
the migration, and then a recompute job for every future rulebook edit — the shape that already bit
the net-worth series in issue 3.10 and had to be repaired in `repairStaleHistory`. It would also make
the column ambiguous: there would be no way to tell a value the engine wrote from one the user chose,
and **step 4 learns from exactly that distinction**.

The cost is that aggregating by nature is a Kotlin fold rather than a SQL `GROUP BY`. The monthly
breakdown is one query for the rows plus one for the profile's overrides, which is two statements for
a month — cheap enough that the alternative would be optimising ahead of a measurement.

The migration is one `ADD COLUMN` with **no backfill**, and `MigrationRoundTripTest` asserts the
column arrives *empty* rather than merely present: a helpfully backfilled guess would pass a shape
check and quietly convert five engine-derived guesses into five user decisions.

### 3. `trueSpend` ships understated, and says so on screen

`liabilities` is reported in full and contributes **nothing** to `trueSpend`. §8.3 wants the interest
half, which needs decision 1's missing split.

Excluding all of it understates true spend by the interest; including all of it would count debt
principal as consumption, which is what §8.3 separates LIABILITY out to prevent. Understating is the
conservative direction — but conservative is not the same as honest, so **the dashboard renders the
caveat** ("Loan repayments are shown separately and aren't counted as spending yet") rather than
leaving it in ENGINE.md where no user will read it. `NatureBreakdownTest` asserts the shortfall as
written, so the day the split lands that test fails and points at the reason.

The alternative — shipping no aggregation at all until the split exists — was rejected because §14
and Safe-to-Spend are issues away, and a taxonomy with no consumer is the failure this repo keeps
re-learning (the `merchant` column in 3.1, the `category` table until 4.1, `merchant_rules` until 4.2).

### 4. Step 6 is a flag, not a prompt

§8.3.1's context modifier ends "prompt once, remember", and the knowledge base routes the result to a
weekly digest that does not exist. So it lowers the confidence below the floor and the detail sheet
says the app is unsure. **It never changes the nature** — an implementation that flipped NEED to WANT
would be the app answering the question it was supposed to ask, and §8.3 is explicit that the flag
never blocks a save.

### 5. The knowledge base stays a Kotlin mirror

ADR-0015's reasoning is unchanged and this is a third disjoint section of the same file
(`nature_classification`), not a second copy of an existing one. `NatureKbDriftTest` compares the six
steps **as an ordered list**, because a reordering is the most consequential edit possible here: it
silently changes which of two conflicting signals wins, and every nature stays plausible either way.

## Triggers to revisit

- **`loan_amortization_rows`** — the moment it exists, step 1 splits and `trueSpend` gains its third
  term. `NatureBreakdownTest`'s shortfall assertion is the tripwire.
- **A goals table or a holdings table** — steps 2 and 3 gain the signals §8.3.1 actually names, and
  the account-type sets in `NatureRules` become a fallback rather than the rule.
- **The weekly digest** — step 6 becomes the prompt §8.3.1 describes.
- **A nature filter or bulk re-nature** on the transactions list — that is the point at which
  classifying a whole page per scroll becomes the question decision 2 deferred, and where storing the
  resolved value might genuinely pay for its recompute job.

## Consequences

**Good.** Every transaction has a nature with a citable rule, an EMI and a gold purchase stop
inflating true spend, and the user can disagree with any of it in one tap — and undo that in one more,
which is only possible *because* the override is the sole stored value. No new table, no backfill, and
the schema moves by one nullable column.

**Bad.** §8.3.1 is implemented at three-fifths strength for its first three steps, and `trueSpend` is
knowingly short. Both are visible to anyone reading the SRS beside the code, which is what this record
is for. CLAUDE.md §6 is still, strictly, violated: the decision order lives in Kotlin and `ai/` is the
source of truth for a *test*.

**Neutral, and worth stating plainly.** The dashboard's two rows now show a plan and an outcome that
can disagree, and nothing yet explains the difference to the user. That is deliberate — explaining it
is the budget-review issue's job (4.6) — but a user who sees "Needs ₹349" under a budget that says
₹42,500 will wonder, and the answer today is "the month is young".

## Alternatives considered

**Wait for the tables §8.3.1 names.** Rejected: it would leave nature unimplemented across all of
Epic 4, and every engine in Epics 5 and 6 is specified in terms of it.

**Store the resolved nature.** Rejected — see decision 2. It is a derived value on disk with a
recompute job attached, and it destroys the signal step 4 learns from.

**Include LIABILITY in true spend.** Rejected: it counts debt principal as consumption, which is the
exact confusion §8.3 introduces the LIABILITY nature to remove.

**Infer an asset purchase from the category rather than the account.** Rejected: "Shopping" is where
a gold purchase and a pair of shoes both land, and treating the category as an asset signal would
exclude ordinary discretionary spending from true spend — an error in the direction that flatters the
user, which is the worst direction for this app to be wrong in.
