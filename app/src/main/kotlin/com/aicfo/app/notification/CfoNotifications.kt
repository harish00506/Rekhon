package com.aicfo.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.aicfo.app.R

/**
 * The app's notification channels (issue 4.5; FR-BUD-004).
 *
 * Why:  budget alerts are the first thing this app ever sends unprompted, and a channel is how
 *       Android lets the user disagree with that at a finer grain than "all notifications off". One
 *       channel per *kind* of message, so muting budget warnings never silences a security alert
 *       later.
 *
 *       **Created at process start, not at send time.** A channel that appears only after the first
 *       notification cannot be found in system settings before then, so a user who wants to turn it
 *       down in advance has nothing to turn down. Creating one that already exists is a no-op, which
 *       is what makes calling this on every launch safe.
 * What: one channel id and the registration call.
 * Result: the budget alert has somewhere to go, and the user has somewhere to object.
 * Changelog: 2026-08-13 — Created for issue 4.5.
 *            2026-08-17 — Issue 6.1 added two card channels. Not one: SRS §17.6 (NTF-006) requires
 *            each Android channel to map 1:1 to a row of §17.1's taxonomy, and a card payment is a
 *            **Critical money event** while a utilisation warning is discipline. They are also the
 *            two the user is most likely to want to treat differently — "remind me to pay" and
 *            "tell me I am using too much" are not the same request.
 *
 * **`IMPORTANCE_DEFAULT`, deliberately not `HIGH`.** A budget crossing 80% is worth telling someone
 * about; it is not worth a heads-up notification that covers what they are doing. Issue 9.6 owns
 * notification policy proper — quiet hours and rate limiting across features — and will revisit this.
 */
object CfoNotifications {
    /** The budget-alert channel (FR-BUD-004). Stable — renaming it would orphan the user's setting. */
    const val BUDGET_ALERTS_CHANNEL_ID = "budget-alerts"

    /**
     * Critical money events (§17.1) — currently the card payment reminder (issue 6.1, FR-ACC-002).
     *
     * The one channel in this app that is `IMPORTANCE_HIGH`, and the SRS asks for it: §17.1 lists
     * "card due with amount" as Critical, exempt from NTF-001's daily budget and allowed to bypass
     * quiet hours. A missed card payment costs a late fee, interest on the whole statement at
     * 36-42% APR, and a credit-report mark — this is the one interruption that earns its place.
     *
     * Named for the taxonomy row rather than for cards, because §17.1's other Critical events
     * (crunch-day warning, bill due tomorrow) belong here too when they are built.
     */
    const val CRITICAL_MONEY_CHANNEL_ID = "critical-money-events"

    /**
     * Debt discipline (§17.1) — currently the card utilisation warning (issue 6.1, RULE-CC-UTIL).
     *
     * Separate from [BUDGET_ALERTS_CHANNEL_ID] even though both are "you are spending a lot",
     * because muting one should not mute the other: a user who has made peace with overshooting a
     * dining budget has not thereby asked to stop hearing that their card is at 70% of its limit.
     * `IMPORTANCE_DEFAULT` — worth saying, not worth covering the screen.
     */
    const val DEBT_DISCIPLINE_CHANNEL_ID = "debt-discipline"

    /**
     * Registers every channel this app uses.
     * Why:    idempotent by design — Android treats re-creating an existing channel as a no-op and,
     *         importantly, does **not** reset a user's changes to it. So this can be called on every
     *         launch without ever overriding someone's decision to mute it.
     * Result: the channels exist. Input: [context] — any context. Output: none.
     */
    fun createChannels(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                BUDGET_ALERTS_CHANNEL_ID,
                context.getString(R.string.notification_channel_budget_alerts),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_budget_alerts_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CRITICAL_MONEY_CHANNEL_ID,
                context.getString(R.string.notification_channel_critical_money),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_critical_money_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                DEBT_DISCIPLINE_CHANNEL_ID,
                context.getString(R.string.notification_channel_debt_discipline),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_debt_discipline_description)
            },
        )
    }
}
