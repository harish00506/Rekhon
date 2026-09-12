package com.aicfo.feature.goals

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionType
import com.aicfo.data.repository.GoalDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [GoalDetailViewModel] — issue 7.4 (§15, FR-GOAL-002, FR-GOAL-004, ARC-004).
 *
 * Why:  `GoalContributionRepositoryTest` proves the sums against real SQL; this proves the state
 *       machine above them, which fails in different ways and none of them loudly:
 *
 *       - the **route argument**. A screen reading the wrong key shows an empty goal for ever, and
 *         every layer below it stays perfectly correct.
 *       - the **ghost clear**. It rewrites the goal, so getting a field wrong silently edits the
 *         user's target or date while appearing to do what was asked.
 *       - the **picker's lifecycle**. A picker that stays open after a link invites the user to link
 *         the same movement twice.
 *       - the **error path**. Six writes share one reporting helper; if it lost its branch, every
 *         failed link would look like a success.
 * What: the subscriptions, every event, and the failure path.
 * Result: the screen's behaviour is pinned without a database.
 * Changelog: 2026-09-06 — Created for issue 7.4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalDetailViewModelTest {
    private val goals = FakeGoalRepository()
    private val links = FakeGoalContributionRepository()
    private val accounts = FakeGoalAccountRepository()

    /** Input: none. Output: the main dispatcher is the test one, so `viewModelScope` is immediate. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: the main dispatcher is restored. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the goal named by the route is the one shown`() =
        runTest {
            goals.save(draft(name = "Kerala trip"))
            goals.save(draft(name = "New laptop"))

            viewModel(goalId = "goal:2").uiState.test {
                val state = awaitItem()
                assertEquals("New laptop", state.goal?.name)
                assertFalse(state.isLoading)
            }
        }

    @Test
    fun `an id that is not in the profile shows nothing rather than the wrong goal`() =
        runTest {
            goals.save(draft())

            viewModel(goalId = "goal:missing").uiState.test {
                val state = awaitItem()
                assertNull(state.goal)
                assertFalse("and it is not still loading, so the screen can say so", state.isLoading)
            }
        }

    @Test
    fun `linking closes the picker and asks the repository for exactly that movement`() =
        runTest {
            goals.save(draft())
            links.linkable.value = listOf(transaction("t1", minor = -5_000_00))
            val viewModel = viewModel()

            viewModel.onEvent(GoalDetailEvent.OpenPicker)
            assertTrue(viewModel.uiState.value.isPickerOpen)
            viewModel.onEvent(GoalDetailEvent.Link("t1"))

            assertEquals(listOf("t1"), links.linked)
            assertFalse("a picker left open invites linking the same money twice", viewModel.uiState.value.isPickerOpen)
            assertEquals(Money(5_000_00), viewModel.uiState.value.contributions.single().amount)
        }

    @Test
    fun `unlinking asks the repository and the contribution leaves the list`() =
        runTest {
            goals.save(draft())
            links.linkable.value = listOf(transaction("t1", minor = -5_000_00))
            val viewModel = viewModel()
            viewModel.onEvent(GoalDetailEvent.Link("t1"))

            viewModel.onEvent(GoalDetailEvent.Unlink("t1"))

            assertEquals(listOf("t1"), links.unlinked)
            assertTrue(viewModel.uiState.value.contributions.isEmpty())
        }

    @Test
    fun `the history question is passed through rather than assumed`() =
        runTest {
            goals.save(draft())
            accounts.setAccounts(account("acct:1"))
            val viewModel = viewModel()

            viewModel.onEvent(GoalDetailEvent.OpenAccountPicker)
            viewModel.onEvent(GoalDetailEvent.LinkAccount("acct:1", countHistory = true))

            assertEquals(listOf("acct:1" to true), links.dedicated)
            assertFalse(viewModel.uiState.value.isAccountPickerOpen)
        }

    @Test
    fun `clearing the ghost figure zeroes what was typed and touches nothing else`() =
        runTest {
            goals.save(draft(saved = Money(50_000_00)))
            val viewModel = viewModel()

            viewModel.onEvent(GoalDetailEvent.ClearGhostProgress)

            val written = goals.saved.last()
            assertEquals(Money.ZERO, written.saved)
            assertEquals("the target must survive", Money(5_00_000_00), written.target)
            assertEquals("and the date", "2028-04-30", written.targetDateIso)
            assertEquals("and the monthly plan", Money(15_000_00), written.plannedMonthly)
            assertEquals("Kerala trip", written.name)
        }

    @Test
    fun `an account already funding the goal is not offered again`() =
        runTest {
            goals.save(draft())
            accounts.setAccounts(account("acct:1"), account("acct:2"))
            links.knownAccounts.value = listOf(account("acct:1"), account("acct:2"))
            val viewModel = viewModel()

            viewModel.onEvent(GoalDetailEvent.LinkAccount("acct:1", countHistory = false))

            assertEquals(listOf("acct:2"), viewModel.uiState.value.dedicatableAccounts.map { it.id })
        }

    @Test
    fun `a failed write is reported as a code the screen maps, and dismissible`() =
        runTest {
            goals.save(draft())
            links.linkable.value = listOf(transaction("t1", minor = -5_000_00))
            links.failOnWrite = AppError.Unexpected(cause = "test")
            val viewModel = viewModel()

            viewModel.onEvent(GoalDetailEvent.Link("t1"))

            assertEquals(GoalsViewModel.STORAGE_ERROR, viewModel.uiState.value.errorCode)
            viewModel.onEvent(GoalDetailEvent.DismissError)
            assertNull(viewModel.uiState.value.errorCode)
        }

    @Test
    fun `ghost progress is reported from the projection rather than recomputed`() =
        runTest {
            goals.save(draft(saved = Money(50_000_00)))

            val state = viewModel().uiState.value

            assertTrue(state.hasGhostProgress)
            assertEquals(Money(50_000_00), state.goal?.savedDeclared)
        }

    // --- helpers -------------------------------------------------------------------------------

    /** Result: a ViewModel wired to the fakes, reading [goalId] the way the nav graph supplies it. */
    private fun viewModel(goalId: String = "goal:1") =
        GoalDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(GoalDetailViewModel.GOAL_ID_KEY to goalId)),
            goals = goals,
            links = links,
            accounts = accounts,
        )

    /** A draft goal with one field varied. */
    private fun draft(
        name: String = "Kerala trip",
        saved: Money = Money.ZERO,
    ) = GoalDraft(
        name = name,
        target = Money(5_00_000_00),
        targetDateIso = "2028-04-30",
        saved = saved,
        plannedMonthly = Money(15_000_00),
    )

    /** Result: one linkable movement. Stored negative, as an outflow is. */
    private fun transaction(
        id: String,
        minor: Long,
    ) = Transaction(
        id = id,
        accountId = "acct:1",
        amount = Money(minor),
        occurredAtUtcMillis = 0L,
        bookedOn = "2026-08-01",
        categoryId = null,
        merchant = "SIP",
        note = null,
        source = TransactionSource.MANUAL,
        type = TransactionType.EXPENSE,
    )

    /** Result: one account, for the dedication chooser. */
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
}
