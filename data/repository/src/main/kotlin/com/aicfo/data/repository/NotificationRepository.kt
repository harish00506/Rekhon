package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.NotificationLogEntity
import com.aicfo.domain.engines.notification.NotificationCandidate
import com.aicfo.domain.engines.notification.NotificationDecision
import com.aicfo.domain.engines.notification.NotificationInput
import com.aicfo.domain.engines.notification.NotificationKind
import com.aicfo.domain.engines.notification.NotificationOutcome
import com.aicfo.domain.engines.notification.NotificationPlan
import com.aicfo.domain.engines.notification.NotificationPolicyEngine
import com.aicfo.domain.engines.notification.NotificationRules
import com.aicfo.domain.engines.notification.SentNotification
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime

/**
 * The notification policy, with its memory (issue 9.6; §17.2 NTF-001/002/005, AI-NTF).
 *
 * Why:  every notification the app posts has to pass one gate — the caps, the quiet hours, the
 *       never-twice rule — and the gate needs to know what has already gone out. This is that gate.
 *       The workers that raise alerts ask it before they post, rather than each counting for
 *       themselves: two workers that each allowed two a day would be four a day.
 * What: reads what has been sent, resolves "now" in the profile's zone from the injected clock
 *       (TIM-001), asks AI-NTF, and records the decisions.
 * Result: the plan; the caller posts [NotificationPlan.deliverable] and nothing else.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
interface NotificationRepository {
    /**
     * Decides a batch and records the decisions.
     * Why:    **a delivered decision is recorded as sent before the caller posts it.** The same
     *         claim-then-notify order `BudgetRepository.markNotified` already uses: if posting then
     *         fails (a permission revoked, a channel switched off) the log says sent and the message
     *         is not retried — which errs toward silence, the side NTF-001 exists to protect.
     * Result: `Ok(plan)`; `Err` only when the log cannot be read or written.
     * Input:  [candidates] — in the order they should spend the ration (most important first).
     * Output: `Result<NotificationPlan, AppError>`.
     */
    suspend fun decide(candidates: List<NotificationCandidate>): Result<NotificationPlan, AppError>
}

/**
 * [NotificationRepository] over `notification_log` (issue 9.6).
 * Input:  [database]; [engine]; [clock] — its zone decides what "today" and "22:00" mean;
 *         [dispatchers]; [activeProfileId]; [idGenerator]; [rules] — the seam.
 * Output: the repository.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
@Suppress("LongParameterList") // the store, the engine, and five seams
internal class RoomNotificationRepository(
    private val database: CfoDatabase,
    private val engine: NotificationPolicyEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
    private val idGenerator: IdGenerator,
    private val rules: NotificationRules = NotificationRules(),
) : NotificationRepository {
    override suspend fun decide(candidates: List<NotificationCandidate>): Result<NotificationPlan, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            val nowUtc = clock.nowUtcMillis()
            runCatchingToResult {
                // Every send, not only the window's: the caps count the window, and the never-twice
                // rule needs every key ever sent. One row per key keeps this bounded by the number
                // of distinct messages, not by time.
                database.notificationLogDao().sentSince(profileId, 0L).mapNotNull(::toSent)
            }.flatMap { history -> engine.decide(NotificationInput(candidates, history, local(nowUtc), nowUtc, rules)) }
                .flatMap { plan ->
                    runCatchingToResult {
                        plan.decisions.forEach { record(profileId, it, nowUtc) }
                        plan
                    }
                }
        }

    /**
     * Stores one decision.
     * Why:    **a repeat is not recorded** — the row that proves it was sent must not be overwritten
     *         by the decision that it already was. Every other outcome updates the key's one row,
     *         keeping its id and its creation time.
     * Result: the row is current. Input: [profileId]; [decision]; [nowUtc]. Output: none.
     */
    private suspend fun record(
        profileId: String,
        decision: NotificationDecision,
        nowUtc: Long,
    ) {
        if (decision.outcome == NotificationOutcome.ALREADY_SENT) return
        val dao = database.notificationLogDao()
        val existing = dao.find(profileId, decision.candidate.key)
        dao.upsert(
            NotificationLogEntity(
                id = existing?.id ?: idGenerator.newId("notification"),
                profileId = profileId,
                key = decision.candidate.key,
                kind = decision.candidate.kind.name,
                outcome = decision.outcome.name.lowercase(),
                decidedAtUtcMillis = nowUtc,
                sentAtUtcMillis = if (decision.outcome == NotificationOutcome.DELIVER) nowUtc else null,
                deliverAfterUtcMillis = decision.deliverAfter?.atZone(clock.zone())?.toInstant()?.toEpochMilli(),
                createdAtUtcMillis = existing?.createdAtUtcMillis ?: nowUtc,
                updatedAtUtcMillis = nowUtc,
            ),
        )
    }

    /** Result: a stored send as the engine reads it, or `null` for a kind this build no longer knows. */
    private fun toSent(row: NotificationLogEntity): SentNotification? {
        val kind = NotificationKind.entries.firstOrNull { it.name == row.kind } ?: return null
        val sentAt = row.sentAtUtcMillis ?: return null
        return SentNotification(row.key, kind, local(sentAt))
    }

    /** Result: [utcMillis] as local time in the profile's zone (TIM-001). */
    private fun local(utcMillis: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(utcMillis), clock.zone())
}
