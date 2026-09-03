package com.aicfo.data.repository

import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.emergencyfund.EmergencyFundEngine
import com.aicfo.domain.engines.emergencyfund.EmergencyFundInput
import com.aicfo.domain.engines.emergencyfund.EmergencyFundPlan
import com.aicfo.domain.engines.emergencyfund.EmergencyFundRules
import com.aicfo.domain.engines.emergencyfund.EssentialsBasis
import com.aicfo.domain.engines.quicksetup.BudgetEnvelope
import com.aicfo.domain.engines.quicksetup.BudgetNature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Resolves what `EmergencyFundEngine` needs, and hands back its answer (issue 7.2; §10.1, ARC-005).
 *
 * Why:  the engine is pure arithmetic over three figures somebody has to decide — what a month of
 *       essentials costs, what the income has been doing, and what could actually be spent today.
 *       **Each of those is a judgement, and all three are storage questions**, so they belong on
 *       this side of the boundary and not in the engine or a ViewModel.
 * What: watch the assessment, recomputed on every change to the ledger, the accounts or the
 *       onboarding envelopes.
 * Result: a ViewModel sees an [EmergencyFundPlan] and nothing else — no Room types, no DAOs.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 *
 * **Nothing derived is stored**, for the reason `GoalRepository` gives: a target written to the
 * database would outlive the spending that produced it, and would go stale simply because a month
 * closed — the one input the user never edits.
 */
interface EmergencyFundRepository {
    /**
     * Watches the active profile's emergency-fund assessment.
     * Why:    every term of it moves on its own schedule — a transaction changes the essentials, a
     *         reconciliation changes the liquid balance — so a one-shot read would be stale before
     *         the user finished reading it.
     * Result: re-emitted on every relevant change. **Never null and never an error**: a profile the
     *         app has never watched spend yields an `UNKNOWN` assessment, which the screen renders
     *         as "we cannot size this yet" rather than an error state (P-04).
     * Input:  none — the active profile. Output: `Flow<EmergencyFundPlan>`.
     */
    fun observeEmergencyFund(): Flow<EmergencyFundPlan>
}

/**
 * The Room-backed [EmergencyFundRepository] (issue 7.2).
 *
 * Why:  `internal`, reached through [RepositoryFactory], like every other repository here.
 * What: combines three sources, resolves the three judgements, calls the engine once per emission.
 * Result: an [EmergencyFundPlan] per emission.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 *
 * **Three flows, not six**, so `combine`'s typed overloads are enough and no term can be transposed
 * — the trap `SafeToSpendRepository` had to nest around when it reached a sixth source.
 *
 * **[rules] is the seventh constructor parameter and detekt's ceiling is seven**, which is why the
 * suppression is here rather than the parameter being dropped. It has to be injectable: the
 * repository reads `essentials_lookback_months` from it to size the history window, so a test that
 * moves that threshold must move the query too, and this is also the seam the runtime rules loader
 * will use.
 */
@Suppress("LongParameterList") // Seven, and the seventh is the rules seam — see the note above.
internal class RoomEmergencyFundRepository(
    private val transactions: TransactionRepository,
    private val accounts: AccountRepository,
    private val quickSetup: QuickSetupRepository,
    private val engine: EmergencyFundEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val rules: EmergencyFundRules = EmergencyFundRules(),
) : EmergencyFundRepository {
    override fun observeEmergencyFund(): Flow<EmergencyFundPlan> =
        combine(
            transactions.observeMonthlyLedger(rules.essentialsLookbackMonths),
            accounts.observeAccounts(),
            quickSetup.observeLatestEnvelopes(),
        ) { history, allAccounts, envelopes ->
            // Read once per emission (TIM-001). The engine reads no clock of its own.
            val today = clock.today()
            val liquid = allAccounts.liquid()
            val essentials = essentialsFrom(history, envelopes)
            val result =
                engine.assess(
                    EmergencyFundInput(
                        monthlyEssentials = essentials.amount,
                        essentialsBasis = essentials.basis,
                        monthlyIncomes = history.map { it.income },
                        liquidFunds = liquid.fold(Money.ZERO) { running, account -> running + account.balance },
                        liquidAccountNames = liquid.map { it.name },
                        essentialCategoryNames = ESSENTIAL_NATURE_LABEL,
                        today = today,
                        nowUtcMillis = clock.nowUtcMillis(),
                        rules = rules,
                    ),
                )
            // An `Err` here means an amount that will not fit in a `Long` (MNY-001) — absurd data,
            // not a broken app. Reporting UNKNOWN keeps the screen renderable, which is what the
            // engine's own null-essentials branch exists to say.
            (result as? Ok)?.value ?: unknownFor(today)
        }.flowOn(dispatchers.io)

    /**
     * What a month of essential living costs, and where that figure came from.
     *
     * Why:    §10.1 wants `FixedLoad + median(SemiFixed) + essentialShare(Variable)`. This app
     *         already separates essential from discretionary at the point of classification (§8.3
     *         `NEED`), so the three terms are one query, and the **median** is what turns a stack of
     *         months into a typical one. The mean would not: a single annual insurance premium in
     *         one month of six lifts a mean by a sixth of the premium and lifts a median by nothing.
     *
     *         **The envelope is a fallback, not an equal.** It is what the user typed at onboarding
     *         and never revisits, so it is the answer only while there is nothing better — the same
     *         standing `QuickSetupEngine` gave it, now demoted rather than removed, because a
     *         day-one user still deserves a target.
     * Result: the amount and its [EssentialsBasis]. **`null` with `NONE` when neither is available**
     *         — never a zero, which would read as "you spend nothing" and size a target of ₹0.
     * Input:  [history] — the closed months; [envelopes] — the onboarding envelopes.
     * Output: [Essentials].
     */
    private fun essentialsFrom(
        history: List<MonthlyLedger>,
        envelopes: List<BudgetEnvelope>,
    ): Essentials {
        val observed = history.map { it.nature.needs }.filter { it > Money.ZERO }
        if (observed.size >= rules.minMonthsObserved) {
            return Essentials(observed.median(), EssentialsBasis.OBSERVED_MEDIAN)
        }
        val declared = envelopes.firstOrNull { it.nature == BudgetNature.NEED }?.amount
        return if (declared != null && declared > Money.ZERO) {
            Essentials(declared, EssentialsBasis.DECLARED_ENVELOPE)
        } else {
            Essentials(null, EssentialsBasis.NONE)
        }
    }

    /**
     * The accounts whose balance could be spent today.
     *
     * Why:    §10.1 defines liquid funds as "savings + cash + FDs breakable without major penalty +
     *         liquid MF", mapped by a per-account liquidity tier that "is stored per account and
     *         user-editable". **No such column exists.** Guessing a tier from [AccountType] would
     *         silently decide that every FD is breakable, or that none is, and both are wrong for
     *         somebody. So only the unambiguous types count.
     *
     *         This **understates** the runway, which fails in the safe direction, and the names are
     *         carried onto the assessment so the user can see exactly what was counted rather than
     *         wondering where their fixed deposit went (P-02). ADR-0034 records the deferral.
     * Result: the live, positive-balance bank and cash accounts. A negative balance is dropped
     *         rather than netted: an overdrawn account is a liability, and subtracting it here would
     *         understate a runway the user could genuinely spend.
     * Input:  the receiver — the profile's accounts. Output: the liquid ones.
     */
    private fun List<Account>.liquid(): List<Account> =
        filter { it.type in LIQUID_TYPES && it.includeInNetWorth && it.balance > Money.ZERO }

    /**
     * The median of a month's-worth of amounts.
     * Why:    §10.1's own word for the semi-fixed term, and the statistic that survives one unusual
     *         month. Written here rather than taken from a library because `:data:repository` has no
     *         statistics dependency and this is four lines.
     * Result: the middle value, or the **lower** of the two middles for an even count — the
     *         conservative choice, and one that needs no division and so cannot lose a paise.
     * Input:  the receiver — a non-empty list. Output: [Money].
     */
    private fun List<Money>.median(): Money = sorted()[(size - 1) / 2]

    /**
     * The assessment for a profile whose figures could not be resolved.
     * Why:    the engine's own `UNKNOWN` branch, reached without it — used only when `assess`
     *         returns an `Err`, which means an overflow rather than missing data.
     * Result: an [EmergencyFundPlan] the screen can render. Input: [today]. Output: the plan.
     */
    private fun unknownFor(today: java.time.LocalDate): EmergencyFundPlan =
        (
            engine.assess(
                EmergencyFundInput(
                    monthlyEssentials = null,
                    essentialsBasis = EssentialsBasis.NONE,
                    today = today,
                    nowUtcMillis = clock.nowUtcMillis(),
                    rules = rules,
                ),
            ) as Ok
        ).value

    /** The amount and its provenance, returned together so the two cannot be mismatched. */
    private data class Essentials(
        val amount: Money?,
        val basis: EssentialsBasis,
    )

    private companion object {
        /**
         * The account types whose balance is unambiguously spendable today.
         *
         * Deliberately short. Every other type needs the liquidity tier §10.1 describes and this
         * schema does not have — an `INVESTMENT` row is an FD or an equity fund or a PPF lock-in,
         * and the column that would tell them apart is a future issue (ADR-0034).
         */
        val LIQUID_TYPES = setOf(AccountType.BANK, AccountType.CASH)

        /**
         * What counted as essential, for the evidence §10.1 requires.
         *
         * A nature, not a list of categories: the essentials figure is the `NEED` total from §8.3's
         * fold, so naming the individual categories would imply a per-category breakdown the figure
         * was not built from. The categories editor is where a user changes which of theirs is a
         * need.
         */
        val ESSENTIAL_NATURE_LABEL = listOf("NEED")
    }
}
