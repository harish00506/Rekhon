package com.aicfo.feature.dashboard

import androidx.compose.runtime.Immutable
import com.aicfo.domain.engines.networth.NetWorthRange
import com.aicfo.domain.engines.networth.NetWorthTrend

/**
 * Everything the net-worth history screen shows, in one immutable value (issue 6.6; FR-ACC-005,
 * ARC-004).
 *
 * Why:  one state class per screen as a `StateFlow`, the shape `AllocationUiState` sets — every
 *       reachable state is constructible in a test, and the screen has no second source of truth to
 *       disagree with.
 *
 *       **The engine's trend is held whole rather than flattened into fields.** Copying out the
 *       points and the change would leave the provenance behind, and with it which window the
 *       figures describe — a chart that cannot say what it is charting is exactly the black box
 *       P-02 forbids.
 * What: the trend, which window is selected, and the loading/error flags around them.
 * Result: a screen whose every state is assertable without a device.
 * Changelog: 2026-08-30 — Created for issue 6.6.
 *
 * @property trend the series and the figures measured off it, or `null` before the first read.
 * @property range the window the user picked; [NetWorthRange.SIX_MONTHS] to open on, because a month
 *   of a daily series is a short line and a year is mostly empty for a new user.
 * @property isLoading whether the first read has landed.
 * @property errorCode a failure to show in the banner, or `null`.
 */
@Immutable
data class NetWorthHistoryUiState(
    val trend: NetWorthTrend? = null,
    val range: NetWorthRange = NetWorthRange.SIX_MONTHS,
    val isLoading: Boolean = true,
    val errorCode: String? = null,
) {
    /**
     * Whether to invite a wait rather than draw a line.
     *
     * Why: `CfoSparkline` needs two points and **draws nothing at all below that**, silently. A
     *      profile on its first day has exactly one snapshot, so without this the screen would show
     *      an empty box and no explanation. The same three-way distinction the sibling screens draw:
     *      still loading is not "no history", and neither is a failed read, which has its own banner.
     */
    val isEmpty: Boolean
        get() = (trend?.points?.size ?: 0) < MIN_POINTS_TO_CHART && !isLoading && errorCode == null

    private companion object {
        /** `CfoSparkline`'s own floor — below it the canvas returns without stroking a path. */
        const val MIN_POINTS_TO_CHART = 2
    }
}

/**
 * What the user can do on the history screen (issue 6.6).
 *
 * Why:  a sealed interface rather than lambdas, matching the screens that have real events. There is
 *       exactly one: the four range chips. Nothing here writes, so there is nothing else to send —
 *       the screen reads a record and cannot alter it (P-07).
 * Result: the type `NetWorthHistoryViewModel.onEvent` switches on.
 * Changelog: 2026-08-30 — Created for issue 6.6.
 */
sealed interface NetWorthHistoryEvent {
    /** The user picked a window. Input: [range] — which of FR-ACC-005's four. */
    data class RangeSelected(val range: NetWorthRange) : NetWorthHistoryEvent
}
