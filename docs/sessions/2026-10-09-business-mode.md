<!--
  Why:  CLAUDE.md §10 — one session file per working session, holding the full reasoning the root
        records only point at.
  What: issue 13.5 — why business mode needs no new isolation, what a GST-aware category looks
        like, and the audit of §33's forward-compatibility promises that writing the design made
        possible.
  Result: a reader can see why "ADR only" was taken literally, and why one of the broken promises
          is losing data every day rather than merely outstanding.
  Changelog: 2026-10-09 — Created.
-->

# 2026-10-09 — Business mode (issue 13.5)

Branch `feature/13-5-business-mode` off `dev` (`1938bc9`). Version 0.13.3 → **0.13.4**
(versionCode 68). Schema unchanged. **No engine, no entity** — AC2 is "ADR only in v1".

---

## 1 · Taking "ADR only" literally

Every other Epic 13 issue so far asked for something built: 13.1's AC said "the data model
supports…", 13.2 and 13.3 and 13.4 each named an engine. **13.5's AC2 says "feature-flagged; ADR
only in v1."** That is a scope *ceiling*, not a floor, and it was followed: no engine, no entity, no
schema change.

What remained was to decide the design — and then to check whether the foundations the SRS says
already exist actually do. The second part turned out to be the valuable half.

---

## 2 · The design

### 2.1 A business book is a second profile

The isolation business mode needs is **exactly** the isolation household mode needs, and issue 13.1
built it: `profile_id` on 36 tables, and `ProfileScopingTest` failing the build for any `@Query`
that touches a scoped table without filtering on it or declaring itself device-wide.

So business mode adds **no new isolation primitive**. A `book_id` beside `profile_id` was the
alternative and is rejected: two scoping rules, two gates, two ways to get it wrong.

It inherits ADR-0069 §5's precondition unchanged — the fourteen id-keyed queries are safe only
because one profile exists. For household mode a leak between members is bad. For business mode a
leak between a person's business and personal books is **the kind a tax authority takes an interest
in**, which is why the flag stays off until those queries constrain `profile_id`.

### 2.2 A GST-aware category is a column, not a parallel taxonomy

| Addition | Where | Why there |
|---|---|---|
| `gst_rate_bps` | `category` | GST is a property of what was bought, which is what a category is. `null` ≠ zero-rated |
| `input_credit_eligible` | `category` | Not derivable from the rate — a category can be taxed and still be blocked credit |
| `tax_minor` | `transactions` | What **this** bill said, which differs from the category's expected rate often enough to matter |

A separate `business_category` table was rejected: a freelancer's "internet bill" is one category
that is sometimes business and sometimes not, so duplicating the taxonomy would make every
classification decision happen twice and drift.

And "business" is not a sixth `nature`. Nature says what a rupee *became*; business-or-personal says
*whose* it was. Different questions.

---

## 3 · The audit: two of §33's three promises were never kept

§33 carries a forward-compatibility table — decisions supposedly made in v1 so later features
inherit usable history. Three rows are checkable here. Each was measured rather than assumed.

### 3.1 Kept: "tags support business/personal from v1"

True. `tags` and `transaction_tags` have existed since schema 1 and a tag carries a free-form name,
so a user can separate the books by tagging today. `BusinessMode.INTERIM_TAG` names the convention
so the eventual migration has one string to look for rather than a guess about what users typed.

What a tag cannot do is separate the **reports** — safe-to-spend, the forecast and the health score
all run over one profile's whole ledger. That is the gap business mode closes, and it is a reporting
problem rather than a storage one.

### 3.2 Broken, and losing data every day: "GST fields captured by OCR are stored"

Traced end to end:

1. `ReceiptFields.tax` — the OCR engine scans for `gst`/`cgst`/`sgst` and extracts the figure.
2. `ReceiptReviewScreen` **displays** it.
3. `ReceiptRepository.save` takes a `TransactionDraft`.
4. `transactions` has eighteen columns and none is a tax field. `attachments` has none either.

The number is read off the bill, shown to the user once, and **discarded**.

**This is not an ordinary missing feature, because the cost compounds.** A column added later can be
backfilled for free only if the source survives — and receipt images deliberately stay on-device and
out of backups (ADR-0023, P-01), with the erase path destroying them. Every receipt scanned before
the column lands is a GST figure nobody can recover without re-photographing a bill that has
probably been thrown away.

Which is exactly what that table existed to prevent: its own stated purpose is that history stays
"analysable retroactively".

### 3.3 Broken: "categories carry `tax_relevance` from v1"

`category` has nine columns and that is not among them.

This one has **already cost something**. Issue 13.4 built AI-TAX last week, and because no category
can say which spending was deductible, the engine must be *handed* a household's deductions rather
than deriving them from the ledger it already has. The §38.1 break-even — its most useful output —
therefore depends on the user typing figures they may not have.

---

## 4 · Pinning findings in the unusual direction

`BusinessModeDriftTest` asserts that the gaps are **still open**. Each test fails when a gap is
*closed*, and the failure message is the instruction to go and delete the stale ADR paragraph.

That inversion is deliberate. An ADR that still describes a solved problem is worse than no ADR,
because the next reader concludes the work is outstanding when it is done. The usual drift test
stops a document falling behind the code; this one stops it running ahead of it.

The engine half uses a **compile-time** reference (`ReceiptFields::tax`) rather than reading source
text: if the engine ever stops extracting GST, the test stops compiling instead of passing quietly.

---

## 5 · The read-at-runtime staleness bug, fifth instance — pre-empted

The drift test reads two files from **outside its own module**: the database's entity declarations
and the ADR itself. Neither is a declared input of `:domain:usecase`'s test task, so without
declaring them Gradle would call the task UP-TO-DATE on exactly the edits the test exists to catch.

Demonstrated both ways rather than assumed:

| | editing the ADR's finding away |
|---|---|
| without the `inputs.file(...)` declarations | `:domain:usecase:test` **UP-TO-DATE**, build green |
| with them | the test goes **red** |

Issues 7.2, 11.5, 11.7 and 13.1 each found this after the fact. This is the first time it was
pre-empted — and note that 13.1's `configureOwnSourceAsTestInput()` does **not** cover it, because
that declares a module's *own* `src/main` and both of these files live elsewhere.

**The rule generalises: if a test reads a file, that file is an input. "Own source" is not enough
once a test reaches across a module boundary.**

---

## 6 · Flow changed this session

**None** — nothing was built. One line was added to `FLOW.md` for what this issue *found* about an
existing path: the receipt flow extracts a GST figure, shows it, and drops it.

---

## 7 · Code changed this session

| Path | What it does now |
|------|------------------|
| `domain/usecase/.../BusinessMode.kt` | **New.** The flag (false) and `INTERIM_TAG`, beside `HouseholdMode` because they are one mechanism |
| `domain/usecase/.../BusinessModeDriftTest.kt` | **New.** 5 tests pinning §33's three promises and the ADR's own claims |
| `domain/usecase/build.gradle.kts` | Declares the two cross-module files the drift test reads as task inputs |
| `docs/adr/0073-*.md` | **New.** The design, and the audit |

---

## 8 · Quiz

1. **Why does business mode need no new isolation mechanism?** It is a second profile, and 13.1's
   `profile_id` scoping plus `ProfileScopingTest` already enforce separation.
2. **Why is a GST-aware category a column rather than a second taxonomy?** One category — "internet
   bill" — is often both business and personal; duplicating it would split every classification.
3. **Which §33 promise is losing data, and why can it not simply be fixed later?** "GST fields are
   stored." Receipt images stay out of backups, so a figure not captured today cannot be recovered.
4. **Why does the drift test fail when a gap is closed?** Because at that moment the ADR's finding
   is stale, and an ADR describing a solved problem reads as unfinished work.
5. **What does `configureOwnSourceAsTestInput()` not cover?** Files a test reads from *another*
   module — those need their own `inputs.file(...)` declaration.
