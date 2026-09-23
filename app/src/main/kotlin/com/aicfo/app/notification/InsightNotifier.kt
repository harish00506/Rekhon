package com.aicfo.app.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aicfo.app.MainActivity
import com.aicfo.app.R
import com.aicfo.core.common.getOrNull
import com.aicfo.core.model.DateFormatter
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.domain.engines.guardrail.GuardrailEngine
import com.aicfo.domain.engines.guardrail.GuardrailEvidence
import com.aicfo.domain.engines.guardrail.GuardrailInput
import com.aicfo.domain.engines.guardrail.GuardrailVerdict
import com.aicfo.domain.engines.insight.Insight
import com.aicfo.domain.engines.insight.InsightType
import com.aicfo.domain.engines.notification.NotificationKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which insights may become a notification, and under which §17.1 row (issue 9.6; ADR-0047).
 *
 * Why:  §7.2 hands the feed to AI-NTF, but most of the feed is not something to interrupt anyone
 *       about, and §17.1 says which. A crunch day is **Critical**. A goal falling behind is a **goal
 *       event**, on by default. An overspent budget is already told by `BudgetAlertWorker` on its
 *       own channel, and saying it twice would spend two of the day's ration on one fact. The
 *       emergency-fund, seasonal and health-lever cards are §17.1's "AI insights" row, whose
 *       per-insight notifications are **off by default** — they belong to the weekly digest, which
 *       ADR-0047 defers — so they stay in the feed and are not posted.
 * What: the mapping and the key.
 * Result: the worker offers the gate exactly the insights §17.1 would let through by default.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
object InsightNotifications {
    /**
     * The row an insight is posted under.
     * Result: the kind, or `null` for an insight §17.1 does not notify by default.
     * Input:  [type]. Output: `NotificationKind?`.
     */
    fun kindFor(type: InsightType): NotificationKind? =
        when (type) {
            InsightType.CRUNCH_DAY -> NotificationKind.CRITICAL_MONEY
            InsightType.GOAL_BEHIND -> NotificationKind.GOAL_EVENT
            InsightType.BUDGET_OVERSPENT,
            InsightType.EMERGENCY_FUND_SHORT,
            InsightType.SEASONAL_MONTH,
            InsightType.HEALTH_LEVER,
            -> null
        }

    /**
     * What makes this notification this notification — the gate never sends a key twice.
     * Why:    the insight's own fingerprint, except for a crunch. A crunch's period is the day it was
     *         computed, so its fingerprint changes daily; keyed that way a crunch that persisted
     *         would notify every morning, on the one channel that ignores the caps. Keyed by the
     *         **first crunch day** it is said once per crunch, and a new, earlier crunch is news.
     * Result: the key. Input: [insight]. Output: [String].
     */
    fun keyFor(insight: Insight): String =
        when (insight.type) {
            InsightType.CRUNCH_DAY -> "insight:${insight.type.name}|${insight.date ?: insight.period}"
            else -> "insight:${insight.fingerprint}"
        }
}

/**
 * Posts an insight to the user (issue 9.6; §7.2 stage 6, §17.1).
 *
 * Why:  the same seam `BudgetAlertNotifier` gives its worker — the real one needs a context, a
 *       channel and a granted permission, and the worker's tests are about *which* insights reach
 *       it, which a stand-in shows and a notification manager hides.
 * What: one operation.
 * Result: the worker depends on the decision to post, not on Android's notification stack.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 */
interface InsightNotifier {
    /**
     * Posts one insight, if the text checks out and the user allowed notifications.
     * Result: `true` when posted; `false` when there is no permission, the channel is off, the
     *         guardrail refused the text, or the insight is not one this app words as a notification.
     * Input:  [insight]; [kind] — its §17.1 row, which picks the channel; [blurAmounts] — NTF-004 /
     *         issue 5.3: when `true` the message carries **no digits at all**, dates included.
     * Output: [Boolean].
     */
    fun notify(
        insight: Insight,
        kind: NotificationKind,
        blurAmounts: Boolean,
    ): Boolean
}

/**
 * Composes, guardrails and posts an insight (issue 9.6; AI-ARC-004, P-03, NTF-003/004).
 *
 * Why:  an insight is the orchestrator's figure, and a notification is that figure arriving with no
 *       screen around it. So the rule `AndroidBudgetAlertNotifier` records applies with full force:
 *       every figure is formatted from the insight's own fields, the text is checked against exactly
 *       those, and a mismatch posts **nothing**.
 * What: string resources, [MoneyFormatter] and [DateFormatter], one guardrail call, one `notify`.
 * Result: a notification whose every figure is traceable to an engine result, or silence.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 *
 * **Nothing here is logged** (`CfoPiiInLogs`, §21.6).
 *
 * Input:  [context] — the application context. Output: an injectable notifier.
 */
@Singleton
internal class AndroidInsightNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val guardrail: GuardrailEngine,
    ) : InsightNotifier {
        /**
         * Composes the message, verifies it, and posts.
         * Why:    the two permission checks stay inline for the reason `AndroidBudgetAlertNotifier`
         *         gives — `MissingPermission` only follows a check within one method.
         * Result: see [InsightNotifier.notify]. Input: [insight]; [kind]; [blurAmounts].
         * Output: [Boolean].
         */
        override fun notify(
            insight: Insight,
            kind: NotificationKind,
            blurAmounts: Boolean,
        ): Boolean {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

            val (title, body) = compose(insight, blurAmounts) ?: return false
            if (!verified(
                    "$title $body",
                    GuardrailEvidence(
                        amounts = listOfNotNull(insight.amount, insight.secondary),
                        counts = listOfNotNull(insight.quantity),
                        dates = dates(insight),
                        names = listOfNotNull(insight.subjectLabel),
                    ),
                )
            ) {
                return false
            }

            NotificationManagerCompat.from(context).notify(
                InsightNotifications.keyFor(insight).hashCode(),
                NotificationCompat.Builder(context, kind.channelId)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    // NTF-004's second half: even an unblurred message shows only its title on a
                    // locked screen. The blur is for the user who also wants the title figure-free.
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .setContentIntent(openApp())
                    .build(),
            )
            return true
        }

        /**
         * Whether the composed text may be shown (AI-ARC-004).
         * Why:    the check runs on the **composed** text, not on the arguments: a resource string
         *         edited to add a figure would sail past any check on the values alone.
         * Result: `true` when every figure resolves to one the engine produced.
         * Input:  [text] — title and body; [evidence] — what the engine produced. Output: [Boolean].
         */
        private fun verified(
            text: String,
            evidence: GuardrailEvidence,
        ): Boolean = guardrail.verify(GuardrailInput(text, evidence)).getOrNull() is GuardrailVerdict.Pass

        /**
         * The words for one insight.
         * Result: the title and the body, or `null` for a type this notifier does not word.
         * Input:  [insight]; [blurAmounts]. Output: `Pair<String, String>?`.
         */
        private fun compose(
            insight: Insight,
            blurAmounts: Boolean,
        ): Pair<String, String>? =
            when (insight.type) {
                InsightType.CRUNCH_DAY -> crunch(insight, blurAmounts)
                InsightType.GOAL_BEHIND -> goal(insight, blurAmounts)
                else -> null
            }

        /** Result: the crunch-day words (the dashboard card's, shortened). Input: [insight]; [blurAmounts]. */
        private fun crunch(
            insight: Insight,
            blurAmounts: Boolean,
        ): Pair<String, String> {
            if (blurAmounts) {
                return context.getString(R.string.insight_crunch_title_blurred) to
                    context.getString(R.string.insight_crunch_body_blurred)
            }
            val days = insight.quantity ?: 1
            val title =
                context.getString(
                    R.string.insight_crunch_title,
                    day(insight.date?.toString() ?: insight.period),
                )
            val body =
                context.resources.getQuantityString(
                    R.plurals.insight_crunch_body,
                    days,
                    days,
                    MoneyFormatter.format(insight.amount ?: Money.ZERO),
                    MoneyFormatter.format(insight.secondary ?: Money.ZERO),
                )
            return title to body
        }

        /** Result: the goal-behind words. Input: [insight]; [blurAmounts]. */
        private fun goal(
            insight: Insight,
            blurAmounts: Boolean,
        ): Pair<String, String> {
            val title = context.getString(R.string.insight_goal_title, insight.subjectLabel.orEmpty())
            if (blurAmounts) return title to context.getString(R.string.insight_goal_body_blurred)
            return title to
                context.getString(
                    R.string.insight_goal_body,
                    MoneyFormatter.format(insight.amount ?: Money.ZERO),
                    day(insight.period),
                )
        }

        /**
         * The dates this insight's words may name (GRD-004).
         * Why:    handed over as dates rather than as rendered strings: which renderings count as
         *         the same day is AI-GRD's to decide, and a notifier that pre-rendered them would be
         *         quietly widening the allowlist to whatever it happened to format.
         * Result: the crunch day and the period, when the period is a day. Input: [insight].
         * Output: `List<LocalDate>`.
         */
        private fun dates(insight: Insight): List<LocalDate> =
            listOfNotNull(insight.date, insight.period.takeIf(DateFormatter::isCalendarDate)?.let(LocalDate::parse))

        /** Result: [isoDate] rendered for a person. */
        private fun day(isoDate: String): String = DateFormatter.day(isoDate)

        /**
         * The tap target (NTF-003): the dashboard, whose feed shows this card with its evidence (P-02).
         * Result: the pending intent. Input: none. Output: [PendingIntent].
         */
        private fun openApp(): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
