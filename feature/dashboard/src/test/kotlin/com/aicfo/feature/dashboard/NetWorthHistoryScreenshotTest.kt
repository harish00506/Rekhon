package com.aicfo.feature.dashboard

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.networth.NetWorthPoint
import com.aicfo.domain.engines.networth.NetWorthRange
import com.aicfo.domain.engines.networth.NetWorthTrend
import org.junit.Rule
import org.junit.Test

/**
 * The history screen as pixels (issue 6.6; FR-ACC-005, §4.2).
 *
 * Why:  this is the app's **first chart with a scale** — a sparkline of a series that can run
 *       negative, sitting above figures that can too — and none of what can go wrong with it is
 *       visible in a semantics tree. A line drawn off the bottom of its box, a row of four chips
 *       that wraps into two at 200% font, a stroke that vanishes against the dark surface: every one
 *       of those renders as a passing test and a broken screen.
 * What: light, dark, 200% font, and the not-enough-history state.
 * Result: the DoD's dark-mode and large-font evidence for this screen (§4.2).
 * Changelog: 2026-08-30 — Created for issue 6.6.
 *
 * Record baselines with `./gradlew :feature:dashboard:recordPaparazziDebug`; they are committed,
 * because a screenshot test with no committed baseline checks nothing.
 */
class NetWorthHistoryScreenshotTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            theme = "android:Theme.Material.Light.NoActionBar",
        )

    @Test
    fun history_light() {
        paparazzi.snapshot { Screen(populated(), darkTheme = false) }
    }

    @Test
    fun history_dark() {
        paparazzi.snapshot { Screen(populated(), darkTheme = true) }
    }

    /** The accessibility case: four chips and a chart at twice the type size. */
    @Test
    fun history_largeFont() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = LARGE_FONT_SCALE))
        paparazzi.snapshot { Screen(populated(), darkTheme = false) }
    }

    /**
     * One snapshot: the state a profile is in on its first day, where the chart cannot be drawn at
     * all. Worth a baseline because "an empty box where a chart belongs" is exactly the regression
     * this state exists to prevent.
     */
    @Test
    fun history_notEnoughYet() {
        val single =
            trend(
                points = listOf(NetWorthPoint("2026-03-01", Money(5_000_00L))),
                change = null,
                changeBps = null,
            )
        paparazzi.snapshot { Screen(NetWorthHistoryUiState(trend = single, isLoading = false), darkTheme = false) }
    }

    /** Result: the screen under the theme. Input: [uiState]; [darkTheme]. Output: none. */
    @Composable
    private fun Screen(
        uiState: NetWorthHistoryUiState,
        darkTheme: Boolean,
    ) {
        CfoTheme(darkTheme = darkTheme) {
            Surface {
                // No wrapping Column and no padding here: NetWorthHistoryContent applies its own,
                // and a harness that added more would render every baseline at double the real
                // spacing — the bug DashboardScreenshotTest records having shipped once already.
                NetWorthHistoryContent(uiState = uiState, onEvent = {}, onDone = {})
            }
        }
    }

    /** Result: a populated state with a series that dips before recovering. Output: the state. */
    private fun populated() =
        NetWorthHistoryUiState(
            trend =
                trend(
                    points =
                        listOf(
                            NetWorthPoint("2026-03-01", Money(2_10_000_00L)),
                            NetWorthPoint("2026-04-01", Money(2_45_000_00L)),
                            NetWorthPoint("2026-05-01", Money(2_28_000_00L)),
                            NetWorthPoint("2026-06-01", Money(2_92_000_00L)),
                            NetWorthPoint("2026-07-01", Money(3_50_821_00L)),
                        ),
                    change = Money(1_40_821_00L),
                    changeBps = 6_705,
                ),
            isLoading = false,
        )

    /** Result: a trend over [points]. Input: [points]; [change]; [changeBps]. Output: the trend. */
    private fun trend(
        points: List<NetWorthPoint>,
        change: Money?,
        changeBps: Int?,
    ) = NetWorthTrend(
        points = points,
        first = points.firstOrNull(),
        last = points.lastOrNull(),
        change = change,
        changeBps = changeBps,
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

    private companion object {
        /** §4.2's 200% font case. */
        const val LARGE_FONT_SCALE = 2.0f
    }
}
