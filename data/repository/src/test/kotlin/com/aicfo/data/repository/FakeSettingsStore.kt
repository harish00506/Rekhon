package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.datastore.OnboardingProfile
import com.aicfo.core.datastore.SettingsSnapshot
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.core.datastore.ThemeSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * An in-memory [SettingsStore] for the repository tests (issue 2.4).
 *
 * Why:  `DemoModeRepositoryTest` is about what happens in the **database** when the demo is entered
 *       and left; the real Proto DataStore would add a file, a scope and a serializer to every test
 *       without changing a single assertion. `SettingsStoreTest` already proves the real store works.
 *       What this fake adds that the real one cannot is [failDemoWrites] — a settings write that
 *       fails on demand, which is the only way to assert the ordering contract the repository
 *       documents: the flag is set *after* the rows on the way in, and *before* the wipe on the way
 *       out.
 * What: a `MutableStateFlow` of one snapshot, plus a switch that makes the demo setter fail.
 * Result: the repository's two transitions are testable including their failure halves.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 *
 * Input:  none. Output: a settings store the test controls completely.
 */
internal class FakeSettingsStore : SettingsStore {
    private val state = MutableStateFlow(SettingsSnapshot())

    /**
     * When true, every [setDemoModeActive] call fails and changes nothing.
     * Why: models a full disk or an unreadable settings file — the case where the rows and the flag
     *      can disagree, which is the only reason their write order matters at all.
     */
    var failDemoWrites: Boolean = false

    /** How many times [setDemoModeActive] was called, so a test can assert a short-circuit. */
    var demoWriteCount: Int = 0
        private set

    override fun observe(): Flow<Result<SettingsSnapshot, AppError>> = state.map { Ok(it) }

    override suspend fun setProfileTimeZone(zoneId: String): Result<Unit, AppError> =
        Ok(Unit).also { state.update { current -> current.copy(profileTimeZoneId = zoneId) } }

    override suspend fun setCurrencyCode(currencyCode: String): Result<Unit, AppError> =
        Ok(Unit).also { state.update { current -> current.copy(currencyCode = currencyCode) } }

    override suspend fun setPrivacyBlurEnabled(enabled: Boolean): Result<Unit, AppError> =
        Ok(Unit).also { state.update { current -> current.copy(privacyBlurEnabled = enabled) } }

    override suspend fun setTheme(theme: ThemeSetting): Result<Unit, AppError> =
        Ok(Unit).also { state.update { current -> current.copy(theme = theme) } }

    override suspend fun completeOnboarding(profile: OnboardingProfile): Result<Unit, AppError> =
        Ok(Unit).also {
            state.update { current ->
                current.copy(
                    profileTimeZoneId = profile.timeZoneId,
                    currencyCode = profile.currencyCode,
                    onboardingCompletedAtUtcMillis = ONBOARDED_AT_MILLIS,
                )
            }
        }

    override suspend fun setDemoModeActive(active: Boolean): Result<Unit, AppError> {
        demoWriteCount++
        if (failDemoWrites) return Err(AppError.Storage("FakeSettingsStore"))
        state.update { current -> current.copy(demoModeActive = active) }
        return Ok(Unit)
    }

    private companion object {
        /** An arbitrary fixed instant — no test asserts it, only that onboarding is marked done. */
        const val ONBOARDED_AT_MILLIS = 1_800_000_000_000L
    }
}
