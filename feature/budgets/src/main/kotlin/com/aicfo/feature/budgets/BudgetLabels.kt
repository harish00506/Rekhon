package com.aicfo.feature.budgets

import androidx.annotation.StringRes
import com.aicfo.core.common.AppError

/**
 * Maps the domain's closed sets to this feature's strings (issue 4.4; §21.6).
 *
 * Why:  `SeasonalityPriors` lives in `:domain:engines:budget`, which is pure Kotlin and must stay
 *       that way (ARC-002) — an `@StringRes` on a seasonal event would drag Android into an engine
 *       module. And `AppError` carries a **code**, never a message, so the wording lives where it can
 *       be translated. Both mappings therefore belong here, in the feature that renders them, exactly
 *       as `CategoryLabels` does for the taxonomy.
 * What: one lookup per closed set.
 * Result: a festival the engine knows about has a name the user recognises, or is named honestly
 *         rather than rendered as `wedding_season` at them.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
internal object BudgetLabels {
    /**
     * The display name of a seasonal event.
     *
     * Why:    a `when` over ids rather than a label on the event, because the events are **data** —
     *         `ai/knowledge/calendar-seasonality.json` mirrored into Kotlin — and CLAUDE.md §6 keeps
     *         that file free of anything presentational. A tenth event added to the knowledge base is
     *         therefore *not* a compile error here, which is the trade: it renders through
     *         [R.string.budgets_reason_seasonal_generic] until someone writes it a name. Falling back
     *         to a generic sentence is right — showing the raw id would leak an internal identifier
     *         into the user's reasons.
     * Result: the string resource for [eventId], or `null` when this build has no name for it.
     * Input:  [eventId] — a `SeasonalEvent.id`. Output: a string resource id, or `null`.
     */
    @StringRes
    fun seasonalEventLabel(eventId: String): Int? =
        when (eventId) {
            "diwali" -> R.string.budgets_season_diwali
            "dussehra_navratri" -> R.string.budgets_season_dussehra_navratri
            "wedding_season" -> R.string.budgets_season_wedding
            "tax_saving_rush" -> R.string.budgets_season_tax_saving
            "school_admission" -> R.string.budgets_season_school_admission
            "monsoon" -> R.string.budgets_season_monsoon
            "summer" -> R.string.budgets_season_summer
            "onam_pongal" -> R.string.budgets_season_onam_pongal
            "akshaya_tritiya" -> R.string.budgets_season_akshaya_tritiya
            else -> null
        }

    /**
     * The message for an error code.
     *
     * Why:    the state carries a code rather than a sentence, so the screen decides the wording and
     *         a translator can change it. The two validation codes are separate entries because they
     *         are two different refusals with two different fixes: type a different amount, or accept
     *         that the category is gone. `BudgetsViewModel.displayCode` is what makes them
     *         distinguishable — the `AppError.Validation` they both come from carries the same `code`.
     * Result: the string resource for [code]. An unrecognised code falls back to the generic message
     *         rather than rendering the code itself at the user.
     * Input:  [code] — a code from `BudgetsViewModel`, or `null`. Output: a string resource id.
     */
    @StringRes
    fun errorMessage(code: String?): Int =
        when (code) {
            VALIDATION_AMOUNT -> R.string.budgets_error_bad_amount
            VALIDATION_CATEGORY -> R.string.budgets_error_no_category
            AppError.Storage("").code -> R.string.budgets_error_storage
            AppError.NotFound.code -> R.string.budgets_error_not_found
            else -> R.string.budgets_error_generic
        }

    /** An amount below zero. A budget of ₹0 is allowed — it means "spend nothing here". */
    const val VALIDATION_AMOUNT = "validation:amount"

    /** A category deleted between the sheet opening and Save being tapped. */
    const val VALIDATION_CATEGORY = "validation:categoryId"

    /**
     * Basis points in one percent (MNY-002).
     *
     * The engine works in bps because a rate held as a fraction is a rounding bug waiting to happen;
     * the user reads percent. Converting for display is a change of unit, like paise to rupees in
     * `MoneyFormatter` — **not** a calculation, and the integer division keeps it exact.
     */
    const val BPS_PER_PERCENT = 100
}
