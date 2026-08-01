package com.aicfo.feature.accounts

import androidx.annotation.StringRes
import com.aicfo.core.common.AppError
import com.aicfo.core.model.AccountType

/**
 * Maps the domain's closed sets to this feature's strings (issue 2.5; §21.6).
 *
 * Why:  `AccountType` lives in `:core:model`, which is pure Kotlin and must stay that way (ARC-002)
 *       — an `@StringRes` on the enum would drag Android into the module every engine depends on.
 *       And `AppError` carries a **code**, never a message, so that the wording lives where it can
 *       be translated. Both mappings therefore belong here, in the feature that renders them.
 * What: one `when` per closed set, exhaustive so the compiler names a missing case.
 * Result: adding an account type is a compile error until it has a label.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
internal object AccountLabels {
    /**
     * The label for an account type.
     * Why:    exhaustive `when` rather than a map keyed by the enum — a map compiles fine with a
     *         missing key and fails at runtime, which for FR-ACC-001's eleven types means one of
     *         them silently renders blank.
     * Result: the string resource for [type].
     * Input:  [type]. Output: a string resource id.
     */
    @StringRes
    fun typeLabel(type: AccountType): Int =
        when (type) {
            AccountType.BANK -> R.string.account_type_bank
            AccountType.CASH -> R.string.account_type_cash
            AccountType.CREDIT_CARD -> R.string.account_type_credit_card
            AccountType.LOAN -> R.string.account_type_loan
            AccountType.INVESTMENT -> R.string.account_type_investment
            AccountType.GOLD -> R.string.account_type_gold
            AccountType.CRYPTO -> R.string.account_type_crypto
            AccountType.PROPERTY -> R.string.account_type_property
            AccountType.VEHICLE -> R.string.account_type_vehicle
            AccountType.RECEIVABLE -> R.string.account_type_receivable
            AccountType.PAYABLE -> R.string.account_type_payable
        }

    /**
     * The message for an error code.
     * Why:    the state carries `AppError.code` rather than a sentence, so the screen decides the
     *         wording and a translator can change it. An unrecognised code falls back to the
     *         generic message rather than rendering the code itself at the user.
     * Result: the string resource for [code].
     * Input:  [code] — an `AppError.code`, or `null`. Output: a string resource id.
     */
    @StringRes
    fun errorMessage(code: String?): Int =
        when (code) {
            AppError.Storage("").code -> R.string.accounts_error_storage
            AppError.NotFound.code -> R.string.accounts_error_not_found
            AccountEditorViewModel.VALIDATION_ERROR_CODE -> R.string.account_editor_error_validation
            else -> R.string.accounts_error_generic
        }
}
