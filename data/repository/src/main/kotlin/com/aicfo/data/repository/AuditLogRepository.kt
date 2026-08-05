package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.dao.AuditLogDao
import com.aicfo.core.database.entity.AuditLogEntity
import com.aicfo.core.model.AuditEvent
import com.aicfo.core.model.AuditMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The security event log (issue 2.2; §21.6, §23, ARC-005).
 *
 * Why:  §21.6 bans PII and amounts from `Log.*` and sends security events to `audit_log` instead —
 *       but a table nobody writes to is not a log, and until this issue nothing produced an event
 *       to write. The app lock is the first thing that does: an unlock, a refusal, a lockout, a
 *       PIN change. The point is answering "was there a burst of failed unlocks on a phone that
 *       went missing?" without ever having stored who, with what, or how much.
 * What: append one event, and read recent ones back.
 * Result: an auditable record the security screen (issue 11.3) and a future export can read.
 * Changelog: 2026-07-26 — Created for issue 2.2 (SEC-002, §21.6).
 *
 * **The only class in this issue allowed to touch a DAO (ARC-005).** The lock ViewModel calls this
 * interface and never sees a Room type.
 *
 * **Nothing here can record PII by construction, not by care.** The parameters are
 * [AuditEvent] and [AuditMethod] — closed enums — so there is no free-text argument for a caller to
 * pass a name, a balance or a typed PIN into, and `AuditLogEntity` has no column to hold one.
 */
interface AuditLogRepository {
    /**
     * Appends one security event.
     * Why:    every auth outcome is recorded, including the failures — a log that only records
     *         successes cannot show an attack.
     * What:   stamps the moment from the injected `Clock` (TIM-001) and inserts.
     * Result: `Ok(Unit)`, or `Err(Storage)` if the write failed. **Callers must not let a failure
     *         here change the auth decision**: failing to record a refusal must never turn it into
     *         an admission.
     * Input:  [event] — what happened; [method] — which factor, or `null` where none applies.
     * Output: `Result<Unit, AppError>`.
     */
    suspend fun record(
        event: AuditEvent,
        method: AuditMethod? = null,
    ): Result<Unit, AppError>

    /**
     * Observes the most recent events, newest first.
     * Result: emits on every append. Bounded, because this table only grows.
     * Input:  [limit] — how many rows at most. Output: `Flow<List<AuditEntry>>`.
     */
    fun observeRecent(limit: Int = DEFAULT_RECENT_LIMIT): Flow<List<AuditEntry>>

    /**
     * Counts events of one kind since an instant.
     * Why:    what a security review asks — "how many failed unlocks this week?" — answered in SQL
     *         rather than by loading every row.
     * Result: `Ok(count)`, or `Err(Storage)`.
     * Input:  [event] — the kind; [sinceUtcMillis] — inclusive lower bound (TIM-001).
     * Output: `Result<Int, AppError>`.
     */
    suspend fun countSince(
        event: AuditEvent,
        sinceUtcMillis: Long,
    ): Result<Int, AppError>

    companion object {
        /** Enough to fill a screen without loading a log that only ever grows. */
        const val DEFAULT_RECENT_LIMIT = 100
    }
}

/**
 * One recorded event, as callers see it.
 *
 * Why:    ARC-005 — nothing outside this module should hold a Room entity, or the schema could
 *         never change without touching every caller. This is also where the stored strings become
 *         typed again, so a caller cannot compare against a misspelled event name.
 * Result: what [AuditLogRepository.observeRecent] emits.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * Input:  [event] — the parsed [AuditEvent], or `null` if the stored name is not one this build
 *         knows (a row written by a newer version); [method] — the factor, or `null`;
 *         [occurredAtUtcMillis] — TIM-001.
 * Output: an immutable value.
 */
data class AuditEntry(
    val event: AuditEvent?,
    val occurredAtUtcMillis: Long,
    val method: AuditMethod? = null,
)

/**
 * The Room-backed [AuditLogRepository].
 * Why:    ARC-003 — one public interface, an internal implementation. Assembled by the DI graph.
 * Result: the implementation injected into the lock ViewModel.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * Input:  [dao] — the only DAO this module touches for audit rows; [clock] — stamps the event time,
 *         never the wall clock (TIM-001); [dispatchers] — database I/O off the caller's thread.
 * Output: a working audit log.
 */
internal class RoomAuditLogRepository(
    private val dao: AuditLogDao,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : AuditLogRepository {
    override suspend fun record(
        event: AuditEvent,
        method: AuditMethod?,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                dao.append(
                    AuditLogEntity(
                        event = event.name,
                        occurredAtUtcMillis = clock.nowUtcMillis(),
                        method = method?.name,
                    ),
                )
            }
        }

    override fun observeRecent(limit: Int): Flow<List<AuditEntry>> =
        dao.observeRecent(limit)
            .map { rows -> rows.map { it.toEntry() } }
            .flowOn(dispatchers.io)

    override suspend fun countSince(
        event: AuditEvent,
        sinceUtcMillis: Long,
    ): Result<Int, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult { dao.countSince(event.name, sinceUtcMillis) }
        }
}

/**
 * Converts a stored row to the caller-facing entry.
 * Why:    the stored `event` is a string, and a row written by a newer build may name a constant
 *         this one has never heard of. Mapping that to `null` rather than throwing means an old
 *         build reading a newer database shows an unknown event instead of crashing on the
 *         security screen.
 * Result: an [AuditEntry].
 * Input:  the receiver. Output: [AuditEntry].
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
private fun AuditLogEntity.toEntry(): AuditEntry =
    AuditEntry(
        event = AuditEvent.entries.firstOrNull { it.name == event },
        occurredAtUtcMillis = occurredAtUtcMillis,
        method = method?.let { stored -> AuditMethod.entries.firstOrNull { it.name == stored } },
    )
