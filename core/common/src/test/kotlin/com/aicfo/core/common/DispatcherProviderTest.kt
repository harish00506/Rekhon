package com.aicfo.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Behaviour tests for [DispatcherProvider] — task 1.1.3 T5 / AC4 (ARC-006, P-08).
 *
 * Why:  hardcoded `Dispatchers.IO` calls cannot be substituted in a test, so suites that touch
 *       them run on real threads and become slow and flaky. Injecting the dispatchers is what lets
 *       a test swap in a virtual-time scheduler — and what keeps `GlobalScope` unnecessary.
 * What: asserts the production provider hands back the real dispatchers, and that a test provider
 *       built on one [StandardTestDispatcher] drives scheduled work deterministically.
 * Result: async work in this app is injectable and its tests are time-controlled, not wall-clocked.
 * Changelog: 2026-07-25 — Created for issue 1.3.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DispatcherProviderTest {
    /**
     * `Dispatchers.Main` has no factory on a plain JVM, so it must be installed before the
     * production provider is asked for it. Doing this in the test — rather than working around it
     * in the production class — is the point: `main` stays a lazy read of the real dispatcher.
     */
    private val mainDispatcher = StandardTestDispatcher()

    /** Input: none. Output: restores the global Main dispatcher so tests stay isolated. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Input: the production provider. Output: asserts it exposes the real platform dispatchers. */
    @Test
    fun `default provider exposes the platform dispatchers`() {
        Dispatchers.setMain(mainDispatcher)
        val provider = DefaultDispatcherProvider()
        assertSame(Dispatchers.IO, provider.io)
        assertSame(Dispatchers.Default, provider.default)
        assertSame(Dispatchers.Main, provider.main)
    }

    /**
     * Input:  a provider whose three dispatchers are one [StandardTestDispatcher], plus a
     *         coroutine that delays for an hour.
     * Output: asserts `advanceUntilIdle()` runs it immediately — virtual time, not real waiting.
     *         This is AC4: a deterministic, seconds-fast test of time-dependent async work.
     */
    @Test
    fun `test provider drives scheduled work in virtual time`() =
        runTest {
            val dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))
            val order = mutableListOf<String>()

            launch(dispatchers.io) {
                delay(60 * 60 * 1000L)
                order += "after the hour"
            }
            launch(dispatchers.default) {
                order += "immediately"
            }

            advanceUntilIdle()
            assertEquals(listOf("immediately", "after the hour"), order)
        }

    /** Input: a test provider. Output: asserts every channel is the same injected dispatcher. */
    @Test
    fun `test provider substitutes all three dispatchers`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val dispatchers = TestDispatchers(dispatcher)
            assertSame(dispatcher, dispatchers.main)
            assertSame(dispatcher, dispatchers.io)
            assertSame(dispatcher, dispatchers.default)
            // And work actually routes through it rather than a real thread pool.
            withContext(dispatchers.io) { assertEquals(0, testScheduler.currentTime.toInt()) }
        }
}
