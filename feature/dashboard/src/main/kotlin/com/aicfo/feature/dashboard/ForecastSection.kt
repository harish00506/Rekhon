package com.aicfo.feature.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.maskedAmount
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.DateFormatter
import com.aicfo.domain.engines.forecast.CashFlowForecast
import com.aicfo.domain.engines.forecast.ItemSource
import com.aicfo.domain.engines.forecast.ScheduledItem

/**
 * The next ninety days (issue 9.2; SRS §9 AI-FCT-001..003; P-02, P-03).
 *
 * Why:  the one figure on the dashboard about the future. It answers the question a forecast exists
 *       for — *when does it get tight, and how tight?* — before the detail: the lowest expected
 *       balance and its date with the likely range beside it, then whether any day falls under the
 *       buffer (AI-FCT-002). **AI-FCT-003 requires every component to be inspectable**, so the three
 *       parts are named with their totals and the next scheduled items are listed by name, each with
 *       where it came from. How much history the everyday estimate rests on is said plainly, and the
 *       rules that fired are named (P-02). Every amount is masked by the privacy blur.
 * What: label; lowest point with its P10–P90; crunch line; the three components; up to three next
 *       scheduled items; the history note; the rules.
 * Result: nothing before the first forecast — a row of zeroes would be a forecast the app made up
 *       (P-03).
 * Changelog: 2026-09-19 — Created for issue 9.2.
 *
 * Input:  [forecast] — or `null`. Output: the composition.
 */
@Composable
internal fun ForecastSection(forecast: CashFlowForecast?) {
    val lowest = forecast?.lowest ?: return

    Text(text = stringResource(R.string.dashboard_forecast_label))
    Text(
        text =
            stringResource(
                R.string.dashboard_forecast_lowest,
                maskedAmount(lowest.p50),
                DateFormatter.day(lowest.date.toString()),
                maskedAmount(lowest.p10),
                maskedAmount(lowest.p90),
            ),
        style = MaterialTheme.typography.bodyMedium,
    )
    CrunchLine(forecast)
    Text(
        text =
            stringResource(
                R.string.dashboard_forecast_components,
                maskedAmount(forecast.scheduledIncome),
                maskedAmount(forecast.scheduledOutflow),
                maskedAmount(forecast.predictedSpend),
            ),
        style = MaterialTheme.typography.bodySmall,
    )
    forecast.scheduled.take(NEXT_ITEMS).forEach { item -> ScheduledLine(item) }
    ProvenanceNotes(forecast)
}

/**
 * How much history the estimate rests on, and the rules that fired (P-02).
 * Why:    a forecast from a fortnight of history reads the same as one from three months unless the
 *         card says which it is; kept apart so [ForecastSection] stays one screen long.
 * Result: the composition. Input: [forecast]. Output: none.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 */
@Composable
private fun ProvenanceNotes(forecast: CashFlowForecast) {
    Note(
        if (forecast.historyDays == 0) {
            stringResource(R.string.dashboard_forecast_no_history)
        } else {
            pluralStringResource(R.plurals.dashboard_forecast_history, forecast.historyDays, forecast.historyDays)
        },
    )
    Note(
        stringResource(
            R.string.dashboard_forecast_rules,
            forecast.provenance.evidence.joinToString(", ") { "${it.ruleId} v${it.ruleVersion}" },
        ),
    )
}

/**
 * Whether any day falls under the buffer (AI-FCT-002, RULE-FCT-CRUNCH).
 * Why:    the count and the first date, in the negative colour when there is one — the day to act
 *         before — and a plain reassurance when there is none, so silence is never ambiguous.
 * Result: the composition. Input: [forecast]. Output: none.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 */
@Composable
private fun CrunchLine(forecast: CashFlowForecast) {
    val first = forecast.crunchDays.firstOrNull()
    if (first == null) {
        Text(
            text = stringResource(R.string.dashboard_forecast_no_crunch, maskedAmount(forecast.buffer)),
            style = MaterialTheme.typography.bodySmall,
            color = CfoTheme.extendedColors.positive,
        )
    } else {
        Text(
            text =
                pluralStringResource(
                    R.plurals.dashboard_forecast_crunch,
                    forecast.crunchDays.size,
                    forecast.crunchDays.size,
                    maskedAmount(forecast.buffer),
                    DateFormatter.day(first.toString()),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = CfoTheme.extendedColors.negative,
        )
    }
}

/**
 * One scheduled item: its date, its name (or a generic word for its source), its amount.
 * Result: the composition. Input: [item]. Output: none.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 */
@Composable
private fun ScheduledLine(item: ScheduledItem) {
    val label = item.label ?: stringResource(item.source.fallbackLabel())
    Text(
        text =
            stringResource(
                R.string.dashboard_forecast_item,
                DateFormatter.day(item.date.toString()),
                label,
                maskedAmount(item.amount),
                stringResource(item.source.sourceLabel()),
            ),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** A small, muted line. Input: [text]. Output: none. */
@Composable
private fun Note(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Where an item came from, in words (AI-FCT-003, P-02).
 * Why:    a `when`, so a new source fails to compile until it has words.
 * Result: a string resource. Input: the receiver. Output: a resource id.
 */
private fun ItemSource.sourceLabel(): Int =
    when (this) {
        ItemSource.RECURRING_RULE -> R.string.dashboard_forecast_source_rule
        ItemSource.FIXED_STREAM -> R.string.dashboard_forecast_source_fixed
        ItemSource.FUTURE_DATED -> R.string.dashboard_forecast_source_future
    }

/** The name shown for an item that has none of its own. Result: a string resource. */
private fun ItemSource.fallbackLabel(): Int =
    when (this) {
        ItemSource.RECURRING_RULE -> R.string.dashboard_forecast_unnamed_rule
        ItemSource.FIXED_STREAM -> R.string.dashboard_forecast_unnamed_fixed
        ItemSource.FUTURE_DATED -> R.string.dashboard_forecast_unnamed_future
    }

/** How many of the next scheduled items the card lists. */
private const val NEXT_ITEMS = 3
