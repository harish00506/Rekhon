<!--
  Why:  CLAUDE.md §5 — any decision needs an ADR, and 13.5's AC2 makes the ADR the whole deliverable
        ("feature-flagged; ADR only in v1").
  What: issue 13.5 — how a business book is separated, what a GST-aware category looks like, and an
        audit of the three forward-compatibility promises §33 made on business mode's behalf.
  Result: a reader can see why business mode needs no new isolation mechanism, and which two of the
          SRS's own promises were never implemented — one of which is losing data every day.
  Changelog: 2026-10-09 — Created.
-->

# ADR-0073 — The business book is a second profile, and two forward-compatibility promises were broken

**Status:** Accepted · **Date:** 2026-10-09 · **Issue:** 13.5 · **SRS:** §3.3, §27, §33 · **Rules:** P-01, P-02, P-03, P-07

## Context

§3.3's third persona is Suresh: 29, freelance designer, income ₹40k–₹1.6L a month, **GST-registered**,
and his stated goal is to "separate business vs personal". §33's roadmap lists business mode as
Phase 5 expansion work.

This issue's acceptance criteria are unusually modest and say so: *"A separate business
profile/book is specified with isolation; a GST-aware category design is captured. Feature-flagged;
**ADR only in v1**."* The deliverable is the design, not an implementation — so the useful work is
deciding the shape, and then **checking whether the foundations the SRS claims already exist
actually do.**

§33 carries a forward-compatibility table: decisions supposedly made in v1 so that later features
inherit usable history. Business mode's row reads:

> **Business mode** — tags support business/personal from v1; GST fields captured by OCR are stored
> even before business reports exist

Both halves were measured. One is true. One is not.

## Decision

### 1 · A business book is a second profile, not a new mechanism

The isolation business mode needs is **exactly** the isolation household mode needs, and issue 13.1
already built it: `profile_id` on 36 tables, and `ProfileScopingTest` failing the build for any
`@Query` that touches a scoped table without filtering on it or declaring itself device-wide.

So business mode adds **no new isolation primitive**. A business book is a profile; the books are
separate because every query is already scoped; and the gate that keeps them separate is the one
that already runs on every build.

This is the whole reason this issue is "ADR only". There is nothing to build that 13.1 did not
build, and inventing a second mechanism — a `book_id` beside `profile_id`, say — would mean two
scoping rules, two gates, and two ways to get it wrong.

**It inherits the precondition unchanged.** ADR-0069 §5 records that fourteen id-keyed DAO queries
are safe today *only because one profile exists*. For household mode a leak between members is bad.
For business mode a leak between a person's business and personal books is **worse than bad**: it
is the thing a tax authority takes an interest in. `BusinessMode.IS_ENABLED` stays `false` until
those fourteen queries constrain `profile_id`.

### 2 · Until then, the separation is a tag — and that promise was kept

§33 says "tags support business/personal from v1". **Checked: true.** `tags` and `transaction_tags`
have existed since schema 1, a tag carries a free-form `name`, and nothing stops a user tagging
work expenses `business` today.

So the interim design is not something to build either. It is a convention, and
`BusinessMode.INTERIM_TAG` names it — so the day the structural version arrives, the migration has
exactly one string to look for rather than a guess about what users typed.

What the tag cannot do is what a profile does: it does not separate the **reports**. Safe-to-spend,
the forecast and the health score all run over one profile's whole ledger, so a freelancer's
business income still inflates their personal cash-flow picture. That is the gap business mode
closes, and it is a reporting problem rather than a storage one.

### 3 · A GST-aware category is a column on the taxonomy, not a parallel one

The design, captured per AC1:

| Addition | Where | Why there |
|---|---|---|
| `gst_rate_bps` | `category` | GST is a property of **what was bought**, which is what a category already is. Basis points (MNY-002), `null` meaning "not GST-rated" — which is different from zero-rated and must stay distinguishable |
| `input_credit_eligible` | `category` | Whether a registered business can reclaim the GST. Not derivable from the rate: a category can be taxed and still be blocked credit |
| `tax_minor` | `transactions` | The GST actually on **this** bill, in paise (MNY-001). The category's rate is the expectation; this is what the receipt said, and they differ often enough that storing only one would be wrong |

**One taxonomy, not two.** A separate `business_category` table was the alternative, and it is
rejected: a freelancer's "internet bill" is one category that is sometimes a business expense and
sometimes not, and duplicating the taxonomy would make every classification decision happen twice
and drift.

`nature` (Need/Want/Invest/Asset/Liability) stays as it is. A business expense is not a sixth
nature — it is a personal-or-business *attribution*, which is the tag today and the profile later.

### 4 · The promise that was broken, and it is losing data every day

§33 says "GST fields captured by OCR are **stored** even before business reports exist". **Checked:
the capture half is true; the storage half is not.**

Traced end to end:

1. `ReceiptFields.tax` exists — the OCR engine scans for `gst`/`cgst`/`sgst` lines and extracts the
   figure, with its own confidence (issue 5.2, FR-OCR-003).
2. `ReceiptReviewScreen` **displays** it: `receipt_tax` + the formatted amount.
3. `ReceiptRepository.save` takes a `TransactionDraft`.
4. `TransactionDraft` maps to `transactions`, whose eighteen columns include no tax field.
   `attachments` has none either.

So the number is read off the bill, shown to the user once, and **discarded**.

**This is not an ordinary missing feature, because the cost compounds.** A column added later can be
backfilled for free only if the source data survives. Receipt images are deliberately kept
on-device and out of backups (ADR-0023, P-01), and the erase path destroys them. Every receipt
scanned between v1 and the day this column lands is a GST figure that cannot be recovered without
the user re-photographing a bill they have probably thrown away.

That is precisely what the forward-compatibility table existed to prevent — the row's own stated
purpose is that history is "analysable retroactively".

**It is not fixed here**, because AC2 is "ADR only" and adding a column is a schema migration with
its own backfill, archive and drill consequences. It is recorded as the first thing business mode
should do, and pinned by a test that fails when the gap closes.

### 5 · A second broken promise, found next door

The adjacent row in the same table reads: *"Tax planning (India) — categories carry `tax_relevance`
tag field (80C/80D/HRA…) from v1 so history is analysable retroactively."*

**Checked: `category` has nine columns and `tax_relevance` is not among them.**

This one has already cost something. Issue 13.4 built AI-TAX, and because no category can say which
spending was deductible, the engine must be **handed** a household's deductions as input rather than
deriving them from the ledger it already has. The §38.1 break-even — the most useful number that
engine produces — therefore depends on the user typing figures they may not have.

Also out of scope here, and recorded for the same reason.

## Consequences

- `BusinessMode.IS_ENABLED` is `false`, beside `HouseholdMode` in `:domain:usecase`, because they
  are one mechanism and whichever ships first pays for the id-keyed queries the other needs.
- `BusinessModeDriftTest` pins the two findings **in the unusual direction**: each test fails when a
  gap is *closed*, and its failure message is the instruction to delete the stale paragraph. An ADR
  that still described a solved problem would read as unfinished work.
- The drift test reads two files from outside its own module, so `domain/usecase/build.gradle.kts`
  declares them as task inputs. Without that it would be UP-TO-DATE-skippable — the bug issues 7.2,
  11.5, 11.7 and 13.1 each found in a different guise, and one that 13.1's
  `configureOwnSourceAsTestInput()` does **not** cover, because that declares a module's own
  `src/main` and both of these live elsewhere.

## Alternatives considered

- **A `book_id` beside `profile_id`.** Rejected, §1: two scoping rules and two gates, when the
  existing one already does the job.
- **A parallel `business_category` taxonomy.** Rejected, §3: one category is often both, and
  duplication would make every classification decision happen twice.
- **Fixing the GST column in this issue.** Rejected, §4: AC2 is "ADR only", and a schema migration
  drags backfill, archive and restore-drill work behind it. Recorded, pinned, and left as business
  mode's first task — but flagged as *time-sensitive* rather than merely outstanding, because the
  data is not recoverable later.
- **Treating "business" as a sixth `nature`.** Rejected, §3: nature says what a rupee became;
  business-or-personal says whose it was. Different questions.
