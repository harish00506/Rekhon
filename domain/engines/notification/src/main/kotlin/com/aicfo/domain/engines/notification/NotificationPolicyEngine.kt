package com.aicfo.domain.engines.notification

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import java.time.LocalDateTime

/**
 * AI-NTF — what may interrupt the user, and when (issue 9.6; SRS §17.1, §17.2, NTF-001/002/005).
 *
 * Why:  an app that can interrupt has to ration the interruptions. §17.2 sets the ration: two
 *       non-critical notifications a day and eight a week, nothing non-critical between 22:00 and
 *       08:00, and critical money events exempt from both. Getting that wrong costs more than a
 *       missed message — the user turns the channel off, and then the one notification that
 *       mattered never arrives either.
 * What: the decision, and only the decision. It posts nothing, reads no clock and knows no Android:
 *       the caller supplies the local time, what has already been sent, and the candidates; it
 *       answers deliver, wait until, or fold into the weekly digest.
 * Result: a [NotificationPlan], one decision per candidate, in the order they were offered.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 *
 * Input:  [NotificationInput]. Output: `Result<NotificationPlan, AppError>`; `Err` only for an
 *         impossible input (a history entry in the future, a candidate with no key).
 */
interface NotificationPolicyEngine {
    /** Decides each candidate. See the interface's doc. */
    fun decide(input: NotificationInput): Result<NotificationPlan, AppError>
}

/**
 * Builds the one [NotificationPolicyEngine] (ARC-003 — the implementation stays `internal`).
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
object NotificationPolicyEngineFactory {
    /** Result: the §17.2 policy engine. Input: none. Output: [NotificationPolicyEngine]. */
    fun create(): NotificationPolicyEngine = BudgetedNotificationPolicyEngine()
}

/**
 * §17.1's taxonomy. **Each row is one Android channel** (NTF-006), so the OS-level switch and the
 * in-app one are the same switch.
 *
 * Input:  [critical] — whether §17.1 classes the row as a critical money event, which is what the
 *         caps and the quiet hours exempt; [channelId] — the Android channel, a stable string:
 *         renaming one would orphan the user's settings.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
enum class NotificationKind(
    val critical: Boolean,
    val channelId: String,
) {
    /** Crunch-day warning, bill due tomorrow, card due with an amount. */
    CRITICAL_MONEY(critical = true, channelId = "critical-money-events"),

    /** 80%/100% budget alerts, pace warnings. */
    BUDGET_DISCIPLINE(critical = false, channelId = "budget-alerts"),

    /** Utilisation and revolving warnings — §17.1's "Budget & discipline" row, on the debt side. */
    DEBT_DISCIPLINE(critical = false, channelId = "debt-discipline"),

    /** The weekly digest, seasonal warnings, idle cash, a health-score change. */
    AI_INSIGHT(critical = false, channelId = "ai-insights"),

    /** Vehicle service, insurance, PUC, a subscription price rise. */
    MAINTENANCE(critical = false, channelId = "maintenance-renewals"),

    /** Milestones, off-track, the contribution reminder on salary day. */
    GOAL_EVENT(critical = false, channelId = "goal-events"),

    /** The daily capture reminder and the uncategorised backlog. Off by default (§17.1). */
    HABIT(critical = false, channelId = "habit-capture"),
}

/**
 * Something the app would like to say.
 * Input:  [key] — what makes this message this message (an insight's fingerprint, an alert's id);
 *         the policy never sends the same key twice; [kind]; [subject] — carried through for the
 *         caller's own use, never read here.
 * Output: an immutable value.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
data class NotificationCandidate(
    val key: String,
    val kind: NotificationKind,
    val subject: String? = null,
)

/**
 * One notification the app has already sent.
 * Input:  [key]; [kind]; [sentAt] — local time in the profile's zone (TIM-001 resolves the zone
 *         before this engine sees it). Output: an immutable value.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
data class SentNotification(
    val key: String,
    val kind: NotificationKind,
    val sentAt: LocalDateTime,
)

/**
 * What the policy decided about one candidate.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
enum class NotificationOutcome {
    /** Send it now. */
    DELIVER,

    /** Right message, wrong hour: hold it until the quiet hours end (NTF-002). */
    WAIT_FOR_QUIET_HOURS,

    /** The day's or the week's ration is spent: it goes into the weekly digest instead (NTF-001). */
    FOLD_INTO_DIGEST,

    /** This key has already been sent; saying it twice is not saying it again. */
    ALREADY_SENT,
}

/**
 * The decision for one candidate.
 * Input:  [candidate]; [outcome]; [deliverAfter] — when the wait ends, set only for
 *         [NotificationOutcome.WAIT_FOR_QUIET_HOURS]; [citations] — the rules that decided it.
 * Output: an immutable value.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
data class NotificationDecision(
    val candidate: NotificationCandidate,
    val outcome: NotificationOutcome,
    val deliverAfter: LocalDateTime? = null,
    val citations: List<com.aicfo.core.model.RuleCitation> = emptyList(),
)

/**
 * What AI-NTF reads.
 * Input:  [candidates] — in the order the caller wants them considered, which for the feed is
 *         RULE-INS-RANK's order, so the ration is spent on the most important first; [history] —
 *         what has been sent, at least over the rule's window; [now] — local time in the profile's
 *         zone; [nowUtcMillis] — stamped on provenance; [rules].
 * Output: an immutable value.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
data class NotificationInput(
    val candidates: List<NotificationCandidate>,
    val history: List<SentNotification>,
    val now: LocalDateTime,
    val nowUtcMillis: Long,
    val rules: NotificationRules = NotificationRules(),
)

/**
 * The plan (issue 9.6).
 * Input:  [decisions] — one per candidate, in the order offered; [provenance].
 * Output: an immutable value.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
data class NotificationPlan(
    val decisions: List<NotificationDecision>,
    val provenance: EngineProvenance,
) {
    /** Result: the candidates to post now, in order. */
    val deliverable: List<NotificationCandidate>
        get() = decisions.filter { it.outcome == NotificationOutcome.DELIVER }.map { it.candidate }
}
