package com.aicfo.feature.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aicfo.core.designsystem.component.LocalPrivacyBlur
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.goals.SurplusBasis
import com.aicfo.domain.engines.orderofoperations.DebtKind
import com.aicfo.domain.engines.orderofoperations.DebtPosition
import com.aicfo.domain.engines.orderofoperations.OrderOfOperations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The next-best-rupee card and the full-order screen, rendered (issue 7.5; §36, FOO-002, P-02, P-07).
 *
 * Why:  the ViewModel tests prove the state; only a render proves the user can *read* it — that a
 *       skipped stage says why, that a rate shows to the basis point, that an unknown card rate says
 *       it was assumed, that every amount obeys the privacy blur, and that each button goes where it
 *       says. Every ranking here comes from the real engine (see [FakeOrderOfOperationsRepository]).
 * What: the card's three states, the full screen's eight stages with reasons and rules, the three
 *       surplus bases, the navigation buttons, the error, and the blur.
 * Result: the screen is proven to show its work (P-02) and to offer only advice (P-07).
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * Excluded from the release unit-test variant in `build.gradle.kts`, for the reason every Compose
 * test here is: it needs the debug manifest's activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h2400dp")
class OrderOfOperationsFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // --- the dashboard card -------------------------------------------------------------------

    /** Input: the card-debt household. Output: the buffer as the top action, with amount and rule. */
    @Test
    fun `the card leads with the top action, its reason, its amount and its rule`() {
        var opened = false
        setCard(rank(cardDebtInput())) { opened = true }

        compose.onNodeWithText("Your next best rupee").assertIsDisplayed()
        compose.onNodeWithText("Build a starter buffer").assertIsDisplayed()
        compose.onNodeWithText(
            "A small buffer you can reach instantly stops the first surprise bill from landing on a credit card.",
        ).assertIsDisplayed()
        // min(₹50,000, ₹28,000) − ₹12,000 liquid = ₹16,000, and the ₹25,000 surplus covers it.
        compose.onNodeWithText("Suggested this month: ₹16,000.00").assertIsDisplayed()
        compose.onNodeWithText("Rule FOO.STARTER_BUFFER v1.0").assertIsDisplayed()

        compose.onNodeWithText("See the full order").performClick()
        assertTrue("the card's button did not open the full order", opened)
    }

    /** Input: no ranking yet. Output: a pending line — not an empty card, and not "nothing to do". */
    @Test
    fun `before the first ranking the card says it is working it out`() {
        setCard(ranking = null)

        compose.onNodeWithText("Working out where your next rupee should go…").assertIsDisplayed()
        compose.onAllNodesWithText("Nothing needs your money right now.").assertCountEquals(0)
    }

    /** Input: a household with nothing to do and money left. Output: says so and names the idle sum. */
    @Test
    fun `with nothing to do the card says so and names the idle money`() {
        setCard(rank(doneHouseholdInput()))

        compose.onNodeWithText("Nothing needs your money right now.").assertIsDisplayed()
        compose.onNodeWithText("₹15,000.00 is free this month.").assertIsDisplayed()
    }

    /** Input: a top action with no surplus to pour. Output: what the stage still needs, not ₹0. */
    @Test
    fun `with no surplus the card shows what is still needed instead of a zero`() {
        setCard(rank(cardDebtInput().copy(monthlySurplus = null, surplusBasis = SurplusBasis.NONE)))

        compose.onNodeWithText("Still needed: ₹16,000.00").assertIsDisplayed()
        compose.onAllNodesWithText("Suggested this month: ₹0.00").assertCountEquals(0)
    }

    // --- the full order -----------------------------------------------------------------------

    /**
     * Input:  the card-debt household.
     * Output: all eight steps in order, both skipped stages with their reasons, the held goal, the
     *         debt with its rate, and the gate's rule beside the fund.
     */
    @Test
    fun `the full order shows every stage, including the skipped and held ones, with why`() {
        setScreen(rank(cardDebtInput()))

        listOf(
            "Step 1 · Build a starter buffer",
            "Step 2 · Capture your EPF and VPF",
            "Step 3 · Pay off high-interest debt",
            "Step 4 · Complete your emergency fund",
            "Step 5 · Use your tax-saving limits",
            "Step 6 · Fund your goals",
            "Step 7 · Consider paying down mid-rate debt",
            "Step 8 · Low-rate debt: prepay or invest?",
        ).forEach { step -> compose.onNodeWithText(step).performScrollTo().assertIsDisplayed() }

        compose.onAllNodesWithText("Can't check yet").assertCountEquals(2)
        compose.onNodeWithText(
            "Tax-saving investments only help if the old tax regime is better for you, and the app can't " +
                "compare the two regimes yet.",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Waiting on your emergency fund").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("HDFC Card · ₹65,000.00 owed · 36.00% a year").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rules: FOO.FULL_EMERGENCY v1.0, RULE-EMERG-FIRST v1.0").performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Based on your typical monthly surplus of ₹25,000.00.").assertIsDisplayed()
        compose.onNodeWithText("Advice, not instructions. The app never moves your money.").performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * Input:  the idle household — a grey-zone car loan and a home loan, from a declared surplus.
     * Output: the choice with both rates to compare, the deferral with no amount, the leftover, and
     *         the declared basis in words.
     */
    @Test
    fun `a grey-zone choice shows both rates and a low-rate loan proposes nothing`() {
        setScreen(rank(idleHouseholdInput()))

        compose.onNodeWithText("Based on the ₹9,000.00 you told us you save each month.").assertIsDisplayed()
        compose.onNodeWithText("Your call").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Car Loan · ₹7,000.00 owed · 11.50% a year").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Investing is expected to earn about 12.00% a year.").performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Needs a comparison").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Home Loan · ₹25,00,000.00 owed · 8.50% a year").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("₹2,000.00 is left after every step.").performScrollTo().assertIsDisplayed()
    }

    /** Input: a ranking with no surplus. Output: says so, instead of showing amounts from nothing. */
    @Test
    fun `with no surplus the screen says no amounts are suggested`() {
        setScreen(rank(cardDebtInput().copy(monthlySurplus = null, surplusBasis = SurplusBasis.NONE)))

        compose.onNodeWithText("We don't know your monthly surplus yet, so no amounts are suggested.")
            .assertIsDisplayed()
        compose.onAllNodesWithText("Suggested this month:", substring = true).assertCountEquals(0)
    }

    /**
     * Input:  the card-debt household.
     * Output: the fund and goals buttons each call their own action, and Back calls done.
     */
    @Test
    fun `each button goes where it says`() {
        val calls = mutableListOf<String>()
        setScreen(
            rank(cardDebtInput()),
            actions =
                OrderOfOperationsActions(
                    onDone = { calls += "done" },
                    onOpenGoals = { calls += "goals" },
                    onOpenEmergencyFund = { calls += "fund" },
                    onOpenAccounts = { calls += "accounts" },
                ),
        )

        compose.onNodeWithText("Open your emergency fund").performScrollTo().performClick()
        compose.onNodeWithText("Open your goals").performScrollTo().performClick()
        compose.onNodeWithText("Back").performScrollTo().performClick()

        assertEquals(listOf("fund", "goals", "done"), calls)
    }

    /**
     * Input:  a card with a balance and no rate recorded.
     * Output: the card is ranked as fire debt, says its rate was assumed, and offers the one screen
     *         where the rate can now be entered. The 7.5 device run withdrew this button because the
     *         card editor had no rate field; the card APR follow-up added the field and the button.
     */
    @Test
    fun `an unrated card says its rate was assumed and links to where it is entered`() {
        val unrated = DebtPosition("axis", "Axis Card", DebtKind.CARD, Money(20_000_00L), null)
        val calls = mutableListOf<String>()
        setScreen(
            rank(cardDebtInput().copy(debts = cardDebtInput().debts + unrated)),
            actions = noActions().copy(onOpenAccounts = { calls += "accounts" }),
        )

        compose.onNodeWithText("Axis Card · ₹20,000.00 owed · rate not entered").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "No interest rate is recorded for this card, so it is treated as high-interest, as most cards are. " +
                "Add the rate in the card's details to be sure.",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add the card's rate in Accounts").performScrollTo().performClick()

        assertEquals(listOf("accounts"), calls)
    }

    /** Input: a household whose only card has a rate. Output: no rate prompt — there is nothing to add. */
    @Test
    fun `a rated card is not prompted for a rate`() {
        setScreen(rank(cardDebtInput()))

        compose.onAllNodesWithText("rate in Accounts", substring = true).assertCountEquals(0)
    }

    /** Input: a failed read. Output: the error and a dismiss that sends the event up. */
    @Test
    fun `an error is shown and can be dismissed`() {
        val events = mutableListOf<OrderOfOperationsEvent>()
        compose.setContent {
            CfoTheme {
                OrderOfOperationsContent(
                    uiState = OrderOfOperationsUiState(isLoading = false, errorCode = "order_of_operations.storage"),
                    onEvent = { events += it },
                    actions = noActions(),
                )
            }
        }

        compose.onNodeWithText("Couldn't work out the order just now.").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()

        assertEquals(listOf(OrderOfOperationsEvent.DismissError), events)
    }

    /** Input: the full screen with the privacy blur on. Output: no rupee figure is rendered anywhere. */
    @Test
    fun `no amount survives the privacy blur on the full order`() {
        compose.setContent {
            CompositionLocalProvider(LocalPrivacyBlur provides true) {
                CfoTheme {
                    OrderOfOperationsContent(
                        uiState = OrderOfOperationsUiState(ranking = rank(idleHouseholdInput()), isLoading = false),
                        onEvent = {},
                        actions = noActions(),
                    )
                }
            }
        }

        val texts =
            compose.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        val leaked = texts.filter { AMOUNT.containsMatchIn(it) }

        assertTrue("the render produced no text at all, so the check below is vacuous", texts.isNotEmpty())
        assertTrue("these amounts escaped the privacy blur: $leaked", leaked.isEmpty())
    }

    // --- fixtures -----------------------------------------------------------------------------

    private fun setCard(
        ranking: OrderOfOperations?,
        onOpen: () -> Unit = {},
    ) {
        compose.setContent { CfoTheme { NextBestRupeeCard(ranking = ranking, onOpen = onOpen) } }
    }

    private fun setScreen(
        ranking: OrderOfOperations,
        actions: OrderOfOperationsActions = noActions(),
    ) {
        compose.setContent {
            CfoTheme {
                OrderOfOperationsContent(
                    uiState = OrderOfOperationsUiState(ranking = ranking, isLoading = false),
                    onEvent = {},
                    actions = actions,
                )
            }
        }
    }

    private fun noActions() =
        OrderOfOperationsActions(onDone = {}, onOpenGoals = {}, onOpenEmergencyFund = {}, onOpenAccounts = {})

    /** A household with every stage done and ₹15,000 spare. */
    private fun doneHouseholdInput() =
        idleHouseholdInput().copy(monthlySurplus = Money(15_000_00L), debts = emptyList())

    private companion object {
        /** A rupee sign followed by a digit — what a leaked amount looks like. */
        val AMOUNT = Regex("₹\\s?\\d")
    }
}
