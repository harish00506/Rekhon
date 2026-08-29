package com.aicfo.feature.accounts

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.InvestmentHolding
import com.aicfo.core.model.InvestmentLot
import com.aicfo.data.repository.HoldingDraft
import com.aicfo.data.repository.InvestmentRepository
import com.aicfo.data.repository.LotDraft
import com.aicfo.data.repository.PricedHolding
import com.aicfo.domain.engines.investment.AllocationInput
import com.aicfo.domain.engines.investment.HoldingInput
import com.aicfo.domain.engines.investment.InvestmentEngineFactory
import com.aicfo.domain.engines.investment.PortfolioAllocation
import com.aicfo.domain.engines.investment.PortfolioPosition
import com.aicfo.domain.engines.investment.PriceFreshnessInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [InvestmentRepository] for this module's tests (issue 6.3).
 *
 * Why:  both ViewModels took a fourth repository at 6.3, and most tests in this module do not care
 *       what it returns — a shared fake keeps the tests about archiving and reconciliation about
 *       archiving and reconciliation, exactly as [FakeLoanRepository] does for 6.2.
 *
 *       **Writes are visible to reads**, and the figures come from the **real** engine rather than
 *       from values a test hands in. Both matter: the holdings screen is a round trip, and a fake
 *       that returned canned performances would let a ViewModel that mangled the units or the price
 *       pass every assertion — the arithmetic reaching the screen is the behaviour under test, not
 *       an implementation detail of Room.
 * What: a `MutableStateFlow` of holdings and lots, priced on read.
 * Result: the holdings dependency is satisfiable without Room.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * Input:  none. Output: a controllable fake.
 */
internal class FakeInvestmentRepository : InvestmentRepository {
    private val holdings = MutableStateFlow<List<InvestmentHolding>>(emptyList())
    private val lots = MutableStateFlow<List<InvestmentLot>>(emptyList())
    private val engine = InvestmentEngineFactory.create()

    /** Set by a test to make the next write fail. */
    var failOnSave: AppError? = null

    /** The day freshness is judged against. Fixed so a verdict does not rot with the calendar. */
    var today: String = "2026-08-29"

    /** How many ids this fake has minted, so a test can assert a create rather than an edit. */
    var minted: Int = 0
        private set

    /** Result: seeds the fake with holdings. Input: [rows]. Output: none. */
    fun setHoldings(vararg rows: InvestmentHolding) {
        holdings.value = rows.toList()
    }

    /** Result: seeds the fake with lots. Input: [rows]. Output: none. */
    fun setLots(vararg rows: InvestmentLot) {
        lots.value = rows.toList()
    }

    override fun observeByAccount(): Flow<Map<String, List<PricedHolding>>> =
        holdings.map { rows -> priced(rows).groupBy { it.performance.accountId } }

    override fun observeForAccount(accountId: String): Flow<List<PricedHolding>> =
        holdings.map { rows -> priced(rows.filter { it.accountId == accountId }) }

    /**
     * The allocation over whatever holdings the fake currently holds (issue 6.4).
     *
     * Accounts are not modelled here — this module's fake has holdings and lots and nothing else —
     * so every holding becomes a position and an account counted whole never appears. That is the
     * repository's join to make and `InvestmentRepositoryTest` is where it is proven against real
     * SQL; what a ViewModel test needs is a real [PortfolioAllocation] that moves when the holdings
     * move, and this gives it one from the real engine.
     */
    override fun observeAllocation(): Flow<PortfolioAllocation> =
        holdings.map { rows ->
            val positions =
                priced(rows).map { it.performance }.map { performance ->
                    PortfolioPosition(
                        holdingId = performance.holdingId,
                        accountId = performance.accountId,
                        name = performance.name,
                        assetClass = performance.assetClass,
                        value = performance.currentValue,
                    )
                }
            (engine.allocation(AllocationInput(positions)) as Ok).value
        }

    override suspend fun find(id: String): Result<InvestmentHolding?, AppError> =
        Ok(holdings.value.firstOrNull { it.id == id })

    override suspend fun lotsOf(holdingId: String): Result<List<InvestmentLot>, AppError> =
        Ok(lots.value.filter { it.holdingId == holdingId })

    override suspend fun saveHolding(
        draft: HoldingDraft,
        id: String?,
    ): Result<String, AppError> {
        failOnSave?.let { return Err(it) }
        val rowId = id ?: "holding:${++minted}"
        val row =
            InvestmentHolding(
                id = rowId,
                accountId = draft.accountId,
                name = draft.name,
                assetClass = draft.assetClass,
                unitPrice = draft.unitPrice,
                pricedOnIsoDate = draft.pricedOnIsoDate,
            )
        holdings.value = holdings.value.filterNot { it.id == rowId } + row
        return Ok(rowId)
    }

    override suspend fun saveLot(
        draft: LotDraft,
        id: String?,
    ): Result<String, AppError> {
        failOnSave?.let { return Err(it) }
        val rowId = id ?: "lot:${++minted}"
        val row =
            InvestmentLot(
                id = rowId,
                holdingId = draft.holdingId,
                kind = draft.kind,
                transactedOnIsoDate = draft.transactedOnIsoDate,
                quantity = draft.quantity,
                amount = draft.amount,
            )
        lots.value = lots.value.filterNot { it.id == rowId } + row
        return Ok(rowId)
    }

    override suspend fun deleteHolding(id: String): Result<Unit, AppError> {
        holdings.value = holdings.value.filterNot { it.id == id }
        lots.value = lots.value.filterNot { it.holdingId == id }
        return Ok(Unit)
    }

    override suspend fun deleteLot(id: String): Result<Unit, AppError> {
        lots.value = lots.value.filterNot { it.id == id }
        return Ok(Unit)
    }

    /** Result: the real engine's figures for [rows]. Input: [rows]. Output: the performances. */
    private fun priced(rows: List<InvestmentHolding>): List<PricedHolding> =
        rows.mapNotNull { holding ->
            val its = lots.value.filter { it.holdingId == holding.id }
            val performance = (engine.holding(HoldingInput(holding, its)) as? Ok)?.value
            performance?.let { PricedHolding(it, freshness(holding)) }
        }

    /**
     * The real engine's freshness verdict against a fixed today.
     *
     * Fixed rather than the wall clock so a test asserting "stale" stays true next year — the same
     * reason every other date in this module's tests is a literal (P-08). Tests that care about the
     * verdict set [today].
     */
    private fun freshness(holding: InvestmentHolding) =
        (
            engine.priceFreshness(
                PriceFreshnessInput(
                    assetClass = holding.assetClass,
                    pricedOnIsoDate = holding.pricedOnIsoDate,
                    fetchedAtUtcMillis = holding.priceFetchedAtUtcMillis,
                    todayIsoDate = today,
                    nowUtcMillis = 1L,
                ),
            ) as Ok
        ).value
}
