package com.aicfo.feature.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.chart.CfoSparkline
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.domain.engines.networth.NetWorthPoint
import com.aicfo.domain.engines.networth.NetWorthRange
import com.aicfo.domain.engines.networth.NetWorthTrend

/**
 * How net worth has moved (issue 6.6; FR-ACC-005, P-02, P-03).
 *
 * Why:  FR-ACC-005 asks for "1M/6M/1Y/All charts", and until this screen the app stored a daily
 *       series nobody could see. It is deliberately read-only: the figures are a **record** of what
 *       was true on each day, and nothing here can alter one (P-07).
 * What: the four windows, the line, and the figures the line is drawn from.
 * Result: the stored series, finally visible.
 * Changelog: 2026-08-30 — Created for issue 6.6.
 *
 * Input:  [onDone] — leaves the screen; [viewModel]. Output: the composition.
 */
@Composable
fun NetWorthHistoryScreen(
    onDone: () -> Unit,
    viewModel: NetWorthHistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    NetWorthHistoryContent(uiState = uiState, onEvent = viewModel::onEvent, onDone = onDone)
}

/**
 * The screen, separated from Hilt so a test can drive it from a literal state.
 * Result: the composition. Input: [uiState]; [onEvent]; [onDone]. Output: none.
 * Changelog: 2026-08-30 — Created for issue 6.6.
 */
@Composable
internal fun NetWorthHistoryContent(
    uiState: NetWorthHistoryUiState,
    onEvent: (NetWorthHistoryEvent) -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(
            text = stringResource(R.string.net_worth_history_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        RangeChips(selected = uiState.range, onEvent = onEvent)

        val trend = uiState.trend
        when {
            uiState.errorCode != null -> Text(stringResource(R.string.net_worth_history_error))
            uiState.isEmpty -> Text(stringResource(R.string.net_worth_history_empty))
            trend == null -> Unit
            else -> {
                Chart(trend)
                Change(trend)
                Extremes(trend)
            }
        }

        CfoSecondaryButton(text = stringResource(R.string.net_worth_history_done), onClick = onDone)
    }
}

/**
 * FR-ACC-005's four windows.
 * Why:    `FilterChip` rather than a segmented control, because the row scrolls horizontally at a
 *         200% font scale rather than squeezing four labels into a fixed width — the accessibility
 *         case the screenshot tests cover.
 * Result: the selected chip reads as selected to TalkBack, not merely as coloured.
 * Input:  [selected]; [onEvent]. Output: none.
 * Changelog: 2026-08-30 — Created for issue 6.6.
 */
@Composable
private fun RangeChips(
    selected: NetWorthRange,
    onEvent: (NetWorthHistoryEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
    ) {
        NetWorthRange.entries.forEach { range ->
            FilterChip(
                selected = range == selected,
                onClick = { onEvent(NetWorthHistoryEvent.RangeSelected(range)) },
                label = { Text(stringResource(range.labelRes())) },
            )
        }
    }
}

/**
 * The line itself.
 * Why:    `CfoSparkline` from the design system, which has existed since issue 1.8 documented as
 *         being for exactly this and has never been used. It takes raw paise and draws no axes: the
 *         figures belong beside it as text, which is what [Change] and [Extremes] are.
 *
 *         **The content description names the window and never an amount.** It is a plain string
 *         read aloud, so a figure in it would survive the privacy blur that is masking the same
 *         number on screen (§23) — an accessibility affordance must not become a leak.
 * Result: the shape of the series. Input: [trend]. Output: none.
 * Changelog: 2026-08-30 — Created for issue 6.6.
 */
@Composable
private fun Chart(trend: NetWorthTrend) {
    CfoCard {
        Column(modifier = Modifier.padding(CfoDimens.spaceMd)) {
            CfoSparkline(
                values = trend.points.map { it.netWorth.minor },
                contentDescription = stringResource(R.string.net_worth_history_chart_description),
            )
            val first = trend.first
            val last = trend.last
            if (first != null && last != null) {
                Text(
                    text = stringResource(R.string.net_worth_history_period, first.asOfIsoDate, last.asOfIsoDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * How much it moved, and — only when the question means anything — by what share.
 * Why:    the absolute change is always shown. The percentage is shown only when the engine returned
 *         one, which it does only for a series starting above zero: net worth is routinely negative,
 *         and dividing by a negative reports an improvement as a fall. When it is absent the screen
 *         **says so** rather than rendering nothing, because a missing figure with no explanation
 *         reads as a bug (P-02, P-03).
 * Result: the change. Input: [trend]. Output: none.
 * Changelog: 2026-08-30 — Created for issue 6.6.
 */
@Composable
private fun Change(trend: NetWorthTrend) {
    val change = trend.change ?: return
    CfoCard {
        Column(modifier = Modifier.padding(CfoDimens.spaceMd)) {
            Text(stringResource(R.string.net_worth_history_change_label))
            CfoAmountText(amount = change, showSign = true)

            val bps = trend.changeBps
            if (bps == null) {
                Text(
                    text = stringResource(R.string.net_worth_history_percent_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.net_worth_history_percent, bps.asPercentText()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * The highest and lowest days in the window.
 * Why:    P-02 — the line's peak and trough are the two points a user looks for, and naming their
 *         dates turns a shape into something checkable against their own memory.
 * Result: two rows. Input: [trend]. Output: none.
 * Changelog: 2026-08-30 — Created for issue 6.6.
 */
@Composable
private fun Extremes(trend: NetWorthTrend) {
    val high = trend.high
    val low = trend.low
    if (high == null || low == null) return
    CfoCard {
        Column(modifier = Modifier.padding(CfoDimens.spaceMd)) {
            ExtremeRow(R.string.net_worth_history_high, high)
            ExtremeRow(R.string.net_worth_history_low, low)
        }
    }
}

/** Result: one labelled extreme. Input: [labelRes]; [point]. Output: none. */
@Composable
private fun ExtremeRow(
    labelRes: Int,
    point: NetWorthPoint,
) {
    Text("${stringResource(labelRes)} · ${point.asOfIsoDate}")
    CfoAmountText(amount = point.netWorth, showSign = true)
}

/**
 * Result: the chip's label. Input: the receiver. Output: a string resource id.
 * Kept as a `when` rather than a field on the enum: `NetWorthRange` is a pure-Kotlin domain type
 * (ARC-002) and cannot name an Android resource.
 */
private fun NetWorthRange.labelRes(): Int =
    when (this) {
        NetWorthRange.ONE_MONTH -> R.string.net_worth_history_range_1m
        NetWorthRange.SIX_MONTHS -> R.string.net_worth_history_range_6m
        NetWorthRange.ONE_YEAR -> R.string.net_worth_history_range_1y
        NetWorthRange.ALL -> R.string.net_worth_history_range_all
    }

/**
 * Renders basis points as a percentage.
 * Why:    integer arithmetic all the way to the string (MNY-002) — `bps / 100` and the remainder,
 *         rather than a `Double` division that would put a floating-point value on a screen showing
 *         money. Two decimal places because that is exactly what basis points carry.
 * Result: e.g. `1234` → `"12.34"`, `-333` → `"-3.33"`.
 * Input:  the receiver — basis points. Output: [String].
 * Changelog: 2026-08-30 — Created for issue 6.6.
 */
private fun Int.asPercentText(): String {
    val sign = if (this < 0) "-" else ""
    val magnitude = kotlin.math.abs(this)
    return "$sign${magnitude / BPS_PER_PERCENT}.${(magnitude % BPS_PER_PERCENT).toString().padStart(2, '0')}"
}

/** One percent, in basis points. */
private const val BPS_PER_PERCENT = 100
