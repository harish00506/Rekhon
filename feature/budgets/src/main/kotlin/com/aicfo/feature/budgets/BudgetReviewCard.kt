package com.aicfo.feature.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoListRow
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.domain.engines.budget.BudgetReview
import com.aicfo.domain.engines.budget.ReviewedCategory
import com.aicfo.domain.engines.budget.VarianceDirection

/**
 * Last closed month, looked back on (issue 4.6; §5.5, P-02, P-07).
 *
 * Why:  the section where the same two golden rules `SuggestionsSection` is built around apply a
 *       month later. **P-07** — nothing changes until [CfoButton] is tapped; there is no "apply
 *       all", and reading the card without acting on it changes nothing. **P-02** — a variance is
 *       never shown as a bare percentage: the two figures behind it and the rule that decided it was
 *       worth mentioning are on the card too, so a user can disagree with the finding rather than
 *       only with the number.
 * What: the month's totals, one row per material category, and a way to close the card.
 * Result: FR-BUD-*'s month-end summary with §5.5's proposed adjustments, each accept-only.
 * Changelog: 2026-08-15 — Created for issue 4.6.
 *
 * Input:  [uiState]; [onEvent]. Output: the composition, or nothing when there is no review to show.
 */
@Composable
internal fun BudgetReviewSection(
    uiState: BudgetsUiState,
    onEvent: (BudgetsEvent) -> Unit,
) {
    val review = uiState.review ?: return
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(text = stringResource(R.string.budgets_review_title), style = MaterialTheme.typography.titleMedium)
            Text(text = review.summary(), style = MaterialTheme.typography.bodyMedium)
            Text(text = review.citation(), style = MaterialTheme.typography.bodySmall)
            review.materialCategories.forEach { category ->
                ReviewedCategoryRow(category = category, onEvent = onEvent)
            }
            CfoSecondaryButton(
                text = stringResource(R.string.budgets_review_dismiss),
                onClick = { onEvent(BudgetsEvent.DismissReview) },
            )
        }
    }
}

/**
 * One category's finding, with its proposal when the engine had enough history to price one.
 * Why:    split out of [BudgetReviewSection] for the 40-line limit (§21.6), the same seam
 *         `SuggestionCard` was cut along.
 * Result: the variance, its rule, and — when there is one — the proposed amount with an Accept
 *         button. A row without a proposal says so rather than showing nothing (P-03: the app does
 *         not fabricate a figure it could not price).
 * Input:  [category]; [onEvent]. Output: the composition.
 * Changelog: 2026-08-15 — Created for issue 4.6.
 */
@Composable
private fun ReviewedCategoryRow(
    category: ReviewedCategory,
    onEvent: (BudgetsEvent) -> Unit,
) {
    CfoListRow(
        title = category.categoryName,
        trailing = {
            BudgetAmountText(
                amount = category.actual,
                contentDescription = stringResource(R.string.budgets_spent_description),
            )
        },
    )
    Text(text = category.varianceSentence(), style = MaterialTheme.typography.bodyMedium)
    Text(text = category.citation(), style = MaterialTheme.typography.bodySmall)
    val proposal = category.proposal
    if (proposal == null) {
        Text(text = stringResource(R.string.budgets_review_no_history), style = MaterialTheme.typography.bodySmall)
    } else {
        CfoButton(
            text = stringResource(R.string.budgets_suggestion_accept),
            onClick = { onEvent(BudgetsEvent.AcceptReviewProposal(category.categoryId)) },
        )
    }
}

/** Result: "you planned X and spent Y in total" (P-02) — the headline before any one row. */
@Composable
private fun BudgetReview.summary(): String =
    stringResource(
        R.string.budgets_review_summary,
        MoneyFormatter.format(totalBudgeted),
        MoneyFormatter.format(totalActual),
    )

/**
 * Result: the rule that decided this month was worth reviewing at all (P-02, AI-ARC-006).
 *
 * `RULE-BUD-REVIEW`, cited once for the whole card — each material row's own citation cites
 * `RULE-BUD-SUGGEST` instead, the rule that priced *that* row's proposal. `BudgetReview`'s own KDoc
 * is where this two-rule split is recorded.
 */
@Composable
private fun BudgetReview.citation(): String {
    val rule = provenance.evidence.first()
    return stringResource(R.string.budgets_reason_rule, rule.ruleId, rule.ruleVersion)
}

/**
 * Result: the direction stated as a sentence, never a bare sign — [VarianceDirection.ON_PLAN] never
 *         reaches this composable, since [BudgetReviewSection] only renders material categories.
 */
@Composable
private fun ReviewedCategory.varianceSentence(): String {
    val actual = MoneyFormatter.format(this.actual)
    val budgeted = MoneyFormatter.format(this.budgeted)
    return when (direction) {
        VarianceDirection.OVER -> stringResource(R.string.budgets_review_over, actual, budgeted, variancePercent)
        VarianceDirection.UNDER -> stringResource(R.string.budgets_review_under, actual, budgeted, variancePercent)
        VarianceDirection.ON_PLAN -> ""
    }
}

/** Result: the rule that priced this row's proposal, empty when there is none (P-02, AI-ARC-006). */
@Composable
private fun ReviewedCategory.citation(): String {
    // RULE-BUD-REVIEW decided *whether* to speak, attached to the review as a whole, not to each
    // category — a proposal's own citation (RULE-BUD-SUGGEST) is what decided *what to say*, and
    // the two are shown separately for the reason BudgetEngine.BudgetReview's own KDoc gives.
    val rule = proposal?.provenance?.evidence?.first()
    return if (rule == null) "" else stringResource(R.string.budgets_reason_rule, rule.ruleId, rule.ruleVersion)
}
