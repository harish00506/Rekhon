package com.aicfo.feature.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
 * What the Opportunity screen's state holder must get right (issue 10.7; §30, ARC-004, P-03).
 *
 * Why:  this is the one class on the path between AI-MKT and a person that could quietly change a
 *       figure, and on this screen a changed figure is investment advice the engine never gave. So
 *       the sequence is asserted end to end: nothing before the first emission, the engine's own
 *       numbers unaltered after it, an empty list that says "loaded and empty" rather than looking
 *       like a screen still thinking, and a failure that is surfaced instead of leaving a blank
 *       page that reads as "nothing to see here".
 * What: the loading state, the pass-through, the empty case and the error case.
 * Result: the screen shows what the engine computed, or says why it cannot.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpportunityViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    /** Input: none. Output: the main dispatcher is the test one. */
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    /** Input: none. Output: the main dispatcher is restored. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `before the first emission the screen knows it has not loaded`() =
        runTest(dispatcher) {
            // Otherwise the empty state — "nothing to score yet" — would flash at someone who holds
            // plenty, in the moment before the database answers.
            val state = OpportunityViewModel(FakeMarketSignalRepository()).uiState.first()

            assertFalse(state.isLoaded)
            assertTrue(state.views.isEmpty())
            assertNull(state.errorCode)
        }

    @Test
    fun `the engine's figures arrive unchanged`() =
        runTest(dispatcher) {
            val repository = FakeMarketSignalRepository()
            val model = OpportunityViewModel(repository)

            repository.emit(listOf(FakeMarketSignalRepository.view()))
            val state = model.uiState.first()

            assertTrue(state.isLoaded)
            val assessment = state.views.single().assessment
            assertEquals("Nifty BeES", state.views.single().holdingLabel)
            assertEquals(41, assessment.score)
            assertEquals(60, assessment.possibleScore)
            assertEquals(64, assessment.hitRate?.ratePct)
            assertEquals(2, assessment.tranches.suggested)
            assertEquals("AI-MKT", assessment.provenance.engineId)
        }

    @Test
    fun `an empty list is loaded and empty, not still loading`() =
        runTest(dispatcher) {
            val repository = FakeMarketSignalRepository()
            val model = OpportunityViewModel(repository)

            repository.emit(emptyList())
            val state = model.uiState.first()

            assertTrue(state.isLoaded)
            assertTrue(state.views.isEmpty())
        }

    @Test
    fun `a failing stream is surfaced rather than left as a blank screen`() =
        runTest(dispatcher) {
            val model = OpportunityViewModel(FakeMarketSignalRepository(IllegalStateException("db")))

            val state = model.uiState.first()

            assertEquals("IllegalStateException", state.errorCode)
            assertFalse("a failure is not a loaded screen", state.isLoaded)
        }
}
