package com.aicfo.core.datastore

import androidx.datastore.core.DataStore
import app.cash.turbine.test
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.common.getOrNull
import com.aicfo.core.datastore.proto.CfoSettingsProto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the app-lock store (issue 2.2; SEC-002, §23.1, TIM-001).
 *
 * Why:  two of these fields are not preferences, they are the lockout itself. SEC-002's escalating
 *       delay is computed from `failedAttempts` and `lastFailureAt`, so if either fails to persist,
 *       an attacker clears the lockout by force-stopping the app and gets unlimited PIN guesses —
 *       a hole that would be completely invisible in normal use. The auto-lock timer has a quieter
 *       trap: proto3 cannot tell "unset" from `0`, and a literal `0` would re-lock the app on every
 *       task switch.
 * What: the defaults, each toggle round-tripping, the zero-means-default rule, and that failures
 *       accumulate, are stamped from the injected clock, and clear on success.
 * Result: the state `LockoutPolicy` reads is proven to survive a restart.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val clock = FakeClock()
    private lateinit var dataStore: DataStore<CfoSettingsProto>
    private lateinit var store: AppLockStore

    private fun open() {
        val file = folder.newFile("settings.pb").also { it.delete() }
        dataStore = CfoSettingsStorage.create(file.absolutePath, scope)
        store =
            DataStoreAppLockStore(
                dataStore,
                clock,
                TestDispatchers(UnconfinedTestDispatcher(scope.testScheduler)),
            )
    }

    /** Input: none. Output: releases the store's scope between tests. */
    @After
    fun tearDown() {
        scope.cancel()
    }

    /**
     * Input:  a fresh store.
     * Output: asserts the lock is **off**, biometrics are off, the timer reads §23.1's one minute,
     *         and no failures are recorded. A lock that defaulted **on** would gate a brand-new
     *         install behind a PIN nobody had set — an app that cannot be opened at all.
     */
    @Test
    fun `a fresh store has the app lock off and the default timer`() =
        scope.runTest {
            open()
            val lock = store.observe().first().getOrNull()!!
            assertFalse(lock.enabled)
            assertFalse(lock.biometricEnabled)
            assertEquals(DEFAULT_AUTO_LOCK_SECONDS, lock.autoLockTimeoutSeconds)
            assertEquals(0, lock.failedAttempts)
            assertNull(lock.lastFailureAtUtcMillis)
        }

    /**
     * Input:  both toggles, changed separately.
     * Output: asserts each round-trips without moving the other. SEC-002 makes the PIN the fallback
     *         and biometrics the convenience, so a user must be able to keep the lock and refuse
     *         the sensor — and a device may have no class-3 sensor at all.
     */
    @Test
    fun `the lock and biometric toggles round trip independently`() =
        scope.runTest {
            open()
            assertWritten(store.setEnabled(true))
            val afterLock = store.observe().first().getOrNull()!!
            assertTrue(afterLock.enabled)
            assertFalse("enabling the lock must not silently enable biometrics too", afterLock.biometricEnabled)

            assertWritten(store.setBiometricEnabled(true))
            assertWritten(store.setEnabled(false))
            val afterBoth = store.observe().first().getOrNull()!!
            assertFalse(afterBoth.enabled)
            assertTrue(afterBoth.biometricEnabled)
        }

    /**
     * Input:  a chosen timeout, then a reset to zero.
     * Output: asserts a real value round-trips and that `0` reads back as the §23.1 default rather
     *         than as "re-lock immediately".
     */
    @Test
    fun `a zero timeout reads back as the one-minute default`() =
        scope.runTest {
            open()
            assertWritten(store.setAutoLockTimeoutSeconds(300))
            assertEquals(300, store.observe().first().getOrNull()!!.autoLockTimeoutSeconds)

            assertWritten(store.setAutoLockTimeoutSeconds(0))
            assertEquals(
                DEFAULT_AUTO_LOCK_SECONDS,
                store.observe().first().getOrNull()!!.autoLockTimeoutSeconds,
            )
        }

    /**
     * Input:  three failed unlocks against a fixed clock.
     * Output: asserts the counter accumulates and the timestamp comes from the injected [FakeClock]
     *         (TIM-001), not the wall clock. `LockoutPolicy` reads both, so a counter that reset —
     *         or a timestamp taken from a clock the user can change — would hand an attacker
     *         unlimited PIN guesses.
     */
    @Test
    fun `failed unlocks accumulate and are stamped from the injected clock`() =
        scope.runTest {
            open()
            clock.setTo(FIXED_FAILURE_MILLIS)
            repeat(3) { assertWritten(store.recordFailedUnlock()) }

            val lock = store.observe().first().getOrNull()!!
            assertEquals(3, lock.failedAttempts)
            assertEquals(FIXED_FAILURE_MILLIS, lock.lastFailureAtUtcMillis)
        }

    /**
     * Input:  a fresh store over the file an earlier store wrote failures to.
     * Output: asserts the failures are still there. **This is the test that matters most in the
     *         file**: SEC-002's lockout is only a defence if force-stopping the app does not reset
     *         it, and a new store over the same file is exactly what a relaunch produces.
     */
    @Test
    fun `failures survive a restart`() =
        scope.runTest {
            open()
            clock.setTo(FIXED_FAILURE_MILLIS)
            repeat(5) { assertWritten(store.recordFailedUnlock()) }

            val afterRestart =
                DataStoreAppLockStore(
                    dataStore,
                    clock,
                    TestDispatchers(UnconfinedTestDispatcher(scope.testScheduler)),
                )
            val lock = afterRestart.observe().first().getOrNull()!!
            assertEquals(5, lock.failedAttempts)
            assertEquals(FIXED_FAILURE_MILLIS, lock.lastFailureAtUtcMillis)
        }

    /**
     * Input:  failures followed by a success.
     * Output: asserts both the counter and the timestamp are cleared, so the next wrong PIN starts
     *         the schedule from the beginning rather than resuming a stale lockout.
     */
    @Test
    fun `a successful unlock clears the failure record`() =
        scope.runTest {
            open()
            clock.setTo(FIXED_FAILURE_MILLIS)
            repeat(5) { assertWritten(store.recordFailedUnlock()) }
            assertEquals(5, store.observe().first().getOrNull()!!.failedAttempts)

            assertWritten(store.clearFailedUnlocks())
            val cleared = store.observe().first().getOrNull()!!
            assertEquals(0, cleared.failedAttempts)
            assertNull(cleared.lastFailureAtUtcMillis)
        }

    /**
     * Input:  a collector watching while a failure is recorded.
     * Output: asserts the change is emitted, which is what drives the lock screen's "2 attempts
     *         remaining" and its countdown without the screen polling anything.
     */
    @Test
    fun `a failed unlock is emitted to observers`() =
        scope.runTest {
            open()
            clock.setTo(FIXED_FAILURE_MILLIS)
            store.observe().test {
                assertEquals(0, awaitItem().getOrNull()!!.failedAttempts)

                assertWritten(store.recordFailedUnlock())

                assertEquals(1, awaitItem().getOrNull()!!.failedAttempts)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  lock settings and unrelated settings written to the same file.
     * Output: asserts neither clobbers the other — all three interfaces share one atomic store.
     */
    @Test
    fun `lock state and other settings share the file without clobbering each other`() =
        scope.runTest {
            open()
            val settings =
                DataStoreSettingsStore(
                    dataStore,
                    clock,
                    TestDispatchers(UnconfinedTestDispatcher(scope.testScheduler)),
                )

            assertWritten(store.setEnabled(true))
            assertWritten(settings.setCurrencyCode("INR"))
            assertWritten(store.recordFailedUnlock())

            assertTrue(store.observe().first().getOrNull()!!.enabled)
            assertEquals(1, store.observe().first().getOrNull()!!.failedAttempts)
            assertEquals("INR", settings.observe().first().getOrNull()!!.currencyCode)
        }

    /** Result: fails the test if a write did not succeed. Input: [result]. Output: none. */
    private fun assertWritten(result: Result<Unit, *>) {
        assertTrue("the write should have succeeded, got $result", result is Ok)
    }

    private companion object {
        /** An arbitrary fixed instant — the point is that it comes from [FakeClock], not the wall. */
        const val FIXED_FAILURE_MILLIS = 1_800_000_500_000L
    }
}
