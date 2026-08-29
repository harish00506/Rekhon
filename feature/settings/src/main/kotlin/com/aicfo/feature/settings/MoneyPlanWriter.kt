package com.aicfo.feature.settings

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.datastore.QuickSetupSeeds
import com.aicfo.core.datastore.SettingsSnapshot
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.ProfileSeed
import com.aicfo.data.repository.QuickSetupRepository
import com.aicfo.domain.engines.quicksetup.QuickSetupEngine
import com.aicfo.domain.engines.quicksetup.QuickSetupInput
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Turns three typed amounts into a stored plan (FR-SET-001; issue 2.3's engine, reused).
 *
 * Why:  the same two-step `FinancialSetupCoordinator` performs at the end of onboarding — derive the
 *       envelopes with the engine, then persist them — but reachable afterwards. It is a class of
 *       its own rather than five constructor arguments on the ViewModel, for the reason onboarding
 *       gives: the ViewModel's job is the screen's state machine, and a screen that also knows how
 *       to drive an engine is a screen that will eventually be asked to do arithmetic (P-03).
 *
 *       **Seeds and envelopes are both written, in that order.** The seeds are what the screen reads
 *       back to prefill itself next time; the envelopes are what the dashboard and Safe-to-Spend
 *       actually consume. Writing only one of them is how the screen and the dashboard come to
 *       disagree about what the user said.
 *
 *       **The profile is read, not invented.** Re-deriving a plan must not rename the user or move
 *       their time zone, so the existing snapshot supplies those three fields and this class
 *       supplies none of them.
 * What: parse → derive → write seeds → write envelopes.
 * Result: the dashboard's needs/wants/savings split and Safe-to-Spend's preferred income basis both
 *       become editable after onboarding, which is the defect this exists to close.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 *
 * Input:  [engine] — derives the split, so no percentage is computed here (P-03); [quickSetup] —
 *         writes the envelopes transactionally; [settingsStore] — stores the seeds and supplies the
 *         profile; [clock] — the injected clock, never the wall clock (TIM-001).
 * Output: a coordinator the ViewModel calls once per save.
 */
interface MoneyPlanWriter {
    /**
     * Stores the seeds and the envelopes derived from them.
     * Result: `Ok(Unit)` when both writes land; `Err(Validation("monthlyIncome"))` when the amounts
     *         do not make a plan; the store's own `Err` when a write fails.
     * Input:  [incomeText], [rentText], [savingsText] — rupee amounts as typed; blank is absent.
     * Output: `Result<Unit, AppError>`.
     */
    suspend fun save(
        incomeText: String,
        rentText: String,
        savingsText: String,
    ): Result<Unit, AppError>
}

/**
 * The production [MoneyPlanWriter].
 *
 * An interface with one implementation, for the reason ARC-003 gives everywhere else here: the
 * contract is what callers depend on, and a test that had to stand up a real engine and a real
 * transaction to assert a field error would be testing the wrong thing.
 */
class DefaultMoneyPlanWriter
    @Inject
    constructor(
        private val engine: QuickSetupEngine,
        private val quickSetup: QuickSetupRepository,
        private val settingsStore: SettingsStore,
        private val clock: Clock,
    ) : MoneyPlanWriter {
        /**
         * Stores the seeds and the envelopes derived from them.
         * Why:    the income is required and the other two are not — a plan with no income has no
         *         basis to split, and the engine refuses it. That refusal is reported as a field
         *         error rather than a crash, because it is input the user can fix.
         * Result: `Ok(Unit)` when both writes land; `Err(Validation("monthlyIncome"))` when the
         *         amounts do not make a plan; the store's own `Err` when a write fails.
         * Input:  [incomeText], [rentText], [savingsText] — rupee amounts as typed; blank is absent.
         * Output: `Result<Unit, AppError>`.
         * Changelog: 2026-08-29 — Created for FR-SET-001.
         */
        override suspend fun save(
            incomeText: String,
            rentText: String,
            savingsText: String,
        ): Result<Unit, AppError> {
            val seeds =
                QuickSetupSeeds(
                    monthlyIncome = MoneyFormatter.parse(incomeText),
                    rentOrEmi = MoneyFormatter.parse(rentText),
                    typicalSavings = MoneyFormatter.parse(savingsText),
                )
            val derived = derive(seeds) ?: return Err(AppError.Validation(INCOME_FIELD))
            val snapshot = (settingsStore.observe().first() as? Ok)?.value

            // Seeds first: they are what this screen reads back, so a failure here must not leave
            // envelopes whose inputs the user can no longer see.
            return settingsStore.setQuickSetupSeeds(seeds).flatMap {
                quickSetup.applySeeds(derived, profileSeed(snapshot))
            }
        }

        /**
         * Runs the engine over the seeds.
         * Result: the plan, or `null` when the engine refused or produced nothing to write.
         * Input:  [seeds]. Output: the plan or `null`.
         */
        private fun derive(seeds: QuickSetupSeeds) =
            (
                engine.plan(
                    QuickSetupInput(
                        monthlyIncome = seeds.monthlyIncome,
                        rentOrEmi = seeds.rentOrEmi,
                        typicalSavings = seeds.typicalSavings,
                        periodStartIsoDate = clock.today().withDayOfMonth(FIRST_OF_MONTH).toString(),
                        nowUtcMillis = clock.nowUtcMillis(),
                    ),
                ) as? Ok
            )?.value?.takeIf { !it.isEmpty }

        /**
         * The profile the envelopes hang off, taken from what is already stored.
         * Why:    the same default id onboarding writes, so a re-derive updates that profile's plan
         *         rather than minting a second one the dashboard would never read — and the
         *         identity fields are echoed back rather than replaced.
         * Result: the seed. Input: [snapshot] — the stored settings, `null` if unreadable.
         * Output: [ProfileSeed].
         */
        private fun profileSeed(snapshot: SettingsSnapshot?): ProfileSeed =
            ProfileSeed(
                displayName = snapshot?.profileDisplayName.orEmpty().ifBlank { DEFAULT_DISPLAY_NAME },
                timeZoneId = snapshot?.profileTimeZoneId ?: clock.zone().id,
                currencyCode = snapshot?.currencyCode ?: DEFAULT_CURRENCY,
            )

        private companion object {
            const val INCOME_FIELD = "monthlyIncome"
            const val FIRST_OF_MONTH = 1

            /** Only ever used when the stored profile has no name, which onboarding does not allow. */
            const val DEFAULT_DISPLAY_NAME = "You"
            const val DEFAULT_CURRENCY = "INR"
        }
    }
