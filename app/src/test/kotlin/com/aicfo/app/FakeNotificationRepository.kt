package com.aicfo.app

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.data.repository.NotificationRepository
import com.aicfo.domain.engines.notification.NotificationCandidate
import com.aicfo.domain.engines.notification.NotificationDecision
import com.aicfo.domain.engines.notification.NotificationOutcome
import com.aicfo.domain.engines.notification.NotificationPlan

/**
 * A stand-in for AI-NTF's gate (issue 9.6).
 *
 * Why:  the workers' tests are about what they *do with* the gate's answer — claim only what it
 *       delivers, post nothing it held, offer in the right order. The real policy is proven in
 *       `:domain:engines:notification` and against a real log in `NotificationRepositoryTest`;
 *       here the answer is whatever the test says it is.
 * Result: every candidate is delivered, except the keys in [held]; or the whole call fails.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
class FakeNotificationRepository : NotificationRepository {
    /** Every batch offered, in order — so a test can assert what was offered and in what order. */
    val offered: MutableList<List<NotificationCandidate>> = mutableListOf()

    /** Keys the gate folds into the digest instead of delivering. */
    var held: Set<String> = emptySet()

    /** When set, the call fails with this error, as an unreadable log would. */
    var failure: AppError? = null

    override suspend fun decide(candidates: List<NotificationCandidate>): Result<NotificationPlan, AppError> {
        offered += candidates
        failure?.let { return Err(it) }
        val decisions =
            candidates.map {
                val outcome = if (it.key in held) NotificationOutcome.FOLD_INTO_DIGEST else NotificationOutcome.DELIVER
                NotificationDecision(it, outcome)
            }
        return Ok(NotificationPlan(decisions, EngineProvenance("AI-NTF", "1.0", 0L)))
    }
}
