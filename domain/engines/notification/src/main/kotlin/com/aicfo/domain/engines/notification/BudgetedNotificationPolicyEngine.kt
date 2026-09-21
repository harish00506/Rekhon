package com.aicfo.domain.engines.notification

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import java.time.LocalDateTime

/**
 * §17.2's policy, as written (issue 9.6; AI-NTF, NTF-001/002, P-08, ADR-0047).
 *
 * Why:  three questions in a fixed order, because the order is what makes the answers explicable.
 *       **Have we said this already?** Then say nothing; a repeat is not a second message. **Is it
 *       the middle of the night?** Then hold it until morning — and holding costs nothing, so a
 *       message waiting for 08:00 does not eat the day's ration and silence the one after it.
 *       **Is the ration spent?** Then it folds into the weekly digest rather than being dropped:
 *       the finding survives, only where it is said changes.
 * What: validate → decide each candidate in the order offered, counting the ones this plan itself
 *       delivers → provenance.
 * Result: a [NotificationPlan].
 * Changelog: 2026-09-20 — Created for issue 9.6.
 *
 * No clock and no I/O (P-08): the caller resolves the profile's zone (TIM-001) and passes the local
 * time, so every boundary in here is testable to the minute. `internal` per ARC-003.
 */
internal class BudgetedNotificationPolicyEngine : NotificationPolicyEngine {
    override fun decide(input: NotificationInput): Result<NotificationPlan, AppError> {
        validate(input)?.let { return Err(it) }
        val rules = input.rules
        val sentKeys = input.history.map { it.key }.toSet()
        val today = input.history.count { it.sentAt.toLocalDate() == input.now.toLocalDate() && !it.kind.critical }
        val window =
            input.history.count { it.sentAt > input.now.minusDays(rules.windowDays.toLong()) && !it.kind.critical }
        var spentToday = today
        var spentInWindow = window
        val decisions =
            input.candidates.map { candidate ->
                val decision = decide(candidate, input, spentToday, spentInWindow, sentKeys)
                if (decision.outcome == NotificationOutcome.DELIVER && !candidate.kind.critical) {
                    spentToday += 1
                    spentInWindow += 1
                }
                decision
            }
        return Ok(NotificationPlan(decisions, provenance(input)))
    }

    /**
     * One candidate, against the three questions in order.
     * Result: the decision. Input: [candidate]; [input]; [spentToday] and [spentInWindow] —
     *         including what this plan has already decided to deliver; [sentKeys].
     * Output: [NotificationDecision].
     */
    private fun decide(
        candidate: NotificationCandidate,
        input: NotificationInput,
        spentToday: Int,
        spentInWindow: Int,
        sentKeys: Set<String>,
    ): NotificationDecision {
        val rules = input.rules
        val exemptFromCaps = candidate.kind.critical && rules.criticalExempt
        val mayBypassQuietHours = candidate.kind.critical && rules.criticalMayBypassQuietHours
        return when {
            candidate.key in sentKeys -> NotificationDecision(candidate, NotificationOutcome.ALREADY_SENT)
            !mayBypassQuietHours && inQuietHours(input.now, rules) ->
                NotificationDecision(
                    candidate = candidate,
                    outcome = NotificationOutcome.WAIT_FOR_QUIET_HOURS,
                    deliverAfter = quietHoursEnd(input.now, rules),
                    citations = listOf(NotificationRules.QUIET),
                )
            !exemptFromCaps && (spentToday >= rules.dailyMax || spentInWindow >= rules.weeklyMax) ->
                NotificationDecision(
                    candidate,
                    NotificationOutcome.FOLD_INTO_DIGEST,
                    citations = listOf(NotificationRules.BUDGET),
                )
            else ->
                NotificationDecision(
                    candidate = candidate,
                    outcome = NotificationOutcome.DELIVER,
                    citations = listOf(NotificationRules.BUDGET, NotificationRules.QUIET),
                )
        }
    }

    /**
     * Whether [now] falls in the quiet window, which wraps midnight.
     * Why:    inclusive at the start and exclusive at the end, so 22:00 is quiet and 08:00 is not —
     *         the two boundaries §17.2 states, and the two a test can hold the engine to.
     * Result: `true` inside the window. Input: [now]; [rules]. Output: [Boolean].
     */
    private fun inQuietHours(
        now: LocalDateTime,
        rules: NotificationRules,
    ): Boolean {
        val hour = now.hour
        return if (rules.quietStartHour < rules.quietEndHour) {
            hour >= rules.quietStartHour && hour < rules.quietEndHour
        } else {
            hour >= rules.quietStartHour || hour < rules.quietEndHour
        }
    }

    /**
     * When the wait ends: the next time the clock reads the window's end hour.
     * Result: that instant. Input: [now] — inside the window; [rules]. Output: [LocalDateTime].
     */
    private fun quietHoursEnd(
        now: LocalDateTime,
        rules: NotificationRules,
    ): LocalDateTime {
        val endToday = now.toLocalDate().atTime(rules.quietEndHour, 0)
        return if (endToday > now) endToday else endToday.plusDays(1)
    }

    /**
     * The inputs no policy can be applied to.
     * Result: the first refusal by field, or `null`. Input: [input]. Output: `AppError.Validation?`.
     */
    private fun validate(input: NotificationInput): AppError.Validation? =
        when {
            input.candidates.any { it.key.isBlank() } -> AppError.Validation(FIELD_KEY)
            input.history.any { it.sentAt > input.now } -> AppError.Validation(FIELD_HISTORY)
            else -> null
        }

    /**
     * Provenance (AI-ARC-003): both rules, and the window the counts were taken over. **No
     * confidence:** a policy decision is not an estimate.
     * Result: the provenance. Input: [input]. Output: [EngineProvenance].
     */
    private fun provenance(input: NotificationInput) =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence = listOf(NotificationRules.BUDGET, NotificationRules.QUIET),
            inputWindow =
                "${input.now.minusDays(input.rules.windowDays.toLong()).toLocalDate()}..${input.now.toLocalDate()}",
        )

    private companion object {
        const val ENGINE_ID = "AI-NTF"
        const val ENGINE_VERSION = "1.0"
        const val FIELD_KEY = "notification.key"
        const val FIELD_HISTORY = "notification.history"
    }
}
