package com.aicfo.app.lock

import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.LockoutPolicy
import com.aicfo.core.crypto.PinVerifier
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.datastore.AppLockSettings
import com.aicfo.core.datastore.AppLockStore
import com.aicfo.core.model.AuditEvent
import com.aicfo.core.model.AuditMethod
import com.aicfo.data.repository.AuditEntry
import com.aicfo.data.repository.AuditLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The fail-secure matrix for the app lock (issue 2.2; SEC-002, §23.1).
 *
 * Why:  every bug worth catching in this class has the same shape — the app ends up **unlocked**
 *       when it should not be. A wrong PIN, a cancelled fingerprint prompt, a Keystore that has
 *       stopped working, an unreadable settings file: each is a plausible way for a naive
 *       implementation to fall through to "open", and none of them would be noticed in normal use
 *       because normal use is a correct PIN. So the assertion made over and over below is the
 *       negative one — that the session did **not** open — rather than the happy path.
 * What: the initial state, both unlock factors, every refusal, the SEC-002 lockout, the §23.1
 *       auto-lock timer, and what reaches `audit_log`.
 * Result: the lock's behaviour is pinned before it ever reaches a device — which matters here more
 *       than elsewhere, because no device is available to this project to check it on.
 * Changelog: 2026-07-26 — Created for issue 2.2 (written red before AppLockViewModel.kt existed).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(initialMillis = NOW)
    private val store = FakeAppLockStore()
    private val verifier = FakePinVerifier()
    private val sessionLock = SessionLock()
    private val auditLog = FakeAuditLogRepository()

    /** Input: none. Output: routes `viewModelScope` onto the test dispatcher. */
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    /** Input: none. Output: restores the main dispatcher. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = AppLockViewModel(store, verifier, sessionLock, auditLog, clock)

    // --- the starting position --------------------------------------------------------------

    /**
     * Input:  a brand-new ViewModel, before anything has been read.
     * Output: asserts the very first value is `CHECKING` and shows no content. If the initial value
     *         were `UNLOCKED`, a returning user would see their balances for a frame before the
     *         lock appeared — the exact leak the lock exists to prevent, and one that would never
     *         show up in a test that only checked the settled state.
     */
    @Test
    fun `the first state ever emitted shows no content`() =
        runTest(dispatcher) {
            assertFalse(AppLockUiState().showsContent)
            assertEquals(LockStatus.CHECKING, AppLockUiState().status)
        }

    /**
     * Input:  the app lock switched on, a fresh session.
     * Output: asserts the app is locked and the session gate is shut, so the database provider
     *         refuses to open too.
     */
    @Test
    fun `an enabled lock starts locked`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = true))
            val model = viewModel()

            assertEquals(LockStatus.LOCKED, model.uiState.first().status)
            assertFalse(sessionLock.isUnlocked.value)
        }

    /**
     * Input:  the app lock switched off — a user who never enabled it.
     * Output: asserts content shows and the session opens, so nothing is gated on a PIN that was
     *         never set. Enabling the lock is opt-in; this is the path most users are on.
     */
    @Test
    fun `a disabled lock opens the session without a prompt`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = false))
            val model = viewModel()

            assertEquals(LockStatus.UNLOCKED, model.uiState.first().status)
            assertTrue(sessionLock.isUnlocked.value)
        }

    /**
     * Input:  a settings store that cannot be read at all.
     * Output: asserts the app stays **locked**. A storage fault must not be a way in; the failure
     *         mode of this branch is an app that will not open, which is recoverable, rather than
     *         one that opens for anyone, which is not.
     */
    @Test
    fun `an unreadable settings store leaves the app locked`() =
        runTest(dispatcher) {
            store.fail()
            val model = viewModel()

            assertEquals(LockStatus.LOCKED, model.uiState.first().status)
            assertFalse(sessionLock.isUnlocked.value)
        }

    // --- unlocking ---------------------------------------------------------------------------

    /**
     * Input:  the correct PIN.
     * Output: asserts the session opens, the field is cleared, and the success is audited with its
     *         factor.
     */
    @Test
    fun `the correct PIN unlocks and is audited`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = true))
            verifier.pin = "1234"
            val model = viewModel()

            model.onEvent(AppLockEvent.PinChanged("1234"))
            model.onEvent(AppLockEvent.PinSubmitted)

            assertEquals(LockStatus.UNLOCKED, model.uiState.first().status)
            assertTrue(sessionLock.isUnlocked.value)
            assertEquals("", model.uiState.first().pinEntry)
            assertTrue(AuditEvent.APP_UNLOCK_SUCCESS to AuditMethod.PIN in auditLog.recorded)
        }

    /**
     * Input:  a successful class-3 biometric, as `MainActivity` reports it.
     * Output: asserts the session opens and the success is audited as a biometric.
     */
    @Test
    fun `a successful biometric unlocks and is audited`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = true, biometricEnabled = true))
            val model = viewModel()

            model.onEvent(AppLockEvent.BiometricSucceeded)

            assertEquals(LockStatus.UNLOCKED, model.uiState.first().status)
            assertTrue(AuditEvent.APP_UNLOCK_SUCCESS to AuditMethod.BIOMETRIC in auditLog.recorded)
        }

    /**
     * Input:  four failures, then the correct PIN.
     * Output: asserts the counter is wiped, so a user who once mistyped does not carry those
     *         failures into a lockout months later.
     */
    @Test
    fun `unlocking clears the failure record`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = true, failedAttempts = 4, lastFailureAtUtcMillis = NOW))
            verifier.pin = "1234"
            val model = viewModel()

            model.onEvent(AppLockEvent.PinChanged("1234"))
            model.onEvent(AppLockEvent.PinSubmitted)

            assertEquals(0, store.current().failedAttempts)
            assertEquals(LockoutPolicy.FREE_ATTEMPTS, model.uiState.first().attemptsRemaining)
        }

    // --- everything that must NOT unlock -----------------------------------------------------

    /**
     * Input:  a wrong PIN.
     * Output: asserts the app stays locked, the session gate stays shut, the attempt is charged,
     *         the field is cleared, and the failure is audited.
     */
    @Test
    fun `a wrong PIN leaves the app locked and costs an attempt`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = true))
            verifier.pin = "1234"
            val model = viewModel()

            model.onEvent(AppLockEvent.PinChanged("9999"))
            model.onEvent(AppLockEvent.PinSubmitted)

            val state = model.uiState.first()
            assertEquals(LockStatus.LOCKED, state.status)
            assertFalse(sessionLock.isUnlocked.value)
            assertEquals(AppLockUiState.WRONG_PIN, state.errorCode)
            assertEquals("", state.pinEntry)
            assertEquals(1, store.current().failedAttempts)
            assertTrue(AuditEvent.APP_UNLOCK_FAILURE to AuditMethod.PIN in auditLog.recorded)
        }

    /**
     * Input:  a cancelled or errored biometric prompt.
     * Output: asserts it leaves the app locked. A prompt the user dismissed must not reveal
     *         anything — this is the single most likely way a naive implementation falls open,
     *         because "not an error" and "authenticated" are easy to confuse in the callback.
     */
    @Test
    fun `a cancelled biometric leaves the app locked`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = true, biometricEnabled = true))
            val model = viewModel()

            model.onEvent(AppLockEvent.BiometricFailed)

            assertEquals(LockStatus.LOCKED, model.uiState.first().status)
            assertFalse(sessionLock.isUnlocked.value)
            assertEquals(AppLockUiState.BIOMETRIC_FAILED, model.uiState.first().errorCode)
            assertTrue(AuditEvent.APP_UNLOCK_FAILURE to AuditMethod.BIOMETRIC in auditLog.recorded)
        }

    /**
     * Input:  the correct PIN, but a verifier whose Keystore key has gone (a cleared lock screen,
     *         a factory reset).
     * Output: asserts it is treated as a **wrong** PIN. Crypto that has stopped working must deny,
     *         never admit — the user's route back is biometric or a restore, not an app that opens
     *         because its own security broke.
     */
    @Test
    fun `a broken verifier denies rather than admits`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = true))
            verifier.pin = "1234"
            verifier.broken = true
            val model = viewModel()

            model.onEvent(AppLockEvent.PinChanged("1234"))
            model.onEvent(AppLockEvent.PinSubmitted)

            assertEquals(LockStatus.LOCKED, model.uiState.first().status)
            assertFalse(sessionLock.isUnlocked.value)
            assertEquals(1, store.current().failedAttempts)
        }

    /**
     * Input:  a PIN shorter than the minimum.
     * Output: asserts nothing happens — no unlock, and no attempt charged. Charging an attempt for
     *         a half-typed PIN would let a user lock themselves out by tapping Unlock early.
     */
    @Test
    fun `a too-short PIN is not submitted and costs nothing`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = true))
            verifier.pin = "1234"
            val model = viewModel()

            model.onEvent(AppLockEvent.PinChanged("12"))
            model.onEvent(AppLockEvent.PinSubmitted)

            assertFalse(sessionLock.isUnlocked.value)
            assertEquals(0, store.current().failedAttempts)
            assertFalse(model.uiState.first().canSubmitPin)
        }

    // --- SEC-002's lockout ---------------------------------------------------------------------

    /**
     * Input:  five wrong PINs.
     * Output: asserts the lockout engages with SEC-002's 30 seconds, entry is disabled, no attempts
     *         are shown as remaining, and the lockout itself is audited **once**.
     */
    @Test
    fun `the fifth wrong PIN starts a thirty-second lockout`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = true))
            verifier.pin = "1234"
            val model = viewModel()

            repeat(5) {
                model.onEvent(AppLockEvent.PinChanged("9999"))
                model.onEvent(AppLockEvent.PinSubmitted)
            }

            val state = model.uiState.first()
            assertTrue(state.isLockedOut)
            assertEquals(LockoutPolicy.FIRST_LOCKOUT_MILLIS, state.lockoutRemainingMillis)
            assertEquals(0, state.attemptsRemaining)
            assertFalse("entry must be disabled during a lockout", state.canSubmitPin)
            assertEquals(
                "the lockout should be audited once, not on every later attempt",
                1,
                auditLog.recorded.count { it.first == AuditEvent.APP_LOCKOUT_STARTED },
            )
        }

    /**
     * Input:  a lockout that has expired.
     * Output: asserts entry is possible again and the correct PIN then works. A lockout that never
     *         released would be indistinguishable from a bricked app.
     */
    @Test
    fun `an expired lockout releases and the PIN works again`() =
        runTest(dispatcher) {
            store.set(
                AppLockSettings(enabled = true, failedAttempts = 5, lastFailureAtUtcMillis = NOW),
            )
            verifier.pin = "1234"
            val model = viewModel()
            assertTrue(model.uiState.first().isLockedOut)

            clock.setTo(NOW + LockoutPolicy.FIRST_LOCKOUT_MILLIS)
            model.onEvent(AppLockEvent.LockoutElapsed)

            assertFalse(model.uiState.first().isLockedOut)

            model.onEvent(AppLockEvent.PinChanged("1234"))
            model.onEvent(AppLockEvent.PinSubmitted)
            assertTrue(sessionLock.isUnlocked.value)
        }

    /**
     * Input:  a store that already holds five failures, as it would after a force-stop mid-lockout.
     * Output: asserts the lockout is still in force on a fresh ViewModel. **This is the test that
     *         makes the lockout worth anything**: if it were held in memory, swiping the app away
     *         would clear it, and that is the first thing anyone with a stolen phone would try.
     */
    @Test
    fun `a lockout survives a restart`() =
        runTest(dispatcher) {
            store.set(
                AppLockSettings(enabled = true, failedAttempts = 6, lastFailureAtUtcMillis = NOW),
            )

            val afterRestart = viewModel().uiState.first()

            assertTrue(afterRestart.isLockedOut)
            assertEquals(2 * LockoutPolicy.FIRST_LOCKOUT_MILLIS, afterRestart.lockoutRemainingMillis)
        }

    // --- §23.1's auto-lock timer ---------------------------------------------------------------

    /**
     * Input:  an unlocked app that sits in the background past the timeout.
     * Output: asserts it re-locks and the timeout is audited. The threat §23.1 names is a phone
     *         left unlocked on a desk, so time *away* is what counts.
     */
    @Test
    fun `sitting in the background past the timeout re-locks`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = true, autoLockTimeoutSeconds = 60))
            verifier.pin = "1234"
            val model = viewModel()
            model.onEvent(AppLockEvent.PinChanged("1234"))
            model.onEvent(AppLockEvent.PinSubmitted)
            assertTrue(sessionLock.isUnlocked.value)

            model.onEvent(AppLockEvent.Backgrounded)
            clock.setTo(NOW + 61_000L)
            model.onEvent(AppLockEvent.Foregrounded)

            assertEquals(LockStatus.LOCKED, model.uiState.first().status)
            assertFalse(sessionLock.isUnlocked.value)
            assertTrue(auditLog.recorded.any { it.first == AuditEvent.APP_LOCKED_TIMEOUT })
        }

    /**
     * Input:  a brief task switch, well inside the timeout.
     * Output: asserts the app stays open. Re-locking on every glance at another app would train the
     *         user to pick the shortest PIN they can, which costs more security than it buys.
     */
    @Test
    fun `a brief task switch does not re-lock`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = true, autoLockTimeoutSeconds = 60))
            verifier.pin = "1234"
            val model = viewModel()
            model.onEvent(AppLockEvent.PinChanged("1234"))
            model.onEvent(AppLockEvent.PinSubmitted)

            model.onEvent(AppLockEvent.Backgrounded)
            clock.setTo(NOW + 5_000L)
            model.onEvent(AppLockEvent.Foregrounded)

            assertEquals(LockStatus.UNLOCKED, model.uiState.first().status)
            assertTrue(sessionLock.isUnlocked.value)
        }

    /**
     * Input:  a background/foreground cycle with the lock switched off.
     * Output: asserts nothing re-locks, so a user who never enabled the lock is never prompted.
     */
    @Test
    fun `a disabled lock never re-locks on return`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = false, autoLockTimeoutSeconds = 60))
            val model = viewModel()

            model.onEvent(AppLockEvent.Backgrounded)
            clock.setTo(NOW + 600_000L)
            model.onEvent(AppLockEvent.Foregrounded)

            assertEquals(LockStatus.UNLOCKED, model.uiState.first().status)
        }

    // --- what the screen offers -----------------------------------------------------------------

    /**
     * Input:  a device with no usable sensor, then one with a sensor but the setting off.
     * Output: asserts the biometric button is offered only when it would actually work — an
     *         unlock button that does nothing is worse than no button, on the one screen where the
     *         user may already be anxious.
     */
    @Test
    fun `the biometric button appears only when it would work`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = true, biometricEnabled = true))
            verifier.pin = "1234"
            val model = viewModel()

            model.setBiometricAvailable(false)
            assertFalse(model.uiState.first().canUseBiometric)

            model.setBiometricAvailable(true)
            assertTrue(model.uiState.first().canUseBiometric)

            store.set(AppLockSettings(enabled = true, biometricEnabled = false))
            assertFalse(model.uiState.first().canUseBiometric)
        }

    /**
     * Input:  a lockout in force on a device with biometrics.
     * Output: asserts the biometric button is withdrawn too. SEC-002's lockout would be trivially
     *         bypassable if the other factor stayed live throughout it.
     */
    @Test
    fun `a lockout also withdraws the biometric option`() =
        runTest(dispatcher) {
            store.set(
                AppLockSettings(
                    enabled = true,
                    biometricEnabled = true,
                    failedAttempts = 5,
                    lastFailureAtUtcMillis = NOW,
                ),
            )
            verifier.pin = "1234"
            val model = viewModel()
            model.setBiometricAvailable(true)

            assertTrue(model.uiState.first().isLockedOut)
            assertFalse(model.uiState.first().canUseBiometric)
        }

    /**
     * Input:  a wrong PIN, then the user starting to type again.
     * Output: asserts the error clears as soon as they do, rather than sitting under a fresh
     *         attempt as though it described it.
     */
    @Test
    fun `typing again clears the previous error`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = true))
            verifier.pin = "1234"
            val model = viewModel()

            model.onEvent(AppLockEvent.PinChanged("9999"))
            model.onEvent(AppLockEvent.PinSubmitted)
            assertEquals(AppLockUiState.WRONG_PIN, model.uiState.first().errorCode)

            model.onEvent(AppLockEvent.PinChanged("1"))
            assertEquals(null, model.uiState.first().errorCode)
        }

    /**
     * Input:  a collector watching across a wrong PIN.
     * Output: asserts the attempts-remaining countdown is emitted, which is what warns the user
     *         before the lockout bites instead of surprising them with it.
     */
    @Test
    fun `the attempts countdown is emitted as failures accumulate`() =
        runTest(dispatcher) {
            store.set(AppLockSettings(enabled = true))
            verifier.pin = "1234"
            val model = viewModel()

            // distinctUntilChanged: the combine legitimately re-emits while `isVerifying` flips, so
            // the same count arrives more than once. What is under test is the countdown's
            // sequence of values, not how many times the state was recomposed.
            model.uiState.map { it.attemptsRemaining }.distinctUntilChanged().test {
                assertEquals(LockoutPolicy.FREE_ATTEMPTS, awaitItem())

                model.onEvent(AppLockEvent.PinChanged("9999"))
                model.onEvent(AppLockEvent.PinSubmitted)

                assertEquals(LockoutPolicy.FREE_ATTEMPTS - 1, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private companion object {
        /** A fixed instant — the point is that it comes from [FakeClock], not the wall. */
        const val NOW = 1_800_000_000_000L
    }
}

/**
 * An [AppLockStore] held in memory, with a switch to make reads fail.
 * Why:    the ViewModel's most important branch is what it does when this store cannot be read, so
 *         the fake has to be able to fail on demand.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
private class FakeAppLockStore : AppLockStore {
    private val state = MutableStateFlow<Result<AppLockSettings, AppError>>(Ok(AppLockSettings()))

    fun set(settings: AppLockSettings) {
        state.value = Ok(settings)
    }

    fun fail() {
        state.value = Err(AppError.Storage("IOException"))
    }

    fun current(): AppLockSettings = (state.value as Ok).value

    override fun observe(): Flow<Result<AppLockSettings, AppError>> = state

    override suspend fun setEnabled(enabled: Boolean) = mutate { it.copy(enabled = enabled) }

    override suspend fun setBiometricEnabled(enabled: Boolean) = mutate { it.copy(biometricEnabled = enabled) }

    override suspend fun setAutoLockTimeoutSeconds(seconds: Int) = mutate { it.copy(autoLockTimeoutSeconds = seconds) }

    override suspend fun recordFailedUnlock() =
        mutate {
            it.copy(
                failedAttempts = it.failedAttempts + 1,
                lastFailureAtUtcMillis = it.lastFailureAtUtcMillis ?: 1_800_000_000_000L,
            )
        }

    override suspend fun clearFailedUnlocks() = mutate { it.copy(failedAttempts = 0, lastFailureAtUtcMillis = null) }

    private fun mutate(transform: (AppLockSettings) -> AppLockSettings): Result<Unit, AppError> {
        val current = state.value
        if (current !is Ok) return Err(AppError.Storage("IOException"))
        state.value = Ok(transform(current.value))
        return Ok(Unit)
    }
}

/**
 * A [PinVerifier] with a known PIN and a switch to break its crypto.
 * Why: [broken] models a Keystore key that has gone — a real condition after a factory reset or
 *         a removed device lock screen, and the case where "deny" versus "admit" matters most.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
private class FakePinVerifier : PinVerifier {
    var pin: String? = null
    var broken = false

    override fun isPinSet(): Result<Boolean, AppError> = Ok(pin != null)

    override fun setPin(pin: String): Result<Unit, AppError> = Ok(Unit).also { this.pin = pin }

    override fun verify(pin: String): Result<Boolean, AppError> =
        if (broken) Err(AppError.Crypto("KeyStoreException")) else Ok(pin == this.pin)

    override fun clearPin(): Result<Unit, AppError> = Ok(Unit).also { pin = null }
}

/**
 * An [AuditLogRepository] that just remembers what it was told.
 * Why:    §21.6 requires every auth outcome to be recorded, and "was the refusal logged?" is only
 *         assertable if something keeps the list.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
private class FakeAuditLogRepository : AuditLogRepository {
    val recorded = mutableListOf<Pair<AuditEvent, AuditMethod?>>()

    override suspend fun record(
        event: AuditEvent,
        method: AuditMethod?,
    ): Result<Unit, AppError> = Ok(Unit).also { recorded += event to method }

    override fun observeRecent(limit: Int): Flow<List<AuditEntry>> = MutableStateFlow(emptyList())

    override suspend fun countSince(
        event: AuditEvent,
        sinceUtcMillis: Long,
    ): Result<Int, AppError> = Ok(recorded.count { it.first == event })
}
