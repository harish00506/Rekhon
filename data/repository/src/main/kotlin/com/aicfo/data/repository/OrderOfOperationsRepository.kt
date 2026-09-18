package com.aicfo.data.repository

import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Ok
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.emergencyfund.EmergencyStatus
import com.aicfo.domain.engines.goals.GoalProjection
import com.aicfo.domain.engines.goals.SurplusBasis
import com.aicfo.domain.engines.orderofoperations.DebtKind
import com.aicfo.domain.engines.orderofoperations.DebtPosition
import com.aicfo.domain.engines.orderofoperations.OrderOfOperations
import com.aicfo.domain.engines.orderofoperations.OrderOfOperationsEngine
import com.aicfo.domain.engines.orderofoperations.OrderOfOperationsInput
import com.aicfo.domain.engines.quicksetup.QuickSetupRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn

/**
 * The Financial Order of Operations for the active profile (issue 7.5; SRS §36, AI-FOO, ARC-005).
 *
 * Why:  AI-FOO ranks the whole household — buffer, debt, emergency fund, goals — and every one of
 *       those figures already has an owner. The surplus is [SurplusRepository]'s, the goals' need is
 *       [GoalRepository]'s projections, the runway and shortfall are [EmergencyFundRepository]'s.
 *       This repository adds only what nobody resolved before: **each debt with its rate**.
 *       Recomputing any of them here would give two screens two answers to one question.
 *
 *       **It no longer reads [GoalWaterfallRepository], and the direction matters.** Until 2026-09-18
 *       it did, and the waterfall poured the whole surplus into goals — so past the emergency gate the
 *       two screens disagreed about whether the fund's monthly pace came first. The waterfall now
 *       allocates what *this* ranking leaves, which makes AI-FOO the base and the goal split a
 *       consumer of it (ADR-0038).
 * What: one flow, recomputed whenever any source changes — a transaction, a goal, a card's APR.
 * Result: an [OrderOfOperations] the dashboard card and the ranking screen both read.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 */
interface OrderOfOperationsRepository {
    /**
     * Observes the ranking.
     * Why:    a flow, because §36 says the ranking is "recomputed monthly and on any surplus change",
     *         and the surplus moves with every transaction.
     * Result: emits the current ranking and every change to it. Never fails: an input the engine
     *         cannot rank (a sum past `Long.MAX_VALUE`) falls back to a ranking without the figures
     *         that overflowed, so the screen always has something true to show.
     * Input:  none — the profile is the active one. Output: `Flow<OrderOfOperations>`.
     */
    fun observe(): Flow<OrderOfOperations>
}

/**
 * Room-backed [OrderOfOperationsRepository] (issue 7.5).
 *
 * Why:  `internal` so the only way in is `RepositoryFactory` (ARC-003).
 * Result: see the interface. Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * **The surplus is taken from the waterfall, not re-derived.** `GoalWaterfall` already carries the
 * figure and its basis (ADR-0035's observed-P50 stand-in for the forecast issue 9.2 never built), and
 * pouring a second, independently computed surplus would let the two screens disagree about how much
 * money there is before they disagreed about anything else.
 *
 * **`RULE-EMERG-FIRST`'s number comes from [QuickSetupRules]**, the repository's one mirror of that
 * row, exactly as [GoalWaterfallRepository] resolves it — see ADR-0035 on why no second mirror.
 */
@Suppress("LongParameterList") // Eight, and each is a distinct binding — as GoalWaterfallRepository.
internal class RoomOrderOfOperationsRepository(
    private val database: CfoDatabase,
    private val goals: GoalRepository,
    private val surplus: SurplusRepository,
    private val emergencyFund: EmergencyFundRepository,
    private val engine: OrderOfOperationsEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : OrderOfOperationsRepository {
    override fun observe(): Flow<OrderOfOperations> =
        combine(
            surplus.observeMonthlySurplus(),
            goals.observeGoals(),
            emergencyFund.observeEmergencyFund(),
            observeDebts(),
        ) { month, projections, fund, debts ->
            val input =
                OrderOfOperationsInput(
                    monthlySurplus = month.amount,
                    surplusBasis = month.basis,
                    monthlyEssentials = fund.monthlyEssentials,
                    liquidFunds = fund.liquidFunds,
                    // EMF reports a zero shortfall when it cannot size the fund at all. Passing that
                    // zero on would tell the user an unsized fund was complete.
                    emergencyShortfall = if (fund.status == EmergencyStatus.UNKNOWN) null else fund.shortfall,
                    emergencyTopUpMonthly = fund.topUpMonthly,
                    emergencyRunwayMonthsBps = fund.runwayMonthsBps,
                    emergencyGateMonths = GATE_RULES.emergencyRunwayMonths,
                    debts = debts,
                    // Σ over the projections themselves, not over a waterfall's lines: this is what
                    // the goals need, before anything decides who gets it.
                    goalsRequiredMonthly = projections.requiredMonthly(),
                    goalCount = projections.size,
                    // Read once per emission (TIM-001). The engine reads no clock of its own.
                    today = clock.today(),
                    nowUtcMillis = clock.nowUtcMillis(),
                )
            (engine.rank(input) as? Ok)?.value ?: rankWithoutSums(input)
        }.flowOn(dispatchers.io)

    /** Result: what every goal needs this month, summed. Input: the receiver. Output: [Money]. */
    private fun List<GoalProjection>.requiredMonthly(): Money =
        fold(Money.ZERO) { sum, goal -> sum + goal.requiredMonthly }

    /**
     * Every debt the profile owes, with its rate.
     *
     * Why:    no repository resolved this before — the card and loan repositories each read their own
     *         table, and neither a card's utilisation nor a loan's schedule is a rate beside a balance.
     * What:   the profile's live, unarchived accounts joined to their card and loan detail rows.
     *         The outstanding is the account's balance negated and floored at zero, the reading
     *         `RoomCreditCardRepository` takes, so the two screens agree on what is owed.
     * Result: one [DebtPosition] per card, and per loan **whose details were entered**. A card with no
     *         detail row, or no APR, is sent with an unknown rate — the engine counts it as fire debt
     *         and says so. A loan with no detail row has no rate to place it by and is left out:
     *         guessing a band for it would be exactly the invented number P-03 forbids. Informal
     *         payables carry no rate and are not sent either.
     * Input:  none. Output: `Flow<List<DebtPosition>>`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDebts(): Flow<List<DebtPosition>> =
        activeProfileId.flatMapLatest { profileId ->
            combine(
                database.accountDao().observeWithBalances(
                    profileId,
                    includeArchived = false,
                    asOfIsoDate = clock.today().toString(),
                ),
                database.creditCardDao().observeForProfile(profileId),
                database.loanDao().observeForProfile(profileId),
            ) { balances, cards, loans ->
                val cardRates = cards.associate { it.accountId to it.aprBps }
                val loanRates = loans.associate { it.accountId to it.annualRateBps }
                balances.mapNotNull { row -> debtOf(row.account, cardRates, loanRates) }
            }
        }

    /**
     * One account as a debt, or null when it is not one this engine can place.
     * Result: a card always; a loan only with a rate; nothing else.
     * Input:  [account]; [cardRates] and [loanRates] — detail rows by account id.
     * Output: [DebtPosition] or null.
     */
    private fun debtOf(
        account: AccountEntity,
        cardRates: Map<String, Int?>,
        loanRates: Map<String, Int>,
    ): DebtPosition? {
        val outstanding = (Money.ZERO - Money(account.currentBalanceMinor)).coerceAtLeast(Money.ZERO)
        return when (AccountType.fromStored(account.type)) {
            AccountType.CREDIT_CARD ->
                DebtPosition(account.id, account.name, DebtKind.CARD, outstanding, cardRates[account.id])
            AccountType.LOAN ->
                loanRates[account.id]?.let { rate ->
                    DebtPosition(account.id, account.name, DebtKind.LOAN, outstanding, rate)
                }
            else -> null
        }
    }

    /**
     * A ranking for an input whose sums would not fit in a `Long`.
     *
     * Why:    the engine refuses to wrap an overflow (MNY-001), and the only sums it takes are the
     *         debts, the goals' need and the surplus poured past them. A flow that simply stopped
     *         emitting would leave the dashboard card loading for ever.
     * Result: the same household ranked **without** those figures — the buffer and the emergency
     *         fund still answered, which is where the next rupee goes first anyway. Nothing left in
     *         the input can overflow: the buffer is at most its cap, and the fund's figures are
     *         echoed rather than summed, so the `Ok` cast below cannot fail.
     * Input:  [input] — the input that overflowed. Output: [OrderOfOperations].
     */
    private fun rankWithoutSums(input: OrderOfOperationsInput): OrderOfOperations =
        (
            engine.rank(
                input.copy(
                    monthlySurplus = null,
                    surplusBasis = SurplusBasis.NONE,
                    debts = emptyList(),
                    goalsRequiredMonthly = Money.ZERO,
                    goalCount = 0,
                ),
            ) as Ok
        ).value

    private companion object {
        /** `RULE-EMERG-FIRST`'s one mirror in this repository, read the way the waterfall reads it. */
        val GATE_RULES = QuickSetupRules()
    }
}
