package com.aicfo.feature.onboarding

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.crypto.PinVerifier
import com.aicfo.core.datastore.AppLockSettings
import com.aicfo.core.datastore.AppLockStore
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.datastore.OnboardingProfile
import com.aicfo.core.datastore.SettingsSnapshot
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.core.datastore.ThemeSetting
import com.aicfo.data.repository.DemoModeRepository
import com.aicfo.data.repository.ProfileSeed
import com.aicfo.data.repository.QuickSetupRepository
import com.aicfo.domain.engines.quicksetup.BudgetEnvelope
import com.aicfo.domain.engines.quicksetup.QuickSetupPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/*
 * Hand-written store doubles for the onboarding tests (issue 2.1).
 *
 * Why:  the ViewModel's whole job is deciding *what* gets written and *whether* the flow completes,
 *       so the tests have to see the exact calls. A mocking library would answer "was it called?",
 *       but these record the argument itself, which is what the assertions are actually about — and
 *       they can be made to fail on demand, which is the case the real store makes hard to
 *       reproduce.
 * What: recording fakes for SettingsStore and ConsentStore, each with an injectable failure.
 * Result: every branch of the save path is reachable from a unit test.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */

/**
 * A [SettingsStore] that records what onboarding wrote.
 * Why:    asserts the profile itself, not just that a write happened — the interesting bugs here
 *         are a currency landing in the time-zone field or a skipped seed arriving as ₹0.
 * Result: the recorded [OnboardingProfile] and the number of write attempts.
 * Input:  [failWith] — when non-null, every write returns `Err` with it. Output: a fake store.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
internal class FakeSettingsStore(
    var failWith: AppError? = null,
) : SettingsStore {
    private val state = MutableStateFlow(SettingsSnapshot())

    /** The last profile handed to [completeOnboarding], or `null` if it was never called. */
    var savedProfile: OnboardingProfile? = null
        private set

    /** How many times a completion was attempted — a retry must not write twice. */
    var completeCallCount: Int = 0
        private set

    override fun observe(): Flow<Result<SettingsSnapshot, AppError>> = state.map { Ok(it) }

    override suspend fun setProfileTimeZone(zoneId: String): Result<Unit, AppError> = write { }

    override suspend fun setCurrencyCode(currencyCode: String): Result<Unit, AppError> = write { }

    override suspend fun setPrivacyBlurEnabled(enabled: Boolean): Result<Unit, AppError> = write { }

    override suspend fun setTheme(theme: ThemeSetting): Result<Unit, AppError> = write { }

    /**
     * Records a demo-flag write (issue 2.4).
     * Why:    the flag is set by `DemoModeRepository`, not by this ViewModel — so what these tests
     *         assert about it is that onboarding **never touches it**, and a recording override is
     *         how that absence becomes visible.
     * Result: `Ok(Unit)`, or [failWith]. Input: [active]. Output: `Result<Unit, AppError>`.
     */
    override suspend fun setDemoModeActive(active: Boolean): Result<Unit, AppError> = write { }

    override suspend fun completeOnboarding(profile: OnboardingProfile): Result<Unit, AppError> {
        completeCallCount++
        return write { savedProfile = profile }
    }

    /**
     * Applies a write unless this fake is set to fail.
     * Why:    a failing write must leave **nothing** recorded, the same way the real store's atomic
     *         `updateData` does; recording first and failing after would let a test pass over a
     *         half-applied change the real store could never produce.
     * Result: `Ok(Unit)`, or `Err(failWith)` with the block skipped.
     * Input:  [block] — what a successful write records. Output: `Result<Unit, AppError>`.
     */
    private fun write(block: () -> Unit): Result<Unit, AppError> {
        val failure = failWith ?: return Ok(Unit).also { block() }
        return Err(failure)
    }
}

/**
 * A [ConsentStore] that records the P-01 decisions it was asked to make.
 * Why:    "the user said no" must produce **no write at all** — a revocation record for a consent
 *         never granted is a false entry in an audit ledger. Only a recording fake can prove the
 *         absence of a call.
 * Result: the grant and revoke calls, in order.
 * Input:  [failWith] — when non-null, every write returns `Err`. Output: a fake store.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
internal class FakeConsentStore(
    var failWith: AppError? = null,
) : ConsentStore {
    /** Every feature granted, in call order. */
    val granted = mutableListOf<ConsentFeature>()

    /** Every feature revoked, in call order. */
    val revoked = mutableListOf<ConsentFeature>()

    override fun observe(feature: ConsentFeature): Flow<Result<ConsentState, AppError>> =
        MutableStateFlow(Ok(ConsentState.NOT_GRANTED))

    override fun observeAll(): Flow<Result<Map<ConsentFeature, ConsentState>, AppError>> =
        MutableStateFlow(Ok(ConsentFeature.entries.associateWith { ConsentState.NOT_GRANTED }))

    override suspend fun grant(feature: ConsentFeature): Result<Unit, AppError> {
        val failure = failWith ?: return Ok(Unit).also { granted += feature }
        return Err(failure)
    }

    override suspend fun revoke(feature: ConsentFeature): Result<Unit, AppError> {
        val failure = failWith ?: return Ok(Unit).also { revoked += feature }
        return Err(failure)
    }
}

/**
 * An [AppLockStore] that records whether onboarding switched the lock on (issue 2.2).
 * Why:    the security step's whole job is turning the lock on with a usable PIN, so the assertion
 *         is "was it enabled, and only after a PIN was set?" — which needs the call recorded, not
 *         merely counted.
 * Result: the recorded flag and an injectable failure.
 * Input:  [failWith] — when non-null, every write returns `Err` with it. Output: a fake store.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
internal class FakeAppLockStore(
    var failWith: AppError? = null,
) : AppLockStore {
    private val state = MutableStateFlow(AppLockSettings())

    /** Whether the lock ended up enabled. */
    val enabled: Boolean get() = state.value.enabled

    override fun observe(): Flow<Result<AppLockSettings, AppError>> = state.map { Ok(it) }

    override suspend fun setEnabled(enabled: Boolean): Result<Unit, AppError> =
        write { state.value = state.value.copy(enabled = enabled) }

    override suspend fun setBiometricEnabled(enabled: Boolean): Result<Unit, AppError> =
        write { state.value = state.value.copy(biometricEnabled = enabled) }

    override suspend fun setAutoLockTimeoutSeconds(seconds: Int): Result<Unit, AppError> =
        write { state.value = state.value.copy(autoLockTimeoutSeconds = seconds) }

    override suspend fun recordFailedUnlock(): Result<Unit, AppError> =
        write { state.value = state.value.copy(failedAttempts = state.value.failedAttempts + 1) }

    override suspend fun clearFailedUnlocks(): Result<Unit, AppError> =
        write { state.value = state.value.copy(failedAttempts = 0) }

    private fun write(apply: () -> Unit): Result<Unit, AppError> {
        failWith?.let { return Err(it) }
        apply()
        return Ok(Unit)
    }
}

/**
 * A [QuickSetupRepository] that records what onboarding asked it to persist (issue 2.3).
 * Why:    the two assertions that matter here are about **absence**: a skipped step must not call
 *         this at all, and a failed earlier write must stop before it. Neither can be shown by a
 *         fake that only counts successes, so the plan itself is captured.
 * Result: the recorded plan and profile, and the number of attempts.
 * Input:  [failWith] — when non-null, `applySeeds` returns `Err` with it. Output: a fake repository.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 */
internal class FakeQuickSetupRepository(
    var failWith: AppError? = null,
) : QuickSetupRepository {
    /** The last plan handed to [applySeeds], or `null` if it was never called. */
    var savedPlan: QuickSetupPlan? = null
        private set

    /** The last profile handed to [applySeeds], or `null` if it was never called. */
    var savedProfile: ProfileSeed? = null
        private set

    /** How many times a write was attempted — the skip path must leave this at zero. */
    var applyCallCount: Int = 0
        private set

    override suspend fun applySeeds(
        plan: QuickSetupPlan,
        profile: ProfileSeed,
    ): Result<Unit, AppError> {
        applyCallCount++
        failWith?.let { return Err(it) }
        savedPlan = plan
        savedProfile = profile
        return Ok(Unit)
    }

    override fun observeLatestEnvelopes(): Flow<List<BudgetEnvelope>> = flowOf(savedPlan?.envelopes.orEmpty())

    /**
     * The profile-scoped read (issue 2.4).
     * Why:    answers identically to the no-argument form, because onboarding never reads envelopes
     *         at all — this exists only to satisfy the interface.
     * Result: the persisted envelopes. Input: [profileId] — ignored. Output: the flow.
     */
    override fun observeLatestEnvelopes(profileId: String): Flow<List<BudgetEnvelope>> =
        flowOf(savedPlan?.envelopes.orEmpty())
}

/**
 * A [DemoModeRepository] that records whether onboarding entered the demo (issue 2.4; FR-ONB-004).
 *
 * Why:    the requirement is about what does **not** happen — the demo must load "without creating
 *         a profile", so the assertions that matter are that `enter()` was called *and* that the
 *         settings and quick-setup fakes beside it recorded nothing. That is a claim about absence,
 *         which only a recording fake can make visible.
 * Result: the number of entries and exits, plus an injectable failure.
 * Input:  [failWith] — when non-null, `enter` returns `Err` with it. Output: a fake repository.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 */
internal class FakeDemoModeRepository(
    var failWith: AppError? = null,
) : DemoModeRepository {
    private val active = MutableStateFlow(false)

    /** How many times the demo was entered — a failed entry must not be retried silently. */
    var enterCallCount: Int = 0
        private set

    override val isActive: Flow<Boolean> = active

    override val activeProfileId: Flow<String> =
        active.map { on ->
            if (on) DemoModeRepository.DEMO_PROFILE_ID else QuickSetupRepository.DEFAULT_PROFILE_ID
        }

    override suspend fun enter(): Result<Unit, AppError> {
        enterCallCount++
        failWith?.let { return Err(it) }
        active.value = true
        return Ok(Unit)
    }

    override suspend fun exit(): Result<Unit, AppError> {
        active.value = false
        return Ok(Unit)
    }
}

/**
 * A [PinVerifier] that records the PIN it was given (issue 2.2).
 * Why:    the security step must store what the user typed and must **not** store anything when the
 *         step was declined — both are assertions about the argument, so it has to be captured.
 * Result: the recorded PIN and an injectable failure.
 * Input:  [failWith] — when non-null, `setPin` returns `Err` with it. Output: a fake verifier.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
internal class FakePinVerifier(
    var failWith: AppError? = null,
) : PinVerifier {
    /** The PIN that was set, or `null` if `setPin` was never called. */
    var storedPin: String? = null
        private set

    override fun isPinSet(): Result<Boolean, AppError> = Ok(storedPin != null)

    override fun setPin(pin: String): Result<Unit, AppError> {
        failWith?.let { return Err(it) }
        storedPin = pin
        return Ok(Unit)
    }

    override fun verify(pin: String): Result<Boolean, AppError> = Ok(pin == storedPin)

    override fun clearPin(): Result<Unit, AppError> = Ok(Unit).also { storedPin = null }
}
