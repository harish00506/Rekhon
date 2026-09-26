package com.aicfo.feature.advisor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.simulator.PayoffPlan
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the simulators show (issue 10.3; §36, §40.2, P-02, P-07).
 *
 * Why:  each answer is a comparison, so the screen has to show **both sides and the gap** — a
 *       verdict alone would be the black box P-02 forbids. And it has to say that nothing was paid,
 *       because a screen full of debts and buttons could otherwise be read as one that moves money.
 * What: both figures and the verdict, the breakeven, both payoff plans and the saving, the
 *       degenerate case where the strategies agree, the evidence line, and the empty states.
 * Result: the working is on screen, in the app's own words.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
@RunWith(RobolectricTestRunner::class)
class SimulatorsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the prepay answer shows both sides, the gap and the rate that would change it`() {
        show(SimulatorsUiState(debts = FakeSimulatorRepository.DEBTS, prepay = FakeSimulatorRepository.PREPAY))

        composeRule.onNodeWithText("Prepaying saves ₹4,78,250.36 in interest and ends the loan 33 months sooner.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Investing the same money would leave you ₹6,99,412.23 after tax.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Investing is ahead, by ₹2,21,161.87.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("The answer flips at a return of about 10% a year.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the payoff answer shows both plans and what the cheaper one is worth`() {
        show(SimulatorsUiState(debts = FakeSimulatorRepository.DEBTS, payoff = FakeSimulatorRepository.PAYOFF))

        composeRule.onNodeWithText("Dearest first: debt-free in 25 months, ₹59,950.55 of interest.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Smallest first: debt-free in 27 months, ₹68,295.02 of interest.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Going dearest first saves ₹8,344.47 and finishes 2 months sooner.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("In order: Credit card, Home loan").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `when the two strategies come out the same, the screen says so rather than claiming a saving`() {
        val level =
            FakeSimulatorRepository.PAYOFF.copy(
                snowball = PayoffPlan(listOf("Credit card", "Home loan"), 25, Money(59_950_55L)),
                interestSavedByAvalanche = Money.ZERO,
                monthsSavedByAvalanche = 0,
            )

        show(SimulatorsUiState(debts = FakeSimulatorRepository.DEBTS, payoff = level))

        composeRule.onNodeWithText("Both ways come out the same here — the dearest debt is also the smallest.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `each answer says which engine and rule produced it`() {
        show(SimulatorsUiState(debts = FakeSimulatorRepository.DEBTS, prepay = FakeSimulatorRepository.PREPAY))

        composeRule.onNodeWithText("From AI-SIM v1.0 · RULE-PREPAY-VS-INVEST").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the screen says plainly that nothing was paid`() {
        // P-07, where it matters most: this looks like a payment screen and is not one.
        show(SimulatorsUiState(debts = FakeSimulatorRepository.DEBTS))

        composeRule.onNodeWithText("Nothing has been paid or moved — this is arithmetic.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `with no loans there is nothing to prepay, and the button is dead`() {
        show(SimulatorsUiState(debts = emptyList()))

        composeRule.onNodeWithText("No loans on file, so there is nothing to prepay.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Compare them").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Nothing is owed, so there is nothing to plan.")
            .performScrollTo().assertIsDisplayed()
    }

    private fun show(uiState: SimulatorsUiState) {
        composeRule.setContent {
            CfoTheme { SimulatorsContent(uiState = uiState, onEvent = {}, onDone = {}) }
        }
    }
}
