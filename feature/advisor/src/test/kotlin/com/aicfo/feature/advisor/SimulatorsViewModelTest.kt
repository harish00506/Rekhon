package com.aicfo.feature.advisor

import com.aicfo.core.common.AppError
import com.aicfo.core.model.Money
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
 * The simulators screen's state machine (issue 10.3; ARC-004, MNY-001/002, P-03).
 *
 * Why:  this class exists to convert. A person types "2,00,000" and "12"; the engines speak paise
 *       and basis points, and a factor of a hundred in the wrong place turns a ₹2,00,000 question
 *       into a ₹2,000 one without anything looking wrong. That is the whole risk here, so it is
 *       what these tests pin — along with the guard that stops an unanswerable question being asked.
 * What: rupees to paise, percent to basis points, the guards, a failure landing as a banner, and
 *       the loan chosen by default.
 * Result: the screen can stay a pure function of its state.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = FakeSimulatorRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `rupees are asked in paise and percents in basis points`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onEvent(SimulatorsEvent.LumpSumChanged("200000"))
            viewModel.onEvent(SimulatorsEvent.ExpectedReturnChanged("12"))
            viewModel.onEvent(SimulatorsEvent.TaxChanged("30"))

            viewModel.onEvent(SimulatorsEvent.SimulatePrepay)

            val asked = repository.prepayAsked.single()
            assertEquals(Money(2_00_000_00L), asked.lumpSum)
            assertEquals(1_200, asked.expectedReturnBps)
            assertEquals(3_000, asked.taxOnReturnsBps)
        }

    @Test
    fun `the first loan is chosen so the screen is usable without a tap`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            assertEquals("account:loan", viewModel.uiState.value.selectedAccountId)
            assertEquals("a card cannot be prepaid", listOf("Home loan"), viewModel.uiState.value.loans.map { it.name })
        }

    @Test
    fun `a question with no sum in it is not asked`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            assertFalse(viewModel.uiState.value.canSimulatePrepay)
            viewModel.onEvent(SimulatorsEvent.SimulatePrepay)

            assertTrue(repository.prepayAsked.isEmpty())
        }

    @Test
    fun `the payoff question carries what is spare each month`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onEvent(SimulatorsEvent.ExtraMonthlyChanged("5000"))

            viewModel.onEvent(SimulatorsEvent.SimulatePayoff)

            assertEquals(Money(5_000_00L), repository.payoffAsked.single())
            assertEquals(25, viewModel.uiState.value.payoff?.avalanche?.months)
        }

    @Test
    fun `with nothing owed there is nothing to plan`() =
        runTest(dispatcher) {
            repository.debts.value = emptyList()
            val viewModel = viewModel()

            viewModel.onEvent(SimulatorsEvent.SimulatePayoff)

            assertTrue(repository.payoffAsked.isEmpty())
        }

    @Test
    fun `a failure is a banner, not a crash, and no answer`() =
        runTest(dispatcher) {
            repository.failure = AppError.Storage("disk")
            val viewModel = viewModel()
            viewModel.onEvent(SimulatorsEvent.LumpSumChanged("200000"))

            viewModel.onEvent(SimulatorsEvent.SimulatePrepay)

            assertEquals("storage", viewModel.uiState.value.errorCode)
            assertNull(viewModel.uiState.value.prepay)
            viewModel.onEvent(SimulatorsEvent.DismissError)
            assertNull(viewModel.uiState.value.errorCode)
        }

    @Test
    fun `anything that is not a digit is not a figure`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(SimulatorsEvent.LumpSumChanged("2,00,000x"))

            assertEquals("200000", viewModel.uiState.value.lumpSumRupees)
        }

    private fun viewModel() = SimulatorsViewModel(repository)
}
