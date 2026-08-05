package com.aicfo.feature.onboarding

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.crypto.PinVerifier
import com.aicfo.core.datastore.AppLockStore
import javax.inject.Inject

/**
 * The onboarding security step's two collaborators, behind one (issue 2.2; SEC-002, FR-ONB-001).
 *
 * Why:  setting a PIN and enabling the lock are not two independent writes — **the order between
 *       them is a security property**, and an ordering rule split across two injected dependencies
 *       is one a future refactor can reverse without noticing. Keeping both here puts the rule and
 *       the things it orders in the same file, next to the reason.
 * What: the PIN write and the enable flag, applied in the only safe order.
 * Result: the ViewModel asks for "apply the security decision" rather than sequencing it itself.
 * Changelog: 2026-07-27 — Extracted from OnboardingViewModel during issue 2.3, unchanged in
 *            behaviour; the class had accumulated eight constructor parameters and two of them
 *            only ever moved together.
 *
 * Input:  [pinVerifier] — stores the PIN as a Keystore-bound MAC, never as typed;
 *         [appLockStore] — the persisted lock flag.
 * Output: a collaborator the ViewModel injects.
 */
class AppLockSetup
    @Inject
    constructor(
        private val pinVerifier: PinVerifier,
        private val appLockStore: AppLockStore,
    ) {
        /**
         * Sets the PIN and enables the lock, if the user asked for one (SEC-002).
         * Why:    **the PIN is written before the lock is enabled, never the other way round.** A
         *         lock switched on whose `setPin` then failed would leave the user staring at a
         *         prompt no PIN opens, on an app they have just finished setting up. This ordering
         *         makes the worst case a lock that is off — which they can simply turn on again.
         * What:   `setPin` then `setEnabled(true)`, or nothing at all when the step was declined.
         * Result: `Ok(Unit)` when there was nothing to do or both writes succeeded; the first
         *         failure otherwise, which stops the caller before anything is marked complete.
         * Input:  [enabled] — whether the user asked for a lock; [pin] — what they typed.
         * Output: `Result<Unit, AppError>`.
         */
        suspend fun apply(
            enabled: Boolean,
            pin: String,
        ): Result<Unit, AppError> =
            if (!enabled) {
                Ok(Unit)
            } else {
                pinVerifier.setPin(pin).flatMap { appLockStore.setEnabled(true) }
            }
    }
