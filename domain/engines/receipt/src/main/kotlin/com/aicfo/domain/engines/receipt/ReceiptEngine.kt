package com.aicfo.domain.engines.receipt

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RecognizedText

/**
 * Reads a receipt's fields out of recognised text (issue 3.8; FR-OCR-003, §18.1).
 *
 * Why:  FR-OCR-003 is a MUST — *"The parser MUST extract: total amount, date, merchant name, and
 *       (best-effort) tax and line items; each field shows a confidence indicator and is editable
 *       before save."* Two words in that sentence decide this engine's whole shape. **"Confidence"**
 *       means every field has to say how sure it is, so nothing here returns a bare value.
 *       **"Editable"** means nothing here is final: the parser proposes and the user corrects
 *       (P-07), which is why it never writes and never refuses to answer.
 *
 *       It is a separate module from `:ml:ocr` for one reason: everything in `:ml:ocr` needs a
 *       device, and everything here needs to be provable. §18.1 sets a numeric target — total-amount
 *       accuracy ≥ 95% — and a target is only a gate if it can run in CI, which means the parser has
 *       to take *text*, not a photo.
 * What: one method, from recognised blocks to four optional fields, each with a confidence.
 * Result: what the review screen pre-fills. **Nothing is computed by a model** (P-03) — every figure
 *       here comes from `MoneyFormatter.parse` and integer arithmetic.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * Pure Kotlin (ARC-002), so the whole parser is provable on the JVM.
 */
interface ReceiptEngine {
    /**
     * Extracts every field it can from one receipt.
     * Why:    a `Result`, matching every other engine, though the realistic failures are all
     *         *per-field* rather than fatal — a receipt with an unreadable date must still hand back
     *         the total, because §18 says failures "fall back to manual entry pre-filled with
     *         whatever was extracted". The `Err` branch is reserved for a malformed
     *         [ReceiptInput.todayIsoDate], which is a programming error in the caller rather than
     *         anything about the photo.
     * What:   scores the currency amounts against §18.1's keyword set, parses Indian date formats,
     *         takes the merchant from the top region, and scans for GST lines.
     * Result: `Ok(fields)`, with **any subset of the four fields null** — including all four, which
     *         is the honest answer for a blurred photo and renders as an empty review screen.
     * Input:  [input] — the recognised text, the rulebook thresholds and the caller's date.
     * Output: `Result<ReceiptFields, AppError>`.
     */
    fun extract(input: ReceiptInput): Result<ReceiptFields, AppError>
}

/**
 * Everything the parser needs, as one value (issue 3.8).
 *
 * Why: [todayIsoDate] and [nowUtcMillis] are **passed in, never read**: TIM-001 bans wall-clock
 *      reads in domain code and `CfoWallClockInDomain` fails the build on one — which is also what
 *      makes every case in the eval set reproducible (P-08). The date is not decoration: a receipt
 *      printed `03/04/26` needs a century, and one dated next month is a misread rather than a
 *      purchase, so the parser has to know what day it is to reject it.
 * Result: the argument to [ReceiptEngine.extract].
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * Input:  [text] — what the recogniser read; [rules] — the rulebook thresholds, injected so a test
 *         can move one (ADR-0005); [todayIsoDate] — the profile-zone day, ISO `yyyy-MM-dd`
 *         (TIM-002); [nowUtcMillis] — the instant stamped into provenance (TIM-001).
 * Output: an immutable value.
 */
data class ReceiptInput(
    val text: RecognizedText,
    val todayIsoDate: String,
    val nowUtcMillis: Long,
    val rules: ReceiptRules = ReceiptRules(),
)

/**
 * What the parser read off one receipt (issue 3.8; FR-OCR-003).
 *
 * Why:    **every field is nullable and independent**, which is the requirement's own structure
 *         rather than a convenience. §18 says failures fall back to manual entry *pre-filled with
 *         whatever was extracted*, so a receipt whose date smudged must still surrender its total.
 *         An all-or-nothing result would throw away the field the user most wanted the app to read.
 * Result: what `ReceiptReviewScreen` pre-fills and flags.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * Input:  [total] — the amount payable, as a **positive magnitude**; the expense/income sign is the
 *         capture screen's to apply, exactly as it is for a typed transaction; [date] — the receipt
 *         date, ISO `yyyy-MM-dd` (TIM-002); [merchant] — the shop, as printed; [tax] — the GST
 *         component, best-effort per FR-OCR-003; [provenance] — engine id, version, instant, the
 *         rule cited, and the overall confidence.
 * Output: an immutable value.
 */
data class ReceiptFields(
    val total: ExtractedMoney?,
    val date: ExtractedText?,
    val merchant: ExtractedText?,
    val tax: ExtractedMoney?,
    val provenance: EngineProvenance,
)

/**
 * One money field and how sure the parser is of it (issue 3.8; FR-OCR-003, MNY-001, MNY-002).
 *
 * Why:    FR-OCR-003 requires a confidence *indicator per field*, so the confidence travels with the
 *         value rather than as a second parallel list the screen has to zip. Basis points, not a
 *         `Float` or a percentage — MNY-002's unit, and it keeps floating point off a type that
 *         carries an amount.
 * Result: the type of [ReceiptFields.total] and [ReceiptFields.tax].
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * Input:  [value] — the amount in paise (MNY-001); [confidenceBps] — 0..10 000.
 * Output: an immutable value.
 */
data class ExtractedMoney(
    val value: Money,
    val confidenceBps: Int,
)

/**
 * One text field and how sure the parser is of it (issue 3.8; FR-OCR-003, MNY-002).
 *
 * Why:    the date and the merchant are both text — the date deliberately, because TIM-002 makes a
 *         date-only field an ISO string and because the review screen puts it in an editable field
 *         the user can retype. One type for both, since the confidence rule is identical.
 * Result: the type of [ReceiptFields.date] and [ReceiptFields.merchant].
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * Input:  [value] — the text; [confidenceBps] — 0..10 000. Output: an immutable value.
 */
data class ExtractedText(
    val value: String,
    val confidenceBps: Int,
)

/**
 * Builds the engine for the DI graph (ARC-003).
 * Why:    the implementation is `internal`, so `:app`'s Hilt module cannot name it — the seam every
 *         engine in this codebase uses, kept identical on purpose.
 * Result: a [ReceiptEngine]. Input: none. Output: the engine.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
object ReceiptEngineFactory {
    /** Result: the production engine. Input: none. Output: [ReceiptEngine]. */
    fun create(): ReceiptEngine = DefaultReceiptEngine()
}
