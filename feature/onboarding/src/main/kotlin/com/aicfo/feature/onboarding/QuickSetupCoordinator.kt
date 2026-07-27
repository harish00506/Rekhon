package com.aicfo.feature.onboarding

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.Result
import com.aicfo.core.common.getOrNull
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.ProfileSeed
import com.aicfo.data.repository.QuickSetupRepository
import com.aicfo.domain.engines.quicksetup.QuickSetupEngine
import com.aicfo.domain.engines.quicksetup.QuickSetupInput
import com.aicfo.domain.engines.quicksetup.QuickSetupPlan
import javax.inject.Inject

/**
 * The quick-setup step's two jobs, behind one collaborator (issue 2.3; FR-ONB-002).
 *
 * Why:  the work between "what the user typed" and "what the engine takes" is real and easy to get
 *       wrong — text has to become exact paise, and the budget month has to be resolved in the
 *       **profile** zone rather than the device's. Left in the ViewModel it sat beside consent and
 *       PIN handling, which is how a screen controller turns into a place where everything lives.
 *       Pulling it out also gives the two halves one home: the same class that derives a plan is
 *       the one that persists it, so they cannot drift over which month they mean.
 * What: parse and derive, and hand the result to the repository.
 * Result: the ViewModel deals in plans rather than in parsing, periods and engine inputs.
 * Changelog: 2026-07-27 — Created for issue 2.3, extracted from OnboardingViewModel.
 *
 * Input:  [engine] — computes every figure, so nothing here does arithmetic (P-03);
 *         [repository] — writes the rows; [clock] — the profile-zone date and the provenance
 *         instant, never a wall-clock read (TIM-001).
 * Output: a collaborator the ViewModel injects.
 */
class QuickSetupCoordinator
    @Inject
    constructor(
        private val engine: QuickSetupEngine,
        private val repository: QuickSetupRepository,
        private val clock: Clock,
    ) {
        /**
         * Derives the plan from what is currently typed.
         * Why:    runs on every keystroke so the summary keeps up, which is affordable only because
         *         the engine is pure. `MoneyFormatter.parse` is the single bridge from text to
         *         [com.aicfo.core.model.Money] (MNY-001) — a half-typed field simply parses to
         *         `null` and contributes nothing.
         * Result: the plan, or `null` when nothing parses yet, the engine rejects the input, or the
         *         result is empty — all three mean "there is nothing to show or store".
         * Input:  [incomeText], [rentText], [savingsText] — raw field contents.
         * Output: `QuickSetupPlan?`.
         */
        fun derive(
            incomeText: String,
            rentText: String,
            savingsText: String,
        ): QuickSetupPlan? =
            engine.plan(
                QuickSetupInput(
                    monthlyIncome = MoneyFormatter.parse(incomeText),
                    rentOrEmi = MoneyFormatter.parse(rentText),
                    typicalSavings = MoneyFormatter.parse(savingsText),
                    periodStartIsoDate = currentPeriodStart(),
                    nowUtcMillis = clock.nowUtcMillis(),
                ),
            ).getOrNull()?.takeIf { !it.isEmpty }

        /**
         * Writes a derived plan and its profile.
         * Result: `Ok(Unit)` when everything landed; `Err` otherwise, with nothing written — the
         *         repository does it in one transaction.
         * Input:  [plan]; [profile]. Output: `Result<Unit, AppError>`.
         */
        suspend fun persist(
            plan: QuickSetupPlan,
            profile: ProfileSeed,
        ): Result<Unit, AppError> = repository.applySeeds(plan, profile)

        /**
         * The first day of the budget month, in the **profile** zone (TIM-002).
         * Why:    `clock.today()` resolves in the zone the user chose two steps ago, so someone
         *         setting up at 23:30 IST on the 31st gets that month rather than the next one —
         *         which is the whole reason `Clock` reads the profile zone (TIM-001).
         * Result: an ISO `yyyy-MM-dd` date. Input: none. Output: [String].
         */
        private fun currentPeriodStart(): String = clock.today().withDayOfMonth(1).toString()
    }
