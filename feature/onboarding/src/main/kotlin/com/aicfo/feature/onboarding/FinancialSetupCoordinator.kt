package com.aicfo.feature.onboarding

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.common.getOrNull
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.AccountDraft
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.ProfileSeed
import com.aicfo.data.repository.QuickSetupRepository
import com.aicfo.domain.engines.quicksetup.QuickSetupEngine
import com.aicfo.domain.engines.quicksetup.QuickSetupInput
import com.aicfo.domain.engines.quicksetup.QuickSetupPlan
import javax.inject.Inject

/**
 * Everything onboarding writes to the **database** — the two financial steps (issues 2.3, 2.5).
 *
 * Why:  the work between "what the user typed" and "what the store takes" is real and easy to get
 *       wrong — text has to become exact paise, and the budget month has to be resolved in the
 *       **profile** zone rather than the device's. Left in the ViewModel it sat beside consent and
 *       PIN handling, which is how a screen controller turns into a place where everything lives.
 *
 *       It covers both financial steps rather than one, and that is not tidying: **the first account
 *       and the quick-setup rows are ordered against each other**. The account has to exist before
 *       anything can point at it, and issue 2.3's recurring rules are exactly what points at it —
 *       so `persistFirstAccount` runs after `persist` and finishes by attaching the two. A pair of
 *       collaborators could not guarantee that between them; one can.
 *
 *       The split against its neighbours is by *store*, not by step: [OnboardingWriter] owns the
 *       DataStore writes (consent, profile settings, the completion flag) and `AppLockSetup` owns
 *       the Keystore ones. This owns Room.
 * What: derive and persist a quick-setup plan; create the first account and attach the rules to it.
 * Result: the ViewModel deals in "apply what the user answered" rather than in parsing, periods and
 *       write ordering.
 * Changelog: 2026-07-27 — Created for issue 2.3 as `QuickSetupCoordinator`, extracted from
 *            OnboardingViewModel.
 *            2026-07-28 — Issue 2.5: renamed, and given FR-ONB-001's fourth step. The rename keeps
 *            the ViewModel at six constructor parameters, which is what detekt allows — the
 *            alternative was a seventh collaborator that could not have owned the ordering anyway.
 *
 * Input:  [engine] — computes every figure, so nothing here does arithmetic (P-03);
 *         [quickSetupRepository] — writes the budget, the rules and the profile row;
 *         [accountRepository] — writes the first account (issue 2.5); [clock] — the profile-zone
 *         date and the provenance instant, never a wall-clock read (TIM-001).
 * Output: a collaborator the ViewModel injects.
 */
class FinancialSetupCoordinator
    @Inject
    constructor(
        private val engine: QuickSetupEngine,
        private val quickSetupRepository: QuickSetupRepository,
        private val accountRepository: AccountRepository,
        private val clock: Clock,
    ) {
        /**
         * Derives the plan from what is currently typed.
         * Why:    runs on every keystroke so the summary keeps up, which is affordable only because
         *         the engine is pure. `MoneyFormatter.parse` is the single bridge from text to
         *         [Money] (MNY-001) — a half-typed field simply parses to `null` and contributes
         *         nothing.
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
        ): Result<Unit, AppError> = quickSetupRepository.applySeeds(plan, profile)

        /**
         * Creates the first account and points the seeded rules at it (issue 2.5; FR-ONB-001 step 4).
         *
         * Why:    FR-ONB-001's fourth step is "add first account with opening balance", deferred to
         *         this issue by ADR-0002 because it needed a repository that did not exist. This is
         *         where it lands, and where issue 2.3's loose end is finally tied: those rules were
         *         written with `account_id = null` because there was no account to name, and now
         *         there is.
         *
         *         **Skipping writes nothing at all**, which matters because the step is optional in
         *         the same way quick setup is: a blank name means the user declined, and inventing a
         *         zero-balance "My Account" for them would be fabricating data (P-03).
         *
         *         The attach runs even when there are no rules to attach — the repository reports
         *         that as success, because a user who skipped quick setup has nothing to point and
         *         has done nothing wrong.
         * What:   parses the amount, creates the account, then attaches.
         * Result: `Ok(Unit)` when there was nothing to do or both writes landed; `Err(Validation)`
         *         when the typed amount is not an exactly representable one; `Err` from whichever
         *         write failed.
         * Input:  [name] — blank means skipped; [type]; [openingBalanceText] — raw field contents,
         *         blank meaning zero; [currencyCode]; [profileId] — whose rules to attach.
         * Output: `Result<Unit, AppError>`.
         * Changelog: 2026-07-28 — Created for issue 2.5.
         */
        suspend fun persistFirstAccount(
            name: String,
            type: AccountType,
            openingBalanceText: String,
            currencyCode: String,
            profileId: String,
        ): Result<Unit, AppError> {
            if (name.isBlank()) return Ok(Unit)
            // Blank is zero — an account opened at nothing is ordinary. Anything else must parse
            // exactly or be refused: guessing what the user meant about money is the one thing this
            // app must never do (P-03, MNY-001).
            val openingBalance =
                if (openingBalanceText.isBlank()) {
                    Money.ZERO
                } else {
                    MoneyFormatter.parse(openingBalanceText)
                        ?: return Err(AppError.Validation("openingBalance"))
                }

            return accountRepository.create(
                AccountDraft(
                    name = name,
                    type = type,
                    openingBalance = openingBalance,
                    currencyCode = currencyCode,
                ),
            ).flatMap { account ->
                quickSetupRepository.attachAccountToSeededRules(profileId, account.id)
            }
        }

        /**
         * The first day of the budget month, in the **profile** zone (TIM-002).
         * Why:    `clock.today()` resolves in the zone the user chose two steps ago, so someone
         *         setting up at 23:30 IST on the 31st gets that month rather than the next one —
         *         which is the whole reason `Clock` reads the profile zone (TIM-001).
         * Result: an ISO `yyyy-MM-dd` date. Input: none. Output: [String].
         */
        private fun currentPeriodStart(): String = clock.today().withDayOfMonth(1).toString()
    }
