package com.aicfo.feature.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.maskedAmount
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.DateFormatter
import com.aicfo.core.model.Money
import com.aicfo.data.repository.FeedInsight
import com.aicfo.domain.engines.insight.Insight
import com.aicfo.domain.engines.insight.InsightType
import com.aicfo.domain.engines.insight.Severity

/**
 * What needs attention — the Insight Orchestrator's feed (issue 9.5; §7.2, FR-HOME-001, FR-AI-002).
 *
 * Why:  FR-HOME-001 asks the dashboard for the "top 3 AI insights", and FR-AI-002 says what a card
 *       must carry: the finding, the evidence behind it, its confidence, and **at most one**
 *       recommended action. Every figure here was published by another engine, and the card names
 *       which one (AI-ARC-006) — so a reader can always get from "you are over budget" to the
 *       engine, the rule and the version that said so.
 * What: one card per insight, each with the two verdicts the user can give it (§FR-AI-001's
 *       "dismiss / snooze / act").
 * Result: nothing at all when the feed is empty — silence is the app agreeing that nothing needs
 *       attention, which is a claim worth making by saying nothing.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 *
 * Input:  [insights]; [onDismiss] and [onSnooze] — the verdicts. Output: the composition.
 */
@Composable
internal fun InsightFeed(
    uiState: DashboardUiState,
    onEvent: (DashboardEvent) -> Unit,
) {
    InsightFeedSection(
        insights = uiState.insights,
        onDismiss = { onEvent(DashboardEvent.InsightDismissed(it)) },
        onSnooze = { onEvent(DashboardEvent.InsightSnoozed(it)) },
    )
}

/**
 * The feed itself, with no dependency on the screen's state (issue 9.5).
 * Why:    stateless, so a render test can pass any feed without a ViewModel — the split
 *         `DashboardContent` already makes for the screen as a whole.
 * Result: the composition. Input: [insights]; [onDismiss]; [onSnooze]. Output: none.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
@Composable
internal fun InsightFeedSection(
    insights: List<FeedInsight>,
    onDismiss: (String) -> Unit,
    onSnooze: (String) -> Unit,
) {
    if (insights.isEmpty()) return
    Text(text = stringResource(R.string.dashboard_insights_label))
    insights.forEach { card ->
        InsightCard(card = card, onDismiss = onDismiss, onSnooze = onSnooze)
    }
}

/**
 * One card: the finding, where it came from, what to do, and the two ways to put it away.
 * Why:    the finding is coloured by severity — the negative colour only for a CRITICAL card, so
 *         "over budget by ₹1,250" does not shout as loudly as "money runs short on the 5th"
 *         (§14's anti-anxiety tone, applied to the feed).
 * Result: the composition. Input: [card]; [onDismiss]; [onSnooze]. Output: none.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
@Composable
private fun InsightCard(
    card: FeedInsight,
    onDismiss: (String) -> Unit,
    onSnooze: (String) -> Unit,
) {
    val insight = card.insight
    Text(
        text = finding(insight),
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (insight.severity == Severity.CRITICAL) {
                CfoTheme.extendedColors.negative
            } else {
                MaterialTheme.colorScheme.onSurface
            },
    )
    Text(
        text = stringResource(insight.type.action()),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text =
            stringResource(
                R.string.dashboard_insights_evidence,
                insight.sourceEngineId,
                insight.sourceEngineVersion,
                insight.citations.joinToString(", ") { "${it.ruleId} v${it.ruleVersion}" },
            ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row {
        TextButton(onClick = { onSnooze(card.id) }) { Text(stringResource(R.string.dashboard_insights_later)) }
        TextButton(onClick = { onDismiss(card.id) }) { Text(stringResource(R.string.dashboard_insights_dismiss)) }
    }
}

/**
 * The finding, as a sentence built from the card's own figures.
 * Why:    §21.6 — the words are resources and the numbers are the engine's; the composable only
 *         puts them together. Every amount goes through the privacy blur like any other.
 * Result: the sentence. Input: [insight]. Output: [String].
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
@Composable
private fun finding(insight: Insight): String {
    val amount = maskedAmount(insight.amount ?: Money.ZERO)
    val secondary = maskedAmount(insight.secondary ?: Money.ZERO)
    val count = insight.quantity ?: 0
    return when (insight.type) {
        InsightType.CRUNCH_DAY ->
            pluralStringResource(
                R.plurals.dashboard_insights_crunch,
                count,
                count,
                insight.date?.toString()?.let(DateFormatter::day).orEmpty(),
                amount,
                secondary,
            )
        InsightType.BUDGET_OVERSPENT ->
            stringResource(R.string.dashboard_insights_budget, insight.subjectLabel.orEmpty(), amount)
        InsightType.EMERGENCY_FUND_SHORT ->
            stringResource(R.string.dashboard_insights_emergency, amount, secondary)
        InsightType.GOAL_BEHIND ->
            stringResource(
                R.string.dashboard_insights_goal,
                insight.subjectLabel.orEmpty(),
                amount,
                DateFormatter.day(insight.period),
            )
        InsightType.SEASONAL_MONTH -> stringResource(R.string.dashboard_insights_seasonal, insight.period, amount)
        InsightType.HEALTH_LEVER ->
            pluralStringResource(
                R.plurals.dashboard_insights_lever,
                count,
                count,
                stringResource(leverName(insight.subject)),
            )
    }
}

/**
 * The one recommended action (FR-AI-002 — at most one).
 * Why:    a `when`, so a new insight type fails to compile until someone has decided what the user
 *         should do about it. A card with no action would be a worry with no exit.
 * Result: a string resource. Input: the receiver. Output: a resource id.
 */
@StringRes
private fun InsightType.action(): Int =
    when (this) {
        InsightType.CRUNCH_DAY -> R.string.dashboard_insights_action_crunch
        InsightType.BUDGET_OVERSPENT -> R.string.dashboard_insights_action_budget
        InsightType.EMERGENCY_FUND_SHORT -> R.string.dashboard_insights_action_emergency
        InsightType.GOAL_BEHIND -> R.string.dashboard_insights_action_goal
        InsightType.SEASONAL_MONTH -> R.string.dashboard_insights_action_seasonal
        InsightType.HEALTH_LEVER -> R.string.dashboard_insights_action_lever
    }

/**
 * The health lever's name, reusing the health card's own words so one signal is called one thing
 * across the screen. Result: a string resource. Input: [signal] — AI-FHS's signal name.
 */
@StringRes
private fun leverName(signal: String?): Int =
    when (signal) {
        "RUNWAY" -> R.string.dashboard_health_lever_runway
        "OBLIGATIONS" -> R.string.dashboard_health_lever_obligations
        "CARD_UTILISATION" -> R.string.dashboard_health_lever_cards
        "SAVINGS_RATE" -> R.string.dashboard_health_lever_savings
        "BUDGET_ADHERENCE" -> R.string.dashboard_health_lever_budgets
        else -> R.string.dashboard_health_lever_goals
    }
