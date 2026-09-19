package com.aicfo.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.domain.engines.stream.StreamProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The fixed / semi-fixed / flexible line, rendered (issue 9.1; §8.2, P-02, P-03).
 *
 * Why:  the ViewModel test proves the figures; only a rendered test proves the user reads them in
 *       the right slots, is told when part of it is an estimate (§8.2 requires the label), sees the
 *       rules that fired (P-02), and sees nothing at all when there is no history (P-03).
 * What: the three figures and the citations; the estimate note present and absent; the empty cases.
 * Result: the section's copy is checked on every `unitTests` run.
 * Changelog: 2026-09-19 — Created for issue 9.1.
 */
@RunWith(RobolectricTestRunner::class)
class StreamLoadSectionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the three figures and the rules that fired are shown`() {
        render(classify(fixtureStreams()))

        compose.onNodeWithText(text(R.string.dashboard_streams_values, "₹25,000.00", "₹1,840.00", "₹0.00"))
            .assertIsDisplayed()
        compose.onNodeWithText(
            text(R.string.dashboard_streams_rules, "CLS-STR-001 v1.0, CLS-STR-004 v1.0, CLS-CAT-003 v1.0"),
        )
            .assertIsDisplayed()
    }

    @Test
    fun `a prior-based stream brings the estimate note`() {
        render(classify(fixtureStreams()))

        compose.onNodeWithText(text(R.string.dashboard_streams_estimate)).assertIsDisplayed()
    }

    @Test
    fun `a fully measured profile carries no estimate note`() {
        render(classify(fixtureStreams().take(1)))

        assertEquals(0, count(text(R.string.dashboard_streams_estimate)))
    }

    @Test
    fun `a profile with no expense history shows no section`() {
        render(classify(emptyList()))

        assertEquals(0, count(text(R.string.dashboard_streams_label)))
    }

    @Test
    fun `no classification yet shows no section`() {
        render(null)

        assertEquals(0, count(text(R.string.dashboard_streams_label)))
    }

    private fun render(profile: StreamProfile?) {
        compose.setContent { CfoTheme { Column { StreamLoadSection(profile) } } }
    }

    private fun count(value: String): Int = compose.onAllNodes(hasText(value)).fetchSemanticsNodes().size

    private fun text(
        id: Int,
        vararg args: Any,
    ): String = if (args.isEmpty()) compose.activity.getString(id) else compose.activity.getString(id, *args)
}
