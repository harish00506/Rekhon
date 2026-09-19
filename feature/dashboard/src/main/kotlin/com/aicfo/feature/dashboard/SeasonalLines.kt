package com.aicfo.feature.dashboard

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.maskedAmount
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.forecast.CashFlowForecast
import com.aicfo.domain.engines.forecast.SeasonalMonth
import com.aicfo.domain.engines.seasonality.SpendFactor
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

/**
 * The forecast's three components, with the seasonal term beside them when there is one (issue 9.3).
 *
 * Why:  §9.2's formula has four terms, and AI-FCT-003 asks for each to be inspectable. The seasonal
 *       term is named as an extra or a saving — never a bare signed number a reader has to decode.
 * Result: the composition. Input: [forecast]. Output: none.
 * Changelog: 2026-09-19 — Moved out of `ForecastSection` and given the seasonal term for issue 9.3.
 */
@Composable
internal fun ComponentsLine(forecast: CashFlowForecast) {
    val seasonal = forecast.seasonalAdjustment.minor
    val income = maskedAmount(forecast.scheduledIncome)
    val outflow = maskedAmount(forecast.scheduledOutflow)
    val everyday = maskedAmount(forecast.predictedSpend)
    val seasonalText = maskedAmount(Money(seasonal.absoluteValue))
    val text =
        when {
            seasonal > 0L ->
                stringResource(R.string.dashboard_forecast_components_extra, income, outflow, everyday, seasonalText)
            seasonal < 0L ->
                stringResource(R.string.dashboard_forecast_components_saving, income, outflow, everyday, seasonalText)
            else -> stringResource(R.string.dashboard_forecast_components, income, outflow, everyday)
        }
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

/**
 * Each month the season moves, with why (issue 9.3; §9.3, P-02).
 *
 * Why:  the acceptance criterion — "the adjustment is shown separately with the rule that fired" —
 *       and §9.3's own example, *"October festival spending typically +38% — plan ₹6,500 extra"*.
 *       The percentage is measured against the last ninety days, because that is what the everyday
 *       prediction is built from; the reasons are the calendar events AI-SEAS named, the ones that
 *       have passed since the lookback, and the user's own earlier years.
 * Result: one line per month the seasonal term moved; nothing when it moved none.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 *
 * Input:  [forecast]. Output: the composition.
 */
@Composable
internal fun SeasonalLines(forecast: CashFlowForecast) {
    forecast.seasonalMonths.forEach { SeasonalLine(it) }
}

/**
 * One month's line.
 * Why:    up or down is read from the **amount**, so the sentence and the rupees can never
 *         disagree; the percentage is the factor's distance from ×1, to a tenth, in integer
 *         arithmetic (MNY-002). The amount is masked by the privacy blur like every other.
 * Result: the composition. Input: [month]. Output: none.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
@Composable
private fun SeasonalLine(month: SeasonalMonth) {
    val distance = (month.factor.factorBps - BPS_PER_UNIT).absoluteValue
    val percent = "${distance / BPS_PER_PERCENT}.${distance % BPS_PER_PERCENT / BPS_PER_TENTH}"
    val up = month.adjustment.minor > 0L
    Text(
        text =
            stringResource(
                if (up) R.string.dashboard_forecast_seasonal_up else R.string.dashboard_forecast_seasonal_down,
                month.factor.month.format(MONTH),
                percent,
                maskedAmount(Money(month.adjustment.minor.absoluteValue)),
                reasons(month.factor),
            ),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Why a month moved, in words.
 * Result: the named events, those that have passed, and "as in your past years" — or "the season"
 *         when nothing was large enough to name. Input: [factor]. Output: [String].
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
@Composable
private fun reasons(factor: SpendFactor): String {
    val parts =
        factor.rising.map { stringResource(seasonName(it)) } +
            factor.easing.map { stringResource(R.string.dashboard_season_after, stringResource(seasonName(it))) } +
            if (factor.fromOwnHistory) listOf(stringResource(R.string.dashboard_season_own)) else emptyList()
    return if (parts.isEmpty()) stringResource(R.string.dashboard_season_generic) else parts.joinToString(", ")
}

/**
 * A calendar event's display name.
 * Why:    the event ids are `calendar-seasonality.json`'s; a `when` keeps the words here, where they
 *         can be translated, and an id added to the knowledge base before it has a name reads as
 *         "the season" rather than as `wedding_season` (the budget card's same rule, ADR-0044).
 * Result: a string resource. Input: [eventId]. Output: a resource id.
 */
@StringRes
private fun seasonName(eventId: String): Int =
    when (eventId) {
        "diwali" -> R.string.dashboard_season_diwali
        "dussehra_navratri" -> R.string.dashboard_season_dussehra_navratri
        "wedding_season" -> R.string.dashboard_season_wedding
        "tax_saving_rush" -> R.string.dashboard_season_tax_saving
        "school_admission" -> R.string.dashboard_season_school_admission
        "monsoon" -> R.string.dashboard_season_monsoon
        "summer" -> R.string.dashboard_season_summer
        "onam_pongal" -> R.string.dashboard_season_onam_pongal
        "akshaya_tritiya" -> R.string.dashboard_season_akshaya_tritiya
        else -> R.string.dashboard_season_generic
    }

/** "October 2026", in the device's locale. */
private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy")

/** 10 000 bps = ×1; 100 bps = 1%; 10 bps = a tenth of a percent (MNY-002). */
private const val BPS_PER_UNIT = 10_000
private const val BPS_PER_PERCENT = 100
private const val BPS_PER_TENTH = 10
