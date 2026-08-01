package com.aicfo.feature.onboarding

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicfo.core.common.AppError
import com.aicfo.core.common.DefaultDispatcherProvider
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.getOrNull
import com.aicfo.core.crypto.KeystoreMacFactory
import com.aicfo.core.datastore.CfoDataStoreFactory
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.DEFAULT_AUTO_LOCK_SECONDS
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import com.aicfo.data.repository.DemoModeRepository
import com.aicfo.data.repository.ProfileSeed
import com.aicfo.data.repository.QuickSetupRepository
import com.aicfo.domain.engines.quicksetup.BudgetEnvelope
import com.aicfo.domain.engines.quicksetup.QuickSetupEngineFactory
import com.aicfo.domain.engines.quicksetup.QuickSetupPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId

/**
 * The onboarding flow against real storage, on a real device (issue 2.1; §21.5, P-01, P-04).
 *
 * Why:  the JVM tests use fake stores, so they prove the ViewModel *asks* for the right writes —
 *       not that the writes land. This is the other half: real Proto DataStore, real file, real
 *       device, one assertion that survives everything in between. It also exercises the path the
 *       Windows rename bug broke once before (handoff trap #4), which no JVM test on this machine
 *       can honestly cover.
 * What: drives all five steps, then reads the profile, the consent and the app lock back out
 *       of storage.
 * Result: proof that finishing onboarding actually persists, rather than appearing to.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *            2026-07-28 — Issue 2.4: **repaired.** This file had not compiled since issue 2.3 —
 *            `OnboardingViewModel`'s constructor changed under it and nothing noticed, because
 *            `androidTest` is only compiled when a device is attached and this project has never had
 *            one. A gate that cannot compile is not a gate; noted in the tracker as a real finding.
 *
 * **No Hilt here on purpose.** Constructor injection (ARC-003) means the ViewModel can simply be
 * constructed, so this needs no test runner, no `@HiltAndroidTest`, and no test module — which is
 * the practical payoff of the rule.
 *
 * There is one test method deliberately: DataStore refuses two active instances over the same file,
 * so a second method creating its own store in the same process would fail for a reason that has
 * nothing to do with onboarding.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Input:  the flow, driven through all five steps on a device, with the app lock enabled.
     * Output: asserts the profile, the quick-setup seed, the SMS consent and the SEC-002 app lock
     *         are all readable from real storage afterwards — and that the completion timestamp came
     *         from the injected clock (TIM-001), which is what makes it assertable at all.
     *
     * The PIN is set through the **real** `TinkPinVerifier` over the real Android Keystore, so this
     * is the only place the Keystore path is exercised at all: the JVM tests use a fake `Mac`
     * because a TEE does not exist off-device. That makes verifying the PIN back out the single
     * most valuable assertion in this file.
     */
    @Test
    fun completingOnboardingPersistsTheProfileAndTheConsent() {
        // The factory owns this name; deleting it here keeps the run repeatable on a device that
        // has already been onboarded by a previous run or by hand.
        File(context.filesDir, SETTINGS_FILE_NAME).delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clock = FakeClock(initialMillis = FIXED_MILLIS, initialZone = ZoneId.of("Asia/Kolkata"))
        val stores = CfoDataStoreFactory.create(context, clock, DefaultDispatcherProvider(), scope)

        try {
            val pinVerifier = KeystoreMacFactory.createVerifier(context)
            pinVerifier.clearPin()
            val viewModel =
                OnboardingViewModel(
                    OnboardingWriter(stores.settings, stores.consents),
                    AppLockSetup(pinVerifier, stores.appLock),
                    QuickSetupCoordinator(QuickSetupEngineFactory.create(), NoOpQuickSetupRepository(), clock),
                    NoOpDemoModeRepository(),
                    clock,
                    SavedStateHandle(),
                )
            compose.setContent {
                CfoTheme { OnboardingScreen(onFinished = {}, onDemoStarted = {}, viewModel = viewModel) }
            }

            clickNext()
            node(string(R.string.onboarding_consent_toggle)).performClick()
            clickNext()
            node("Asia/Dubai").performClick()
            clickNext()
            // SECURITY (issue 2.2, SEC-002): enable the lock and set a PIN.
            node(string(R.string.onboarding_security_toggle)).performClick()
            node(string(R.string.onboarding_security_pin_label)).performTextInput("135790")
            node(string(R.string.onboarding_security_pin_confirm_label)).performTextInput("135790")
            clickNext()
            node(string(R.string.onboarding_quick_setup_income_label)).performTextInput("85000")
            node(string(R.string.onboarding_finish)).performClick()
            // waitForIdle() is not enough here, and the difference is the whole point of running
            // this on a device: the write runs on a real I/O dispatcher, so the composition can be
            // idle while the file has not been touched yet. `isComplete` is set from the write's
            // own result, so waiting on it waits for the write. The JVM twin never sees this — its
            // unconfined test dispatcher makes the write look instantaneous.
            compose.waitUntil(WRITE_TIMEOUT_MILLIS) { viewModel.uiState.value.isComplete }

            runBlocking {
                val settings = stores.settings.observe().first().getOrNull()!!
                assertTrue("onboarding must be marked complete on disk", settings.isOnboarded)
                assertEquals(FIXED_MILLIS, settings.onboardingCompletedAtUtcMillis)
                assertEquals("Asia/Dubai", settings.profileTimeZoneId)
                assertEquals(DEFAULT_CURRENCY_CODE, settings.currencyCode)
                assertEquals(Money(85_000_00), settings.quickSetup.monthlyIncome)

                val consent = stores.consents.observe(ConsentFeature.SMS_PARSING).first().getOrNull()!!
                assertTrue("the opt-in must survive the write", consent.granted)
                assertEquals(FIXED_MILLIS, consent.grantedAtUtcMillis)

                val lock = stores.appLock.observe().first().getOrNull()!!
                assertTrue("the app lock must be on after the security step", lock.enabled)
                assertEquals(DEFAULT_AUTO_LOCK_SECONDS, lock.autoLockTimeoutSeconds)

                // The real Keystore round trip: the PIN verifies, and a different one does not.
                assertTrue("a PIN must have been stored", pinVerifier.isPinSet().getOrNull() == true)
                assertTrue("the chosen PIN must verify", pinVerifier.verify("135790").getOrNull() == true)
                assertTrue("any other PIN must be refused", pinVerifier.verify("000000").getOrNull() == false)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun string(id: Int): String = context.getString(id)

    /** Brings a node into view first — the profile step is taller than a phone. */
    private fun node(label: String) = compose.onNodeWithText(label).performScrollTo()

    private fun clickNext() = node(string(R.string.onboarding_next)).performClick()

    private companion object {
        /** Owned by `CfoDataStoreFactory`; repeated here only so the test can start from nothing. */
        const val SETTINGS_FILE_NAME = "cfo_settings.pb"

        /** An arbitrary fixed instant — the point is that it comes from the injected clock. */
        const val FIXED_MILLIS = 1_800_000_000_000L

        /** Generous: a cold DataStore write on a software-rendered emulator is not fast. */
        const val WRITE_TIMEOUT_MILLIS = 10_000L
    }
}

/**
 * A [QuickSetupRepository] that accepts everything and stores nothing.
 *
 * Why:  what this file exists to prove is that **Proto DataStore and the Keystore** hold what
 *       onboarding wrote — the assertions at the bottom are all about those two. The Room half is
 *       proven, against a real SQL engine, by `QuickSetupRepositoryTest`. Opening the encrypted
 *       database here would drag SQLCipher, the Keystore-wrapped passphrase and the session gate
 *       into a test about a settings file, and a failure in any of them would look like an
 *       onboarding bug.
 * Result: a double that lets the flow finish. Input: none. Output: the double.
 * Changelog: 2026-07-28 — Created for issue 2.4, repairing a call this file could not compile.
 */
private class NoOpQuickSetupRepository : QuickSetupRepository {
    override suspend fun applySeeds(
        plan: QuickSetupPlan,
        profile: ProfileSeed,
    ): Result<Unit, AppError> = Ok(Unit)

    override fun observeLatestEnvelopes(): Flow<List<BudgetEnvelope>> = flowOf(emptyList())

    override fun observeLatestEnvelopes(profileId: String): Flow<List<BudgetEnvelope>> = flowOf(emptyList())
}

/**
 * A [DemoModeRepository] the flow never reaches.
 * Why:    this test drives the *real* setup path to the end, so it never taps the demo. The double
 *         exists only to satisfy the constructor; the demo entry is asserted by the JVM twin
 *         (`OnboardingFlowTest`) and its persistence by `DemoModeRepositoryTest`.
 * Result: a double. Input: none. Output: the double.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 */
private class NoOpDemoModeRepository : DemoModeRepository {
    override val isActive: Flow<Boolean> = flowOf(false)

    override val activeProfileId: Flow<String> = flowOf(QuickSetupRepository.DEFAULT_PROFILE_ID)

    override suspend fun enter(): Result<Unit, AppError> = Ok(Unit)

    override suspend fun exit(): Result<Unit, AppError> = Ok(Unit)
}
