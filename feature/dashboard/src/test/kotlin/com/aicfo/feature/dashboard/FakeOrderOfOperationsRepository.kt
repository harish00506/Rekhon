package com.aicfo.feature.dashboard

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.data.repository.OrderOfOperationsRepository
import com.aicfo.domain.engines.goals.SurplusBasis
import com.aicfo.domain.engines.orderofoperations.DebtKind
import com.aicfo.domain.engines.orderofoperations.DebtPosition
import com.aicfo.domain.engines.orderofoperations.OrderOfOperations
import com.aicfo.domain.engines.orderofoperations.OrderOfOperationsEngineFactory
import com.aicfo.domain.engines.orderofoperations.OrderOfOperationsInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * A scriptable [OrderOfOperationsRepository] for the dashboard's tests (issue 7.5).
 *
 * Why:  every ranking a test pushes is produced by the **real** engine, the choice
 *       `FakeGoalWaterfallRepository` makes — a hand-built `OrderOfOperations` could describe a
 *       ranking the engine never emits, and a screen test passing against it would prove nothing.
 * What: a replay-less stream a test can push rankings or a failure down.
 * Result: the ViewModels can be driven through every state they render.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 */
internal class FakeOrderOfOperationsRepository : OrderOfOperationsRepository {
    // kotlin.Result, so a test can push a failure down the same stream the ViewModel's `catch` reads.
    private val rankings = MutableSharedFlow<Result<OrderOfOperations>>(replay = 0, extraBufferCapacity = 8)

    override fun observe(): Flow<OrderOfOperations> = rankings.map { it.getOrThrow() }

    /** Pushes the ranking for [input]. Input: [input]. Output: none. */
    fun emit(input: OrderOfOperationsInput) {
        rankings.tryEmit(Result.success(rank(input)))
    }

    /** Pushes a storage failure. Input: none. Output: none. */
    fun fail() {
        rankings.tryEmit(Result.failure(IllegalStateException("disk unavailable")))
    }
}

/** The day every fixture ranking is reckoned from. */
internal val FOO_TODAY: LocalDate = LocalDate.of(2026, 9, 17)

/** Result: the real engine's ranking for [input]. Input: [input]. Output: [OrderOfOperations]. */
internal fun rank(input: OrderOfOperationsInput): OrderOfOperations =
    (OrderOfOperationsEngineFactory.create().rank(input) as Ok).value

/**
 * A household with a card owing ₹65,000 at 36% and a thin buffer — the top action is the buffer, the
 * card is next, and the goal is held by the emergency-fund gate. Every stage status is represented
 * except the grey-zone choice and the simulator deferral, which [idleHouseholdInput] covers.
 */
internal fun cardDebtInput(): OrderOfOperationsInput =
    OrderOfOperationsInput(
        monthlySurplus = Money(25_000_00L),
        surplusBasis = SurplusBasis.OBSERVED_MEDIAN,
        monthlyEssentials = Money(28_000_00L),
        liquidFunds = Money(12_000_00L),
        emergencyShortfall = Money(1_56_000_00L),
        emergencyTopUpMonthly = Money(26_000_00L),
        emergencyRunwayMonthsBps = 4_285,
        debts = listOf(DebtPosition("hdfc", "HDFC Card", DebtKind.CARD, Money(65_000_00L), 3_600)),
        goalsRequiredMonthly = Money(8_000_00L),
        goalCount = 1,
        today = FOO_TODAY,
    )

/**
 * A household past every gate with a grey-zone car loan and a home loan — the top action is the
 * grey-zone *choice*, the home loan defers to the simulator, and ₹2,000 is left idle.
 */
internal fun idleHouseholdInput(): OrderOfOperationsInput =
    OrderOfOperationsInput(
        monthlySurplus = Money(9_000_00L),
        surplusBasis = SurplusBasis.DECLARED_ENVELOPE,
        monthlyEssentials = Money(30_000_00L),
        liquidFunds = Money(3_00_000_00L),
        emergencyShortfall = Money.ZERO,
        emergencyRunwayMonthsBps = 1_00_000,
        debts =
            listOf(
                DebtPosition("car", "Car Loan", DebtKind.LOAN, Money(7_000_00L), 1_150),
                DebtPosition("home", "Home Loan", DebtKind.LOAN, Money(25_00_000_00L), 850),
            ),
        today = FOO_TODAY,
    )
