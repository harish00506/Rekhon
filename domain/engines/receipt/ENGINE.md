# ReceiptEngine — FR-OCR-003

**SRS:** §5.4, §18.1  ·  **Pipeline layer:** L3 (rules)  ·  **Module:** `:domain:engines:receipt`
**Version:** 1.0  ·  **Status:** active

## Why this engine exists

Every capture path in Epic 3 so far starts with a person keying in an amount. This one reads. The
requirement is FR-OCR-003, a MUST:

> *"The parser MUST extract: total amount, date, merchant name, and (best-effort) tax and line
> items; each field shows a confidence indicator and is editable before save."*

Two words in that sentence decide the engine's shape. **"Confidence"** means no field may be
returned as a bare value. **"Editable"** means nothing it produces is final — it proposes and the
user corrects (P-07).

It is a separate module from `:ml:ocr` for one reason: everything in `:ml:ocr` needs a device, and
§18.1 sets a numeric target — *total-amount accuracy ≥ 95%* — that is only a gate if it can run in
CI. So this engine takes **text**, not a photograph, and is pure Kotlin (ARC-002).

## Contract

```
interface ReceiptEngine {
    fun extract(input: ReceiptInput): Result<ReceiptFields, AppError>
}
```

- **Input** — `ReceiptInput`:
  - `text: RecognizedText` — blocks of `text` plus `topFraction`, the block's top edge as **integer
    basis points of image height** (MNY-002's unit applied to a position, so no `Float` reaches the
    engine). Lives in `:core:model` so a pure-Kotlin engine can name it.
  - `todayIsoDate: String` — the profile-zone day (TIM-002), **passed in, never read** (TIM-001).
  - `nowUtcMillis: Long` — stamped into provenance.
  - `rules: ReceiptRules` — the rulebook thresholds, **injected** (ADR-0005).
- **Output** — `ReceiptFields`: `total: ExtractedMoney?`, `date: ExtractedText?` (ISO `yyyy-MM-dd`),
  `merchant: ExtractedText?`, `tax: ExtractedMoney?`, and `provenance` (`engineId = receipt-parser`,
  `engineVersion`, `computedAtUtcMillis`, `evidence = [RULE-RECEIPT-PARSE@1.0]`, `inputWindow =
  todayIsoDate`, `confidenceBps`). Each `Extracted*` carries `confidenceBps` (0..10 000).

**Every field is nullable and independent**, which is the requirement's own structure: §18 says a
failed read falls back to manual entry *pre-filled with whatever was extracted*, so a receipt whose
date smudged must still surrender its total. `Ok` with all four null is the honest answer for a
blurred photo. The only `Err` is a malformed `todayIsoDate` — a caller bug, not a receipt.

`total` is a **positive magnitude**. The expense/income sign is the capture screen's to apply, the
same split `TransactionDraft` already makes for a typed transaction.

## Formula / algorithm

Straight from §18.1's pipeline.

0. **Rows, not lines.** ML Kit returns a receipt's *cells*, not its rows: a bill printed
   `GRAND TOTAL     365.80` comes back as two blocks sitting at the same height. So single-line
   blocks within `same_row_bps` of each other are joined into one logical line first — which is what
   §18.1's "near keywords" means on a two-column layout, and the reason `RecognizedBlock` carries a
   position at all. A **multi-line** block is left alone: every line of it shares one `topFraction`,
   so joining by height would put one row's label beside another row's amount.
1. **Candidate amounts.** §18.1 says candidates are *currency-formatted numbers*, and honouring that
   literally is the single most important line in the engine: an amount must carry a currency marker
   (`₹`, `Rs`, `INR`) **or** a decimal point with one or two digits after it. Without that rule,
   `GST 18%` above `Bill No 20260406` reads as a ₹20,260,406 purchase. **One** decimal digit is
   accepted because ML Kit routinely returns `365.8` for a printed `365.80`, and
   `MoneyFormatter.parse` pads that to ₹365.80 exactly — a single fraction digit is *tens* of paise,
   not a rounding guess. The guards on either side of the pattern carry the cost of the relaxation:
   without them `04.08.2026` would read as ₹4.08. Parsing goes through `MoneyFormatter.parse`, which
   refuses anything not exactly representable in paise and never rounds (MNY-001).
2. **Total**, in three tiers, tried in order:
   - a currency-formatted amount on a line containing a `total_keywords` word → `9500` bps
     (`7500` when several distinct amounts sat beside keywords — subtotal, tax and total all
     matched);
   - failing that, a **bare integer** on a keyword line → `7000` bps. Plenty of tills print
     `TOTAL 1240` with no paise, and the keyword is itself the proof the number is money. A separate
     tier rather than merged into the first, so a receipt printing both is never decided by a count
     of items;
   - failing that, the largest currency-formatted amount anywhere → **`4000` bps, deliberately below
     the rulebook's `low_confidence_bps` floor**, so FR-OCR-004's review screen flags it rather than
     presenting a guess as a reading.
3. **Date** — `dd/mm/yy(yy)` with `/`, `-` or `.`. **Day-first, deliberately**: `03/04/2026` is
   3 April in every shop in India and 4 March in the convention most OCR training data comes from.
   A two-digit year takes its century from `todayIsoDate` and is pulled back one century if it would
   land in the future. **A date after today is rejected** rather than accepted with low confidence —
   a receipt cannot be from the future, and pre-filling one would create a *scheduled* transaction
   (FR-TXN-010). An impossible calendar date (`31/02`) is skipped, not thrown.
   Confidence: `7000` base, `+2000` for a four-digit year, `+1000` when the leading component is
   over 12 and can therefore only be a day.
4. **Merchant** = the highest block by `topFraction` within `merchant_top_region_bps`, taking its
   first line that is neither an amount nor a date (tills print bill numbers above the name).
   Confidence falls linearly from `10000` at the very top to `5000` at the edge of the band.
5. **GST** — `cgst`/`sgst` lines are **added** (`8000` bps), because an intra-state bill splits the
   levy in half; only if there are none is a single `gst` line taken as printed (`7000` bps). Summing
   everything would double-count a receipt that prints both a split and a summary line.

`provenance.confidenceBps` **is the total's**: §18.1 sets one numeric target and it is total-amount
accuracy. A parse that read the shop name and missed the amount has not read the receipt.

## Assumptions & guardrails

- Money is `Long` paise in `Money`; every confidence is integer basis points (MNY-002). **There is
  not a `Double` in the module.**
- Dates are ISO strings (TIM-002); the clock is passed in (TIM-001) — `CfoWallClockInDomain` fails
  the build on a wall-clock read here.
- **No merchant knowledge base**, though §18.1 mentions matching one. `merchant_id` and the alias
  table are issue 4.1's; guessing a canonical name here would put a payee on the user's row that
  they never saw on their receipt, which is worse than leaving it as printed.
- **No line items.** FR-OCR-003 calls both tax and line items best-effort; qty–name–price table
  detection is a project of its own and is deliberately out of scope for issue 3.8.
- Latin-script only, which is what `:ml:ocr` recognises: Indian receipts are printed in Latin script
  even when the shop's name is not.
- It produces numbers and text read off the page, never prose (P-03) and never orders (P-07).

## Rules / knowledge consumed

| ID / file | What it provides |
|-----------|------------------|
| `RULE-RECEIPT-PARSE` (`ai/rules/rules-kb.json`) | `total_keywords: [total, grand, amount, payable]`, `tax_keywords: [gst, cgst, sgst]`, `low_confidence_bps: 6000`, `merchant_top_region_bps: 3000`, `same_row_bps: 200`, `duplicate_amount_tolerance_pct: 1`, `duplicate_date_tolerance_days: 1` |

Mirrored as `ReceiptRules` per **ADR-0005** because nothing in the app loads `ai/` yet.
`RulebookDriftTest` fails the build if the two disagree — verified to bite by moving
`low_confidence_bps` in the rulebook and watching it go red (2026-08-06).

The last two parameters are read by `ReceiptRepository` rather than by this engine: FR-OCR-006's
duplicate guard is a query, not a parse. They live on the same rulebook row so both halves of the
receipt feature cite one rule.

## Evidence shown to the user (P-02)

The review screen pre-fills each field with what was read and **flags the ones below
`low_confidence_bps`** — as a word and a content description, not only a colour (FR-OCR-004, and the
accessibility line in §21.6). Every field is editable, and the correction is what gets saved. The
cited rule is `RULE-RECEIPT-PARSE@1.0`.

## Tests

- Golden case: a full printed bill yielding all four fields.
- Refusals — the cases that matter most, because each is a way the app could record a purchase that
  never happened: a percentage is not an amount; a bill number is not an amount; a word ending in
  `rs` does not create one; a serial number is not a date; a receipt cannot be from the future; a
  zero total is not offered.
- Total tiers: a keyword line beats a larger amount elsewhere; the largest amount on the keyword
  lines wins; a whole-rupee total is read; a keyword-less guess falls **below the flag floor**; a
  lone figure beside a keyword reads as surer than a crowded one.
- Date: `/`, `-` and `.`; a two-digit year takes its century from today and goes back one when that
  would be the future; `31/02` is skipped rather than thrown; unambiguous and four-digit years score
  higher.
- Merchant: the topmost block; a till time above the name is skipped; nothing in the top region
  means no merchant; a name printed lower is less certain.
- Tax: a split levy is added; a single `GST` line is taken as printed; a receipt printing both does
  not double-count.
- The rulebook seam: moving `merchant_top_region_bps` moves the merchant, moving `total_keywords`
  moves the total.
- Determinism: the same text twice gives an identical result, provenance included (P-08).
- **The eval gate** (`ReceiptEvalTest` over `src/test/resources/eval/receipts.txt`): 46 frozen,
  anonymised receipts — supermarkets, restaurants, fuel, pharmacy, e-commerce, autos, utilities, and
  six deliberately awkward ones (no space after the label, the figure on the line below it, a misread
  keyword, `/-` after the amount, a dotted date, a name broken across two lines). Asserts §18.1's
  **total-amount accuracy ≥ 95%** and **field-complete ≥ 80%**, plus a counterweight that most
  correct reads are *not* flagged — a parser could otherwise hit 95% by flagging everything.
  Verified to bite by mis-labelling three fixtures and watching it go red (2026-08-06).
- Two-column layouts, which the device run is what found: a row split across two blocks is read as
  one line and the total is *confident* rather than a fallback; a multi-line block is not joined by
  height; a split levy across two columns is still added; a clipped `365.8` is still ₹365.80; and
  `04.08.2026` is still not ₹4.08.
- **One device test** (`:app`'s `ReceiptScanDeviceTest`, instrumentation only): a real image through
  the real ML Kit recogniser and this parser. Not a gate — CI has no device — but it is the only
  thing that checks what the recogniser actually hands over, and it is what caught both of the above.
- Coverage: **100% line** (gate: engine ≥ 85%).

### What the eval set does and does not prove

It scores 100% on total amount, and that is **not** an independent estimate of how the app performs
on a real photograph: the fixtures were written alongside the parser, so they are the shapes it was
built to handle. What the gate provides is **regression protection** — a heuristic edit that breaks
one of these shapes fails the build. The independent measurement is the device run against receipts
nobody wrote for this file, and against ML Kit's own recognition, which is not reproducible in CI
and not ours to regression-test.

## Version log

| Version | Date | Change |
|---------|------|--------|
| 1.0 | 2026-08-06 | Initial implementation from SRS §5.4 (FR-OCR-003) and §18.1, issue 3.8. Row-joining and the one-decimal-digit rule landed within the same version, before release, after the first emulator run against a real recognition read an item price as the total. |
