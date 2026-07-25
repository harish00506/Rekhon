package com.aicfo.core.datastore

import androidx.datastore.core.DataStore
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.datastore.proto.CfoSettingsProto
import com.aicfo.core.datastore.proto.ConsentRecordProto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The per-feature consent ledger (P-01).
 *
 * Why:  P-01 is the rule the whole product is sold on — no financial data leaves the device without
 *       explicit, revocable, per-feature consent. Every opt-in feature (SMS parsing, market data,
 *       cloud LLM, off-device backup) has to ask the same component the same question, or each will
 *       invent its own flag and one of them will get it wrong.
 * What: observe a feature's state as a [Flow], and grant or revoke it.
 * Result: one auditable answer to "may this run?", with the history of how it was decided.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 *
 * **Reads are a `Flow` on purpose.** A consent that is only read once, at startup, cannot be
 * revoked — the feature would keep running until the app restarted, which is not what "revocable"
 * means to a user who just turned it off. Consumers must *collect* this and stop their work when it
 * emits `granted = false`. This module can only provide the signal; honouring it is each feature's
 * obligation, and is worth an explicit check in their reviews.
 */
interface ConsentStore {
    /**
     * Watches one feature's consent.
     * Result: emits on every change; `Ok(ConsentState.NOT_GRANTED)` when nothing is recorded, and
     *         `Err(Storage)` if the store cannot be read — never a default that reads as granted.
     * Input:  [feature]. Output: `Flow<Result<ConsentState, AppError>>`.
     */
    fun observe(feature: ConsentFeature): Flow<Result<ConsentState, AppError>>

    /**
     * Watches every known feature at once, for the consents dashboard.
     * Result: a map covering **all** [ConsentFeature] entries, with un-recorded ones present as
     *         not granted — so the UI cannot omit a feature it forgot to look up.
     * Input:  none. Output: `Flow<Result<Map<ConsentFeature, ConsentState>, AppError>>`.
     */
    fun observeAll(): Flow<Result<Map<ConsentFeature, ConsentState>, AppError>>

    /**
     * Records that the user agreed.
     * Why:    stamped from the injected `Clock` (TIM-001), so a test can fix the time and the app
     *         can answer "since when?".
     * Result: `Ok(Unit)`, or `Err(Storage)` if the write failed. Re-granting a previously revoked
     *         feature starts a fresh record — the old revocation is not left dangling on it.
     * Input:  [feature]. Output: `Result<Unit, AppError>`.
     */
    suspend fun grant(feature: ConsentFeature): Result<Unit, AppError>

    /**
     * Records that the user withdrew permission.
     * Why:    the record is kept rather than deleted, so the withdrawal itself is auditable — "I
     *         turned that off in March" is a question the app must be able to answer.
     * Result: `Ok(Unit)` with `granted = false` and the revocation time stamped; `Err(Storage)` on
     *         failure.
     * Input:  [feature]. Output: `Result<Unit, AppError>`.
     */
    suspend fun revoke(feature: ConsentFeature): Result<Unit, AppError>
}

/**
 * The DataStore-backed [ConsentStore].
 *
 * Why:    §21.3 pins Proto DataStore and bans SharedPreferences: consent is structured, versioned
 *         data with timestamps, and `getBoolean("sms", false)` cannot carry any of that.
 * What:   maps the generated proto to [ConsentState] and back, with every write going through
 *         `updateData`, which is atomic — a crash mid-write cannot leave a half-granted record.
 * Result: the implementation injected wherever a feature must be gated.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 *
 * Input:  [dataStore] — the typed store; [clock] — supplies the timestamps (never the wall clock,
 *         TIM-001); [dispatchers] — I/O runs off the caller's thread (ARC-006).
 * Output: a working consent ledger.
 */
internal class DataStoreConsentStore(
    private val dataStore: DataStore<CfoSettingsProto>,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : ConsentStore {
    override fun observe(feature: ConsentFeature): Flow<Result<ConsentState, AppError>> =
        dataStore.data
            .map { settings -> Ok(settings.consentFor(feature)) as Result<ConsentState, AppError> }
            .catch { failure -> emit(Err(failure.toStorageError())) }
            .flowOn(dispatchers.io)

    override fun observeAll(): Flow<Result<Map<ConsentFeature, ConsentState>, AppError>> =
        dataStore.data
            .map { settings ->
                // Every known feature is present, so a dashboard cannot silently omit one it
                // forgot to query — an un-listed consent looks like a feature with no gate.
                Ok(ConsentFeature.entries.associateWith(settings::consentFor))
                    as Result<Map<ConsentFeature, ConsentState>, AppError>
            }.catch { failure -> emit(Err(failure.toStorageError())) }
            .flowOn(dispatchers.io)

    override suspend fun grant(feature: ConsentFeature): Result<Unit, AppError> =
        update(feature) {
            ConsentRecordProto
                .newBuilder()
                .setGranted(true)
                .setGrantedAtUtcMillis(clock.nowUtcMillis())
                // Deliberately not carrying an older revocation forward: this is a new decision,
                // and leaving the old timestamp on it would read as "granted and revoked".
                .build()
        }

    override suspend fun revoke(feature: ConsentFeature): Result<Unit, AppError> =
        update(feature) { existing ->
            existing
                .toBuilder()
                .setGranted(false)
                .setRevokedAtUtcMillis(clock.nowUtcMillis())
                .build()
        }

    /**
     * Applies a change to one feature's record.
     * Why:    both writers need the same atomic read-modify-write and the same error mapping;
     *         duplicating it is how one of them ends up throwing across the boundary (§21.6).
     * Result: `Ok(Unit)`, or `Err(Storage)` — no exception escapes.
     * Input:  [feature]; [transform] — builds the new record from the existing one.
     * Output: `Result<Unit, AppError>`.
     */
    private suspend fun update(
        feature: ConsentFeature,
        transform: (ConsentRecordProto) -> ConsentRecordProto,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            try {
                dataStore.updateData { current ->
                    val existing =
                        current.consentsMap[feature.id] ?: ConsentRecordProto.getDefaultInstance()
                    current.toBuilder().putConsents(feature.id, transform(existing)).build()
                }
                Ok(Unit)
            } catch (failure: IOException) {
                Err(failure.toStorageError())
            }
        }
}

/**
 * Reads one feature's state out of the stored settings.
 * Why:    the "absence is not consent" rule lives here, in one place, so no caller can default the
 *         other way.
 * Result: the recorded state, or [ConsentState.NOT_GRANTED] when there is no record.
 * Input:  [feature]. Output: [ConsentState].
 * Changelog: 2026-07-25 — Created for issue 1.9.
 */
internal fun CfoSettingsProto.consentFor(feature: ConsentFeature): ConsentState {
    val record = consentsMap[feature.id] ?: return ConsentState.NOT_GRANTED
    return ConsentState(
        granted = record.granted,
        // proto3 has no null: an unset int64 reads as 0, which here means "never happened".
        grantedAtUtcMillis = record.grantedAtUtcMillis.takeIf { it > 0L },
        revokedAtUtcMillis = record.revokedAtUtcMillis.takeIf { it > 0L },
    )
}

/**
 * Maps a storage failure to the typed error.
 * Why:    keeps only the exception's class name, never its message, which can carry a file path
 *         (P-01) — the same rule `AppError.toAppError` follows.
 * Result: `AppError.Storage`.
 * Input:  the receiver. Output: [AppError].
 * Changelog: 2026-07-25 — Created for issue 1.9.
 */
internal fun Throwable.toStorageError(): AppError = AppError.Storage(this::class.java.simpleName)
