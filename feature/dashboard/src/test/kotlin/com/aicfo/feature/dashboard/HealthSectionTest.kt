package com.aicfo.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.domain.engines.healthscore.HealthScore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The health card, rendered (issue 9.4; §14, FR-AI-001, P-02).
 *
 * Why:  the engine proves the numbers; only a rendered test proves the user can read the total with
 *       its band, each pillar's points and share, a missing pillar said to be missing (not shown as
 *       zero), each signal against where it scores full marks, and the one lever — and that a profile
 *       with no data is told so in words.
 * What: the fixture score (765, Good, protection missing); the empty score; no score at all.
 * Result: the card's copy is checked on every `unitTests` run.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
@RunWith(RobolectricTestRunner::class)
class HealthSectionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the total is shown with its band`() {
        render(fixtureHealth())

        compose.onNodeWithText(
            text(R.string.dashboard_health_score, 765, 1_000, text(R.string.dashboard_health_band_good)),
        )
            .assertIsDisplayed()
    }

    @Test
    fun `each pillar shows its points, the points it adds and its share of the weight`() {
        render(fixtureHealth())

        compose.onNodeWithText("Liquidity & emergency: 80.0/100 · 235 points (weight 29.4%)").assertIsDisplayed()
        compose.onNodeWithText("Debt health: 90.4/100 · 213 points (weight 23.5%)").assertIsDisplayed()
    }

    @Test
    fun `a pillar with no data says so and names the weight it gave away`() {
        render(fixtureHealth())

        // Near the card's foot, below Robolectric's viewport: present, not necessarily on screen.
        compose.onNodeWithText(
            "Protection & growth: — not enough data yet (its 15.0% weight is shared among the others)",
        )
            .assertExists()
        compose.onNodeWithText(
            compose.activity.resources.getQuantityString(R.plurals.dashboard_health_coverage, 5, 4, 5),
        ).assertExists()
    }

    @Test
    fun `each signal is shown against where it scores full marks`() {
        render(fixtureHealth())

        compose.onNodeWithText("Runway 4.8 of 6.0 months — 80.0/100").assertIsDisplayed()
        compose.onNodeWithText(
            "Fixed costs and EMIs 33.1% of income (full marks at 30.0%) — 87.3/100",
        ).assertIsDisplayed()
        compose.onNodeWithText("Card statements 34.5% of limits (full marks at 30.0%) — 93.5/100")
            .assertIsDisplayed()
        compose.onNodeWithText("Kept 19.3% of income (full marks at 30.0%) — 64.3/100").assertIsDisplayed()
        compose.onNodeWithText("Goals on track 66.6% — 66.6/100").assertIsDisplayed()
    }

    @Test
    fun `the biggest lever and the rules are named`() {
        render(fixtureHealth())

        // The card's last lines, below Robolectric's viewport: present, not necessarily on screen.
        compose.onNodeWithText("Biggest lever: goals on track — up to +78 points").assertExists()
        compose.onNodeWithText(
            text(
                R.string.dashboard_health_rules,
                "RULE-FHS-PILLARS v1.0, RULE-FHS-BANDS v1.0, RULE-FHS-SIGNALS v1.0, " +
                    "RULE-CC-UTIL v1.0, RULE-SAVE-RATE v1.0",
            ),
        ).assertExists()
    }

    @Test
    fun `a profile with no data is told so in words, with no number`() {
        render(fixtureHealth(empty = true))

        compose.onNodeWithText(text(R.string.dashboard_health_empty)).assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText(" of 1000 · ", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun `no score yet shows no card`() {
        render(null)

        assertEquals(0, compose.onAllNodes(hasText(text(R.string.dashboard_health_label))).fetchSemanticsNodes().size)
    }

    private fun render(health: HealthScore?) {
        compose.setContent { CfoTheme { Column { HealthSection(health) } } }
    }

    private fun text(
        id: Int,
        vararg args: Any,
    ): String = if (args.isEmpty()) compose.activity.getString(id) else compose.activity.getString(id, *args)
}
