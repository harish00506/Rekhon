package com.aicfo.feature.onboarding

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.datastore.OnboardingProfile
import com.aicfo.core.datastore.SettingsStore
import javax.inject.Inject

/**
 * The two DataStore writes onboarding makes, and the order they must happen in (issues 2.1, 2.4).
 *
 * Why:  these two writes are only correct **together**, and the reason is not visible from either
 *       one. The consent decision goes first so that a failed profile save leaves nothing marked
 *       complete and the user retries from a clean state; the profile write carries the completion
 *       flag, so it is deliberately last — whatever fails, the app is never "onboarded" without a
 *       time zone (TIM-001), which is the one thing nothing ever asks again.
 *
 *       Extracted from `OnboardingViewModel` for issue 2.4, when adding demo mode pushed that class
 *       past detekt's parameter and function limits. The seam is the same one `AppLockSetup` and
 *       `QuickSetupCoordinator` already sit on: a step's writes, and the invariant between them,
 *       owned by the class that performs them rather than described in a comment on a screen
 *       controller.
 * What: one call, from the answered consent decision and the captured profile to two ordered writes.
 * Result: the ViewModel deals in "apply what the user answered" rather than in write ordering.
 * Changelog: 2026-07-28 — Created for issue 2.4, extracted from OnboardingViewModel (issue 2.1).
 *
 * Input:  [settingsStore] — where the profile and the completion flag land; [consentStore] — the
 *         P-01 ledger. Output: a collaborator the ViewModel injects.
 */
class OnboardingWriter
    @Inject
    constructor(
        private val settingsStore: SettingsStore,
        private val consentStore: ConsentStore,
    ) {
        /**
         * Whether this session has already written a consent grant.
         *
         * Why:  it lets a user who opts in, hits a failed save, then changes their mind be honoured
         *       exactly. Revoking unconditionally instead would stamp a withdrawal date on a
         *       consent that was never given — a false entry in a ledger whose whole purpose is
         *       being able to answer "since when?" truthfully (P-01).
         */
        private var consentGrantWritten = false

        /**
         * Applies the consent decision, then completes onboarding.
         * Why:    one call rather than two, so no caller can get the order wrong — see the class
         *         note for why the order is what it is.
         * Result: `Ok(Unit)` when both landed; `Err` from whichever failed first, with the second
         *         never attempted.
         * Input:  [smsConsentGranted] — the FR-ONB-003 toggle's final state; [profile] — the
         *         captured answers. Output: `Result<Unit, AppError>`.
         */
        suspend fun apply(
            smsConsentGranted: Boolean,
            profile: OnboardingProfile,
        ): Result<Unit, AppError> =
            applyConsentDecision(smsConsentGranted)
                .flatMap { settingsStore.completeOnboarding(profile) }

        /**
         * Records the SMS-parsing decision (FR-ONB-003, P-01).
         * Why:    a decision of "no" needs no write — absence already reads as not granted, and
         *         writing one would be a revocation of something never granted. The exception is a
         *         user who opted in, hit a failed save, and changed their mind before retrying;
         *         [consentGrantWritten] is how that one case is honoured without inventing history.
         * Result: `Ok(Unit)` when there was nothing to write or the write succeeded; `Err` otherwise.
         * Input:  [granted] — the toggle's state. Output: `Result<Unit, AppError>`.
         */
        private suspend fun applyConsentDecision(granted: Boolean): Result<Unit, AppError> =
            when {
                granted -> consentStore.grant(ConsentFeature.SMS_PARSING).also { consentGrantWritten = it is Ok }
                consentGrantWritten -> consentStore.revoke(ConsentFeature.SMS_PARSING)
                else -> Ok(Unit)
            }
    }
