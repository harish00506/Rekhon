package com.aicfo.feature.budgets

import androidx.compose.runtime.Immutable
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.CategoryBudget
import com.aicfo.data.repository.CategoryBudgetAlert
import com.aicfo.data.repository.CategoryBudgetSuggestion
import com.aicfo.domain.engines.budget.BudgetAlertBand
import com.aicfo.domain.engines.budget.BudgetReview

/**
 * Everything the budgets screen renders, as one value (issue 4.4; ARC-004, FR-BUD-001/002/003).
 *
 * Why:  §21.2 requires one immutable state class per screen exposed as a `StateFlow`, so a screen
 *       can never render a half-updated mix and every state it can be in is nameable in a test.
 * What: the loading flag, every category with its budget and live status, the suggestions on offer,
 *       the open editor, and any error to surface.
 * Result: the screen is a pure function of this value.
 * Changelog:
 *   2026-08-11 — Created for issue 4.4.
 *   2026-08-13 — Added the alert bands and the notification prompt for issue 4.5 (FR-BUD-004).
 *   2026-08-15 — Added the monthly review for issue 4.6 (§5.5).
 *
 * **Not one number here is computed on this side** (P-03). [CategoryBudget.status] arrives from
 * `BudgetEngine` through the repository with its provenance attached; the properties below only
 * *select* and *order* rows. The day one of them starts adding two amounts together is the day this
 * screen starts inventing figures.
 *
 * Input:  [isLoading]; [budgets] — every live category, budgeted or not; [suggestions] — proposals
 *         for categories with history and no budget; [alerts] — the bands crossed this month
 *         (FR-BUD-004); [review] — last closed month's review, `null` once dismissed or when
 *         nothing was budgeted (§5.5); [editing] — the open amount sheet, `null` when closed;
 *         [errorCode] — an `AppError.code`, never a message, so the wording stays in `strings.xml`
 *         (§21.6); [requestNotificationPermission] — a one-shot flag asking the screen to prompt.
 * Output: an immutable snapshot for the composable.
 */
@Immutable
data class BudgetsUiState(
    val isLoading: Boolean = true,
    val budgets: List<CategoryBudget> = emptyList(),
    val suggestions: List<CategoryBudgetSuggestion> = emptyList(),
    val alerts: List<CategoryBudgetAlert> = emptyList(),
    val review: BudgetReview? = null,
    val editing: BudgetEditorState? = null,
    val errorCode: String? = null,
    val requestNotificationPermission: Boolean = false,
) {
    /**
     * The band each category is in, if any (FR-BUD-004).
     *
     * Why: a lookup rather than a field on the row, because the alert and the budget arrive from two
     *      different reads and pairing them in the state would mean the screen could render a row
     *      whose band belonged to a stale list. A missing key is the honest answer for a category
     *      that has crossed nothing.
     */
    val bandByCategoryId: Map<String, BudgetAlertBand>
        get() = alerts.associate { it.category.id to it.alert.band }

    /**
     * Every category that has gone past its budget, worst kind first.
     *
     * Why: the banner summarises rather than repeats — a user with four warnings does not want four
     *      banners. Overspends lead because they are the ones that cannot be fixed by spending less
     *      for the rest of the month, only mitigated.
     */
    val overspentAlerts: List<CategoryBudgetAlert>
        get() = alerts.filter { it.alert.band == BudgetAlertBand.EXCEEDED }

    /** Result: the categories at the warn band but still inside their budget. */
    val warnedAlerts: List<CategoryBudgetAlert>
        get() = alerts.filter { it.alert.band == BudgetAlertBand.WARN }

    /**
     * The categories the user has actually planned, worst first.
     *
     * Why: order is the only editorial decision this screen makes, and it is made here rather than in
     *      the composable so it is assertable without rendering. Overspent categories sort above
     *      on-track ones because a budget screen exists to answer "where am I in trouble?" — a list
     *      in category-name order buries the one row that needed an answer today under Groceries.
     */
    val planned: List<CategoryBudget>
        get() =
            budgets.filterNot { it.isUnbudgeted }
                .sortedWith(
                    compareByDescending<CategoryBudget> { it.status.isOverspent }
                        .thenByDescending { it.status.isProjectedToOverspend }
                        .thenByDescending { it.status.isAheadOfPace }
                        .thenBy { it.category.name },
                )

    /**
     * Categories with spending this month and no budget at all.
     *
     * Why: **shown rather than hidden.** A screen that listed only budgeted categories would hide the
     *      spending a user most needs to see — the category they have not thought about yet. Ones
     *      with no spending *and* no budget are left out: a taxonomy has dozens of those and none of
     *      them is news.
     */
    val unplanned: List<CategoryBudget>
        get() =
            budgets.filter { it.isUnbudgeted && it.status.spent > Money.ZERO }
                .sortedByDescending { it.status.spent }

    /**
     * Whether to show the "nothing planned yet" invitation rather than a list.
     *
     * Why: the same distinction the categories editor draws, and for the same
     *      reason — still loading, a failed read, and a genuinely empty plan are three different
     *      states that all leave the list empty, and only the third is an invitation. Suggestions
     *      and the review count as content, for the same reason: a user with proposals waiting, or
     *      a closed month to look back on, has something to act on, and telling them the screen is
     *      empty while showing it to them would contradict itself.
     */
    val isEmpty: Boolean
        get() =
            !isLoading && errorCode == null && planned.isEmpty() && unplanned.isEmpty() &&
                suggestions.isEmpty() && review == null
}

/**
 * The open amount sheet (issue 4.4; FR-BUD-001, ARC-004).
 *
 * Why:  setting a budget and changing one are the same field and the same validation, so they are one
 *       sheet keyed by category rather than two — the shape `CategoryEditorState` already uses, and
 *       for the same reason: duplicating the form is how the two drift apart. It is keyed by
 *       **category** rather than by budget id because the category is what exists in both cases; the
 *       budget row may not exist yet.
 * What: the amount as the user is typing it, the rollover choice, and whether a save is in flight.
 * Result: every state the sheet can be in is assertable.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * Input:  [categoryId]; [categoryName] — for the heading, so the sheet does not have to look it up;
 *         [amountText] — raw, as typed; [rolloverEnabled] — FR-BUD-001's opt-in; [isSaving] — the
 *         write is in flight, so Save is disabled.
 * Output: an immutable value.
 */
@Immutable
data class BudgetEditorState(
    val categoryId: String,
    val categoryName: String,
    val amountText: String = "",
    val rolloverEnabled: Boolean = false,
    val isSaving: Boolean = false,
) {
    /**
     * What the user typed, as money.
     *
     * Why:    parsed through [MoneyFormatter], the one parser in the app that refuses a figure it
     *         cannot represent exactly in paise (MNY-001). A `toDouble()` here would be a
     *         floating-point monetary value and would fail the build (`CfoMoneyAsFloatingPoint`) —
     *         correctly, because ₹0.1 + ₹0.2 is the bug this whole rule exists to prevent.
     * Result: the amount, or `null` when the text is not a number the app can hold exactly.
     */
    val amount: Money? get() = MoneyFormatter.parse(amountText)

    /**
     * Whether Save may proceed.
     *
     * Only parseability is checked here, and only to disable the button. **Negativity is deliberately
     * not duplicated**: the repository rejects a negative amount inside the transaction that writes,
     * which is the only place the answer cannot go stale, and a second copy of the rule is how the
     * screen and the store come to disagree about what is allowed.
     */
    val canSave: Boolean get() = amount != null && !isSaving
}

/**
 * Everything the user can do on the budgets screen (ARC-004).
 *
 * Why:    events flow **up** through a sealed interface, so the composable holds no logic and the
 *         compiler lists every interaction the ViewModel must handle.
 * Result: the screen's complete input surface.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
sealed interface BudgetsEvent {
    /** The user tapped Set or Edit on a category — opens the sheet on it. */
    data class EditClicked(val categoryId: String) : BudgetsEvent

    /** The user typed in the amount field. */
    data class AmountChanged(val value: String) : BudgetsEvent

    /** The user flipped the carry-over switch (FR-BUD-001). */
    data class RolloverChanged(val value: Boolean) : BudgetsEvent

    /** The user tapped Save in the sheet. */
    data object Save : BudgetsEvent

    /** The user dismissed the sheet without saving. */
    data object CancelEdit : BudgetsEvent

    /** The user removed a budget, leaving the category unplanned. */
    data class DeleteClicked(val id: String) : BudgetsEvent

    /**
     * The user accepted a suggested amount (FR-BUD-002's one-tap accept).
     *
     * **A tap, always.** P-07 makes this the whole difference between advice and an order: nothing
     * writes a budget row until this event arrives from a finger.
     */
    data class AcceptSuggestion(val categoryId: String) : BudgetsEvent

    /** The user dismissed the error banner. */
    data object DismissError : BudgetsEvent

    /**
     * The system permission prompt has been answered, whichever way (issue 4.5).
     *
     * **The answer is deliberately not carried.** The screen does not branch on it: a denial is a
     * supported state, the in-app band renders either way, and the notifier re-checks the permission
     * at send time anyway. Recording the answer here would be storing a fact with no reader — and
     * one that goes stale the moment the user changes it in system settings.
     */
    data object NotificationPermissionSettled : BudgetsEvent

    /**
     * The user accepted a reviewed category's proposed adjustment (issue 4.6; §5.5, P-07).
     *
     * **A tap, always** — the same rule [AcceptSuggestion] states, for the same reason: nothing
     * writes a budget row until this event arrives from a finger.
     */
    data class AcceptReviewProposal(val categoryId: String) : BudgetsEvent

    /** The user dismissed the monthly review card (issue 4.6; §5.5). */
    data object DismissReview : BudgetsEvent
}
