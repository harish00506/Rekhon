package com.aicfo.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.networth.NetWorthPoint
import com.aicfo.domain.engines.networth.NetWorthRange
import com.aicfo.domain.engines.networth.NetWorthTrend
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the history screen actually renders (issue 6.6; FR-ACC-005, P-02, P-03).
 *
 * Why:  the ViewModel test proves the right values reach the state; this proves they reach the
 *       *user*, and two of them are refusals that are invisible in a data class:
 *
 *       - **a percentage that must not appear.** The engine returns `null` for a series starting at
 *         or below zero, and a screen that rendered `null` as nothing would leave a user staring at
 *         a gap. It says why instead.
 *       - **a chart that cannot be drawn.** `CfoSparkline` needs two points and draws *nothing*
 *         below that, silently — so a one-snapshot profile must get a sentence, not an empty box.
 * What: the four chips, both change cases, the empty state, and the error.
 * Result: the P-02/P-03 promises are checked on a rendered tree rather than on a state object.
 * Changelog: 2026-08-30 — Created for issue 6.6.
 *
 * Robolectric on the JVM, the `AllocationScreenTest` pattern: the screen is checked on every `test`
 * run rather than only when an emulator happens to be up.
 */
@RunWith(RobolectricTestRunner::class)
class NetWorthHistoryScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `all four of the requirement's windows are offered`() {
        render(NetWorthHistoryUiState(trend = trend(), isLoading = false))

        // FR-ACC-005 names 1M/6M/1Y/All. A missing chip is a requirement quietly unmet.
        listOf("1M", "6M", "1Y", "All").forEach { label ->
            composeRule.onNodeWithText(label).assertExists()
        }
    }

    @Test
    fun `tapping a window asks for that window`() {
        var picked: NetWorthRange? = null
        render(
            state = NetWorthHistoryUiState(trend = trend(), isLoading = false),
            onEvent = { event -> picked = (event as NetWorthHistoryEvent.RangeSelected).range },
        )

        composeRule.onNodeWithText("1Y").performClick()

        assertEquals(NetWorthRange.ONE_YEAR, picked)
    }

    @Test
    fun `a sound percentage is shown beside the amount`() {
        // +25.00% on a series that started above zero — the one case where a ratio means something.
        render(NetWorthHistoryUiState(trend = trend(changeBps = 2_500), isLoading = false))

        composeRule.onNodeWithText("25.00%").assertExists()
    }

    @Test
    fun `an unsound percentage is explained, not left blank`() {
        // The engine refused a percentage because the series started at or below zero. A blank space
        // where a figure belongs reads as a bug; the screen has to say why there is none (P-02).
        render(NetWorthHistoryUiState(trend = trend(changeBps = null), isLoading = false))

        composeRule.onNodeWithText("Percentage isn't meaningful from this starting point").assertExists()
        composeRule.onNodeWithText("%", substring = true).assertDoesNotExist()
    }

    @Test
    fun `one snapshot is explained rather than drawn as an empty chart`() {
        val single = trend(points = listOf(NetWorthPoint("2026-03-01", Money(5_000_00L))), change = null)

        render(NetWorthHistoryUiState(trend = single, isLoading = false))

        composeRule.onNodeWithText("Not enough history yet", substring = true).assertExists()
    }

    @Test
    fun `a failed read raises the banner instead of leaving a stale line`() {
        render(NetWorthHistoryUiState(trend = null, isLoading = false, errorCode = "storage"))

        composeRule.onNodeWithText("Couldn't read your history just now.").assertExists()
    }

    /** Result: renders the screen from a literal state. Input: [state]; [onEvent]. Output: none. */
    private fun render(
        state: NetWorthHistoryUiState,
        onEvent: (NetWorthHistoryEvent) -> Unit = {},
    ) {
        composeRule.setContent {
            CfoTheme { NetWorthHistoryContent(uiState = state, onEvent = onEvent, onDone = {}) }
        }
    }

    /**
     * Result: a trend a test can vary one field of.
     * Input: [points]; [change]; [changeBps]. Output: [NetWorthTrend].
     */
    private fun trend(
        points: List<NetWorthPoint> =
            listOf(
                NetWorthPoint("2026-03-01", Money(10_000_00L)),
                NetWorthPoint("2026-04-01", Money(12_500_00L)),
            ),
        change: Money? = Money(2_500_00L),
        changeBps: Int? = 2_500,
    ) = NetWorthTrend(
        points = points,
        first = points.firstOrNull(),
        last = points.lastOrNull(),
        change = change,
        changeBps = changeBps.takeIf { change != null },
        high = points.maxByOrNull { it.netWorth.minor },
        low = points.minByOrNull { it.netWorth.minor },
        provenance =
            EngineProvenance(
                engineId = "net-worth-trend",
                engineVersion = "1.0",
                computedAtUtcMillis = 1_785_542_400_000L,
                inputWindow = NetWorthRange.SIX_MONTHS.name,
            ),
    )
}
