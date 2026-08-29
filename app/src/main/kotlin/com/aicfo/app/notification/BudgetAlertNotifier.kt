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
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.core.model.NumericGuardrail
import com.aicfo.data.repository.CategoryBudgetAlert
import com.aicfo.domain.engines.budget.BudgetAlertBand
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a budget alert to the user (issue 4.5; FR-BUD-004).
 *
 * Why:  an interface for one implementation, because the implementation needs a `Context`, a
 *       registered channel and a granted runtime permission — none of which a JVM test can arrange
 *       honestly, and none of which is what [com.aicfo.app.work.BudgetAlertWorker]'s tests are
 *       about. They are about *which* alerts reach this and in what order, which a stand-in makes
 *       visible and a real notification manager hides. The same seam ARC-003 gives every engine.
 * What: one operation.
 * Result: the worker depends on the decision to post, not on Android's notification stack.
 * Changelog: 2026-08-13 — Created for issue 4.5.
 */
interface BudgetAlertNotifier {
    /**
     * Posts one alert, if the text checks out and the user allowed notifications.
     * Result: `true` when a notification was posted, `false` when it was suppressed — either because
     *         permission is absent or because the text failed the guardrail. The caller does not
     *         distinguish them; both mean the user was not told this way, which is a supported state
     *         rather than a failure (the in-app banner still shows the band).
     * Input:  [alert] — the band and every figure that may appear in the message;
     *         [blurAmounts] — issue 5.3's privacy blur. When `true` the message keeps the category
     *         and the band and carries **no figures at all**.
     * Output: [Boolean].
     *
     * **Why the flag is a parameter rather than a `SettingsStore` read in here.** A notification is
     * composed off the main thread from a worker that is already a coroutine, and the read is
     * `suspend`; taking it as an argument keeps this interface synchronous and keeps DataStore out
     * of the one class whose job is Android's notification stack. It also makes the blurred and
     * unblurred messages equally easy to assert.
     */
    fun notify(
        alert: CategoryBudgetAlert,
        blurAmounts: Boolean,
    ): Boolean
}

/**
 * Composes and posts a budget alert (issue 4.5; FR-BUD-004, AI-ARC-004).
 *
 * Why:  this is the first thing the app ever says to the user unprompted, and it says it with
 *       numbers. That makes it the strictest place in the app for P-03: every figure is formatted
 *       from a [CategoryBudgetAlert] the engine produced, and the composed text is then handed to
 *       [NumericGuardrail] before it is posted. If the text and the engine's values disagree — a
 *       translation that hard-coded a figure, a placeholder wired to the wrong argument — nothing is
 *       sent at all.
 *
 *       **Fail-closed is the whole point.** Posting unverified text would be worse than posting
 *       nothing: a notification arrives out of context, with no screen to tap through to, and the
 *       user has no way to check it.
 * What: string resources plus [MoneyFormatter], one guardrail call, one `notify`.
 * Result: a notification whose every figure is traceable to an engine result, or silence.
 * Changelog: 2026-08-13 — Created for issue 4.5.
 *
 * **Nothing here is logged** (`CfoPiiInLogs`, §21.6): a suppressed alert is dropped silently rather
 * than logged with the amounts that failed to verify, which would put the user's spending in logcat.
 *
 * Input:  [context] — the application context, for resources and the notification manager.
 * Output: an injectable notifier.
 */
@Singleton
internal class AndroidBudgetAlertNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : BudgetAlertNotifier {
        /**
         * Composes the message, verifies it, and posts.
         *
         * Why:    the permission is checked here rather than assumed, because a denial is a normal
         *         state this feature is designed for — the in-app banner carries the same information
         *         for a user who said no, so a refused post is not a failure and must not be reported
         *         as one.
         * Result: see [BudgetAlertNotifier.notify].
         * Input:  [alert]; [blurAmounts]. Output: [Boolean].
         * Changelog: 2026-08-16 — Issue 5.3: honours the privacy blur.
         */
        override fun notify(
            alert: CategoryBudgetAlert,
            blurAmounts: Boolean,
        ): Boolean {
            // Two separate refusals, and both are the user's: the runtime permission on API 33+, and
            // the per-app or per-channel switch in settings. Checking only the first would post into
            // a void the user explicitly closed.
            //
            // **Inline rather than extracted**, which reads worse and is deliberate: `MissingPermission`
            // only follows the check within one method, so hiding it behind a helper turns a
            // build-blocking lint error into a suppression — and a suppressed permission check is
            // exactly the thing that becomes wrong later without anyone noticing.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

            val (title, body) = compose(alert, blurAmounts)

            // Checked on the composed text, both halves, against exactly the values the engine
            // returned. A figure that is not in this list may not appear on the user's phone.
            //
            // The category name is declared as text, not as a figure: it is the user's own words,
            // and "Zone 3 parking" is an ordinary thing to call a budget. Without this the digit in
            // the name would read as an unverifiable count and this notification would be suppressed
            // — correct message, silently dropped, indistinguishable at this call site from a denied
            // permission (issue 4.6).
            val verified =
                NumericGuardrail.verify(
                    candidateText = "$title $body",
                    allowedAmounts = listOfNotNull(alert.alert.budgeted, alert.alert.spent, alert.alert.overspentBy),
                    allowedPercents = listOf(alert.alert.usedPercent),
                    allowedText = listOf(alert.category.name),
                )
            if (verified !is GuardrailResult.Pass) return false

            NotificationManagerCompat.from(context).notify(
                alert.budgetId.hashCode(),
                NotificationCompat.Builder(context, CfoNotifications.BUDGET_ALERTS_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(openBudgets())
                    .build(),
            )
            return true
        }

        /**
         * Builds the message for one band.
         * Why:    every figure is formatted from the alert's own values through the one sanctioned
         *         formatter, and none is computed here (P-03) — which is also what lets the guardrail
         *         above check the result against exactly those values. The two bands read differently
         *         on purpose: a warning names how far through the budget the user is, an overspend
         *         names how far past it, because those are the two different things they need to know.
         * Result: the title and the body. Input: [alert]; [blurAmounts]. Output: a pair of [String].
         * Changelog: 2026-08-16 — Issue 5.3: the figure-free variants.
         *
         * **The blurred variants carry no digits at all — not even the percentage.** A lock-screen
         * notification is the single most exposed surface this app has: it renders without the app
         * lock, to anyone who glances at the phone face-up on a table, which is precisely the
         * shoulder-surfing case §23 is about. Masking the on-screen figures while a notification
         * announced "You have spent ₹8,000 of ₹10,000" would leave the biggest hole open. The band
         * and the category survive, because a warning nobody can act on is not worth sending.
         */
        private fun compose(
            alert: CategoryBudgetAlert,
            blurAmounts: Boolean,
        ): Pair<String, String> {
            val exceeded = alert.alert.band == BudgetAlertBand.EXCEEDED
            if (blurAmounts) {
                val title =
                    if (exceeded) {
                        context.getString(R.string.budget_alert_exceeded_title, alert.category.name)
                    } else {
                        context.getString(R.string.budget_alert_warn_title_blurred, alert.category.name)
                    }
                return title to context.getString(R.string.budget_alert_body_blurred)
            }

            val budgeted = MoneyFormatter.format(alert.alert.budgeted)
            val spent = MoneyFormatter.format(alert.alert.spent)
            return if (exceeded) {
                val overspent = MoneyFormatter.format(alert.alert.overspentBy ?: Money.ZERO)
                context.getString(R.string.budget_alert_exceeded_title, alert.category.name) to
                    context.getString(R.string.budget_alert_exceeded_body, spent, budgeted, overspent)
            } else {
                context.getString(
                    R.string.budget_alert_warn_title,
                    alert.category.name,
                    alert.alert.usedPercent,
                ) to context.getString(R.string.budget_alert_warn_body, spent, budgeted)
            }
        }

        /**
         * The tap target: the budgets screen.
         * Why:    P-02 — a notification that states a figure has to lead somewhere the user can see
         *         the working. `CLEAR_TOP` rather than a new task, so tapping it lands in the app the
         *         user already has rather than a second copy of it.
         * Result: the pending intent. Input: none. Output: [PendingIntent].
         */
        private fun openBudgets(): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
