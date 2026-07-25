package com.aicfo.core.datastore

import androidx.datastore.core.DataStore
import com.aicfo.core.common.AppError
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.datastore.proto.CfoSettingsProto
import com.aicfo.core.datastore.proto.ThemePreferenceProto
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
}

/**
 * The DataStore-backed [SettingsStore].
 * Why:    §21.3 — Proto DataStore, never SharedPreferences. Writes go through `updateData`, which
 *         is atomic, so a crash cannot leave a partially applied change.
 * Result: the implementation injected into the settings screen and the `Clock` wiring.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 *
 * Input:  [dataStore]; [dispatchers] — I/O off the caller's thread (ARC-006).
 * Output: a working settings store.
 */
internal class DataStoreSettingsStore(
    private val dataStore: DataStore<CfoSettingsProto>,
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
    )

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
