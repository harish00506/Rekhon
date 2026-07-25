package com.aicfo.core.common

import kotlinx.coroutines.CoroutineDispatcher

/**
 * A [DispatcherProvider] that routes every channel to one test dispatcher.
 *
 * Why:  a test can only control virtual time if all the work lands on the scheduler it owns. If
 *       `io` stayed real while `default` was faked, `advanceUntilIdle()` would return while the
 *       I/O coroutine was still running on another thread — the classic source of a suite that
 *       passes locally and fails in CI. Collapsing all three onto one dispatcher removes the race
 *       entirely (P-08, ARC-006).
 * What: holds the single dispatcher a test supplies — normally
 *       `StandardTestDispatcher(testScheduler)` from inside `runTest`, so it shares that test's
 *       clock.
 * Result: `advanceUntilIdle()` / `advanceTimeBy()` drive every coroutine the code under test
 *       starts, whichever channel it asked for.
 * Changelog: 2026-07-25 — Created for issue 1.3.
 *
 * Lives in `testFixtures` (no dependency on `kotlinx-coroutines-test`) so any module can inject it
 * with whatever dispatcher its own test creates.
 *
 * Input:  [dispatcher] — the dispatcher all three roles resolve to.
 * Output: a [DispatcherProvider] safe to inject anywhere in a test.
 */
class TestDispatchers(
    private val dispatcher: CoroutineDispatcher,
) : DispatcherProvider {
    override val main: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
}
