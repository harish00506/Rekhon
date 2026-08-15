package com.aicfo.feature.categories

import androidx.annotation.StringRes
import com.aicfo.core.common.AppError
import com.aicfo.core.model.CategoryNature

/**
 * Maps the domain's closed sets to this feature's strings (issue 4.1; §21.6).
 *
 * Why:  `CategoryNature` lives in `:core:model`, which is pure Kotlin and must stay that way
 *       (ARC-002) — an `@StringRes` on the enum would drag Android into the module every engine
 *       depends on. And `AppError` carries a **code**, never a message, so the wording lives where
 *       it can be translated. Both mappings therefore belong here, in the feature that renders them,
 *       exactly as `AccountLabels` does for accounts.
 * What: one `when` per closed set, exhaustive so the compiler names a missing case.
 * Result: adding a nature is a compile error until it has a label.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
internal object CategoryLabels {
    /**
     * The label for a nature.
     * Why:    exhaustive `when` rather than a map keyed by the enum — a map compiles fine with a
     *         missing key and fails at runtime, which here means a whole section of the list
     *         rendering with a blank heading.
     * Result: the string resource for [nature].
     * Input:  [nature]. Output: a string resource id.
     */
    @StringRes
    fun natureLabel(nature: CategoryNature): Int =
        when (nature) {
            CategoryNature.NEED -> R.string.category_nature_need
            CategoryNature.WANT -> R.string.category_nature_want
            CategoryNature.INVEST -> R.string.category_nature_invest
            CategoryNature.ASSET -> R.string.category_nature_asset
            CategoryNature.LIABILITY -> R.string.category_nature_liability
        }

    /**
     * The message for an error code.
     *
     * Why:    the state carries a code rather than a sentence, so the screen decides the wording and
     *         a translator can change it. **The three validation codes are separate entries and not
     *         one shared "check what you entered"**, because they are three different refusals with
     *         three different fixes: rename it, pick a different parent, or delete the children
     *         first. `CategoriesViewModel.displayCode` is what makes them distinguishable — the
     *         `AppError.Validation` they all come from carries the same `code`.
     * Result: the string resource for [code]. An unrecognised code falls back to the generic message
     *         rather than rendering the code itself at the user.
     * Input:  [code] — a code from `CategoriesViewModel`, or `null`. Output: a string resource id.
     */
    @StringRes
    fun errorMessage(code: String?): Int =
        when (code) {
            VALIDATION_NAME -> R.string.categories_error_duplicate_name
            VALIDATION_PARENT -> R.string.categories_error_bad_parent
            VALIDATION_CHILDREN -> R.string.categories_error_has_children
            AppError.Storage("").code -> R.string.categories_error_storage
            AppError.NotFound.code -> R.string.categories_error_not_found
            else -> R.string.categories_error_generic
        }

    /** A name that is blank or already taken on this profile. */
    const val VALIDATION_NAME = "validation:name"

    /** A parent that does not exist, is itself a child, or would make the taxonomy three deep. */
    const val VALIDATION_PARENT = "validation:parentId"

    /** A delete refused because the category still has live children. */
    const val VALIDATION_CHILDREN = "validation:children"
}
