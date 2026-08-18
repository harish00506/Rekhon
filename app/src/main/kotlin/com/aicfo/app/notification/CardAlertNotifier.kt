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
import com.aicfo.core.model.GuardrailResult
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.core.model.NumericGuardrail
import com.aicfo.data.repository.CardAlertForAccount
import com.aicfo.domain.engines.card.CardAlertKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a credit-card alert to the user (issue 6.1; FR-ACC-002, §17.1).
 *
 * Why:  an interface for one implementation, for the reason [BudgetAlertNotifier] gives: the
 *       implementation needs a `Context`, a registered channel and a granted runtime permission,
 *       none of which a JVM test can arrange honestly — and none of which is what
 *       [com.aicfo.app.work.CardAlertWorker]'s tests are about. They are about *which* alerts reach
 *       this and in what order.
 * What: one operation.
 * Result: the worker depends on the decision to post, not on Android's notification stack.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
interface CardAlertNotifier {
    /**
     * Posts one card alert, if the text checks out and the user allowed notifications.
     * Result: `true` when a notification was posted, `false` when it was suppressed — permission
     *         absent, or the text failed the guardrail. The caller does not distinguish them; both
     *         mean the user was not told this way, which is a supported state rather than a failure.
     * Input:  [alert] — the kind, the card's name, and every figure that may appear;
     *         [blurAmounts] — issue 5.3's privacy blur. When `true` the message keeps the card's
     *         name and carries **no digits at all**, per NTF-004.
     * Output: [Boolean].
     */
    fun notify(
        alert: CardAlertForAccount,
        blurAmounts: Boolean,
    ): Boolean
}

/**
 * Composes and posts a card alert (issue 6.1; AI-ARC-004, §17.1).
 *
 * Why:  the strictest place in the app for P-03 after the budget notifier, and for a harder reason:
 *       this one can say "pay ₹70,000 by Thursday". Every figure is formatted from the engine's own
 *       result and the composed text is handed to [NumericGuardrail] before it is posted. If the
 *       text and the engine's values disagree — a placeholder wired to the wrong argument, a
 *       translation that hard-coded a number — nothing is sent at all.
 *
 *       **Fail-closed, and here it matters more than for budgets.** A wrong amount in a payment
 *       reminder is a user paying the wrong amount.
 * What: string resources plus [MoneyFormatter], one guardrail call, one `notify` on the channel
 *       §17.1 assigns to that kind of message.
 * Result: a notification whose every figure is traceable to an engine result, or silence.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * **Nothing here is logged** (`CfoPiiInLogs`, §21.6): a suppressed alert is dropped silently rather
 * than logged with the amounts that failed to verify, which would put the user's debts in logcat.
 *
 * Input:  [context] — the application context, for resources and the notification manager.
 * Output: an injectable notifier.
 */
@Singleton
internal class AndroidCardAlertNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CardAlertNotifier {
        /**
         * Composes the message, verifies it, and posts it on the right channel.
         *
         * Why:    the channel is chosen by [CardAlertKind] rather than fixed, because NTF-006 maps
         *         one channel per §17.1 taxonomy row and these two alerts sit in different rows —
         *         which is the whole reason a user can mute one and keep the other.
         * Result: see [CardAlertNotifier.notify].
         * Input:  [alert]; [blurAmounts]. Output: [Boolean].
         */
        override fun notify(
            alert: CardAlertForAccount,
            blurAmounts: Boolean,
        ): Boolean {
            // Two separate refusals, both the user's: the runtime permission on API 33+, and the
            // per-app or per-channel switch. **Inline rather than extracted**, which reads worse and
            // is deliberate — `MissingPermission` only follows the check within one method, so a
            // helper would turn a build-blocking lint error into a suppression.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

            val (title, body) = compose(alert, blurAmounts)

            // Checked on the composed text against exactly the values the engine returned. A figure
            // not in this list may not appear on the user's phone.
            //
            // The card's name is declared as text, not as a figure: it is the user's own words, and
            // "HDFC Regalia 4521" is an ordinary thing to call a card. Without this the digits in
            // the name would read as an unverifiable count and a correct reminder would be silently
            // dropped — the failure issue 4.6 hit with category names.
            val verified =
                NumericGuardrail.verify(
                    candidateText = "$title $body",
                    allowedAmounts = listOfNotNull(alert.alert.amount, alert.alert.minimumDue, alert.alert.creditLimit),
                    allowedPercents = listOfNotNull(alert.alert.ratioBps?.let { alert.alert.usedPercent }),
                    allowedText = listOf(alert.accountName),
                    allowedCounts = listOf(alert.alert.daysUntilDue),
                )
            if (verified !is GuardrailResult.Pass) return false

            NotificationManagerCompat.from(context).notify(
                // The kind is part of the id, not just the account: a card that is both due and over
                // its limit posts two notifications, and a shared id would have the second silently
                // replace the first.
                (alert.alert.accountId + alert.alert.kind.name).hashCode(),
                NotificationCompat.Builder(context, channelFor(alert.alert.kind))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(openAccounts())
                    .build(),
            )
            return true
        }

        /**
         * Builds the message for one alert.
         * Why:    every figure is formatted from the alert's own values through the one sanctioned
         *         formatter, and none is computed here (P-03) — which is what lets the guardrail
         *         check the result against exactly those values.
         * Result: the title and the body.
         * Input:  [alert]; [blurAmounts]. Output: a pair of [String].
         *
         * **The blurred variants carry no digits at all**, not even the day count or the percentage
         * (NTF-004). A lock-screen notification renders without the app lock, to anyone who glances
         * at a phone face-up on a table — precisely the shoulder-surfing case §23 is about. The
         * card's name survives, because a reminder nobody can act on is not worth sending.
         *
         * **"Due today" is its own string, not "due in 0 days".** The zero case is the one that
         * matters most and the one a plural would render worst.
         */
        private fun compose(
            alert: CardAlertForAccount,
            blurAmounts: Boolean,
        ): Pair<String, String> {
            val name = alert.accountName
            val due = alert.alert.kind == CardAlertKind.DUE_SOON

            if (blurAmounts) {
                return if (due) {
                    context.getString(R.string.card_due_title_blurred, name) to
                        context.getString(R.string.card_due_body_blurred)
                } else {
                    context.getString(R.string.card_utilisation_title_blurred, name) to
                        context.getString(R.string.card_utilisation_body_blurred)
                }
            }

            val amount = MoneyFormatter.format(alert.alert.amount)
            return if (due) {
                val title =
                    if (alert.alert.daysUntilDue == 0) {
                        context.getString(R.string.card_due_title_today, name)
                    } else {
                        context.getString(R.string.card_due_title, name, alert.alert.daysUntilDue)
                    }
                val body =
                    alert.alert.minimumDue
                        ?.let { context.getString(R.string.card_due_body, amount, MoneyFormatter.format(it)) }
                        ?: context.getString(R.string.card_due_body_no_minimum, amount)
                title to body
            } else {
                context.getString(R.string.card_utilisation_title, name, alert.alert.usedPercent) to
                    context.getString(
                        R.string.card_utilisation_body,
                        amount,
                        MoneyFormatter.format(alert.alert.creditLimit),
                    )
            }
        }

        /**
         * Picks the channel §17.1 assigns to this kind of message.
         * Why:    NTF-006 — one channel per taxonomy row, so the OS-level switch matches what the
         *         user thinks they are turning off. A payment due is a Critical money event; a
         *         utilisation warning is discipline, and muting the second must not mute the first.
         * Result: the channel id. Input: [kind]. Output: [String].
         */
        private fun channelFor(kind: CardAlertKind): String =
            when (kind) {
                CardAlertKind.DUE_SOON -> CfoNotifications.CRITICAL_MONEY_CHANNEL_ID
                CardAlertKind.UTILISATION -> CfoNotifications.DEBT_DISCIPLINE_CHANNEL_ID
            }

        /**
         * The tap target: the accounts screen, where the card and its working live.
         * Why:    P-02 and NTF-003 — a notification that states a figure has to lead somewhere the
         *         user can see how it was reached. `CLEAR_TOP` rather than a new task, so tapping it
         *         lands in the app the user already has rather than a second copy of it.
         * Result: the pending intent. Input: none. Output: [PendingIntent].
         */
        private fun openAccounts(): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
