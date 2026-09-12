package com.aicfo.feature.goals

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aicfo.core.common.Ok
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionType
import com.aicfo.data.repository.GoalContribution
import com.aicfo.data.repository.GoalFundingAccount
import com.aicfo.domain.engines.goals.GoalEngineFactory
import com.aicfo.domain.engines.goals.GoalPlanInput
import com.aicfo.domain.engines.goals.GoalProjection
import com.aicfo.domain.engines.goals.GoalSpec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Compose tests for the goal detail screen (issue 7.4; §21.5's "critical flows", §15).
 *
 * Why:  [GoalDetailViewModelTest] proves what the state does; these prove the screen renders it and
 *       routes the taps back. Three things only a rendered test catches:
 *
 *       - **ghost progress being visually distinct**, which is §15's requirement in so many words.
 *         A state flag nobody draws satisfies nothing, and this is exactly the shape of the bug
 *         issue 6.7 found: a full stack whose surface was missing.
 *       - **the split reading as two sentences a person can act on**, rather than one total that
 *         hides the distinction.
 *       - **`RULE-PAY-FIRST` appearing only when the day is known** (P-03), and carrying its
 *         citation when it does (P-02).
 * What: the split, the ghost warning and its one-tap clear, the linked list, the pickers, and the
 *       anchor line in both its states.
 * Result: the screen is exercised on every `test` run, not only when a device is attached.
 * Changelog: 2026-09-06 — Created for issue 7.4.
 *
 * On the JVM via Robolectric, following `GoalsFlowTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h1600dp")
class GoalDetailFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the two halves of progress are shown as separate sentences`() {
        setContent(state(goal = partlyEvidencedGoal()))

        compose.onNodeWithText("₹1,50,000.00 of ₹5,00,000.00 saved").performScrollTo()
        compose.onNodeWithText("₹50,000.00 of that is backed by movements in your accounts.").performScrollTo()
        compose
            .onNodeWithText("₹1,00,000.00 is a figure you typed in. Nothing in your accounts backs it yet.")
            .performScrollTo()
    }

    @Test
    fun `a fully evidenced goal is not warned about`() {
        setContent(state(goal = fullyEvidencedGoal()))

        compose.onNodeWithText("Clear the typed figure").assertDoesNotExist()
    }

    @Test
    fun `clearing the typed figure is one tap and reaches the ViewModel`() {
        val events = mutableListOf<GoalDetailEvent>()
        setContent(state(goal = partlyEvidencedGoal()), onEvent = events::add)

        compose.onNodeWithText("Clear the typed figure").performScrollTo().performClick()

        assertEquals(listOf(GoalDetailEvent.ClearGhostProgress), events)
    }

    @Test
    fun `a linked movement is listed with its amount and can be taken back off`() {
        val events = mutableListOf<GoalDetailEvent>()
        setContent(
            state(
                goal = partlyEvidencedGoal(),
                contributions = listOf(contribution("t1", Money(50_000_00))),
            ),
            onEvent = events::add,
        )

        compose.onNodeWithText("SIP — ₹50,000.00 on 2026-08-01").performScrollTo()
        compose.onNodeWithText("Unlink").performScrollTo().performClick()

        assertEquals(listOf(GoalDetailEvent.Unlink("t1")), events)
    }

    @Test
    fun `a goal with nothing linked says so rather than showing an empty heading`() {
        setContent(state(goal = ghostOnlyGoal()))

        compose
            .onNodeWithText(
                "Nothing is linked to this goal yet, so all of its progress is a figure you typed.",
            ).performScrollTo()
    }

    @Test
    fun `the picker offers every unlinked movement and links the one tapped`() {
        val events = mutableListOf<GoalDetailEvent>()
        setContent(
            state(
                goal = ghostOnlyGoal(),
                linkable = listOf(transaction("t1"), transaction("t2")),
                isPickerOpen = true,
            ),
            onEvent = events::add,
        )

        compose.onNodeWithText("Pick a movement").performScrollTo()
        // Signed, exactly as the ledger holds it: the picker shows the movement, the
        // contributions list below shows what it gives the goal.
        compose.onAllNodesWithText("SIP — -₹5,000.00 on 2026-08-01").assertCountEquals(2)
        compose.onAllNodesWithText("SIP — -₹5,000.00 on 2026-08-01")[0].performScrollTo().performClick()

        assertEquals(listOf(GoalDetailEvent.Link("t1")), events)
    }

    @Test
    fun `the account chooser asks the history question rather than assuming it`() {
        val events = mutableListOf<GoalDetailEvent>()
        setContent(
            state(goal = ghostOnlyGoal(), accounts = listOf(account("acct:1")), isAccountPickerOpen = true),
            onEvent = events::add,
        )

        compose.onNodeWithText("Savings acct:1, from today").performScrollTo()
        compose.onNodeWithText("Savings acct:1, counting everything in it").performScrollTo().performClick()

        assertEquals(
            listOf(GoalDetailEvent.LinkAccount("acct:1", countHistory = true)),
            events,
        )
    }

    @Test
    fun `a dedicated account is listed with the day it starts counting`() {
        setContent(
            state(
                goal = ghostOnlyGoal(),
                fundingAccounts = listOf(fundingAccount("acct:1", from = "2026-08-30")),
            ),
        )

        compose.onNodeWithText("Savings acct:1, counting from 2026-08-30").performScrollTo()
    }

    @Test
    fun `an account dedicated with its history says so, never the sentinel date`() {
        // Found by running it: the screen rendered the stored date, so this read
        // "counting from 0001-01-01" — true about the database, meaningless about the money.
        setContent(
            state(
                goal = ghostOnlyGoal(),
                fundingAccounts = listOf(fundingAccount("acct:1", from = "0001-01-01")),
            ),
        )

        compose.onNodeWithText("Savings acct:1, counting everything in it").performScrollTo()
        compose.onNodeWithText("Savings acct:1, counting from 0001-01-01").assertDoesNotExist()
    }

    @Test
    fun `a known salary day is said out loud, with the rule that says it`() {
        setContent(state(goal = partlyEvidencedGoal(anchorDay = 7)))

        compose
            .onNodeWithText("Your income lands on day 7 of the month. Contribute then, rather than at month end.")
            .performScrollTo()
        compose.onNodeWithText("RULE-PAY-FIRST v1.0").performScrollTo()
    }

    @Test
    fun `an unknown salary day is silence, not a guess`() {
        setContent(state(goal = partlyEvidencedGoal()))

        compose.onNodeWithText("RULE-PAY-FIRST v1.0").assertDoesNotExist()
    }

    @Test
    fun `a goal that is no longer there says so instead of rendering an empty card`() {
        setContent(GoalDetailUiState(goal = null, isLoading = false))

        compose.onNodeWithText("This goal is no longer here. It may have been deleted.").performScrollTo()
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Result: renders the screen body with the given state. Input: [state]; [onEvent]. */
    private fun setContent(
        state: GoalDetailUiState,
        onEvent: (GoalDetailEvent) -> Unit = {},
    ) {
        compose.setContent {
            CfoTheme { GoalDetailContent(uiState = state, onEvent = onEvent, onDone = {}) }
        }
    }

    /** Result: a state with one field varied. */
    @Suppress("LongParameterList") // Six slices of one immutable state; every case varies one.
    private fun state(
        goal: GoalProjection,
        contributions: List<GoalContribution> = emptyList(),
        fundingAccounts: List<GoalFundingAccount> = emptyList(),
        linkable: List<Transaction> = emptyList(),
        accounts: List<Account> = emptyList(),
        isPickerOpen: Boolean = false,
        isAccountPickerOpen: Boolean = false,
    ) = GoalDetailUiState(
        goal = goal,
        contributions = contributions,
        fundingAccounts = fundingAccounts,
        linkable = linkable,
        accounts = accounts,
        isPickerOpen = isPickerOpen,
        isAccountPickerOpen = isAccountPickerOpen,
        isLoading = false,
    )

    /** ₹1,50,000 of progress, a third of it evidenced. */
    private fun partlyEvidencedGoal(anchorDay: Int? = null) =
        project(saved = Money(1_50_000_00), evidenced = Money(50_000_00), anchorDay = anchorDay)

    /** Every rupee of it linked, so there is no ghost half to warn about. */
    private fun fullyEvidencedGoal() = project(saved = Money(1_50_000_00), evidenced = Money(1_50_000_00))

    /** Nothing linked at all — every profile on the day 7.4 shipped. */
    private fun ghostOnlyGoal() = project(saved = Money(1_50_000_00), evidenced = Money.ZERO)

    /** Result: a projection from the real engine, so the screen never renders a made-up figure. */
    private fun project(
        saved: Money,
        evidenced: Money,
        anchorDay: Int? = null,
    ): GoalProjection =
        (
            GoalEngineFactory.create().plan(
                GoalPlanInput(
                    goals =
                        listOf(
                            GoalSpec(
                                id = "g1",
                                name = "Kerala trip",
                                target = Money(5_00_000_00),
                                targetDate = LocalDate.parse("2028-04-30"),
                                saved = saved,
                                plannedMonthly = Money(15_000_00),
                                savedEvidenced = evidenced,
                            ),
                        ),
                    today = TODAY,
                    contributionAnchorDay = anchorDay,
                ),
            ) as Ok
        ).value.goals.single()

    /** Result: one linked movement. */
    private fun contribution(
        id: String,
        amount: Money,
    ) = GoalContribution(transaction = transaction(id), amount = amount, linkedAtUtcMillis = 0L)

    /** Result: one movement, stored negative as an outflow is. */
    private fun transaction(id: String) =
        Transaction(
            id = id,
            accountId = "acct:1",
            amount = Money(-5_000_00),
            occurredAtUtcMillis = 0L,
            bookedOn = "2026-08-01",
            categoryId = null,
            merchant = "SIP",
            note = null,
            source = TransactionSource.MANUAL,
            type = TransactionType.EXPENSE,
        )

    /** Result: one dedicated account. */
    private fun fundingAccount(
        id: String,
        from: String,
    ) = GoalFundingAccount(
        account = account(id),
        linkedFromIsoDate = from,
        countsWholeHistory = from == "0001-01-01",
        linkedAtUtcMillis = 0L,
    )

    /** Result: one account. */
    private fun account(id: String) =
        Account(
            id = id,
            profileId = "profile:real",
            name = "Savings $id",
            type = AccountType.BANK,
            institution = null,
            openingBalance = Money.ZERO,
            balance = Money.ZERO,
            currencyCode = "INR",
            isArchived = false,
        )

    private companion object {
        val TODAY: LocalDate = LocalDate.parse("2026-08-30")
    }
}
