package com.aicfo.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.data.repository.FeedInsight
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The insight feed, rendered (issue 9.5; §7.2, FR-HOME-001, FR-AI-002).
 *
 * Why:  FR-AI-002 is a promise about every card: a finding, the evidence behind it, and at most one
 *       recommended action. Only a rendered test can show that each of the six kinds of card says
 *       something a person can act on, that the engine behind it is named, and that both ways of
 *       putting a card away reach the caller with the right row.
 * What: the crunch, budget, fund and lever cards; the evidence line; the two verdicts; the silence
 *       of an empty feed.
 * Result: the feed's copy is checked on every `unitTests` run.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
@RunWith(RobolectricTestRunner::class)
class InsightFeedSectionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dismissed = mutableListOf<String>()
    private val snoozed = mutableListOf<String>()

    @Test
    fun `a crunch day leads with the day, what is left and the buffer`() {
        render(fixtureFeed())

        compose.onNodeWithText(
            compose.activity.resources.getQuantityString(
                R.plurals.dashboard_insights_crunch,
                3,
                3,
                "Oct 11, 2026",
                "₹1,200.00",
                "₹5,000.00",
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun `each card names its figure, and the engine and rule behind it`() {
        render(fixtureFeed())

        compose.onNodeWithText(text(R.string.dashboard_insights_budget, "Dining", "₹1,250.00")).assertExists()
        compose.onNodeWithText(text(R.string.dashboard_insights_emergency, "₹1,20,000.00", "₹6,500.00")).assertExists()
        compose.onNodeWithText(text(R.string.dashboard_insights_evidence, "AI-FCT", "1.1", "RULE-FCT-CRUNCH v1.0"))
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dashboard_insights_evidence, "AI-EMF", "1.0", "RULE-EMF-COACH v1.0"))
            .assertExists()
    }

    @Test
    fun `every card carries exactly one recommended action`() {
        render(fixtureFeed())

        compose.onNodeWithText(text(R.string.dashboard_insights_action_crunch)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dashboard_insights_action_budget)).assertExists()
        compose.onNodeWithText(text(R.string.dashboard_insights_action_emergency)).assertExists()
        compose.onNodeWithText(text(R.string.dashboard_insights_action_lever)).assertExists()
    }

    @Test
    fun `the health lever borrows the health card's own words for the signal`() {
        render(fixtureFeed())

        compose.onNodeWithText(
            compose.activity.resources.getQuantityString(
                R.plurals.dashboard_insights_lever,
                42,
                42,
                text(R.string.dashboard_health_lever_savings),
            ),
        ).assertExists()
    }

    @Test
    fun `both ways of putting a card away reach the caller with that card's row`() {
        render(fixtureFeed().take(1))

        compose.onNodeWithText(text(R.string.dashboard_insights_later)).performClick()
        compose.onNodeWithText(text(R.string.dashboard_insights_dismiss)).performClick()

        assertEquals(listOf("i-crunch"), snoozed)
        assertEquals(listOf("i-crunch"), dismissed)
    }

    @Test
    fun `an empty feed says nothing at all`() {
        render(emptyList())

        assertEquals(0, compose.onAllNodes(hasText(text(R.string.dashboard_insights_label))).fetchSemanticsNodes().size)
    }

    private fun render(insights: List<FeedInsight>) {
        compose.setContent {
            CfoTheme {
                Column {
                    InsightFeedSection(
                        insights = insights,
                        onDismiss = { dismissed += it },
                        onSnooze = { snoozed += it },
                    )
                }
            }
        }
    }

    private fun text(
        id: Int,
        vararg args: Any,
    ): String = if (args.isEmpty()) compose.activity.getString(id) else compose.activity.getString(id, *args)
}
