package com.aicfo.feature.transactions

import androidx.annotation.StringRes
import com.aicfo.core.common.AppError
import com.aicfo.core.model.TransactionSource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle

/**
 * Maps codes and stored dates to what this feature shows (issue 3.1; §21.6).
 *
 * Why:  `AppError` carries a **code**, never a message, so that the wording lives where it can be
 *       reviewed and translated — the same argument `AccountLabels` makes. And a booked day is
 *       stored as an ISO string (TIM-002), which is exactly right for sorting and grouping and
 *       exactly wrong to show a user.
 * What: one exhaustive-ish `when` over error codes, a date formatter, and the source vocabulary.
 * Result: nothing in a composable turns a code, a raw date or an enum into English.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *            2026-08-03 — Issue 3.5: [sourceLabel] and [sourceName], so FR-TXN-009's provenance is
 *            worded in one reviewable place rather than at each call site.
 */
internal object TransactionLabels {
    /**
     * The message for an error code.
     * Why:    an unrecognised code falls back to the generic message rather than rendering the code
     *         itself at the user — a screen that shows `storage_4` has told them nothing and leaked
     *         an internal name.
     * Result: the string resource for [code].
     * Input:  [code] — an `AppError.code`, or `null`. Output: a string resource id.
     */
    @StringRes
    fun errorMessage(code: String?): Int =
        when (code) {
            AppError.Validation("").code -> R.string.add_txn_error_validation
            AppError.NotFound.code -> R.string.add_txn_error_not_found
            AppError.Storage("").code -> R.string.add_txn_error_storage
            else -> R.string.add_txn_error_unknown
        }

    /**
     * Renders a booked day for a list header.
     *
     * Why:    `bookedOn` is ISO `yyyy-MM-dd` because that sorts lexicographically in date order
     *         (TIM-002); showing it raw would put "2026-08-02" in front of a user. `FormatStyle.
     *         MEDIUM` follows the device locale, so an Indian user sees "2 Aug 2026" without this
     *         module hardcoding a pattern.
     *
     *         **Reads no clock.** It formats a date it was given rather than comparing it to today,
     *         which is why there is no "Today"/"Yesterday" wording here — that needs the profile
     *         zone's current date, and the only sanctioned source of that is the injected `Clock`
     *         (TIM-001), which a label object has no business holding.
     * Result: the localised date, or the input unchanged when it is not a date this build can parse
     *         — a header showing the raw value is better than a list that crashes on one bad row.
     * Input:  [isoDate] — ISO `yyyy-MM-dd`. Output: [String].
     */
    fun dayHeader(isoDate: String): String =
        try {
            LocalDate.parse(isoDate).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        } catch (_: DateTimeParseException) {
            isoDate
        }

    /**
     * How a transaction's provenance reads to a user (issue 3.5; FR-TXN-009, P-02).
     *
     * Why:    P-02 requires every output to show what produced it, and the row this exists for is
     *         the **reconciliation adjustment**: its `note` is deliberately null, so before this it
     *         rendered as an anonymous "Uncategorised −₹500" with nothing saying the *app* posted it
     *         to close a gap against a statement. On that row the source is the only explanation
     *         there is.
     *
     *         **The wording describes provenance, not mechanism.** "From a receipt", not "OCR";
     *         "Balance adjustment", not "reconciliation". A user does not care which subsystem wrote
     *         a row, only how it came to be there.
     *
     *         **Exhaustive `when`, no `else`** — a source added later cannot ship without someone
     *         deciding how it reads, which is the same guard the sealed row kinds give the list.
     * Result: a string resource id, or **`null` for [TransactionSource.MANUAL]**. Null is not a
     *         missing label: it is the deliberate absence of one. Manual is the default and the
     *         overwhelming majority, and tagging every hand-typed row "Manual" would bury the few
     *         labels that carry information. The detail sheet spells it out anyway, because there a
     *         blank field would read as missing data rather than as the default.
     * Input:  [source] — the transaction's provenance. Output: `@StringRes Int?`.
     * Changelog: 2026-08-03 — Created for issue 3.5 (FR-TXN-009).
     */
    @StringRes
    fun sourceLabel(source: TransactionSource): Int? =
        when (source) {
            TransactionSource.MANUAL -> null
            TransactionSource.OCR -> R.string.transactions_source_ocr
            TransactionSource.SMS -> R.string.transactions_source_sms
            TransactionSource.IMPORT -> R.string.transactions_source_import
            TransactionSource.RECURRING_AUTO -> R.string.transactions_source_recurring
            TransactionSource.RECONCILIATION -> R.string.transactions_source_reconciliation
            TransactionSource.DEMO -> R.string.transactions_source_demo
        }

    /**
     * The same label, but never null — for the detail sheet and the filter chips (issue 3.5).
     *
     * Why: [sourceLabel] returns null for [TransactionSource.MANUAL] so a hand-typed *row* shows
     *      nothing. Two places need the word anyway: the detail sheet, where an empty "Source" field
     *      would read as missing data, and the filter chip, which cannot be a chip with no text.
     * Result: a string resource id, always. Input: [source]. Output: `@StringRes Int`.
     * Changelog: 2026-08-03 — Created for issue 3.5.
     */
    @StringRes
    fun sourceName(source: TransactionSource): Int = sourceLabel(source) ?: R.string.transactions_source_manual
}
