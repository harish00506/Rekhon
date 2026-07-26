package com.aicfo.feature.onboarding

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.datastore.OnboardingProfile
import com.aicfo.core.datastore.SettingsSnapshot
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.core.datastore.ThemeSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
