package com.aicfo.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.aicfo.core.common.AppError
import com.aicfo.core.common.FakeClock
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.model.Money
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
import java.time.ZoneId

/**
 * Tests for [OnboardingViewModel] (issue 2.1; FR-ONB-001/002/003, P-01, ARC-004).
 *
 * Why:  this is the first ViewModel in the app that writes, and three of its decisions are the kind
 *       that look right and are not: a consent must not be recorded when the user said no, a
 *       skipped amount must not be stored as ₹0, and a **failed** save must not let the flow
 *       complete. Each is asserted here rather than trusted, because none of them is visible on
 *       screen when it goes wrong.
 * What: navigation between steps, the captured answers, both save paths, and process-death restore.
 * Result: every state the flow can reach, including the failure, is proven.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val settings = FakeSettingsStore()
    private val consents = FakeConsentStore()
    private val appLock = FakeAppLockStore()
    private val pinVerifier = FakePinVerifier()
    private val clock = FakeClock(initialZone = ZoneId.of("Asia/Kolkata"))
    private var savedState = SavedStateHandle()

    /** Input: none. Output: pins `viewModelScope` to a test dispatcher so saves run inline. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: releases the main dispatcher between tests. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = OnboardingViewModel(settings, consents, appLock, pinVerifier, clock, savedState)

    /** Advances the flow to its last step. Derived from the enum, so a new step needs no edit. */
    private fun OnboardingViewModel.goToLastStep() {
        repeat(OnboardingStep.entries.lastIndex) { onEvent(OnboardingEvent.Next) }
    }

    /**
     * Advances to a named step.
     * Why:    issue 2.2's tests care about the SECURITY step specifically, and counting Next presses
     *         at each call site would break every one of them the next time a step is inserted —
     *         which ADR-0002 says will happen again in issue 2.5.
     * Result: the flow sits on [target]. Input: [target]. Output: none.
     * Changelog: 2026-07-26 — Created for issue 2.2.
     */
    private fun OnboardingViewModel.goTo(target: OnboardingStep) {
        repeat(target.ordinal) { onEvent(OnboardingEvent.Next) }
    }

    /**
     * Input:  a fresh ViewModel.
     * Output: asserts the flow opens on the welcome step with the SMS consent **off**. A consent
     *         that defaulted on would be granted by a user who simply pressed Next — which is the
     *         precise opposite of the explicit opt-in P-01 requires.
     */
    @Test
    fun `opens on the welcome step with consent off`() {
        val state = viewModel().uiState.value
        assertEquals(OnboardingStep.WELCOME, state.step)
        assertFalse("absence is never consent", state.smsConsentGranted)
        assertFalse(state.canGoBack)
        assertEquals(1, state.stepNumber)
        // Five since issue 2.2 inserted SECURITY, exactly where ADR-0002 said it would go. The
        // count is derived from the enum, so this asserts the indicator can never disagree with it.
        assertEquals(OnboardingStep.entries.size, state.stepCount)
        assertEquals(5, state.stepCount)
    }

    /**
     * Input:  a fresh ViewModel on a device in Asia/Kolkata.
     * Output: asserts the time zone is pre-filled from the **injected** clock, not read from the
     *         platform (TIM-001), and that the device's zone heads the offered list.
     */
    @Test
    fun `pre-fills the time zone from the injected clock`() {
        clock.setZone(ZoneId.of("Europe/London"))
        val state = viewModel().uiState.value
        assertEquals("Europe/London", state.timeZoneId)
        assertEquals("Europe/London", state.timeZoneOptions.first())
        assertEquals(state.timeZoneOptions.distinct(), state.timeZoneOptions)
    }

    /**
     * Input:  Next to the end, then Back past the beginning.
     * Output: asserts the step is clamped at both ends — a Back on the first step must be a no-op,
     *         not an index the enum has no member for.
     */
    @Test
    fun `steps forward and back, clamped at both ends`() {
        val viewModel = viewModel()
        viewModel.goToLastStep()
        assertEquals(OnboardingStep.QUICK_SETUP, viewModel.uiState.value.step)

        repeat(OnboardingStep.entries.size + 2) { viewModel.onEvent(OnboardingEvent.Back) }
        assertEquals(OnboardingStep.WELCOME, viewModel.uiState.value.step)
    }

    /**
     * Input:  every answer filled in, then Finish.
     * Output: asserts the profile is written once, with the amounts converted to exact paise
     *         (MNY-001), the name trimmed, and the consent granted for SMS parsing only.
     */
    @Test
    fun `finishing writes the whole profile once`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(OnboardingEvent.SmsConsentChanged(true))
            viewModel.onEvent(OnboardingEvent.DisplayNameChanged("  Harish  "))
            viewModel.onEvent(OnboardingEvent.TimeZoneChanged("Asia/Dubai"))
            viewModel.onEvent(OnboardingEvent.MonthlyIncomeChanged("85,000"))
            viewModel.onEvent(OnboardingEvent.RentOrEmiChanged("22000.50"))
            viewModel.goToLastStep()
            viewModel.onEvent(OnboardingEvent.Next)

            val saved = settings.savedProfile!!
            assertEquals(1, settings.completeCallCount)
            assertEquals("Asia/Dubai", saved.timeZoneId)
            assertEquals(DEFAULT_CURRENCY_CODE, saved.currencyCode)
            assertEquals("Harish", saved.displayName)
            assertEquals(Money(85_000_00), saved.quickSetup.monthlyIncome)
            assertEquals(Money(22_000_50), saved.quickSetup.rentOrEmi)
            assertNull("an unanswered seed is not ₹0", saved.quickSetup.typicalSavings)
            assertEquals(listOf(ConsentFeature.SMS_PARSING), consents.granted)
            assertTrue(viewModel.uiState.value.isComplete)
        }

    /**
     * Input:  the flow finished without opting in to SMS parsing.
     * Output: asserts **nothing** is written to the consent ledger. Recording a revocation for a
     *         consent that was never granted would put a withdrawal date on a decision the user
     *         never made, in a ledger whose whole purpose is answering "since when?" truthfully.
     */
    @Test
    fun `declining the consent writes nothing to the ledger`() =
        runTest {
            val viewModel = viewModel()
            viewModel.goToLastStep()
            viewModel.onEvent(OnboardingEvent.Next)

            assertTrue("no grant", consents.granted.isEmpty())
            assertTrue("and no false revocation either", consents.revoked.isEmpty())
            assertTrue(viewModel.uiState.value.isComplete)
        }

    /**
     * Input:  amounts typed, then Skip.
     * Output: asserts skipping discards the typed seeds rather than saving them — Skip has to mean
     *         skip, or a user who changed their mind is recorded as having answered.
     */
    @Test
    fun `skipping quick setup writes no seeds`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(OnboardingEvent.MonthlyIncomeChanged("85000"))
            viewModel.goToLastStep()
            viewModel.onEvent(OnboardingEvent.SkipQuickSetup)

            val saved = settings.savedProfile!!
            assertNull(saved.quickSetup.monthlyIncome)
            assertNull(saved.quickSetup.rentOrEmi)
            assertNull(saved.quickSetup.typicalSavings)
            assertTrue(viewModel.uiState.value.isComplete)
        }

    /**
     * Input:  unparseable text in an amount field.
     * Output: asserts it is dropped rather than becoming an amount. A parser that guessed here
     *         would seed a budget from something the user never said.
     */
    @Test
    fun `unparseable amounts are left unanswered`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(OnboardingEvent.MonthlyIncomeChanged("about 85k"))
            viewModel.goToLastStep()
            viewModel.onEvent(OnboardingEvent.Next)

            assertNull(settings.savedProfile!!.quickSetup.monthlyIncome)
        }

    /**
     * Input:  a settings store whose write fails.
     * Output: asserts the flow reports the error and **stays incomplete**. The screen navigates on
     *         `isComplete`, so completing here would drop the user on a dashboard with no profile
     *         and no way back into onboarding — the failure would look like data loss.
     */
    @Test
    fun `a failed save surfaces the error and does not complete`() =
        runTest {
            settings.failWith = AppError.Storage("IOException")
            val viewModel = viewModel()
            viewModel.goToLastStep()
            viewModel.onEvent(OnboardingEvent.Next)

            val state = viewModel.uiState.value
            assertFalse("must not navigate away from a save that did not happen", state.isComplete)
            assertEquals(AppError.Storage("IOException").code, state.errorCode)
            assertFalse(state.isSaving)
            assertNull(settings.savedProfile)
        }

    /**
     * Input:  a failed consent write.
     * Output: asserts the profile is never written. The consent goes first precisely so that a
     *         failure leaves nothing marked complete and the retry starts from a clean state.
     */
    @Test
    fun `a failed consent write stops the profile being saved`() =
        runTest {
            consents.failWith = AppError.Storage("IOException")
            val viewModel = viewModel()
            viewModel.onEvent(OnboardingEvent.SmsConsentChanged(true))
            viewModel.goToLastStep()
            viewModel.onEvent(OnboardingEvent.Next)

            assertEquals(0, settings.completeCallCount)
            assertFalse(viewModel.uiState.value.isComplete)
        }

    /**
     * Input:  a user who opted in, hit a failed save, then changed their mind and retried.
     * Output: asserts the earlier grant is withdrawn. This is the one case where a revocation is
     *         honest — the consent really was granted — and it is why the grant is tracked rather
     *         than revoked unconditionally.
     */
    @Test
    fun `changing your mind after a failed save withdraws the earlier grant`() =
        runTest {
            settings.failWith = AppError.Storage("IOException")
            val viewModel = viewModel()
            viewModel.onEvent(OnboardingEvent.SmsConsentChanged(true))
            viewModel.goToLastStep()
            viewModel.onEvent(OnboardingEvent.Next)
            assertEquals(listOf(ConsentFeature.SMS_PARSING), consents.granted)

            settings.failWith = null
            viewModel.onEvent(OnboardingEvent.SmsConsentChanged(false))
            viewModel.onEvent(OnboardingEvent.Next)

            assertEquals(listOf(ConsentFeature.SMS_PARSING), consents.revoked)
            assertTrue(viewModel.uiState.value.isComplete)
        }

    /**
     * Input:  answers given, then the ViewModel rebuilt from the same [SavedStateHandle] — what the
     *         system does when it kills a backgrounded app.
     * Output: asserts the step and every answer survive. Nothing reaches disk until Finish, so this
     *         handle is the *only* thing standing between a process death and the user starting
     *         over.
     */
    @Test
    fun `every answer survives process death`() {
        val first = viewModel()
        first.onEvent(OnboardingEvent.SmsConsentChanged(true))
        first.onEvent(OnboardingEvent.DisplayNameChanged("Harish"))
        first.onEvent(OnboardingEvent.TimeZoneChanged("Asia/Dubai"))
        first.onEvent(OnboardingEvent.Next)
        first.onEvent(OnboardingEvent.MonthlyIncomeChanged("85000"))

        val restored = viewModel().uiState.value
        assertEquals(OnboardingStep.CONSENT, restored.step)
        assertTrue(restored.smsConsentGranted)
        assertEquals("Harish", restored.displayName)
        assertEquals("Asia/Dubai", restored.timeZoneId)
        assertEquals("85000", restored.monthlyIncomeText)
    }

    /**
     * Input:  a state saved as complete, then rebuilt.
     * Output: asserts the flow does **not** come back complete. A save that was in flight when the
     *         process died did not finish, and restoring "complete" would navigate past a profile
     *         that was never written.
     */
    @Test
    fun `a save interrupted by process death is not restored as complete`() =
        runTest {
            val first = viewModel()
            first.goToLastStep()
            first.onEvent(OnboardingEvent.Next)
            assertTrue(first.uiState.value.isComplete)

            assertFalse(viewModel().uiState.value.isComplete)
        }

    /**
     * Input:  a collector watching a save that fails, then the dismissal.
     * Output: asserts the whole sequence, including the intermediate saving state the DoD asks for.
     */
    @Test
    fun `emits saving, then the error, then the dismissal`() =
        runTest {
            settings.failWith = AppError.Storage("IOException")
            val viewModel = viewModel()
            viewModel.goToLastStep()

            viewModel.uiState.test {
                assertFalse(awaitItem().isSaving)

                viewModel.onEvent(OnboardingEvent.Next)
                assertTrue("the DoD asks for the loading state to be assertable", awaitItem().isSaving)

                val failed = awaitItem()
                assertFalse(failed.isSaving)
                assertEquals(AppError.Storage("IOException").code, failed.errorCode)

                viewModel.onEvent(OnboardingEvent.DismissError)
                assertNull(awaitItem().errorCode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- the SECURITY step (issue 2.2, SEC-002, FR-ONB-001 step 3) -------------------------

    /**
     * Input:  a flow finished without touching the security step.
     * Output: asserts **no** PIN is stored and the lock stays off. ADR-0002 requires the step to be
     *         skippable, and a lock switched on by a user who simply pressed Next would be the
     *         security equivalent of the consent bug P-01 exists to prevent.
     */
    @Test
    fun `skipping the security step sets no PIN and leaves the lock off`() {
        val viewModel = viewModel()
        viewModel.goToLastStep()
        viewModel.onEvent(OnboardingEvent.Next)

        assertNull("a declined step must write no credential", pinVerifier.storedPin)
        assertFalse(appLock.enabled)
        assertTrue(settings.savedProfile != null)
    }

    /**
     * Input:  the lock enabled with a matching PIN, then the flow finished.
     * Output: asserts the PIN is stored and the lock is on — the step's whole purpose.
     */
    @Test
    fun `enabling the lock stores the PIN and turns the lock on`() {
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.SECURITY)
        viewModel.onEvent(OnboardingEvent.AppLockToggled(true))
        viewModel.onEvent(OnboardingEvent.PinChanged("135790"))
        viewModel.onEvent(OnboardingEvent.PinConfirmChanged("135790"))
        viewModel.onEvent(OnboardingEvent.Next)
        viewModel.onEvent(OnboardingEvent.Next)

        assertEquals("135790", pinVerifier.storedPin)
        assertTrue(appLock.enabled)
    }

    /**
     * Input:  the lock enabled with a PIN that is too short.
     * Output: asserts the flow refuses to advance and says why. Letting it through would enable a
     *         lock with a credential the verifier rejects — an app that cannot be opened at all.
     */
    @Test
    fun `a too-short PIN blocks the step`() {
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.SECURITY)
        viewModel.onEvent(OnboardingEvent.AppLockToggled(true))
        viewModel.onEvent(OnboardingEvent.PinChanged("12"))
        viewModel.onEvent(OnboardingEvent.PinConfirmChanged("12"))
        viewModel.onEvent(OnboardingEvent.Next)

        val state = viewModel.uiState.value
        assertEquals(OnboardingStep.SECURITY, state.step)
        assertEquals(ERROR_PIN_TOO_SHORT, state.errorCode)
        assertNull(pinVerifier.storedPin)
    }

    /**
     * Input:  two PINs that differ.
     * Output: asserts the flow refuses and names the mismatch. A mistyped PIN set here is not
     *         recoverable — the next thing that asks for it is the lock screen.
     */
    @Test
    fun `mismatched PINs block the step`() {
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.SECURITY)
        viewModel.onEvent(OnboardingEvent.AppLockToggled(true))
        viewModel.onEvent(OnboardingEvent.PinChanged("1234"))
        viewModel.onEvent(OnboardingEvent.PinConfirmChanged("4321"))
        viewModel.onEvent(OnboardingEvent.Next)

        assertEquals(OnboardingStep.SECURITY, viewModel.uiState.value.step)
        assertEquals(ERROR_PIN_MISMATCH, viewModel.uiState.value.errorCode)
        assertNull(pinVerifier.storedPin)
    }

    /**
     * Input:  a PIN typed, then the lock toggled back off.
     * Output: asserts both fields are cleared. A secret with no remaining purpose should not sit in
     *         memory waiting for a later refactor to write it.
     */
    @Test
    fun `turning the lock back off clears the typed PIN`() {
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.SECURITY)
        viewModel.onEvent(OnboardingEvent.AppLockToggled(true))
        viewModel.onEvent(OnboardingEvent.PinChanged("1234"))
        viewModel.onEvent(OnboardingEvent.PinConfirmChanged("1234"))

        viewModel.onEvent(OnboardingEvent.AppLockToggled(false))

        val state = viewModel.uiState.value
        assertEquals("", state.pinText)
        assertEquals("", state.pinConfirmText)
        assertTrue("with the lock off the step must not block", state.canAdvance)
    }

    /**
     * Input:  a PIN typed, then the state persisted and restored as process death would.
     * Output: asserts the PIN did **not** survive. `SavedStateHandle` is written to disk by the
     *         platform, so a PIN in it would be a plaintext credential sitting in a bundle —
     *         defeating the point of verifying it against a Keystore-bound MAC. The toggle does
     *         survive, so the user comes back to the step they were on.
     */
    @Test
    fun `the typed PIN is never written to saved state`() {
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.SECURITY)
        viewModel.onEvent(OnboardingEvent.AppLockToggled(true))
        viewModel.onEvent(OnboardingEvent.PinChanged("135790"))
        viewModel.onEvent(OnboardingEvent.PinConfirmChanged("135790"))

        assertTrue(
            "no saved-state value may contain the PIN",
            savedState.keys().none { key -> savedState.get<Any?>(key)?.toString() == "135790" },
        )

        val restored = viewModel().uiState.value
        assertEquals("", restored.pinText)
        assertTrue("the toggle itself is safe to restore", restored.appLockEnabled)
    }

    /**
     * Input:  a PIN write that fails, e.g. an unavailable Keystore.
     * Output: asserts onboarding does **not** complete and the lock is left **off**. Enabling a lock
     *         whose PIN never stored would leave the user staring at a prompt nothing opens.
     */
    @Test
    fun `a failed PIN write leaves the lock off and onboarding incomplete`() {
        pinVerifier.failWith = AppError.Crypto("KeyStoreException")
        val viewModel = viewModel()
        viewModel.goTo(OnboardingStep.SECURITY)
        viewModel.onEvent(OnboardingEvent.AppLockToggled(true))
        viewModel.onEvent(OnboardingEvent.PinChanged("1234"))
        viewModel.onEvent(OnboardingEvent.PinConfirmChanged("1234"))
        viewModel.onEvent(OnboardingEvent.Next)
        viewModel.onEvent(OnboardingEvent.Next)

        assertFalse(appLock.enabled)
        assertFalse(viewModel.uiState.value.isComplete)
        assertNull("nothing may be marked onboarded after a failed step", settings.savedProfile)
        assertEquals(AppError.Crypto("KeyStoreException").code, viewModel.uiState.value.errorCode)
    }
}
