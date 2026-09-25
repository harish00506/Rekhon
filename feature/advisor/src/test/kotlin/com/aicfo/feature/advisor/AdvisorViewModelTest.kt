package com.aicfo.feature.advisor

import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.model.Money
import com.aicfo.data.repository.KeptVerdict
import com.aicfo.domain.engines.purchase.PaymentMethod
import com.aicfo.domain.engines.purchase.Urgency
import com.aicfo.domain.engines.purchase.Verdict
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
 * The advisor screen's state machine (issue 10.1; ARC-004, P-03, P-07).
 *
 * Why:  the ViewModel's one job is to hold a question and show the answer. The risks are that it
 *       asks with the wrong units — rupees typed, paise expected (MNY-001) — that it asks an
 *       incomplete question, or that it asks again while an answer is already in flight.
 * What: the rupee-to-paise conversion, the guard on asking, an EMI needing its instalment, a
 *       failure landing as a banner rather than a crash, and the history.
 * Result: the screen can be a pure function of the state.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdvisorViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = FakePurchaseAdvisorRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `what is typed in rupees is asked in paise`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onEvent(AdvisorEvent.ItemChanged("Headphones"))
            viewModel.onEvent(AdvisorEvent.PriceChanged("8000"))

            viewModel.onEvent(AdvisorEvent.Ask)

            assertEquals(Money(8_000_00L), repository.asked.single().price)
            assertEquals("Headphones", repository.asked.single().item)
        }

    @Test
    fun `anything that is not a digit is not a price`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onEvent(AdvisorEvent.PriceChanged("8,0a0 0"))

            assertEquals("8000", viewModel.uiState.value.priceRupees)
        }

    @Test
    fun `an incomplete question is not asked`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onEvent(AdvisorEvent.ItemChanged("  "))
            viewModel.onEvent(AdvisorEvent.PriceChanged("8000"))

            assertFalse(viewModel.uiState.value.canAsk)
            viewModel.onEvent(AdvisorEvent.Ask)
            assertTrue("nothing should have been asked", repository.asked.isEmpty())
        }

    @Test
    fun `an EMI is not asked without its instalment`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onEvent(AdvisorEvent.ItemChanged("Fridge"))
            viewModel.onEvent(AdvisorEvent.PriceChanged("45000"))
            viewModel.onEvent(AdvisorEvent.MethodChanged(PaymentMethod.EMI))

            assertFalse("§13.1's obligation gate has nothing to judge without it", viewModel.uiState.value.canAsk)

            viewModel.onEvent(AdvisorEvent.EmiChanged("4000"))
            assertTrue(viewModel.uiState.value.canAsk)
            viewModel.onEvent(AdvisorEvent.Ask)
            assertEquals(Money(4_000_00L), repository.asked.single().monthlyEmi)
        }

    @Test
    fun `the urgency the user chose is the urgency that is asked`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onEvent(AdvisorEvent.ItemChanged("Fridge"))
            viewModel.onEvent(AdvisorEvent.PriceChanged("45000"))
            viewModel.onEvent(AdvisorEvent.UrgencyChanged(Urgency.URGENT))

            viewModel.onEvent(AdvisorEvent.Ask)

            assertEquals(Urgency.URGENT, repository.asked.single().urgency)
        }

    @Test
    fun `the answer lands on the state`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.uiState.test {
                assertNull(awaitItem().card)

                viewModel.onEvent(AdvisorEvent.ItemChanged("Headphones"))
                assertEquals("Headphones", awaitItem().item)
                viewModel.onEvent(AdvisorEvent.PriceChanged("30000"))
                awaitItem()
                viewModel.onEvent(AdvisorEvent.Ask)

                assertTrue(awaitItem().isAsking)
                val answered = awaitItem()
                assertEquals(Verdict.STRETCH, answered.card?.verdict)
                assertFalse(answered.isAsking)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a failure is a banner, not a crash, and no card`() =
        runTest(dispatcher) {
            repository.failure = AppError.Storage("disk")
            val viewModel = viewModel()
            viewModel.onEvent(AdvisorEvent.ItemChanged("Headphones"))
            viewModel.onEvent(AdvisorEvent.PriceChanged("30000"))

            viewModel.onEvent(AdvisorEvent.Ask)

            assertEquals("storage", viewModel.uiState.value.errorCode)
            assertNull(viewModel.uiState.value.card)
            viewModel.onEvent(AdvisorEvent.DismissError)
            assertNull(viewModel.uiState.value.errorCode)
        }

    @Test
    fun `the history follows the store`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            repository.history.value =
                listOf(KeptVerdict("purchase:1", "Fridge", Money(45_000_00L), Verdict.NOT_NOW, "2026-09-25"))

            assertEquals(listOf("Fridge"), viewModel.uiState.value.history.map { it.item })
        }

    @Test
    fun `a kept verdict can be opened again`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.onEvent(AdvisorEvent.OpenKept("purchase:1"))

            assertEquals(Verdict.STRETCH, viewModel.uiState.value.card?.verdict)
        }

    private fun viewModel() = AdvisorViewModel(repository)
}
