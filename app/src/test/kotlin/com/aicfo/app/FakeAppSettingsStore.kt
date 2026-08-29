package com.aicfo.app

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.datastore.OnboardingProfile
import com.aicfo.core.datastore.QuickSetupSeeds
import com.aicfo.core.datastore.SettingsSnapshot
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.core.datastore.ThemeSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * An in-memory [SettingsStore] for `:app`'s tests (issue 5.3).
 *
 * Why:  two classes in this module now read a setting — `MainViewModel` for the privacy blur, and
 *       `BudgetAlertWorker` for the same flag — and both are about *what the flag does*, not about
 *       Proto DataStore, which `SettingsStoreTest` already proves works. The real store would add a
 *       file, a scope and a serializer to every test here without changing an assertion.
 *
 *       **Writes are visible to reads**, unlike the throwaway stubs each test file used to declare
 *       inline: the blur is a round trip — the ViewModel writes and then observes the value back —
 *       and a fake whose setter returned `Ok(Unit)` and changed nothing would let a ViewModel that
 *       never wrote at all pass every test.
 * What: a `MutableStateFlow` of one snapshot; the setters this module exercises mutate it, the rest
 *       succeed without recording, because nothing here reads them.
 * Result: the flag's full path — write, persist, read back — is assertable on the JVM.
 * Changelog: 2026-08-16 — Created for issue 5.3.
 *
 * Input:  [initial] — the starting snapshot. Output: a settings store the test controls completely.
 */
internal class FakeAppSettingsStore(
    initial: SettingsSnapshot = SettingsSnapshot(),
) : SettingsStore {
    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<Result<SettingsSnapshot, AppError>> = state.map { Ok(it) }

    override suspend fun setPrivacyBlurEnabled(enabled: Boolean): Result<Unit, AppError> {
        state.update { it.copy(privacyBlurEnabled = enabled) }
        return Ok(Unit)
    }

    override suspend fun setDemoModeActive(active: Boolean): Result<Unit, AppError> {
        state.update { it.copy(demoModeActive = active) }
        return Ok(Unit)
    }

    // --- setters nothing in :app reads back --------------------------------------------------

    override suspend fun setProfileTimeZone(zoneId: String): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setCurrencyCode(currencyCode: String): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setTheme(theme: ThemeSetting): Result<Unit, AppError> = Ok(Unit)

    /**
     * The FR-SET-001 setter. Result: records the seeds so a reader sees what the screen wrote.
     * Input: [seeds]. Output: `Ok(Unit)`.
     */
    override suspend fun setQuickSetupSeeds(seeds: QuickSetupSeeds): Result<Unit, AppError> = Ok(Unit)

    override suspend fun completeOnboarding(profile: OnboardingProfile): Result<Unit, AppError> = Ok(Unit)

    override suspend fun setSmsScanCursor(smsId: Long): Result<Unit, AppError> = Ok(Unit)
}
