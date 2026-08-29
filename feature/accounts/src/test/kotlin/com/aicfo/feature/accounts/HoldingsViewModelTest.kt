package com.aicfo.feature.accounts

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.aicfo.core.common.Ok
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.InvestmentHolding
import com.aicfo.core.model.InvestmentLot
import com.aicfo.core.model.LotKind
import com.aicfo.core.model.Money
import com.aicfo.core.model.Quantity
import com.aicfo.domain.engines.investment.XirrUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The holdings screen's state machine (issue 6.3; §11, ARC-004).
 *
 * Why:  the screen's arithmetic is already proven three layers down, so what is left here is the
 *       part only this class owns — **turning what the user typed into a draft**. Every case below
 *       is one a person hits on their first use:
 *
 *       - a **blank price**, which means "not valued yet" and must be accepted, while a *mistyped*
 *         price must not be. Collapsing the two is how an unparseable amount becomes a silent zero.
 *       - a **price without its date**, which would leave XIRR with no terminal date and an answer
 *         that changed by the day for a holding nobody touched (P-08).
 *       - **fractional units**, which are the normal case for a mutual fund and the exact place a
 *         `toDouble()` would creep in.
 * What: the editor's open/fill/save cycle, both parse refusals, the delete, and the list.
 * Result: the screen's every state is reachable without a device.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HoldingsViewModelTest {
    private val repository = FakeInvestmentRepository()

    /** Input: none. Output: pins `viewModelScope` to a test dispatcher so writes run inline. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    /** Input: none. Output: releases the main dispatcher between tests. */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Result: a ViewModel on the fixture account. Input: none. Output: [HoldingsViewModel]. */
    private fun viewModel() =
        HoldingsViewModel(
            repository,
            SavedStateHandle(mapOf(HoldingsViewModel.ACCOUNT_ID_KEY to ACCOUNT)),
        )

    /** Result: what the fake holds under [id], or `null`. Input: [id]. Output: [InvestmentHolding]?. */
    private suspend fun stored(id: String): InvestmentHolding? = (repository.find(id) as Ok).value

    /** Result: a stored holding. Input: the fields that vary. Output: [InvestmentHolding]. */
    private fun holding(
        id: String = "holding:1",
        unitPrice: Money? = Money(8_250),
        pricedOn: String? = "2027-01-01",
    ) = InvestmentHolding(
        id = id,
        accountId = ACCOUNT,
        name = "Parag Parikh Flexi Cap",
        assetClass = AssetClass.EQUITY,
        unitPrice = unitPrice,
        pricedOnIsoDate = pricedOn,
    )

    /** Input: a seeded holding and lot. Output: asserts the priced row reaches the state. */
    @Test
    fun `the list carries what the engine computed`() =
        runTest {
            repository.setHoldings(holding())
            repository.setLots(
                InvestmentLot(
                    id = "lot:1",
                    holdingId = "holding:1",
                    kind = LotKind.BUY,
                    transactedOnIsoDate = "2026-01-01",
                    quantity = Quantity(100 * Quantity.SCALE),
                    amount = Money(750_000),
                ),
            )

            viewModel().uiState.test {
                val loaded = awaitItem()
                assertFalse(loaded.isLoading)
                val row = loaded.holdings.single()
                assertEquals("Parag Parikh Flexi Cap", row.name)
                assertEquals(Money(825_000), row.currentValue)
                assertEquals("100 units bought, worth 825000 a year later", 1_000, row.xirrBps)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /** Input: an account with nothing in it. Output: asserts the empty state, not a spinner. */
    @Test
    fun `an account with nothing in it settles on empty rather than loading`() =
        runTest {
            viewModel().uiState.test {
                val loaded = awaitItem()
                assertFalse(loaded.isLoading)
                assertTrue(loaded.isEmpty)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /** Input: the add event. Output: asserts an empty editor opens. */
    @Test
    fun `adding a holding opens an empty editor`() =
        runTest {
            val subject = viewModel()

            subject.onEvent(HoldingsEvent.AddHolding)

            val editor = assertNotNull(subject.uiState.value.editor)
            assertNull("a new holding has no id yet", subject.uiState.value.editor?.holdingId)
            assertEquals("", subject.uiState.value.editor?.name)
        }

    /**
     * Input:  a filled editor with a price and its date.
     * Output: asserts the holding is written and the editor closes.
     */
    @Test
    fun `saving a filled editor writes the holding and closes`() =
        runTest {
            val subject = viewModel()
            subject.onEvent(HoldingsEvent.AddHolding)
            subject.onEvent(HoldingsEvent.NameChanged("Parag Parikh Flexi Cap"))
            subject.onEvent(HoldingsEvent.UnitPriceChanged("82.50"))
            subject.onEvent(HoldingsEvent.PricedOnChanged("2027-01-01"))

            subject.onEvent(HoldingsEvent.SaveEditor)

            assertNull("a successful save closes the editor", subject.uiState.value.editor)
            val stored = stored("holding:1")
            assertEquals(Money(8_250), stored?.unitPrice)
            assertEquals("2027-01-01", stored?.pricedOnIsoDate)
        }

    /**
     * Input:  an editor with no price at all.
     * Output: asserts it saves, with the price absent rather than zero.
     *
     * "Not valued yet" is the ordinary state of a holding somebody just added, and refusing it
     * would force a made-up price onto every new row (P-03).
     */
    @Test
    fun `a holding with no price yet still saves`() =
        runTest {
            val subject = viewModel()
            subject.onEvent(HoldingsEvent.AddHolding)
            subject.onEvent(HoldingsEvent.NameChanged("Unpriced Fund"))

            subject.onEvent(HoldingsEvent.SaveEditor)

            assertNull(subject.uiState.value.editor)
            val stored = stored("holding:1")
            assertNull("absent, never zero", stored?.unitPrice)
        }

    /**
     * Input:  a price with no date beside it.
     * Output: asserts the save is refused and the editor stays open naming the field.
     *
     * Without the date there is no terminal flow date, so the return would change with the calendar
     * rather than with the holding (P-08).
     */
    @Test
    fun `a price without its date is refused rather than guessed at`() =
        runTest {
            val subject = viewModel()
            subject.onEvent(HoldingsEvent.AddHolding)
            subject.onEvent(HoldingsEvent.NameChanged("Parag Parikh Flexi Cap"))
            subject.onEvent(HoldingsEvent.UnitPriceChanged("82.50"))

            subject.onEvent(HoldingsEvent.SaveEditor)

            assertEquals(HoldingsViewModel.FIELD_HOLDING, subject.uiState.value.editor?.fieldError)
            assertNull("nothing was written", stored("holding:1"))
        }

    /** Input: an editor with no name. Output: asserts the save is refused. */
    @Test
    fun `a holding with no name is refused`() =
        runTest {
            val subject = viewModel()
            subject.onEvent(HoldingsEvent.AddHolding)

            subject.onEvent(HoldingsEvent.SaveEditor)

            assertEquals(HoldingsViewModel.FIELD_HOLDING, subject.uiState.value.editor?.fieldError)
        }

    /**
     * Input:  a lot with three decimal places of units.
     * Output: asserts they survive exactly.
     *
     * A mutual fund quotes three decimals and crypto quotes eight; a `toDouble()` here would drift
     * against the statement the number was typed from.
     */
    @Test
    fun `fractional units survive the parse exactly`() =
        runTest {
            val subject = viewModel()
            subject.onEvent(HoldingsEvent.AddHolding)
            subject.onEvent(HoldingsEvent.NameChanged("Parag Parikh Flexi Cap"))
            subject.onEvent(HoldingsEvent.AddLot)
            subject.onEvent(
                HoldingsEvent.LotChanged(
                    0,
                    LotEditorState(kind = LotKind.BUY, day = "2026-01-01", units = "123.456", amount = "1000"),
                ),
            )

            subject.onEvent(HoldingsEvent.SaveEditor)

            val stored = (repository.lotsOf("holding:1") as Ok).value
            assertEquals(Quantity(123_456_000_000L), stored.single().quantity)
        }

    /** Input: a lot with an unparseable amount. Output: asserts the save is refused, naming the lot. */
    @Test
    fun `a lot with an amount that will not parse is refused`() =
        runTest {
            val subject = viewModel()
            subject.onEvent(HoldingsEvent.AddHolding)
            subject.onEvent(HoldingsEvent.NameChanged("Parag Parikh Flexi Cap"))
            subject.onEvent(HoldingsEvent.AddLot)
            subject.onEvent(
                HoldingsEvent.LotChanged(0, LotEditorState(day = "2026-01-01", units = "1", amount = "lots")),
            )

            subject.onEvent(HoldingsEvent.SaveEditor)

            assertEquals(HoldingsViewModel.FIELD_LOT, subject.uiState.value.editor?.fieldError)
        }

    /** Input: an existing holding opened for edit. Output: asserts its fields fill the editor. */
    @Test
    fun `editing an existing holding fills the editor from the store`() =
        runTest {
            repository.setHoldings(holding())

            val subject = viewModel()
            subject.onEvent(HoldingsEvent.EditHolding("holding:1"))

            val editor = subject.uiState.value.editor
            assertEquals("holding:1", editor?.holdingId)
            assertEquals("Parag Parikh Flexi Cap", editor?.name)
            assertEquals(AssetClass.EQUITY, editor?.assetClass)
            assertEquals("2027-01-01", editor?.pricedOn)
        }

    /** Input: a delete. Output: asserts the row leaves the list. */
    @Test
    fun `deleting a holding removes it from the list`() =
        runTest {
            repository.setHoldings(holding())
            val subject = viewModel()

            subject.onEvent(HoldingsEvent.DeleteHolding("holding:1"))

            assertTrue(subject.uiState.value.holdings.isEmpty())
        }

    /** Input: a holding with lots but no price. Output: asserts the reason travels to the state. */
    @Test
    fun `an unpriced holding carries the reason it has no return`() =
        runTest {
            repository.setHoldings(holding(unitPrice = null, pricedOn = null))
            repository.setLots(
                InvestmentLot(
                    id = "lot:1",
                    holdingId = "holding:1",
                    kind = LotKind.BUY,
                    transactedOnIsoDate = "2026-01-01",
                    quantity = Quantity(100 * Quantity.SCALE),
                    amount = Money(750_000),
                ),
                InvestmentLot(
                    id = "lot:2",
                    holdingId = "holding:1",
                    kind = LotKind.BUY,
                    transactedOnIsoDate = "2026-07-01",
                    quantity = Quantity(50 * Quantity.SCALE),
                    amount = Money(400_000),
                ),
            )

            val row = viewModel().uiState.value.holdings.single()

            assertNull(row.currentValue)
            assertEquals(XirrUnavailable.SAME_SIGN, row.xirrUnavailable)
        }

    private companion object {
        const val ACCOUNT = "account:zerodha"
    }
}
