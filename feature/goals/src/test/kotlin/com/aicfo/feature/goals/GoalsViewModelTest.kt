package com.aicfo.feature.goals

import com.aicfo.core.common.AppError
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.goals.Feasibility
import com.aicfo.domain.engines.goals.GoalStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [GoalsViewModel] — the parsing and the state machine (issue 7.1; ARC-004).
 *
 * Why:  the engine's arithmetic is proven in `:domain:engines:goals`, so repeating it here would
 *       assert nothing. **What this class owns is the boundary between text and money**, and the
 *       ways it can go wrong all leave the screen looking fine: a rupee amount parsed as paise is
 *       out by a hundred, a blank plan refused instead of defaulted blocks the user, and an editor
 *       cleared on a failed save loses their typing.
 * What: the state sequence, the parse, every refusal, and what actually reaches the repository.
 * Result: the goals screen's behaviour is checked without a device.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: FakeGoalRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeGoalRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Result: a subject over the fakes. Input: [surplus] — the month's spare money, null for the
     *   UNKNOWN branch; [runwayBps] — the emergency runway, defaulting clear of the gate.
     * Output: [GoalsViewModel].
     *
     * The waterfall fake reads the **same** goal fake, so a reorder driven through the ViewModel
     * shows up in the plan — which is what makes the drag testable without a database (issue 7.3).
     */
    private fun viewModel(
        surplus: Money? = Money(30_000_00),
        runwayBps: Int? = 90_000,
    ) = GoalsViewModel(
        repository,
        FakeGoalWaterfallRepository(repository, surplus = surplus, runwayBps = runwayBps),
    )

    @Test
    fun `an empty store settles on empty rather than loading for ever`() =
        runTest(dispatcher) {
            val subject = viewModel()

            val state = subject.uiState.value
            assertTrue("the empty state must be reachable", state.isEmpty)
            assertTrue(!state.isLoading)
        }

    @Test
    fun `saving a filled editor writes the goal and closes`() =
        runTest(dispatcher) {
            val subject = viewModel()
            subject.fill()

            subject.onEvent(GoalsEvent.SaveEditor)

            assertNull("a successful save closes the editor", subject.uiState.value.editor)
            val draft = repository.saved.single()
            assertEquals("MNY-001: rupees typed, paise stored", Money(5_00_000_00), draft.target)
            assertEquals(Money(1_00_000_00), draft.saved)
            assertEquals(Money(15_000_00), draft.plannedMonthly)
            assertEquals("2028-04-30", draft.targetDateIso)
        }

    @Test
    fun `the saved goal comes back with the engine's figure, not one this screen made up`() =
        runTest(dispatcher) {
            // ₹4,00,000 over the 20 months to the date is ₹20,000 a month, against a ₹15,000 plan.
            val subject = viewModel()
            subject.fill()

            subject.onEvent(GoalsEvent.SaveEditor)

            val goal = subject.uiState.value.goals.single()
            assertEquals(Money(20_000_00), goal.requiredMonthly)
            assertEquals(Money(5_000_00), goal.shortfallMonthly)
            assertEquals(GoalStatus.BEHIND, goal.status)
        }

    @Test
    fun `a blank monthly plan means zero, not a refusal`() =
        runTest(dispatcher) {
            // Deciding the monthly is the thing the required figure exists to help with. Demanding
            // it up front would ask for the answer before showing the question.
            val subject = viewModel()
            subject.fill(planned = "")

            subject.onEvent(GoalsEvent.SaveEditor)

            assertNull(subject.uiState.value.editor)
            assertEquals(Money.ZERO, repository.saved.single().plannedMonthly)
            assertNull("no plan, so no date invented", subject.uiState.value.goals.single().etaIsoDate)
        }

    @Test
    fun `a blank saved amount means zero, because a new goal has nothing in it`() =
        runTest(dispatcher) {
            val subject = viewModel()
            subject.fill(saved = "")

            subject.onEvent(GoalsEvent.SaveEditor)

            assertEquals(Money.ZERO, repository.saved.single().saved)
        }

    @Test
    fun `a goal with no name is refused and the typing is kept`() =
        runTest(dispatcher) {
            val subject = viewModel()
            subject.fill(name = "  ")

            subject.onEvent(GoalsEvent.SaveEditor)

            val editor = subject.uiState.value.editor
            assertEquals(GoalsViewModel.FIELD_GOAL, editor?.fieldError)
            assertEquals("the target the user typed must survive the refusal", "500000", editor?.target)
            assertTrue("nothing was written", repository.saved.isEmpty())
        }

    @Test
    fun `a target that will not parse is refused, and so is one of zero`() =
        runTest(dispatcher) {
            // Blank and unparseable are different outcomes (P-03), and a zero target would store a
            // goal that reads "no target set yet" with no clue why.
            val subject = viewModel()

            subject.fill(target = "five lakh")
            subject.onEvent(GoalsEvent.SaveEditor)
            assertEquals(GoalsViewModel.FIELD_GOAL, subject.uiState.value.editor?.fieldError)

            subject.fill(target = "0")
            subject.onEvent(GoalsEvent.SaveEditor)
            assertEquals(GoalsViewModel.FIELD_GOAL, subject.uiState.value.editor?.fieldError)
            assertTrue(repository.saved.isEmpty())
        }

    @Test
    fun `a missing date is refused rather than defaulted to today`() =
        runTest(dispatcher) {
            val subject = viewModel()
            subject.fill(date = "")

            subject.onEvent(GoalsEvent.SaveEditor)

            assertEquals(GoalsViewModel.FIELD_GOAL, subject.uiState.value.editor?.fieldError)
        }

    @Test
    fun `editing a field clears the error, so it never outlives what it was about`() =
        runTest(dispatcher) {
            val subject = viewModel()
            subject.fill(name = "")
            subject.onEvent(GoalsEvent.SaveEditor)

            subject.onEvent(GoalsEvent.NameChanged("Kerala trip"))

            assertNull(subject.uiState.value.editor?.fieldError)
        }

    @Test
    fun `a store that refuses the write keeps the editor open`() =
        runTest(dispatcher) {
            val subject = viewModel()
            subject.fill()
            repository.failOnSave = AppError.Storage("disk")

            subject.onEvent(GoalsEvent.SaveEditor)

            assertEquals(GoalsViewModel.FIELD_GOAL, subject.uiState.value.editor?.fieldError)
        }

    @Test
    fun `editing an existing goal fills the editor from what is on screen`() =
        runTest(dispatcher) {
            val subject = viewModel()
            subject.fill()
            subject.onEvent(GoalsEvent.SaveEditor)
            val id = subject.uiState.value.goals.single().goalId

            subject.onEvent(GoalsEvent.EditGoal(id))

            val editor = subject.uiState.value.editor
            assertEquals(id, editor?.goalId)
            assertEquals("Kerala trip", editor?.name)
            assertEquals("the stored date refills the field it came from", "2028-04-30", editor?.targetDate)
        }

    @Test
    fun `editing a goal updates it rather than minting a second`() =
        runTest(dispatcher) {
            val subject = viewModel()
            subject.fill()
            subject.onEvent(GoalsEvent.SaveEditor)
            val id = subject.uiState.value.goals.single().goalId

            subject.onEvent(GoalsEvent.EditGoal(id))
            subject.onEvent(GoalsEvent.SavedChanged("200000"))
            subject.onEvent(GoalsEvent.SaveEditor)

            assertEquals(1, subject.uiState.value.goals.size)
            assertEquals(Money(2_00_000_00), subject.uiState.value.goals.single().saved)
        }

    @Test
    fun `deleting a goal removes it from the list`() =
        runTest(dispatcher) {
            val subject = viewModel()
            subject.fill()
            subject.onEvent(GoalsEvent.SaveEditor)
            val id = subject.uiState.value.goals.single().goalId

            subject.onEvent(GoalsEvent.DeleteGoal(id))

            assertTrue(subject.uiState.value.goals.isEmpty())
        }

    @Test
    fun `cancelling the editor writes nothing`() =
        runTest(dispatcher) {
            val subject = viewModel()
            subject.fill()

            subject.onEvent(GoalsEvent.CancelEditor)

            assertNull(subject.uiState.value.editor)
            assertTrue(repository.saved.isEmpty())
        }

    /** Opens the editor and types an ordinary goal, varying one field. */
    private fun GoalsViewModel.fill(
        name: String = "Kerala trip",
        target: String = "500000",
        date: String = "2028-04-30",
        saved: String = "100000",
        planned: String = "15000",
    ) {
        onEvent(GoalsEvent.AddGoal)
        onEvent(GoalsEvent.NameChanged(name))
        onEvent(GoalsEvent.TargetChanged(target))
        onEvent(GoalsEvent.TargetDateChanged(date))
        onEvent(GoalsEvent.SavedChanged(saved))
        onEvent(GoalsEvent.PlannedMonthlyChanged(planned))
    }

    // --- issue 7.3: the waterfall and the draggable plan ------------------------------------------

    @Test
    fun `the plan arrives beside the goals and says whether they fit`() =
        runTest(dispatcher) {
            val subject = viewModel(surplus = Money(30_000_00))
            // ₹1,20,000 over 12 months is ₹10,000 a month, comfortably inside ₹30,000 spare.
            subject.saveGoal("Kerala trip", target = "1,20,000.00", on = "2027-08-30")

            val plan = requireNotNull(subject.uiState.value.waterfall)
            assertEquals(Feasibility.FEASIBLE, plan.feasibility)
            assertEquals(Money(10_000_00), plan.lines.single().allocatedMonthly)
        }

    @Test
    fun `goals that together exceed the surplus report the gap, not just a verdict`() =
        runTest(dispatcher) {
            // The whole reason this issue exists: each goal alone is affordable, the pair is not.
            val subject = viewModel(surplus = Money(15_000_00))
            subject.saveGoal("Kerala trip", target = "1,20,000.00", on = "2027-08-30")
            subject.saveGoal("New laptop", target = "1,20,000.00", on = "2027-08-30")

            val plan = requireNotNull(subject.uiState.value.waterfall)
            assertEquals(Feasibility.INFEASIBLE, plan.feasibility)
            assertEquals("₹20,000 asked for against ₹15,000 spare", Money(5_000_00), plan.gapMonthly)
            assertEquals(Money(10_000_00), plan.lines[0].allocatedMonthly)
            assertEquals(Money(5_000_00), plan.lines[1].allocatedMonthly)
        }

    @Test
    fun `moving a goal up changes which one goes short`() =
        runTest(dispatcher) {
            // FR-GOAL-005, through the accessible action rather than a gesture — the path a Compose
            // test and a TalkBack user both take.
            val subject = viewModel(surplus = Money(15_000_00))
            subject.saveGoal("Kerala trip", target = "1,20,000.00", on = "2027-08-30")
            val laptop = subject.saveGoal("New laptop", target = "1,20,000.00", on = "2027-08-30")

            subject.onEvent(GoalsEvent.MoveUp(laptop))

            val plan = requireNotNull(subject.uiState.value.waterfall)
            assertEquals(listOf("New laptop", "Kerala trip"), plan.lines.map { it.name })
            assertEquals("the laptop is funded now", Money(10_000_00), plan.lines[0].allocatedMonthly)
            assertEquals("and the trip is the one that goes short", Money(5_000_00), plan.lines[1].allocatedMonthly)
        }

    @Test
    fun `the whole order is written, not just the moved pair`() =
        runTest(dispatcher) {
            // `sort_order` is positional: writing only the two that moved would leave the rest
            // sharing a rank, and the tie-break would decide the plan instead of the user.
            val subject = viewModel()
            val a = subject.saveGoal("A", target = "1,20,000.00", on = "2027-08-30")
            val b = subject.saveGoal("B", target = "1,20,000.00", on = "2027-08-30")
            val c = subject.saveGoal("C", target = "1,20,000.00", on = "2027-08-30")

            subject.onEvent(GoalsEvent.MoveDown(a))

            assertEquals(listOf(listOf(b, a, c)), repository.reorders)
        }

    @Test
    fun `a move that would fall off the end of the list is ignored`() =
        runTest(dispatcher) {
            // The bounds live in the ViewModel rather than the composable, so an out-of-range drag
            // is harmless rather than a silent write of a rank nothing else shares.
            val subject = viewModel()
            val only = subject.saveGoal("A", target = "1,20,000.00", on = "2027-08-30")

            subject.onEvent(GoalsEvent.MoveUp(only))
            subject.onEvent(GoalsEvent.MoveDown(only))
            subject.onEvent(GoalsEvent.MoveGoal(0, 9))

            assertEquals(emptyList<List<String>>(), repository.reorders)
        }

    @Test
    fun `RULE-EMERG-FIRST holding is reported on the line, not just in the total`() =
        runTest(dispatcher) {
            // ₹0.00 allocated because the buffer comes first, and ₹0.00 allocated because the money
            // ran out, are the same figure and completely different advice.
            val subject = viewModel(surplus = Money(30_000_00), runwayBps = 10_000)
            subject.saveGoal("Kerala trip", target = "1,20,000.00", on = "2027-08-30")

            val plan = requireNotNull(subject.uiState.value.waterfall)
            assertTrue("the gate must fire below three months", plan.emergencyFirstApplied)
            assertTrue(plan.lines.single().blockedByEmergencyFund)
        }

    @Test
    fun `a surplus that cannot be resolved reads as unknown, never as impossible`() =
        runTest(dispatcher) {
            val subject = viewModel(surplus = null)
            subject.saveGoal("Kerala trip", target = "1,20,000.00", on = "2027-08-30")

            assertEquals(Feasibility.UNKNOWN, requireNotNull(subject.uiState.value.waterfall).feasibility)
        }

    /**
     * Saves one goal through the editor, the way the screen does.
     * Result: the new goal's id. Input: [name]; [target] as typed; [on] — the ISO date.
     * Output: [String].
     */
    private fun GoalsViewModel.saveGoal(
        name: String,
        target: String,
        on: String,
    ): String {
        onEvent(GoalsEvent.AddGoal)
        onEvent(GoalsEvent.NameChanged(name))
        onEvent(GoalsEvent.TargetChanged(target))
        onEvent(GoalsEvent.TargetDateChanged(on))
        onEvent(GoalsEvent.SaveEditor)
        return uiState.value.goals.first { it.name == name }.goalId
    }
}
