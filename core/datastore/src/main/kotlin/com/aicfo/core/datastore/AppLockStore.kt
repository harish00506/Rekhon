package com.aicfo.core.datastore

import androidx.datastore.core.DataStore
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.datastore.proto.CfoSettingsProto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The app lock's persisted state (issue 2.2; SEC-002, FR-SET-001).
 *
 * Why:  a third narrow interface over the same file, for the same reason [ConsentStore] is a second
 *       one — a screen that changes the theme has no business resetting a lockout counter, and the
 *       lock screen has no business setting a currency. Splitting them is also what keeps either
 *       interface small enough to read.
 * What: observe the lock state, and the five writes that change it.
 * Result: one place that answers "is the app locked, for how long, and how many tries are left?".
 * Changelog: 2026-07-26 — Created for issue 2.2 (SEC-002).
 *
 * **Reads are a `Flow` on purpose.** The failure counter changes while the lock screen is showing —
 * that is what drives "2 attempts remaining" and the lockout countdown without the screen polling.
 *
 * **The failure counter is on disk, not in memory.** SEC-002's escalating lockout is only a defence
 * if force-stopping the app does not reset it, and force-stopping is the first thing someone with a
 * stolen phone would try.
 */
interface AppLockStore {
    /**
     * Watches the lock state.
     * Result: emits on every change; `Err(Storage)` if the store cannot be read. A caller that
     *         cannot read this must **stay locked** — a read failure is never permission to open.
     * Input:  none. Output: `Flow<Result<AppLockSettings, AppError>>`.
     */
    fun observe(): Flow<Result<AppLockSettings, AppError>>

    /**
     * Turns the app lock on or off (FR-SET-001).
     * Why:    the switch the lock screen's whole existence hangs on. Callers must have set a PIN
     *         first — enabling the lock with no credential would leave nothing to unlock with, so
     *         onboarding's security step and the settings screen both check `PinVerifier.isPinSet`
     *         before calling this.
     * Result: `Ok(Unit)` or `Err(Storage)`. Input: [enabled]. Output: `Result`.
     */
    suspend fun setEnabled(enabled: Boolean): Result<Unit, AppError>

    /**
     * Turns biometric unlock on or off (SEC-002).
     * Why:    independent of the lock itself — a user may keep the lock and refuse biometrics, and
     *         a device may have no class-3 sensor at all. The PIN is the fallback that always works.
     * Result: `Ok(Unit)` or `Err(Storage)`. Input: [enabled]. Output: `Result`.
     */
    suspend fun setBiometricEnabled(enabled: Boolean): Result<Unit, AppError>

    /**
     * Sets how long the app may sit in the background before re-locking (§23.1, FR-SET-001).
     * Result: `Ok(Unit)` or `Err(Storage)`. Storing `0` reads back as [DEFAULT_AUTO_LOCK_SECONDS],
     *         not as "re-lock instantly".
     * Input:  [seconds] — the idle allowance. Output: `Result`.
     */
    suspend fun setAutoLockTimeoutSeconds(seconds: Int): Result<Unit, AppError>

    /**
     * Records one failed unlock (SEC-002).
     * What:   increments the counter and stamps the moment from the injected `Clock` (TIM-001).
     * Result: `Ok(Unit)` — after which `LockoutPolicy` may report a lockout — or `Err(Storage)`.
     * Input:  none. Output: `Result<Unit, AppError>`.
     */
    suspend fun recordFailedUnlock(): Result<Unit, AppError>

    /**
     * Clears the failure record after a successful unlock (SEC-002).
     * Why:    without this a user who once mistyped four times would carry those failures forever,
     *         and their fifth mistake months later would trigger a lockout.
     * Result: `Ok(Unit)` or `Err(Storage)`. Input: none. Output: `Result`.
     */
    suspend fun clearFailedUnlocks(): Result<Unit, AppError>
}

/**
 * The DataStore-backed [AppLockStore].
 * Why:    §21.3 — Proto DataStore, never SharedPreferences. Writes go through `updateData`, which
 *         is atomic, so a crash cannot leave a counter incremented but unstamped.
 * Result: the implementation injected into the lock ViewModel and the security settings.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * Input:  [dataStore] — the same file [SettingsStore] and [ConsentStore] use; [clock] — stamps the
 *         failure time, never the wall clock (TIM-001); [dispatchers] — I/O off the caller's thread.
 * Output: a working app-lock store.
 */
internal class DataStoreAppLockStore(
    private val dataStore: DataStore<CfoSettingsProto>,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : AppLockStore {
    override fun observe(): Flow<Result<AppLockSettings, AppError>> =
        dataStore.data
            .map { settings -> Ok(settings.toAppLockSettings()) as Result<AppLockSettings, AppError> }
            .catch { failure -> emit(Err(failure.toStorageError())) }
            .flowOn(dispatchers.io)

    override suspend fun setEnabled(enabled: Boolean): Result<Unit, AppError> = update { it.setAppLockEnabled(enabled) }

    override suspend fun setBiometricEnabled(enabled: Boolean): Result<Unit, AppError> =
        update { it.setBiometricUnlockEnabled(enabled) }

    override suspend fun setAutoLockTimeoutSeconds(seconds: Int): Result<Unit, AppError> =
        update { it.setAutoLockTimeoutSeconds(seconds) }

    override suspend fun recordFailedUnlock(): Result<Unit, AppError> =
        update { builder ->
            // Read-modify-write inside updateData, which is atomic per call — incrementing from a
            // value read earlier would lose failures under two racing unlock attempts, and a lost
            // failure is a free guess.
            builder
                .setFailedUnlockAttempts(builder.failedUnlockAttempts + 1)
                .setLastFailedUnlockAtUtcMillis(clock.nowUtcMillis())
        }

    override suspend fun clearFailedUnlocks(): Result<Unit, AppError> =
        update { builder ->
            builder
                .setFailedUnlockAttempts(0)
                // Cleared as well as zeroed: a stale timestamp beside a zero count would read as a
                // lockout anchored to an instant that no longer means anything.
                .setLastFailedUnlockAtUtcMillis(0L)
        }

    /**
     * Applies one change atomically.
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
 * Reads the lock state out of the stored proto.
 * Why:    proto3 cannot tell "unset" from zero, and for the auto-lock timer those mean very
 *         different things — a literal `0` would re-lock the app on every task switch, which reads
 *         as a broken app rather than as a security feature.
 * Result: an [AppLockSettings].
 * Input:  the receiver. Output: [AppLockSettings].
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
internal fun CfoSettingsProto.toAppLockSettings(): AppLockSettings =
    AppLockSettings(
        enabled = appLockEnabled,
        biometricEnabled = biometricUnlockEnabled,
        autoLockTimeoutSeconds = autoLockTimeoutSeconds.takeIf { it > 0 } ?: DEFAULT_AUTO_LOCK_SECONDS,
        failedAttempts = failedUnlockAttempts,
        lastFailureAtUtcMillis = lastFailedUnlockAtUtcMillis.takeIf { it > 0L },
    )
