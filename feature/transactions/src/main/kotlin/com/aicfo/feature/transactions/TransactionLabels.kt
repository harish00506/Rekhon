package com.aicfo.feature.transactions

import androidx.annotation.StringRes
import com.aicfo.core.common.AppError
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
 * What: one exhaustive-ish `when` over error codes, and one date formatter.
 * Result: nothing in a composable turns a code or a raw date into English.
 * Changelog: 2026-08-02 — Created for issue 3.1.
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
}
