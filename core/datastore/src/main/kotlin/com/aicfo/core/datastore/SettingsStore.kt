package com.aicfo.core.datastore

import androidx.datastore.core.DataStore
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.datastore.proto.CfoSettingsProto
import com.aicfo.core.datastore.proto.ThemePreferenceProto
import com.aicfo.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The user's settings (issue 1.9; §21.3, TIM-001).
 *
 * Why:  three of these four settings change how the rest of the app behaves rather than just how it
 *       looks. [SettingsSnapshot.profileTimeZoneId] is the value `SystemClock`'s zone provider
 *       (issue 1.3) was built to read — every day boundary, month rollover and due date resolves
 *       through it, so it is a correctness setting, not a preference.
 * What: one observable snapshot, and a setter per field.
 * Result: settings that the UI observes and the `Clock` can be wired to.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 */
interface SettingsStore {
    /**
     * Watches the settings.
     * Why:    one Flow of one snapshot, so a screen cannot render a half-updated mix of old and
     *         new values.
     * Result: emits on every change; `Err(Storage)` if the store cannot be read.
     * Input:  none. Output: `Flow<Result<SettingsSnapshot, AppError>>`.
     */
    fun observe(): Flow<Result<SettingsSnapshot, AppError>>

    /**
     * Sets the profile time zone.
     * Why:    this is what makes "today" mean the user's today (TIM-001) — the single most
     *         consequential setting in the app.
     * Result: `Ok(Unit)` or `Err(Storage)`.
     * Input:  [zoneId] — an IANA id such as `Asia/Kolkata`. Output: `Result<Unit, AppError>`.
     */
    suspend fun setProfileTimeZone(zoneId: String): Result<Unit, AppError>

    /**
     * Sets the display currency.
     * Result: `Ok(Unit)` or `Err(Storage)`. Input: [currencyCode] — ISO-4217. Output: `Result`.
     */
    suspend fun setCurrencyCode(currencyCode: String): Result<Unit, AppError>

    /**
     * Turns the privacy blur on or off (issue 5.3).
     * Why:    hides amounts on screen in public without deleting or altering any data.
     * Result: `Ok(Unit)` or `Err(Storage)`. Input: [enabled]. Output: `Result`.
     */
    suspend fun setPrivacyBlurEnabled(enabled: Boolean): Result<Unit, AppError>

    /**
     * Sets the theme preference.
     * Result: `Ok(Unit)` or `Err(Storage)`. Input: [theme]. Output: `Result`.
     */
    suspend fun setTheme(theme: ThemeSetting): Result<Unit, AppError>

    /**
     * Records everything first-run onboarding captured, in one write (issue 2.1, FR-ONB-001/002).
     *
     * Why:    **one call, not five setters.** `updateData` is atomic per call, so writing the
     *         fields separately could leave the app marked onboarded with no time zone — after
     *         which every day boundary, month rollover and due date silently resolves in the wrong
     *         zone (TIM-001) and nothing ever asks again. Either the whole profile lands or none of
     *         it does.
     * What:   writes the profile, the optional seeds, and the completion timestamp stamped from the
     *         injected `Clock`.
     * Result: `Ok(Unit)` — after which `SettingsSnapshot.isOnboarded` is `true` — or `Err(Storage)`
     *         with nothing written. A caller that ignores this and navigates away anyway would
     *         strand the user on a dashboard with no profile.
     * Input:  [profile] — the captured answers. Output: `Result<Unit, AppError>`.
     */
    suspend fun completeOnboarding(profile: OnboardingProfile): Result<Unit, AppError>

    /**
     * Rewrites the quick-setup seeds after onboarding (issue: Settings, FR-SET-001).
     *
     * Why:  the seeds were writable only by [completeOnboarding], so a user who skipped the optional
     *       head-start step could never supply an income — and the dashboard's needs/wants/savings
     *       split, which reads the envelopes derived from these, stayed empty for ever while telling
     *       them to visit a Settings screen that did not exist. This is the setter that makes the
     *       screen possible; it deliberately touches only the three seeds, leaving the completion
     *       flag and the profile identity alone.
     * Result: `Ok(Unit)` when the write lands, `Err(Storage)` otherwise. Absent seeds are stored as
     *       zero, the same encoding [completeOnboarding] uses, and read back as `null`.
     * Input:  [seeds] — what the user typed. Output: `Result<Unit, AppError>`.
     */
    suspend fun setQuickSetupSeeds(seeds: QuickSetupSeeds): Result<Unit, AppError>

    /**
     * Turns demo mode on or off (issue 2.4; FR-ONB-004).
     *
     * Why:    a setter of its own rather than a field on [completeOnboarding], because demo mode is
     *         the one path that must write **nothing else**. FR-ONB-004 says the sample data is
     *         available "without creating a profile", so entering the demo may not set a time zone,
     *         a currency, a display name or the completion flag — a demo user who exits must land
     *         back on first-run onboarding, not on an empty dashboard belonging to a profile they
     *         never made.
     * Result: `Ok(Unit)` — after which `SettingsSnapshot.demoModeActive` reflects [active] — or
     *         `Err(Storage)` with nothing written.
     * Input:  [active] — whether the sample dataset is being shown. Output: `Result<Unit, AppError>`.
     */
    suspend fun setDemoModeActive(active: Boolean): Result<Unit, AppError>

    /**
     * Records how far through the inbox the SMS scanner has read (issue 3.9; §18, P-01).
     *
     * Why:    the scanner must never re-read a message it has already judged — not for efficiency,
     *         but because touching the user's private messages again for no new information is
     *         exactly what P-01 asks this app not to do. Written **after** a batch is processed, so
     *         a scan that fails halfway leaves the cursor behind the messages it did not handle and
     *         the next scan picks them up rather than skipping them for ever.
     *
     *         Passing `0` resets it, which is what revoking the consent does: a later re-grant then
     *         starts from a clean inbox rather than silently inheriting a position from a decision
     *         the user has since withdrawn.
     * Result: `Ok(Unit)` or `Err(Storage)`.
     * Input:  [smsId] — the highest `Telephony.Sms._ID` processed, or `0` to reset.
     * Output: `Result<Unit, AppError>`.
     */
    suspend fun setSmsScanCursor(smsId: Long): Result<Unit, AppError>
}

/**
 * What onboarding captured, as one value (issue 2.1).
 *
 * Why:    passing five loose parameters through the ViewModel and into the store is how a currency
 *         ends up in a time-zone field — both are `String`, and the compiler would not object.
 * Result: the argument to [SettingsStore.completeOnboarding].
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *
 * Input:  [timeZoneId] — IANA zone (TIM-001); [currencyCode] — ISO-4217; [displayName] — local
 *         only, may be blank if the user skipped it; [quickSetup] — the optional FR-ONB-002 seeds.
 * Output: an immutable value.
 */
data class OnboardingProfile(
    val timeZoneId: String,
    val currencyCode: String,
    val displayName: String = "",
    val quickSetup: QuickSetupSeeds = QuickSetupSeeds(),
)

/**
 * The DataStore-backed [SettingsStore].
 * Why:    §21.3 — Proto DataStore, never SharedPreferences. Writes go through `updateData`, which
 *         is atomic, so a crash cannot leave a partially applied change.
 * Result: the implementation injected into the settings screen and the `Clock` wiring.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 *
 * Input:  [dataStore]; [clock] — stamps the onboarding completion time, never the wall clock
 *         (TIM-001); [dispatchers] — I/O off the caller's thread (ARC-006).
 * Output: a working settings store.
 */
internal class DataStoreSettingsStore(
    private val dataStore: DataStore<CfoSettingsProto>,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : SettingsStore {
    override fun observe(): Flow<Result<SettingsSnapshot, AppError>> =
        dataStore.data
            .map { settings -> Ok(settings.toSnapshot()) as Result<SettingsSnapshot, AppError> }
            .catch { failure -> emit(Err(failure.toStorageError())) }
            .flowOn(dispatchers.io)

    override suspend fun setProfileTimeZone(zoneId: String): Result<Unit, AppError> =
        update { it.setProfileTimeZoneId(zoneId) }

    override suspend fun setCurrencyCode(currencyCode: String): Result<Unit, AppError> =
        update { it.setCurrencyCode(currencyCode) }

    override suspend fun setPrivacyBlurEnabled(enabled: Boolean): Result<Unit, AppError> =
        update { it.setPrivacyBlurEnabled(enabled) }

    override suspend fun setTheme(theme: ThemeSetting): Result<Unit, AppError> = update { it.setTheme(theme.toProto()) }

    override suspend fun setQuickSetupSeeds(seeds: QuickSetupSeeds): Result<Unit, AppError> =
        update { builder ->
            builder
                .setQuickSetupMonthlyIncomeMinor(seeds.monthlyIncome.orZero())
                .setQuickSetupRentEmiMinor(seeds.rentOrEmi.orZero())
                .setQuickSetupTypicalSavingsMinor(seeds.typicalSavings.orZero())
        }

    override suspend fun completeOnboarding(profile: OnboardingProfile): Result<Unit, AppError> =
        update { builder ->
            builder
                .setProfileTimeZoneId(profile.timeZoneId)
                .setCurrencyCode(profile.currencyCode)
                .setProfileDisplayName(profile.displayName)
                .setQuickSetupMonthlyIncomeMinor(profile.quickSetup.monthlyIncome.orZero())
                .setQuickSetupRentEmiMinor(profile.quickSetup.rentOrEmi.orZero())
                .setQuickSetupTypicalSavingsMinor(profile.quickSetup.typicalSavings.orZero())
                // Stamped last so the flag is only ever set alongside the profile it belongs to.
                .setOnboardingCompletedAtUtcMillis(clock.nowUtcMillis())
        }

    override suspend fun setDemoModeActive(active: Boolean): Result<Unit, AppError> =
        update { it.setDemoModeActive(active) }

    override suspend fun setSmsScanCursor(smsId: Long): Result<Unit, AppError> = update { it.setSmsScanCursorId(smsId) }

    /**
     * Applies one field change atomically.
     * Result: `Ok(Unit)` or `Err(Storage)` — nothing throws across the boundary (§21.6).
     * Input:  [transform] — mutates the builder. Output: `Result<Unit, AppError>`.
     */
    private suspend fun update(
        transform: (CfoSettingsProto.Builder) -> CfoSettingsProto.Builder,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            try {
                dataStore.updateData { current -> transform(current.toBuilder()).build() }
                Ok(Unit)
            } catch (failure: IOException) {
                Err(failure.toStorageError())
            }
        }
}

/**
 * Converts the stored proto to the caller-facing snapshot.
 * Why:    proto3 has no null — an unset string reads as `""`. Mapping empty to `null` here means
 *         "never set" stays distinguishable from "deliberately set to empty", which matters for
 *         the time zone: unset must fall back to the device zone, not to a blank zone id.
 * Result: a [SettingsSnapshot].
 * Input:  the receiver. Output: [SettingsSnapshot].
 * Changelog: 2026-07-25 — Created for issue 1.9.
 */
internal fun CfoSettingsProto.toSnapshot(): SettingsSnapshot =
    SettingsSnapshot(
        profileTimeZoneId = profileTimeZoneId.takeIf { it.isNotEmpty() },
        currencyCode = currencyCode.takeIf { it.isNotEmpty() },
        privacyBlurEnabled = privacyBlurEnabled,
        theme = theme.toSetting(),
        profileDisplayName = profileDisplayName.takeIf { it.isNotEmpty() },
        // An unset int64 reads as 0, which here means "onboarding never finished".
        onboardingCompletedAtUtcMillis = onboardingCompletedAtUtcMillis.takeIf { it > 0L },
        demoModeActive = demoModeActive,
        // 0 is both proto3's default and this field's meaning of "nothing read yet", so unlike the
        // quick-setup seeds there is nothing to disambiguate — there is no inbox row with _ID 0.
        smsScanCursorId = smsScanCursorId,
        quickSetup =
            QuickSetupSeeds(
                monthlyIncome = quickSetupMonthlyIncomeMinor.toSeed(),
                rentOrEmi = quickSetupRentEmiMinor.toSeed(),
                typicalSavings = quickSetupTypicalSavingsMinor.toSeed(),
            ),
    )

/**
 * Reads a stored quick-setup amount.
 * Why:    proto3 cannot distinguish "not answered" from zero, and for a seed those mean different
 *         things — a skipped income must not seed a ₹0 budget. Zero is therefore read as unset,
 *         which is the safe direction: the worst case is asking the user again.
 * Result: the [Money], or `null` when unanswered.
 * Input:  the receiver — minor units (MNY-001). Output: `Money?`.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
private fun Long.toSeed(): Money? = takeIf { it != 0L }?.let(::Money)

/**
 * Writes an optional quick-setup amount.
 * Why:    the inverse of [toSeed] — an unanswered field is stored as proto3's own default, so it
 *         reads back as unanswered rather than as a claim of zero.
 * Result: the minor units, or `0` when there is no answer.
 * Input:  the receiver. Output: `Long`.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
private fun Money?.orZero(): Long = this?.minor ?: 0L

/** Result: the proto enum for a [ThemeSetting]. Input: the receiver. Output: [ThemePreferenceProto]. */
internal fun ThemeSetting.toProto(): ThemePreferenceProto =
    when (this) {
        ThemeSetting.SYSTEM -> ThemePreferenceProto.THEME_SYSTEM
        ThemeSetting.LIGHT -> ThemePreferenceProto.THEME_LIGHT
        ThemeSetting.DARK -> ThemePreferenceProto.THEME_DARK
    }

/**
 * Result: the [ThemeSetting] for a stored enum; an unrecognised value falls back to SYSTEM, which
 *         is the safe default for a value written by a newer build.
 * Input:  the receiver. Output: [ThemeSetting].
 */
internal fun ThemePreferenceProto.toSetting(): ThemeSetting =
    when (this) {
        ThemePreferenceProto.THEME_LIGHT -> ThemeSetting.LIGHT
        ThemePreferenceProto.THEME_DARK -> ThemeSetting.DARK
        else -> ThemeSetting.SYSTEM
    }
