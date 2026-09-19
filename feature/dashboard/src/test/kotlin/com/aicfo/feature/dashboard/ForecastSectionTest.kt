package com.aicfo.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.DateFormatter
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.domain.engines.forecast.CashFlowForecast
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The forecast card, rendered (issue 9.2; §9 AI-FCT-001..003, P-02, P-03).
 *
 * Why:  the ViewModel test proves the figures; only a rendered test proves the user reads the
 *       lowest point with its range, is told plainly whether any day dips under the buffer
 *       (AI-FCT-002), can see each component and the next scheduled items by name with where they
 *       came from (AI-FCT-003), and sees nothing at all before there is a forecast (P-03).
 * What: the lowest line; the crunch and no-crunch lines; the components; a scheduled item; the
 *       history note and the rules; the empty case.
 * Result: the card's copy is checked on every `unitTests` run.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 *            2026-09-19 — Issue 9.3: the seasonal components line, the month lines, and their absence.
 */
@RunWith(RobolectricTestRunner::class)
class ForecastSectionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the lowest point is shown with its date and likely range`() {
        val forecast = fixtureForecast()
        render(forecast)

        val lowest = forecast.lowest!!
        compose.onNodeWithText(
            text(
                R.string.dashboard_forecast_lowest,
                MoneyFormatter.format(lowest.p50),
                DateFormatter.day(lowest.date.toString()),
                MoneyFormatter.format(lowest.p10),
                MoneyFormatter.format(lowest.p90),
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun `a forecast that never dips says so`() {
        render(fixtureForecast(opening = 50_000_00L))

        compose.onNodeWithText(text(R.string.dashboard_forecast_no_crunch, "₹5,000.00")).assertIsDisplayed()
    }

    @Test
    fun `a forecast that dips names how many days and the first one`() {
        val forecast = fixtureForecast(opening = 4_000_00L)
        render(forecast)

        val first = DateFormatter.day(forecast.crunchDays.first().toString())
        val size = forecast.crunchDays.size
        compose.onNodeWithText(
            compose.activity.resources.getQuantityString(
                R.plurals.dashboard_forecast_crunch,
                size,
                size,
                "₹5,000.00",
                first,
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun `the components and the next scheduled item are inspectable`() {
        val forecast = fixtureForecast(seasonal = false)
        render(forecast)

        compose.onNodeWithText(
            text(
                R.string.dashboard_forecast_components,
                MoneyFormatter.format(forecast.scheduledIncome),
                MoneyFormatter.format(forecast.scheduledOutflow),
                MoneyFormatter.format(forecast.predictedSpend),
            ),
        ).assertIsDisplayed()
        val salary = forecast.scheduled.first()
        compose.onNodeWithText(
            text(
                R.string.dashboard_forecast_item,
                DateFormatter.day(salary.date.toString()),
                "Employer",
                MoneyFormatter.format(salary.amount),
                text(R.string.dashboard_forecast_source_rule),
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun `the history behind the estimate and the rules are named`() {
        render(fixtureForecast())

        compose.onNodeWithText(
            compose.activity.resources.getQuantityString(R.plurals.dashboard_forecast_history, 90, 90),
        )
            .assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.dashboard_forecast_rules, "RULE-FCT-METHOD v1.0, RULE-FCT-CRUNCH v1.0, SEAS-INDEX v1.0"),
        ).assertIsDisplayed()
    }

    @Test
    fun `the seasonal term is named beside the components as an extra or a saving`() {
        val forecast = fixtureForecast()
        render(forecast)

        // October +20% of ₹200 a day for 31 days; November −10% for 30: ₹1,240 − ₹600 = ₹640 extra.
        assertEquals(Money(640_00L), forecast.seasonalAdjustment)
        compose.onNodeWithText(
            text(
                R.string.dashboard_forecast_components_extra,
                MoneyFormatter.format(forecast.scheduledIncome),
                MoneyFormatter.format(forecast.scheduledOutflow),
                MoneyFormatter.format(forecast.predictedSpend),
                "₹640.00",
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun `each month the season moves says how much, against what, and why`() {
        render(fixtureForecast())

        compose.onNodeWithText(
            text(R.string.dashboard_forecast_seasonal_up, "October 2026", "20.0", "₹1,240.00", "Diwali"),
        ).assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.dashboard_forecast_seasonal_down, "November 2026", "10.0", "₹600.00", "after the monsoon"),
        ).assertIsDisplayed()
    }

    @Test
    fun `with no seasonal term there is no seasonal line and the plain components`() {
        val forecast = fixtureForecast(seasonal = false)
        render(forecast)

        assertEquals(
            0,
            compose.onAllNodes(
                hasText("everyday spending than the last 90 days", substring = true),
            ).fetchSemanticsNodes().size,
        )
        assertEquals(0, compose.onAllNodes(hasText("Seasonal", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun `no forecast yet shows no card`() {
        render(null)

        assertEquals(0, compose.onAllNodes(hasText(text(R.string.dashboard_forecast_label))).fetchSemanticsNodes().size)
    }

    private fun render(forecast: CashFlowForecast?) {
        compose.setContent { CfoTheme { Column { ForecastSection(forecast) } } }
    }

    private fun text(
        id: Int,
        vararg args: Any,
    ): String = if (args.isEmpty()) compose.activity.getString(id) else compose.activity.getString(id, *args)
}
