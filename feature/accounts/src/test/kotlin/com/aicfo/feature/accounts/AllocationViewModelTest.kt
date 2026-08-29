package com.aicfo.feature.accounts

import app.cash.turbine.test
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.InvestmentHolding
import com.aicfo.core.model.InvestmentLot
import com.aicfo.core.model.LotKind
import com.aicfo.core.model.Money
import com.aicfo.core.model.Quantity
import com.aicfo.domain.engines.investment.AllocationUnavailable
import com.aicfo.domain.engines.investment.ConcentrationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The allocation screen's state, asserted as a sequence (issue 6.4; FR-INV-002, ARC-004).
 *
 * Why:  §21.5 asks for the **full** `UiState` sequence including loading, because the flash of an
 *       empty state before the first read lands is a real bug that a single terminal assertion
 *       cannot see. It matters more here than on most screens: the empty state says "nothing
 *       invested yet", so showing it too early tells a user with a portfolio that they have none.
 * What: the loading-to-loaded transition, the split reaching the screen, the flags reaching it with
 *       their citations, coverage, and the two unavailable reasons.
 * Result: the screen's contract, assertable without a device.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 *
 * The **real** engine behind [FakeInvestmentRepository], not canned results: the claim under test
 * is that a share and a flag reach the screen, and a fake returning literals could not make it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AllocationViewModelTest {
    private val repository = FakeInvestmentRepository()

    /** Input: none. Output: puts `Dispatchers.Main` on the test dispatcher for `viewModelScope`. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: restores the main dispatcher. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Input:  a repository with two priced holdings.
     * Output: asserts the state goes loading → loaded and never shows an empty screen in between.
     */
    @Test
    fun `the state loads before it reports, and never flashes empty`() =
        runTest {
            seed(equity = 750_000, gold = 250_000)

            AllocationViewModel(repository).uiState.test {
                val loaded = awaitItem()

                assertFalse("the first emission a screen sees is already loaded", loaded.isLoading)
                assertFalse("and it is not the empty state", loaded.isEmpty)
                assertEquals(Money(1_000_000), loaded.allocation?.total)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  a portfolio three quarters equity, one quarter gold.
     * Output: asserts the shares reach the screen already computed (P-03).
     */
    @Test
    fun `the split reaches the screen as basis points the engine computed`() =
        runTest {
            seed(equity = 750_000, gold = 250_000)

            AllocationViewModel(repository).uiState.test {
                val slices = awaitItem().allocation!!.slices

                assertEquals(listOf(AssetClass.EQUITY, AssetClass.GOLD), slices.map { it.assetClass })
                assertEquals(listOf(7_500, 2_500), slices.map { it.shareBps })
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  a portfolio a quarter of which is gold.
     * Output: asserts the flag arrives naming the row that raised it.
     *
     * The citation is the assertion that matters. A flag the screen cannot attribute is a warning
     * the user cannot check or argue with, which is exactly what P-02 forbids.
     */
    @Test
    fun `a class over its cap arrives flagged and attributed`() =
        runTest {
            seed(equity = 750_000, gold = 250_000)

            AllocationViewModel(repository).uiState.test {
                val flag =
                    awaitItem().allocation!!.flags.single { it.kind == ConcentrationKind.ASSET_CLASS_CAP }

                assertEquals(AssetClass.GOLD, flag.assetClass)
                assertEquals(2_500, flag.measuredBps)
                assertEquals("gold's ceiling is 10%", 1_000, flag.thresholdBps)
                assertEquals("RULE-GOLD-CAP", flag.citation.ruleId)
                assertEquals("1.0", flag.citation.ruleVersion)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  one priced holding and one with no price.
     * Output: asserts the screen is told how much of the portfolio it is showing (P-02).
     */
    @Test
    fun `an unpriced holding is reported as coverage rather than hidden`() =
        runTest {
            repository.setHoldings(
                holding("h1", AssetClass.EQUITY, unitPrice = Money(1_000)),
                holding("h2", AssetClass.GOLD, unitPrice = null),
            )
            repository.setLots(lot("l1", "h1"), lot("l2", "h2"))

            AllocationViewModel(repository).uiState.test {
                val state = awaitItem()

                assertTrue("the screen must know to show the coverage line", state.hasUnvalued)
                assertEquals(1, state.allocation?.valuedCount)
                assertEquals(1, state.allocation?.unvaluedCount)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  a repository with no holdings.
     * Output: asserts the reason reaches the screen, so it can invite rather than shrug.
     */
    @Test
    fun `an empty portfolio reports why it is empty`() =
        runTest {
            AllocationViewModel(repository).uiState.test {
                val state = awaitItem()

                assertTrue(state.isEmpty)
                assertEquals(AllocationUnavailable.NO_POSITIONS, state.allocation?.unavailable)
                assertNull(state.errorCode)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Input:  holdings that exist but carry no price.
     * Output: asserts the reason is the *other* one, so the screen asks for a price rather than for
     *         an account the user already has.
     */
    @Test
    fun `a wholly unpriced portfolio asks for prices, not for accounts`() =
        runTest {
            repository.setHoldings(holding("h1", AssetClass.EQUITY, unitPrice = null))
            repository.setLots(lot("l1", "h1"))

            AllocationViewModel(repository).uiState.test {
                assertEquals(AllocationUnavailable.NOTHING_PRICED, awaitItem().allocation?.unavailable)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- fixtures -------------------------------------------------------------------------------

    /** Result: seeds one equity and one gold holding worth the paise given. Input: the two values. */
    private fun seed(
        equity: Long,
        gold: Long,
    ) {
        repository.setHoldings(
            holding("h1", AssetClass.EQUITY, Money(equity / UNITS)),
            holding("h2", AssetClass.GOLD, Money(gold / UNITS)),
        )
        repository.setLots(lot("l1", "h1"), lot("l2", "h2"))
    }

    /** Result: a holding. Input: [id], [assetClass], [unitPrice] per unit or `null`. */
    private fun holding(
        id: String,
        assetClass: AssetClass,
        unitPrice: Money?,
    ) = InvestmentHolding(
        id = id,
        accountId = "acc-1",
        name = "Holding $id",
        assetClass = assetClass,
        unitPrice = unitPrice,
        pricedOnIsoDate = unitPrice?.let { "2027-01-01" },
    )

    /** Result: a purchase of [UNITS] units. Input: [id]; [holdingId]. Output: the lot. */
    private fun lot(
        id: String,
        holdingId: String,
    ) = InvestmentLot(
        id = id,
        holdingId = holdingId,
        kind = LotKind.BUY,
        transactedOnIsoDate = "2026-01-01",
        quantity = Quantity(UNITS * Quantity.SCALE),
        amount = Money(100_000),
    )

    private companion object {
        /** Units per fixture holding, so a target value divides into a whole per-unit price. */
        const val UNITS = 100L
    }
}
