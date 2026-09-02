package com.aicfo.feature.emergencyfund

import app.cash.turbine.test
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.emergencyfund.EmergencyStatus
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
 * The emergency-fund ViewModel (issue 7.2; ARC-004, §21.5).
 *
 * Why:  §21.5 asks for Turbine on the `StateFlow` with the **full** sequence asserted, loading
 *       included — the loading state is the one a screenshot never catches and a user always sees.
 *
 *       There is deliberately little else to prove here, and that is the design: this ViewModel does
 *       no arithmetic. Every figure arrives computed (P-03). What it owns is the loading edge, the
 *       error edge, and the evidence toggle.
 * What: the state sequence, the toggle, and the error path.
 * Result: the state contract is pinned.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmergencyFundViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = FakeEmergencyFundRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Input:  one assessment.
     * Output: asserts loading first, then the plan — **both**, in order.
     *
     * Asserting only the final state would pass against a ViewModel that never sets `isLoading`,
     * and the screen would then draw an empty fund for a fraction of a second before the real one.
     */
    @Test
    fun `the state goes loading then loaded, in that order`() =
        runTest(dispatcher) {
            val viewModel = EmergencyFundViewModel(repository)

            viewModel.uiState.test {
                val loading = awaitItem()
                assertTrue("the first state must be loading", loading.isLoading)
                assertNull("nothing is known before the first emission", loading.plan)

                repository.emit(FakeEmergencyFundRepository.plan())

                val loaded = awaitItem()
                assertFalse(loaded.isLoading)
                assertEquals(EmergencyStatus.BUILDING, loaded.plan?.status)
                assertNull(loaded.errorCode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  the toggle, twice.
     * Output: asserts it opens and closes, and that nothing else moves.
     *
     * §10.1's evidence drawer. Collapsed first, so the headline leads.
     */
    @Test
    fun `the evidence drawer opens and closes without disturbing the plan`() =
        runTest(dispatcher) {
            val viewModel = EmergencyFundViewModel(repository)
            repository.emit(FakeEmergencyFundRepository.plan())

            assertFalse("the drawer starts closed", viewModel.uiState.value.isEvidenceOpen)

            viewModel.onEvent(EmergencyFundEvent.ToggleEvidence)
            assertTrue(viewModel.uiState.value.isEvidenceOpen)
            assertEquals(EmergencyStatus.BUILDING, viewModel.uiState.value.plan?.status)

            viewModel.onEvent(EmergencyFundEvent.ToggleEvidence)
            assertFalse(viewModel.uiState.value.isEvidenceOpen)
        }

    /**
     * Input:  an assessment with no essentials.
     * Output: asserts `UNKNOWN` reaches the state as a **plan**, not as an error and not as null.
     *
     * The distinction the screen depends on: a profile with no history has an answer to render
     * ("we cannot size this yet"), and rendering it as an error would put a failure message in front
     * of every fresh install (P-04).
     */
    @Test
    fun `an unknown fund is a loaded state, not an error`() =
        runTest(dispatcher) {
            val viewModel = EmergencyFundViewModel(repository)

            repository.emit(FakeEmergencyFundRepository.plan(essentials = null))

            val state = viewModel.uiState.value
            assertEquals(EmergencyStatus.UNKNOWN, state.plan?.status)
            assertNull("an unanswerable question is not a failure", state.errorCode)
            assertFalse(state.isLoading)
        }

    /**
     * Input:  a surplus assessment, then dismissing an error that was never set.
     * Output: asserts the dismissal is a no-op rather than clearing the plan.
     *
     * Cheap, and it covers the second arm of the event `when` — an exhaustive `when` with an
     * untested branch is a branch nobody has read.
     */
    @Test
    fun `dismissing an error leaves the assessment alone`() =
        runTest(dispatcher) {
            val viewModel = EmergencyFundViewModel(repository)
            repository.emit(FakeEmergencyFundRepository.plan(liquid = Money(5_00_000_00L)))
            assertEquals(EmergencyStatus.SURPLUS, viewModel.uiState.value.plan?.status)

            viewModel.onEvent(EmergencyFundEvent.DismissError)

            assertNull(viewModel.uiState.value.errorCode)
            assertEquals(EmergencyStatus.SURPLUS, viewModel.uiState.value.plan?.status)
        }
}
