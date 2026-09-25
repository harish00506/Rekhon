package com.aicfo.feature.advisor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import com.aicfo.data.repository.KeptVerdict
import com.aicfo.domain.engines.purchase.Verdict
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the Purchase Advisor actually shows (issue 10.1; §13.2, P-02).
 *
 * Why:  §13.2 is a promise about the screen, not the engine: a verdict, the gate-by-gate table with
 *       its numbers, what the purchase moves, and what would change the answer. A card that decided
 *       all of that and then rendered only the verdict would keep the letter of the engine tests and
 *       break the promise — so the working is asserted where the user would read it.
 * What: the verdict and its summary; a gate with its figures, a percentage and a plural; the impact
 *       strip; the alternatives and the cooling-off note; the evidence line; the history; and the
 *       ask button's guard.
 * Result: the reasoning is on screen, in the app's own words.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
@RunWith(RobolectricTestRunner::class)
class AdvisorScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the verdict and its summary are shown`() {
        show(AdvisorUiState(card = FakePurchaseAdvisorRepository.card(Verdict.STRETCH)))

        composeRule.onNodeWithText("A stretch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Headphones · ₹30,000.00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "You can do this, with something to accept below.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `each gate shows what it concluded and the figures behind it`() {
        show(AdvisorUiState(card = FakePurchaseAdvisorRepository.card()))

        composeRule.onNodeWithText("Money available · Fine").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Money available now: ₹1,00,000.00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Left afterwards: ₹70,000.00").performScrollTo().assertIsDisplayed()
        // A ratio is shown as a percentage, never as the basis points the engine speaks (MNY-002).
        composeRule.onNodeWithText("EMIs and rent afterwards: 30%").performScrollTo().assertIsDisplayed()
        // And a delay is a sentence with a plural, not a bare number.
        composeRule.onNodeWithText("Goals arrive 60 days later").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the impact strip shows the before and after`() {
        show(AdvisorUiState(card = FakePurchaseAdvisorRepository.card()))

        composeRule.onNodeWithText("Money: ₹1,00,000.00 → ₹70,000.00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Runway: 2.4 → 1.7 months").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the alternatives and the cooling-off note are shown`() {
        show(AdvisorUiState(card = FakePurchaseAdvisorRepository.card()))

        composeRule.onNodeWithText("At ₹5,000.00, nothing would object.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "This is a big one for your income. Sleep on it — the advisor will say the same tomorrow.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the card says which engine and rules decided it`() {
        show(AdvisorUiState(card = FakePurchaseAdvisorRepository.card()))

        composeRule.onNodeWithText("From AI-PA v1.0 · RULE-PA-GATES").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an incomplete question cannot be asked`() {
        show(AdvisorUiState(item = "Headphones", priceRupees = ""))

        composeRule.onNodeWithText("Ask the advisor").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a past verdict can be reopened from the history`() {
        var opened: String? = null
        val history = listOf(KeptVerdict("purchase:1", "Fridge", Money(45_000_00L), Verdict.NOT_NOW, "2026-09-25"))
        composeRule.setContent {
            CfoTheme {
                AdvisorContent(
                    uiState = AdvisorUiState(history = history),
                    onEvent = { event -> if (event is AdvisorEvent.OpenKept) opened = event.id },
                    onDone = {},
                )
            }
        }

        composeRule.onNodeWithText("Fridge · Not now").performScrollTo().performClick()

        assertEquals("purchase:1", opened)
    }

    @Test
    fun `with nothing asked yet the history says so`() {
        show(AdvisorUiState())

        composeRule.onNodeWithText("Nothing yet. Ask about something and it will be kept here.")
            .performScrollTo().assertIsDisplayed()
    }

    private fun show(uiState: AdvisorUiState) {
        composeRule.setContent {
            CfoTheme { AdvisorContent(uiState = uiState, onEvent = {}, onDone = {}) }
        }
    }
}
