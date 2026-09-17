package com.aicfo.feature.dashboard

import app.cash.turbine.test
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
 * The full-order screen's state sequence (issue 7.5; ARC-004, §21.5 "Turbine on StateFlow").
 *
 * Why:  the screen has four states — loading, ranked, failed, failed-after-ranked — and each renders
 *       differently. A ViewModel that dropped the ranking on an error, or kept spinning after one,
 *       would pass any test that only looked at the happy path.
 * What: the full `UiState` sequence through each transition, and the one event.
 * Result: every state the screen can show is reached and asserted.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderOfOperationsViewModelTest {
    private val repository = FakeOrderOfOperationsRepository()

    /** `viewModelScope` runs on `Dispatchers.Main`, which has no factory on a plain JVM. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Output: restores the global Main dispatcher so tests stay isolated. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Input: a fresh ViewModel, then a ranking. Output: loading, then loaded with that ranking. */
    @Test
    fun `starts loading and settles on the ranking`() =
        runTest {
            val viewModel = OrderOfOperationsViewModel(repository)
            viewModel.uiState.test {
                val opened = awaitItem()
                assertTrue(opened.isLoading)
                assertNull(opened.ranking)
                assertNull(opened.errorCode)

                repository.emit(cardDebtInput())

                val loaded = awaitItem()
                assertFalse(loaded.isLoading)
                assertEquals(rank(cardDebtInput()), loaded.ranking)
                assertNull(loaded.errorCode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /** Input: a failure before any ranking. Output: the spinner stops and the error is raised. */
    @Test
    fun `a failure stops the spinner and raises the error`() =
        runTest {
            val viewModel = OrderOfOperationsViewModel(repository)

            repository.fail()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertEquals("order_of_operations.storage", state.errorCode)
            assertNull(state.ranking)
        }

    /** Input: a ranking, then a failure. Output: the error is raised and the last ranking stays. */
    @Test
    fun `a failure after a ranking keeps the ranking on screen`() =
        runTest {
            val viewModel = OrderOfOperationsViewModel(repository)
            repository.emit(idleHouseholdInput())

            repository.fail()

            val state = viewModel.uiState.value
            assertEquals("order_of_operations.storage", state.errorCode)
            assertEquals(rank(idleHouseholdInput()), state.ranking)
        }

    /** Input: a raised error, then DismissError. Output: the error is cleared and nothing else moves. */
    @Test
    fun `dismissing the error clears only the error`() =
        runTest {
            val viewModel = OrderOfOperationsViewModel(repository)
            repository.fail()
            val before = viewModel.uiState.value

            viewModel.onEvent(OrderOfOperationsEvent.DismissError)

            assertEquals(before.copy(errorCode = null), viewModel.uiState.value)
        }
}
