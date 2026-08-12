package com.aicfo.feature.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoListRow
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.CategoryBudgetSuggestion

/**
 * What the app proposes, and why (issue 4.4; FR-BUD-002, P-02, P-07).
 *
 * Why:  the section where two golden rules are visible or they are not implemented. **P-07** — the
 *       app recommends and the user decides — means nothing here is applied until [CfoButton] is
 *       tapped; there is no "apply all", and dismissing the screen changes nothing. **P-02** — show
 *       the work — means the amount is never shown alone: the median it came from, the festival that
 *       moved it and the rule that fired are all on the card, so a user can disagree with the
 *       reasoning rather than only with the number.
 * What: one card per suggestible category.
 * Result: FR-BUD-002's one-tap accept, with its evidence attached.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * Input:  [uiState]; [onEvent]. Output: the composition.
 */
@Composable
internal fun SuggestionsSection(
    uiState: BudgetsUiState,
    onEvent: (BudgetsEvent) -> Unit,
) {
    if (uiState.suggestions.isEmpty()) return
    Text(
        text = stringResource(R.string.budgets_section_suggested),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = stringResource(R.string.budgets_suggested_intro),
        style = MaterialTheme.typography.bodySmall,
    )
    uiState.suggestions.forEach { suggestion ->
        SuggestionCard(suggestion = suggestion, onEvent = onEvent)
    }
}

/**
 * One proposal.
 * Why:    split out of [SuggestionsSection] for the 40-line limit (§21.6).
 * Result: a card with the amount, the reasoning and an Accept button.
 * Input:  [suggestion]; [onEvent]. Output: the composition.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
@Composable
private fun SuggestionCard(
    suggestion: CategoryBudgetSuggestion,
    onEvent: (BudgetsEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            CfoListRow(
                title = suggestion.category.name,
                trailing = {
                    CfoAmountText(
                        amount = suggestion.suggestion.amount,
                        contentDescription = stringResource(R.string.budgets_suggested_description),
                        showSign = false,
                    )
                },
            )
            Text(text = suggestion.reason(), style = MaterialTheme.typography.bodyMedium)
            Text(text = suggestion.citation(), style = MaterialTheme.typography.bodySmall)
            CfoButton(
                text = stringResource(R.string.budgets_suggestion_accept),
                onClick = { onEvent(BudgetsEvent.AcceptSuggestion(suggestion.category.id)) },
            )
        }
    }
}

/**
 * Result: why the amount is what it is, in a sentence.
 *
 * Two cases. An unadjusted suggestion is the median of the months read, and says so. A seasonal one
 * says the median **and** what was added to it and for what — a user who is told "₹6,900 for
 * Shopping" learns nothing, while one told "usually ₹5,000, plus 38% for Diwali" can tell the app it
 * is wrong about their Diwali. The uplift is read from the engine's basis points and only *displayed*
 * as a percentage (see [BudgetLabels.BPS_PER_PERCENT]); nothing here recomputes the amount (P-03).
 *
 * A festival this build has no name for falls back to a sentence that states the uplift without
 * naming it, rather than rendering an internal id at the user. Output: a [String].
 */
@Composable
private fun CategoryBudgetSuggestion.reason(): String {
    val median = MoneyFormatter.format(suggestion.medianAmount)
    if (!suggestion.isSeasonallyAdjusted) {
        return stringResource(R.string.budgets_reason_median, median)
    }
    val upliftPercent = (suggestion.seasonalIndexBps - BPS_FULL) / BudgetLabels.BPS_PER_PERCENT
    val eventLabel = suggestion.seasonalEventId?.let { BudgetLabels.seasonalEventLabel(it) }
    return if (eventLabel == null) {
        stringResource(R.string.budgets_reason_seasonal_generic, median, upliftPercent)
    } else {
        stringResource(R.string.budgets_reason_seasonal, median, upliftPercent, stringResource(eventLabel))
    }
}

/**
 * Result: the rule that produced the amount, named and versioned (P-02, AI-ARC-006).
 *
 * Shown on the card rather than hidden behind a tap: §29's governance clause is that a figure the app
 * proposes can be traced to the row that produced it, and a citation nobody can see is not a
 * citation. The evidence list's first entry is the rule; a seasonal suggestion appends the calendar
 * event, which the sentence above already names in words. Output: a [String].
 */
@Composable
private fun CategoryBudgetSuggestion.citation(): String {
    val rule = suggestion.provenance.evidence.first()
    return stringResource(R.string.budgets_reason_rule, rule.ruleId, rule.ruleVersion)
}

/**
 * 10 000 bps = 100% (MNY-002) — the engine's "no adjustment" index.
 *
 * Duplicated here rather than exposed from the engine module: it is a property of the basis-point
 * unit itself, not of the budget rules, and `:domain:engines:budget` keeps its copy `internal`
 * deliberately so that nothing outside it can build a rate by hand.
 */
private const val BPS_FULL = 10_000
