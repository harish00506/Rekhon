package com.aicfo.feature.emergencyfund

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.emergencyfund.EmergencyFundPlan
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the emergency-fund screen (issue 7.2; §21.5's "critical flows", §10.1).
 *
 * Why:  [EmergencyFundViewModelTest] proves what the state does; these prove the screen renders it.
 *       Five things only a rendered test catches:
 *
 *       - **the runway reaching the screen as a sentence a person can read.** It leaves the engine
 *         as `45000` basis points of a month, and "4.5 months of cover" is a conversion no state
 *         test can distinguish from a blank label;
 *       - **the evidence §10.1 requires** — which accounts counted as liquid, which categories as
 *         essential, and why M came out as it did. §10.1 says "every number in the explanation links
 *         to its evidence", and a card showing only "4.5 months" would not;
 *       - **the funded fund not being told to top up.** Issue 7.1 shipped a goal card reading "At
 *         ₹0.00 a month you get there 2026-08-30" — arithmetically true, and absurd. No assertion
 *         about a figure caught it. This one is about a sentence;
 *       - **the unknown state rendering an explanation rather than a zero**, which is the state
 *         every fresh install sees first;
 *       - **the screen existing at all.** Issue 6.7 found a whole market-data stack whose every
 *         layer shipped and whose editor field did not. A rendered test is what would have caught it.
 * What: the headline, the coach line, the evidence drawer, and both edge states.
 * Result: the screen is exercised on every `test` run, not only when a device is attached.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 *
 * On the JVM via Robolectric, following `:feature:goals`' `GoalsFlowTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1600dp")
class EmergencyFundFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * ₹1,00,000 liquid against ₹40,000 of essentials is 2.5 months, and M is 6 on a steady income.
     * Output: asserts the headline, the target and the top-up all render as readable sentences.
     */
    @Test
    fun `the runway renders as months, not as basis points`() {
        setContent(FakeEmergencyFundRepository.plan())

        compose.onNodeWithText("2.5 months of cover").assertIsDisplayed()
        compose.onNodeWithText("Building up").assertIsDisplayed()
        // ₹2,40,000 target − ₹1,00,000 liquid = ₹1,40,000 over six months.
        compose.onNodeWithText("Target ₹2,40,000.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("₹23,333.34 a month closes it over 6 months").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("₹1,40,000.00 still to find").performScrollTo().assertIsDisplayed()
    }

    /**
     * Output: asserts §10.1's evidence is reachable and names all three of its parts.
     *
     * Collapsed first — the assertion that it is *not* shown before the tap is what makes the tap
     * meaningful rather than incidental.
     */
    @Test
    fun `the evidence drawer names the essentials, the multiplier and the accounts`() {
        setContent(FakeEmergencyFundRepository.plan())

        val essentialsLine =
            "A month of essentials: ₹40,000.00 — the middle month of what you actually spent on needs"
        compose.onNodeWithText(essentialsLine).assertDoesNotExistYet()

        compose.onNodeWithText("Show the working").performScrollTo().performClick()

        compose.onNodeWithText(essentialsLine).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "Held for 6 months: 6 to start with, and nothing added — your income has been steady",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Counted as spendable today: HDFC Savings").performScrollTo().assertIsDisplayed()
        // The regression guard for a defect only the running app showed: this line rendered as
        // "Counted as essential: NEED", a domain token the user has met nowhere else in the app.
        compose.onNodeWithText("Counted as essential: everything you have categorised as a need")
            .performScrollTo().assertIsDisplayed()
        // The honest footnote for ADR-0034's deferral: a user with an FD is told why it is missing.
        compose.onNodeWithText(
            "Only savings and cash count for now. Deposits and investments are not included, so your " +
                "real cover may be longer than this.",
        ).performScrollTo().assertIsDisplayed()
    }

    /**
     * Input:  a fund that covers its target exactly.
     * Output: asserts it says there is nothing more to put aside — **not** "₹0.00 a month closes it
     *         over 6 months", which is what a single top-up line would have produced.
     *
     * This is issue 7.1's defect, written down as a test before it could happen again.
     */
    @Test
    fun `a funded fund is not told to top up zero rupees a month`() {
        setContent(FakeEmergencyFundRepository.plan(liquid = Money(2_40_000_00L)))

        compose.onNodeWithText("Fully funded").assertIsDisplayed()
        compose.onNodeWithText("Nothing more to put aside").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("₹0.00 a month closes it over 6 months").assertDoesNotExistYet()
        compose.onNodeWithText("₹0.00 still to find").assertDoesNotExistYet()
    }

    /**
     * Input:  a profile the app has never watched spend.
     * Output: asserts an explanation, not a zero target and not an error.
     *
     * The first state every install is in. Rendering "Target ₹0.00" here would tell somebody with
     * nothing saved that their emergency fund is complete.
     */
    @Test
    fun `an unsizeable fund explains itself rather than showing a zero target`() {
        setContent(FakeEmergencyFundRepository.plan(essentials = null))

        compose.onNodeWithText("We cannot work out your cover yet").assertIsDisplayed()
        compose.onNodeWithText("Not enough to go on yet").assertIsDisplayed()
        compose.onNodeWithText(
            "Record a few months of spending, or set your monthly needs during setup, and the app can " +
                "size this for you.",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Target ₹0.00").assertDoesNotExistYet()
    }

    /**
     * Output: asserts the rule citation and the disclaimer are both on the screen.
     *
     * P-02 forbids a black-box verdict and §11.1 requires the advisory framing; both are one line
     * each, and both are the kind of line that quietly disappears in a refactor.
     */
    @Test
    fun `the screen names its rules and frames itself as advice`() {
        setContent(FakeEmergencyFundRepository.plan())

        compose.onNodeWithText("Rules RULE-EMF-MULT v1.0, RULE-EMF-COACH v1.0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "These figures are worked out from what you have recorded — what you spend on essentials " +
                "and what is in your savings and cash. They are a guide, not advice about a particular " +
                "product.",
        ).performScrollTo().assertIsDisplayed()
    }

    /** Result: renders the screen's body around [plan]. Input: [plan]. Output: none. */
    private fun setContent(plan: EmergencyFundPlan) {
        compose.setContent {
            CfoTheme {
                var isOpen by rememberSaveable { mutableStateOf(false) }
                EmergencyFundContent(
                    uiState = EmergencyFundUiState(plan = plan, isEvidenceOpen = isOpen, isLoading = false),
                    onEvent = { if (it is EmergencyFundEvent.ToggleEvidence) isOpen = !isOpen },
                    onDone = {},
                )
            }
        }
    }

    /**
     * Asserts a node is absent.
     * Why:    named rather than inlined because three tests below assert an absence, and an absence
     *         asserted the wrong way — `assertIsNotDisplayed` on a node that does not exist — throws
     *         instead of passing.
     * Result: none. Input: the receiver. Output: none.
     */
    private fun SemanticsNodeInteraction.assertDoesNotExistYet() {
        assertDoesNotExist()
    }
}
