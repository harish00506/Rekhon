<!--
  Why:  CLAUDE.md §5 — a decision record for schema 32 and the nullability choice behind it.
  What: the fix for the §33 promise ADR-0073 found broken — the OCR's GST figure was extracted,
        displayed, and discarded.
  Result: a reader can see why a nullable column was the whole design question, and why the bug
          survived a suite that tested both ends of the path.
  Changelog: 2026-10-10 — Created.
-->

# ADR-0077 — The GST figure is stored, and `null` is not zero

**Status:** Accepted · **Date:** 2026-10-10 · **Closes the finding in:** [ADR-0073 §4](0073-the-business-book-is-a-second-profile-and-two-promises-were-broken.md) · **SRS:** §33, §18.1, FR-OCR-003 · **Rules:** P-01, P-03, MNY-001, DB-003

## Context

§33's forward-compatibility table promises: *"GST fields captured by OCR are **stored** even before
business reports exist."* Issue 13.5 traced it end to end and found the storage half had never been
built:

1. `ReceiptFields.tax` — the OCR engine scans for `gst`/`cgst`/`sgst` and extracts the figure
   (issue 3.8, FR-OCR-003).
2. `ReceiptReviewScreen` **displays** it.
3. `ReceiptRepository.save` takes a `TransactionDraft`, which had no tax field.
4. `transactions` had eighteen columns, none of them tax.

So the number was read off the bill, shown to the user once, and discarded.

**The cost compounded.** Receipt images stay on-device and out of backups (ADR-0023, P-01) and the
erase path destroys them, so a figure not captured at scan time cannot be recovered later — which is
exactly what a forward-compatibility table exists to prevent. Every day the gap stayed open was a
day of unrecoverable history.

## Decision

### 1 · One nullable column, and the nullability is the whole design

Schema **32** adds `transactions.tax_minor INTEGER`, nullable, no default. `TransactionDraft` gains
`tax: Money?`, and the review screen passes the parsed figure it has been rendering all along.

**`null` and `0` mean different things, and collapsing them would be a lie:**

| Value | Means |
|---|---|
| `null` | No tax figure was read. Every manually typed row; every receipt without a GST line |
| `0` | The bill stated zero tax. A real thing — a zero-rated or exempt supply |

The business reports Epic 13 designed need to tell those apart. A `DEFAULT 0` on the migration would
have asserted that every transaction in a user's existing history was zero-rated — a false claim
about rows nobody ever recorded a figure for, and one no later code could undo. That is the P-03
failure in its quietest form: not an invented number on screen, but an invented fact in storage.

### 2 · A tax larger than its bill is a parse error, and the write is refused

A GST line is part of a receipt's total and can never exceed it. A draft whose tax is negative, or
larger than `|amount|`, is rejected with `Validation("tax")` — naming the field, so the screen can
point at it rather than at the amount.

**Refused rather than saved-with-the-tax-dropped**, deliberately: silently discarding a figure the
user can see is precisely the behaviour this ADR exists to end, and doing it in the name of
validation would be worse than the original bug, not better.

### 3 · The archive needs no change, and that is the point

`CfoArchive` holds the Room entities directly (ADR-0023), so a new column is in the backup the
moment it is in the table. The restore drill's fixture now seeds a tax figure, so the round trip
proves it survives rather than merely compiling.

This is the design decision ADR-0023 made paying off: the `goal` incident it records — a whole
*table* silently missing from exports for two issues — was about a missing field on the envelope, a
failure mode a *column* does not have.

## Why the bug survived the suite

Both ends of the path were tested. The receipt engine had tests proving it extracts GST; the review
screen had a test proving it *displays* GST. **Nothing tested the join.**

`toDraftOrNull` simply did not mention tax, and no test asked whether the draft carried it — so the
figure fell between a green test on the screen and a green test on the parser.

Found again during this change, by mutation: deleting `tax = tax` from `toDraftOrNull` left the
entire suite passing even *after* the column existed. Two tests now cover that join, asserting the
figure reaches the saved draft and that an absent figure stays null.

**The lesson: testing that a value is produced and testing that it is displayed does not test that
it is kept.** A pipeline needs a test at the seam, not only at both ends.

## Consequences

- Schema **32**; `MIGRATION_31_32` is one additive `ALTER TABLE`, so DB-003 holds. The round-trip
  test asserts existing rows come back **null** — the assertion that would catch a `DEFAULT 0`.
- `BusinessModeDriftTest`'s GST assertion was **inverted rather than deleted**. It used to fail when
  the gap closed, telling whoever closed it to remove ADR-0073's stale paragraph. It now fails if
  the column is removed, because the behaviour worth guarding has changed from "this is missing" to
  "this must not go missing again".
- Nothing reads `tax_minor` yet. It is captured now so that business mode has a history to read when
  it arrives — which was §33's intention all along.

## Alternatives considered

- **A `DEFAULT 0` on the migration.** Rejected, §1: it writes a false fact into every historical row.
- **Store the tax on `attachments` instead.** Considered: the figure comes from a receipt, and the
  attachment is the receipt. Rejected because the tax is a property of the *transaction* — a bill
  can be entered by hand from a paper receipt with no image at all, and a user who deletes the
  image should not lose the figure.
- **Save the row and drop an invalid tax.** Rejected, §2.
- **Wait for business mode and add the column then.** Rejected: that is what made this urgent. The
  column is cheap; the history is not replaceable.
