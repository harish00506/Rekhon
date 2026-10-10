<!--
  Why:  CLAUDE.md §10 — one session file per session that changes code. This one is maintenance
        rather than an issue: the data-losing finding ADR-0073 §4 recorded, closed on request.
  What: schema 32's nullable `transactions.tax_minor`, the field that was missing from the review
        screen's state, the validation that refuses an impossible tax, and the mutation that caught
        the bug a second time.
  Result: a reader can see why the nullability was the whole design question, and why a path with a
          green test at each end still lost data.
  Changelog: 2026-10-10 — Created.
-->

# 2026-10-10 — The GST figure is stored

Branch `fix/store-the-gst-figure` off `dev` (`2152697`). Version 0.13.7 → **0.13.8**
(versionCode 72). **Not an issue** — the finding ADR-0073 §4 recorded and deferred, fixed on request.

---

## 1 · Decisions this session

### 1.1 One nullable column, and the nullability is the entire design

Schema **32** adds `transactions.tax_minor INTEGER` — nullable, **no default**.

The column itself was never the question; §33 specified it and ADR-0073 §3 designed it. The question
was what to write into the rows that already exist. `null` and `0` are different claims:

| Value | Claim |
|---|---|
| `null` | No tax figure was read. Every hand-typed row; every receipt without a GST line |
| `0` | The bill stated zero tax — a zero-rated or exempt supply, which is a real thing |

A `DEFAULT 0` would have asserted that every transaction in a user's history was zero-rated. That is
a **false fact in storage**, written by a migration, which no later code can distinguish from a real
zero and therefore can never undo. It is P-03's failure mode at its quietest: not an invented number
on a screen, but an invented number in a table, for rows nobody ever recorded a figure for.

The round-trip migration test asserts existing rows come back `null`. That assertion exists
specifically to fail if someone later adds the default for convenience.

### 1.2 A tax larger than its bill is refused, not quietly dropped

A GST line is a component of a receipt's total; it cannot exceed it. `validated()` rejects a draft
whose tax is negative or whose tax exceeds `|amount|`, returning `Validation("tax")` — naming the
field, so the review screen can point at the tax rather than at the amount.

The alternative — save the row and drop the bad tax — was rejected on principle. **Silently
discarding a figure the user can see on screen is the exact behaviour this change exists to end**,
and doing it under the name of validation would make it harder to find, not easier.

Equal to the whole amount is allowed, and has a test. A ₹0 bill with ₹0 tax is legitimate, and so is
a line item whose displayed total *is* the tax on a split receipt; the engine's job is to refuse the
impossible, not the unusual.

### 1.3 The archive needed no change, and that is the finding

`CfoArchive` holds the Room entities directly (ADR-0023), so the new column is in the backup the
moment it is in the table. The drill fixture now seeds a tax figure, so the round trip **proves** it
survives rather than merely compiling.

Worth a line because it is ADR-0023's design paying off. The `goal` incident ADR-0023 records — a
whole table silently absent from exports for two issues — was a missing field on the envelope. A
*column* does not have that failure mode, and choosing entity-shaped archiving is why.

### 1.4 13.5's drift test was inverted, not deleted

`BusinessModeDriftTest` had a test that **failed when the gap closed** — an "unusual direction" pin
whose job was to tell whoever added the column to come back and delete ADR-0073's now-stale
paragraph. It did that job today.

Deleting it would have been the obvious move and the wrong one. It was rewritten as
*"the promise kept late — the GST figure is extracted, and now stored"*, failing if the column is
removed. **The behaviour worth guarding changed from "this is missing" to "this must not go missing
again"**, and the pin follows the behaviour.

---

## 2 · Why the bug survived a suite that tested both ends

Both ends of the path were covered, and had been for months:

- `ReceiptScanEngine` had tests proving it extracts `gst`/`cgst`/`sgst` (3.8, FR-OCR-003).
- `ReceiptReviewViewModelTest` had a test proving the figure is **displayed**.

`toDraftOrNull()` simply did not mention tax. No test asked whether the draft carried it, so the
value fell through the one unwatched seam between two green tests.

**It happened again during the fix, and mutation caught it.** With the column, the entity, the
repository mapping and the validation all in place, removing `tax = tax,` from `toDraftOrNull` left
the **entire suite passing** — mutation G2 survived. That is the identical defect, in the identical
line, reproduced with the fix half-built. Two ViewModel tests now cover the seam (the figure reaches
the saved draft; an absent figure saves `null`, not `0`), and G2 and G5 both go red.

> *Testing that a value is produced and testing that it is displayed does not test that it is kept.
> A pipeline needs a test at the seam, not only at both ends.*

All five mutations now kill: the column (G1), the ViewModel's pass-through (G2), the repository
mapping (G3), the validation (G4), and the state field (G5).

---

## 3 · Flow changed this session

The chain existed end to end except for its last link. One field, threaded:

```
ReceiptScanEngine (reads gst/cgst/sgst)
  → ReceiptReviewUiState.tax                       ← the field that was missing
    → ReceiptReviewViewModel.toDraftOrNull()
      → TransactionDraft.tax: Money?
        → TransactionRepository.validated()        ← refuses tax < 0 or tax > |amount|
          → TransactionEntity.taxMinor
            → transactions.tax_minor (schema 32, nullable, no default)
              → CfoArchive (no change needed — it holds the entity, ADR-0023)
```

Nothing **reads** `tax_minor` yet. It is captured now so that business mode has a history to read
when it arrives — which is what §33's forward-compatibility table was for.

---

## 4 · Code changed this session

| Path | What it does now |
|---|---|
| `core/database/.../entity/Entities.kt` | `TransactionEntity.taxMinor: Long?`, declared **last** to match `ALTER TABLE ADD COLUMN` append order |
| `core/database/.../migration/Migrations.kt` | `MIGRATION_31_32` — one additive `ALTER TABLE`, nullable, no default (DB-003 holds) |
| `core/database/.../CfoDatabase.kt` | `VERSION = 32`; `schemas/…/32.json` exported |
| `data/repository/.../TransactionRepository.kt` | `TransactionDraft.tax: Money?`; maps to `taxMinor`; `validated()` refuses negative or above-bill; `invalidField()` names `"tax"` |
| `feature/transactions/.../ReceiptReviewUiState.kt` | `val tax: Money?` beside `taxText` — **this field is the fix** |
| `feature/transactions/.../ReceiptReviewViewModel.kt` | carries the parsed figure into the state and on into the draft |
| `data/repository/src/sharedTest/.../DrillFixture.kt` | seeds a tax figure, so the restore drill proves it survives |
| `data/repository/src/test/.../TransactionRepositoryTest.kt` | 5 tests: stored not discarded · null never becomes zero · tax == amount allowed · tax > bill refused naming the field · negative refused |
| `core/database/src/androidTest/.../MigrationRoundTripTest.kt` | `migrate31To32_leavesHistoryNullAndCarriesANewTaxFigure()` |
| `feature/transactions/src/test/.../ReceiptReviewViewModelTest.kt` | 2 tests at the seam G2 exposed |
| `domain/usecase/src/test/.../BusinessModeDriftTest.kt` | the GST pin **inverted** (§1.4) |
| `docs/adr/0077-*.md` | the decision record |
| `docs/adr/0073-*.md` | §4's finding marked closed, text preserved — it is why the fix happened |

`ai/` is unchanged: no threshold moved, so no data row did.
