package com.aicfo.app.di

import com.aicfo.app.notification.AndroidBudgetAlertNotifier
import com.aicfo.app.notification.AndroidCardAlertNotifier
import com.aicfo.app.notification.BudgetAlertNotifier
import com.aicfo.app.notification.CardAlertNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the notification collaborators (issue 4.5; FR-BUD-004).
 *
 * Why:  `AndroidBudgetAlertNotifier` is `internal` (ARC-003), so the graph needs a binding to hand
 *       the interface to `BudgetAlertWorker`. Its own file rather than [PlatformModule] because the
 *       question a reviewer asks here is different — "what can interrupt the user?" is a shorter and
 *       more consequential list than "what touches the device", and it should stay that way.
 * What: one `@Binds`.
 * Result: the worker injects an interface and never names an implementation.
 * Changelog: 2026-08-13 — Created for issue 4.5.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface NotificationModule {
    /** Result: the notifier. Input: [implementation]. Output: [BudgetAlertNotifier]. */
    @Binds
    @Singleton
    fun budgetAlertNotifier(implementation: AndroidBudgetAlertNotifier): BudgetAlertNotifier

    /**
     * Result: the card notifier (issue 6.1). Input: [implementation]. Output: [CardAlertNotifier].
     *
     * A second binding rather than one notifier with a `when`: the two compose different sentences
     * from different result types and post to different channels, and the only thing they share is
     * the permission dance — which is nine lines that must stay inline anyway, or Android's
     * `MissingPermission` lint stops seeing them.
     */
    @Binds
    @Singleton
    fun cardAlertNotifier(implementation: AndroidCardAlertNotifier): CardAlertNotifier
}
