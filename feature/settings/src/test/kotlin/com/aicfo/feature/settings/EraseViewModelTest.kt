package com.aicfo.feature.settings

import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.PinVerifier
import com.aicfo.data.repository.EraseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * The gates in front of an irreversible erase (issue 11.4; §23, §34, SEC-003).
 *
 * Why:  the acceptance criterion is "explicit confirmation **and** auth", and both halves of that
 *       are the kind of requirement that passes review and then turns out not to be enforced —
 *       because a disabled button is not a gate. Anything that can send [EraseEvent.Confirmed]
 *       bypasses the button: a stale recomposition, a test, a future caller, an accessibility
 *       action. So the view model re-checks what the button already checked, and these tests drive
 *       the event directly, never through a state that would have hidden it.
 *
 *       The other thing being pinned down here is what happens on the **wrong PIN**: nothing is
 *       erased, and the failure looks exactly like a failed unlock rather than telling the user
 *       whether the data was reachable.
 * What: the word, the PIN, the wrong PIN, the no-PIN device, the repository failure, and the
 *       finished state.
 * Result: an erase that cannot happen by accident and cannot happen without authentication.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EraseViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = RecordingEraseRepository()
    private val verifier = StubPinVerifier()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a device with a PIN requires one`() =
        runTest(dispatcher) {
            verifier.pinSet = true

            viewModel().uiState.test {
                assertFalse(awaitItem().isPinRequired)
                assertTrue("a PIN exists, so it must be demanded", awaitItem().isPinRequired)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a device with no PIN asks only for the typed word`() =
        runTest(dispatcher) {
            // SEC-002 makes the app lock optional. A user who never set one has no secret to prove,
            // and demanding a PIN they do not have would lock them out of erasing their own data.
            verifier.pinSet = false
            val viewModel = viewModel().also { it.awaitLoaded() }

            viewModel.onEvent(EraseEvent.ConfirmationTyped(WORD))

            assertFalse(viewModel.uiState.value.isPinRequired)
            assertTrue(viewModel.uiState.value.canErase)
        }

    @Test
    fun `the wrong word erases nothing`() =
        runTest(dispatcher) {
            val viewModel = viewModel().also { it.awaitLoaded() }
            viewModel.onEvent(EraseEvent.ConfirmationTyped("delete"))

            viewModel.onEvent(EraseEvent.Confirmed)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, repository.erases)
            assertFalse(viewModel.uiState.value.isErased)
        }

    @Test
    fun `the right word alone erases nothing on a device with a PIN`() =
        runTest(dispatcher) {
            // The criterion is confirmation *and* auth. A screen that erased here would satisfy
            // neither the requirement nor anyone whose phone was taken out of their hand unlocked.
            verifier.pinSet = true
            val viewModel = viewModel().also { it.awaitLoaded() }
            viewModel.onEvent(EraseEvent.ConfirmationTyped(WORD))

            viewModel.onEvent(EraseEvent.Confirmed)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, repository.erases)
        }

    @Test
    fun `a wrong PIN erases nothing, and says only that the PIN was wrong`() =
        runTest(dispatcher) {
            verifier.pinSet = true
            verifier.accepts = "1234"
            val viewModel = viewModel().also { it.awaitLoaded() }
            viewModel.onEvent(EraseEvent.ConfirmationTyped(WORD))
            viewModel.onEvent(EraseEvent.PinTyped("9999"))

            viewModel.onEvent(EraseEvent.Confirmed)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, repository.erases)
            assertEquals("erase.pin", viewModel.uiState.value.errorCode)
            assertFalse("a refused PIN must not leave the screen looking busy", viewModel.uiState.value.isErasing)
        }

    @Test
    fun `the right word and the right PIN erase everything, once`() =
        runTest(dispatcher) {
            verifier.pinSet = true
            verifier.accepts = "1234"
            val viewModel = viewModel().also { it.awaitLoaded() }
            viewModel.onEvent(EraseEvent.ConfirmationTyped(WORD))
            viewModel.onEvent(EraseEvent.PinTyped("1234"))

            viewModel.onEvent(EraseEvent.Confirmed)
            viewModel.onEvent(EraseEvent.Confirmed)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("a second tap must not run a second erase", 1, repository.erases)
            assertTrue(viewModel.uiState.value.isErased)
            assertFalse(viewModel.uiState.value.isErasing)
        }

    @Test
    fun `the PIN is cleared from the state the moment it has been used`() =
        runTest(dispatcher) {
            // It is held only long enough to verify. Nothing logs state, but a PIN sitting in a
            // StateFlow outlives the screen, survives into a heap dump and is the sort of thing a
            // later `copy()` carries somewhere it should not go.
            verifier.pinSet = true
            verifier.accepts = "1234"
            val viewModel = viewModel().also { it.awaitLoaded() }
            viewModel.onEvent(EraseEvent.ConfirmationTyped(WORD))
            viewModel.onEvent(EraseEvent.PinTyped("1234"))

            viewModel.onEvent(EraseEvent.Confirmed)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("", viewModel.uiState.value.pinText)
        }

    @Test
    fun `a failed erase is reported and the screen does not claim to be finished`() =
        runTest(dispatcher) {
            // The one failure that must never be smoothed over: the user has been told their data
            // is about to become unrecoverable, and it has not.
            verifier.pinSet = false
            repository.failWith = AppError.Crypto("erase.key_survived")
            val viewModel = viewModel().also { it.awaitLoaded() }
            viewModel.onEvent(EraseEvent.ConfirmationTyped(WORD))

            viewModel.onEvent(EraseEvent.Confirmed)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("crypto", viewModel.uiState.value.errorCode)
            assertFalse(viewModel.uiState.value.isErased)
        }

    @Test
    fun `an unreadable credential is treated as a PIN being required`() =
        runTest(dispatcher) {
            // "I could not tell whether a PIN is set" must resolve to demanding one. The opposite
            // default would turn a storage error into a way past the authentication gate.
            verifier.failIsPinSet = AppError.Storage("IOException")
            val viewModel = viewModel().also { it.awaitLoaded() }

            assertTrue(viewModel.uiState.value.isPinRequired)
        }

    @Test
    fun `before the word has loaded, an untouched screen cannot erase`() =
        runTest(dispatcher) {
            // Found by a mutation that removed the blank-word check and survived every other test
            // in this file, because they all load the word first. They should not all do that: both
            // strings start empty, and `"" == ""` is a match — so a screen that had not yet read
            // its own resources would have let an erase through on a first frame with no typing at
            // all. The gate has to refuse a word it does not have.
            verifier.pinSet = false
            val viewModel = EraseViewModel(repository, verifier)
            dispatcher.scheduler.advanceUntilIdle()

            assertFalse("an empty confirmation must never match", viewModel.uiState.value.canErase)

            viewModel.onEvent(EraseEvent.Confirmed)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(0, repository.erases)
        }

    @Test
    fun `dismissing the error clears it`() =
        runTest(dispatcher) {
            verifier.failIsPinSet = AppError.Storage("IOException")
            val viewModel = viewModel().also { it.awaitLoaded() }

            viewModel.onEvent(EraseEvent.DismissError)

            assertEquals(null, viewModel.uiState.value.errorCode)
        }

    private fun viewModel(): EraseViewModel = EraseViewModel(repository, verifier).also { it.onWordLoaded(WORD) }

    /** Result: the view model after its first load. Input: the receiver. Output: none. */
    private fun EraseViewModel.awaitLoaded() = dispatcher.scheduler.advanceUntilIdle()

    private companion object {
        /** Stands in for the localised word from `strings.xml`; the gate never hardcodes English. */
        const val WORD = "ERASE"
    }
}

/**
 * An erase repository that counts, and can fail (issue 11.4).
 * Why:    "once, and only on the happy path" is the whole assertion, and it is invisible in a
 *         result. Counting is the only way to see a double tap or a bypassed gate.
 * Result: `erases` is how many times the irreversible operation ran.
 * Input:  none. Output: the fake.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
private class RecordingEraseRepository : EraseRepository {
    var erases = 0
    var failWith: AppError? = null

    override suspend fun eraseEverything(): Result<Unit, AppError> {
        erases++
        return failWith?.let { Err(it) } ?: Ok(Unit)
    }
}

/**
 * A PIN verifier that accepts one PIN (issue 11.4).
 * Input:  none — set [pinSet], [accepts] and [failIsPinSet] per test. Output: the stub.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
private class StubPinVerifier : PinVerifier {
    var pinSet = false
    var accepts: String? = null
    var failIsPinSet: AppError? = null

    override fun isPinSet(): Result<Boolean, AppError> = failIsPinSet?.let { Err(it) } ?: Ok(pinSet)

    override fun setPin(pin: String): Result<Unit, AppError> = Ok(Unit)

    override fun verify(pin: String): Result<Boolean, AppError> = Ok(pin == accepts)

    override fun clearPin(): Result<Unit, AppError> = Ok(Unit)
}
