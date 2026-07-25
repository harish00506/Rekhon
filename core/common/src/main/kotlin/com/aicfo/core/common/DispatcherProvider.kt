package com.aicfo.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The coroutine dispatchers, injected rather than referenced directly.
 *
 * Why:  ARC-006 (SRS §21.2) requires structured concurrency on injected scopes and bans
 *       `GlobalScope`. Code that names `Dispatchers.IO` inline is untestable in the same way code
 *       that names `System.currentTimeMillis()` is: a test cannot substitute it, so the suite runs
 *       on real thread pools and turns slow and flaky. Injecting the three channels lets a test
 *       swap in one virtual-time scheduler and drive async work deterministically (P-08).
 * What: the three dispatcher roles the app uses — UI, blocking I/O, CPU work.
 * Result: every suspending call site declares which pool it belongs on, and every test can
 *       replace all three.
 * Changelog: 2026-07-25 — Created for issue 1.3 / task 1.1.3 (ARC-006).
 */
interface DispatcherProvider {
    /** The UI thread. Only for touching Compose/view state. */
    val main: CoroutineDispatcher

    /** Blocking I/O — Room, DataStore, file and (consented) network work. */
    val io: CoroutineDispatcher

    /** CPU-bound work — engine maths, parsing, sorting. */
    val default: CoroutineDispatcher
}

/**
 * The production [DispatcherProvider].
 *
 * Why:    one binding for the whole app so no module invents its own thread pool.
 * What:   hands back the platform dispatchers unchanged.
 * Result: real concurrency behaviour in production, replaceable in tests.
 * Changelog: 2026-07-25 — Created for issue 1.3.
 *
 * Each dispatcher is read on access rather than captured in a constructor. That matters for
 * [main]: on a plain JVM there is no Main dispatcher factory, so resolving it eagerly would throw
 * at construction in any unit test that never touches the UI.
 */
class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
}
