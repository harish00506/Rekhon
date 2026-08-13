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
 *
 * **`IMPORTANCE_DEFAULT`, deliberately not `HIGH`.** A budget crossing 80% is worth telling someone
 * about; it is not worth a heads-up notification that covers what they are doing. Issue 9.6 owns
 * notification policy proper — quiet hours and rate limiting across features — and will revisit this.
 */
object CfoNotifications {
    /** The budget-alert channel (FR-BUD-004). Stable — renaming it would orphan the user's setting. */
    const val BUDGET_ALERTS_CHANNEL_ID = "budget-alerts"

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
    }
}
