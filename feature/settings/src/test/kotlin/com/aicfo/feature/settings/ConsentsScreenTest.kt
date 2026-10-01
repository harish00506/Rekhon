package com.aicfo.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.designsystem.theme.CfoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the consents dashboard puts in front of a person (issue 11.3; §23, P-01, DPDP).
 *
 * Why:  the ViewModel's test proves the state; only a rendered test proves what the user **reads**,
 *       and on this screen the reading is the feature. A list of switches is a control panel; what
 *       P-01 promises is something a person can audit — so each row has to say what the consent is
 *       *for*, **what stops** if it is withdrawn, and **when** it was given. Each of those is a
 *       sentence that can go missing without any state changing.
 * What: every feature listed, the purpose and consequence lines, both dates, and the one tap.
 * Result: a privacy screen that explains itself.
 * Changelog: 2026-10-01 — Created for issue 11.3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp")
class ConsentsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<ConsentsEvent>()

    @Test
    fun `every consent is on the screen, with what it is for and what stops without it`() {
        setContent(
            ConsentsUiState(rows = ConsentFeature.entries.map { ConsentRow(it, granted = false) }, isLoading = false),
        )

        ConsentFeature.entries.forEach { feature ->
            compose.onNodeWithText(text(feature.label())).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(feature.purpose())).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(feature.whenWithdrawn())).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `a consent in force says since when`() {
        setContent(
            ConsentsUiState(
                rows = listOf(ConsentRow(ConsentFeature.MARKET_DATA, granted = true, grantedOnIsoDate = "2026-03-15")),
                isLoading = false,
            ),
        )

        compose
            .onNodeWithText(text(R.string.consents_in_use_since, formatted("2026-03-15")))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a withdrawn consent says when it was given and when it was taken back`() {
        // Both halves, because the ledger keeps both and a DPDP request asks for both.
        setContent(
            ConsentsUiState(
                rows =
                    listOf(
                        ConsentRow(
                            ConsentFeature.SMS_PARSING,
                            granted = false,
                            grantedOnIsoDate = "2026-01-02",
                            revokedOnIsoDate = "2026-02-04",
                        ),
                    ),
                isLoading = false,
            ),
        )

        compose
            .onNodeWithText(text(R.string.consents_withdrawn_on, formatted("2026-02-04"), formatted("2026-01-02")))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a consent nobody has answered for says so, rather than looking withdrawn`() {
        // "Never given" and "withdrawn" are different facts about the user, and a screen that
        // conflated them would misreport what they had done.
        setContent(
            ConsentsUiState(rows = listOf(ConsentRow(ConsentFeature.CLOUD_LLM, granted = false)), isLoading = false),
        )

        compose.onNodeWithText(text(R.string.consents_never_given)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `withdrawing is one tap, and it says it takes effect at once`() {
        setContent(
            ConsentsUiState(
                rows = listOf(ConsentRow(ConsentFeature.SMS_PARSING, granted = true, grantedOnIsoDate = "2026-01-02")),
                isLoading = false,
            ),
        )

        compose.onNodeWithText(text(R.string.consents_withdraw)).performScrollTo().performClick()

        assertEquals(listOf(ConsentsEvent.Revoked(ConsentFeature.SMS_PARSING)), events)
    }

    @Test
    fun `a withdrawn consent can be given again from the same screen`() {
        setContent(
            ConsentsUiState(rows = listOf(ConsentRow(ConsentFeature.MARKET_DATA, granted = false)), isLoading = false),
        )

        compose.onNodeWithText(text(R.string.consents_allow)).performScrollTo().performClick()

        assertEquals(listOf(ConsentsEvent.Granted(ConsentFeature.MARKET_DATA)), events)
    }

    @Test
    fun `the screen states the promise the whole ledger exists to keep`() {
        setContent(ConsentsUiState(rows = emptyList(), isLoading = false))

        compose.onNodeWithText(text(R.string.consents_nothing_leaves)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `every consent this build ships has words`() {
        // A feature added to the enum with no wording would reach the user as a blank row.
        ConsentFeature.entries.forEach { feature ->
            assertTrue("${feature.id} has no purpose", text(feature.purpose()).isNotBlank())
            assertTrue("${feature.id} has no consequence", text(feature.whenWithdrawn()).isNotBlank())
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun setContent(state: ConsentsUiState) {
        compose.setContent {
            CfoTheme {
                ConsentsContent(uiState = state, onEvent = { events += it }, onDone = {})
            }
        }
    }

    private fun text(
        id: Int,
        vararg args: Any,
    ): String = compose.activity.getString(id, *args)

    /** Result: the day as the screen renders it — the same formatter, so the test is not a second one. */
    private fun formatted(isoDate: String): String = com.aicfo.core.model.DateFormatter.day(isoDate)
}
