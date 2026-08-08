package com.aicfo.app.sms

import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.data.repository.SmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Erases what the app inferred, the moment the user withdraws permission to infer it (issue 3.9).
 *
 * Why:  `SmsRepository.onConsentRevoked()` existed with **no caller**, which made P-01's "revocable"
 *       a half-promise: turning the consent off stopped all *reading* — the gate is unconditional —
 *       but the drafts already parsed stayed on disk. Revocable has to mean the inference goes, not
 *       that a switch flipped while the app keeps what it worked out.
 *
 *       **A watcher rather than a call at each revoke site**, and that is the whole design decision.
 *       Today there is exactly one place that revokes (onboarding's toggle) and it cannot have
 *       drafts yet, so calling the purge there would be dead code that looks like a guarantee. The
 *       consents dashboard (issue 5.x) will be the second, and a settings screen the third. Every
 *       one of them would have to remember — and the one that forgot would leave the user's
 *       inferences behind with nothing to catch it. Observing the *state* rather than the *event*
 *       means the purge happens however the consent came to be off, including a revocation written
 *       by a build that has not been released yet.
 * What: watches `ConsentFeature.SMS_PARSING` and purges on a granted → revoked transition.
 * Result: revoking, from anywhere, erases the pending drafts and resets the scan cursor.
 * Changelog: 2026-08-07 — Created for issue 3.9, closing the gap ADR-0013 recorded.
 *
 * **Only on the transition, not on every emission that reads "off".** A user who has never opted in
 * emits `false` on every launch, and purging then would run a delete against the database on every
 * cold start for the overwhelming majority of users, who have no drafts at all. It would also fire
 * before the app is unlocked, where the database provider throws by design (SEC-002).
 *
 * Input:  [consents] — the ledger to watch; [repository] — deferred, see [start]; [scope] — the
 *         application scope the collector lives on (injected, never `GlobalScope` — ARC-006).
 * Output: a watcher `CfoApplication` starts once.
 */
@Singleton
class SmsConsentWatcher
    @Inject
    constructor(
        private val consents: ConsentStore,
        /**
         * Deferred for the reason `ProfileZoneProvider` defers its store: resolving the repository
         * eagerly would build the database — which is gated on the session lock and **throws while
         * the app is locked** (SEC-002). Constructing this watcher must not be able to take the
         * process down before the user has even unlocked.
         */
        private val repository: Provider<SmsRepository>,
        private val scope: CoroutineScope,
    ) {
        /**
         * Starts watching.
         * Why:    called from `CfoApplication.onCreate`, the same seam `ProfileZoneProvider.start()`
         *         uses, so the app has one list of things that begin at launch.
         *
         *         The first emission only records the starting point; it never purges. Otherwise
         *         every launch of an app whose owner has not opted in would issue a delete, and it
         *         would do so before unlock.
         * Result: a collector on [scope] that outlives every screen. Input: none. Output: none.
         */
        fun start() {
            scope.launch {
                var wasGranted: Boolean? = null
                consents.observe(ConsentFeature.SMS_PARSING).collectLatest { state ->
                    // An unreadable ledger reads as not granted everywhere else in this feature, and
                    // it must not read as a *revocation* here — a transient read failure would then
                    // delete the user's pending drafts. So a failure is simply not a transition.
                    val granted = (state as? com.aicfo.core.common.Ok)?.value?.granted ?: return@collectLatest
                    if (wasGranted == true && !granted) repository.get().onConsentRevoked()
                    wasGranted = granted
                }
            }
        }
    }
