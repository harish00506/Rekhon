package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.FakeIdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.LotKind
import com.aicfo.core.model.Money
import com.aicfo.core.model.Quantity
import com.aicfo.domain.engines.investment.AllocationUnavailable
import com.aicfo.domain.engines.investment.InvestmentEngineFactory
import com.aicfo.domain.engines.investment.XirrUnavailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The data half of holdings — issue 6.3 (SRS §11, ARC-005).
 *
 * Why:  `InvestmentEngineTest`, the property test and the golden file already prove the arithmetic
 *       against fixtures, so repeating it here would assert nothing new. **What is unproven above
 *       SQLite is what this class owns**, and each part fails while still returning something
 *       plausible:
 *
 *       - the **round trip**. Seven columns and two nullables; storing an unentered unit price as
 *         `0` instead of `NULL` would turn "not valued yet" into "worthless", and the screen would
 *         show a total loss with no way to tell it was a storage bug.
 *       - the **grouping**. Lots reach the engine grouped by holding id; group them wrong and every
 *         holding gets somebody else's cash flows, producing returns that are wrong and plausible
 *         at the same time.
 *       - the **type guard**: a holding hung off a savings account would give a bank balance an
 *         asset class and an XIRR over lots it has not got.
 *       - the **delete cascade**: a tombstoned holding whose lots stayed live leaves the engine
 *         summing flows for something the user removed, and there is no foreign key to catch it.
 * What: the round trip, the grouping, both guards, the cascade, and the unpriced case end to end.
 * Result: the first holding figures in the app are proven against a real SQL engine.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * Unencrypted in-memory Room and the **real** engine rather than a stub, the reasoning
 * `LoanRepositoryTest` gives: the claim is that a return reaches the screen, and a stub could not
 * make it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InvestmentRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var investments: InvestmentRepository
    private lateinit var accounts: AccountRepository
    private lateinit var accountId: String

    private val clock = FakeClock(initialMillis = Instant.parse("2027-02-01T06:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()
    private val dispatcher = UnconfinedTestDispatcher()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database, the repositories, and one broker account. */
    @Before
    fun setUp() =
        runTest(dispatcher) {
            database =
                Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    CfoDatabase::class.java,
                ).allowMainThreadQueries().build()
            val dispatchers = TestDispatchers(dispatcher)
            accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId)
            investments =
                RepositoryFactory.investments(
                    database, InvestmentEngineFactory.create(), clock, ids, dispatchers, activeProfileId,
                )
            accountId =
                accounts.create(
                    AccountDraft(
                        name = "Zerodha",
                        type = AccountType.INVESTMENT,
                        openingBalance = Money(0),
                        currencyCode = "INR",
                    ),
                ).expectOk().id
        }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    /**
     * A draft holding on the fixture account, priced a year after its purchase.
     * Result: the draft. Input: the fields that vary. Output: [HoldingDraft].
     */
    private fun holding(
        name: String = "Parag Parikh Flexi Cap",
        assetClass: AssetClass = AssetClass.EQUITY,
        unitPrice: Money? = Money(8_250),
        pricedOn: String? = "2027-01-01",
    ) = HoldingDraft(
        accountId = accountId,
        name = name,
        assetClass = assetClass,
        unitPrice = unitPrice,
        pricedOnIsoDate = pricedOn,
    )

    /** Result: a draft lot on [holdingId]. Input: the fields that vary. Output: [LotDraft]. */
    private fun lot(
        holdingId: String,
        kind: LotKind = LotKind.BUY,
        day: String = "2026-01-01",
        units: Long = 100,
        minor: Long = 750_000,
    ) = LotDraft(
        holdingId = holdingId,
        kind = kind,
        transactedOnIsoDate = day,
        quantity = Quantity(units * Quantity.SCALE),
        amount = Money(minor),
    )

    // --- the round trip -------------------------------------------------------------------------

    /**
     * Input:  a holding saved and read back.
     * Output: asserts every field survives — the class string, the paise, and the ISO date.
     */
    @Test
    fun `a holding round-trips through SQL`() =
        runTest(dispatcher) {
            val id = investments.saveHolding(holding()).expectOk()

            val stored = investments.find(id).expectOk()

            assertEquals("Parag Parikh Flexi Cap", stored?.name)
            assertEquals(AssetClass.EQUITY, stored?.assetClass)
            assertEquals(Money(8_250), stored?.unitPrice)
            assertEquals("2027-01-01", stored?.pricedOnIsoDate)
            assertEquals(accountId, stored?.accountId)
        }

    /**
     * Input:  a holding with no price at all.
     * Output: asserts the absence survives as `NULL` rather than becoming zero.
     *
     * The most consequential round-trip case in the file: a zero here reads as a worthless holding
     * and shows the user their whole cost as a loss (P-03).
     */
    @Test
    fun `an unentered price stays absent, never becomes zero`() =
        runTest(dispatcher) {
            val id = investments.saveHolding(holding(unitPrice = null, pricedOn = null)).expectOk()

            val stored = investments.find(id).expectOk()

            assertNull("an unentered price must stay absent", stored?.unitPrice)
            assertNull("and so must its date", stored?.pricedOnIsoDate)
        }

    /** Input: a holding saved twice under its own id. Output: asserts the second write is an edit. */
    @Test
    fun `saving an existing holding edits the one row`() =
        runTest(dispatcher) {
            val id = investments.saveHolding(holding()).expectOk()

            investments.saveHolding(holding().copy(name = "PPFAS Flexi Cap"), id).expectOk()

            assertEquals(1, database.investmentHoldingDao().forProfile(REAL_PROFILE).size)
            assertEquals("PPFAS Flexi Cap", investments.find(id).expectOk()?.name)
        }

    /** Input: three lots on one holding. Output: asserts each round-trips with its kind and units. */
    @Test
    fun `lots round-trip with their kind, units and cash`() =
        runTest(dispatcher) {
            val id = investments.saveHolding(holding()).expectOk()
            investments.saveLot(lot(id)).expectOk()
            investments
                .saveLot(lot(id, kind = LotKind.SELL, day = "2026-10-01", units = 30, minor = 260_000))
                .expectOk()
            investments
                .saveLot(lot(id, kind = LotKind.INCOME, day = "2026-11-01", units = 0, minor = 12_000))
                .expectOk()

            val stored = investments.lotsOf(id).expectOk()

            assertEquals(3, stored.size)
            assertEquals(listOf(LotKind.BUY, LotKind.SELL, LotKind.INCOME), stored.map { it.kind })
            assertEquals(Quantity(100 * Quantity.SCALE), stored.first().quantity)
            assertEquals(Money(750_000), stored.first().amount)
        }

    // --- the grouping ---------------------------------------------------------------------------

    /**
     * Input:  two holdings on one account, each with its own lots.
     * Output: asserts each holding is priced from **its own** lots and nobody else's.
     *
     * The failure this guards is not a crash: mis-grouped lots produce returns that are wrong and
     * entirely plausible, which is the only kind that reaches a user.
     */
    @Test
    fun `each holding is priced from its own lots`() =
        runTest(dispatcher) {
            val equity = investments.saveHolding(holding()).expectOk()
            val debt =
                investments.saveHolding(
                    holding(name = "Liquid Fund", assetClass = AssetClass.DEBT, unitPrice = Money(10_000)),
                ).expectOk()
            investments.saveLot(lot(equity, units = 100, minor = 750_000)).expectOk()
            investments.saveLot(lot(debt, units = 20, minor = 200_000)).expectOk()

            val byAccount = investments.observeByAccount().first()
            val priced = byAccount.getValue(accountId).associateBy { it.performance.holdingId }

            assertEquals(Quantity(100 * Quantity.SCALE), priced.getValue(equity).performance.netQuantity)
            assertEquals(Money(825_000), priced.getValue(equity).performance.currentValue)
            assertEquals(Quantity(20 * Quantity.SCALE), priced.getValue(debt).performance.netQuantity)
            assertEquals(Money(200_000), priced.getValue(debt).performance.currentValue)
        }

    /**
     * Input:  one purchase and a price a year later.
     * Output: asserts a real money-weighted return reaches the caller through SQL.
     *
     * ₹7,500 for 100 units, worth ₹8,250 exactly 365 days later: +10%.
     */
    @Test
    fun `a return computed from stored rows reaches the caller`() =
        runTest(dispatcher) {
            val id = investments.saveHolding(holding()).expectOk()
            investments.saveLot(lot(id, day = "2026-01-01", units = 100, minor = 750_000)).expectOk()

            val priced = investments.observeForAccount(accountId).first().single().performance

            assertEquals(1_000, priced.xirrBps)
            assertNull(priced.xirrUnavailable)
            assertEquals(Money(75_000), priced.gain)
        }

    /**
     * Input:  a holding with lots but no price.
     * Output: asserts the value and gain are absent and the reason for the missing rate is carried.
     */
    @Test
    fun `an unpriced holding reaches the screen with its reason, not a zero`() =
        runTest(dispatcher) {
            val id = investments.saveHolding(holding(unitPrice = null, pricedOn = null)).expectOk()
            investments.saveLot(lot(id)).expectOk()
            investments.saveLot(lot(id, day = "2026-07-01", units = 50, minor = 400_000)).expectOk()

            val priced = investments.observeForAccount(accountId).first().single().performance

            assertNull(priced.currentValue)
            assertNull(priced.gain)
            assertEquals(Money(1_150_000), priced.invested)
            assertEquals(XirrUnavailable.SAME_SIGN, priced.xirrUnavailable)
        }

    /**
     * Input:  an account with no holdings.
     * Output: asserts it is **absent** from the map rather than present with an empty list.
     *
     * "Not set up yet" and "worth nothing" are different things, and the accounts screen renders
     * them differently (P-03).
     */
    @Test
    fun `an account with no holdings is absent from the map, not zero`() =
        runTest(dispatcher) {
            val byAccount = investments.observeByAccount().first()

            assertTrue("no holdings means no key", accountId !in byAccount)
        }

    // --- the guards -----------------------------------------------------------------------------

    /**
     * Input:  a holding hung off a savings account.
     * Output: asserts it is refused before anything is written.
     */
    @Test
    fun `a holding cannot hang off an account that holds no instruments`() =
        runTest(dispatcher) {
            val savings =
                accounts.create(
                    AccountDraft(
                        name = "HDFC Savings",
                        type = AccountType.BANK,
                        openingBalance = Money(5_000_000),
                        currencyCode = "INR",
                    ),
                ).expectOk().id

            val refused = investments.saveHolding(holding().copy(accountId = savings))

            assertEquals(Err(AppError.Validation("account.notInvestable")), refused)
            assertTrue(database.investmentHoldingDao().forProfile(REAL_PROFILE).isEmpty())
        }

    /** Input: a holding on an account that does not exist. Output: asserts `NotFound`. */
    @Test
    fun `a holding on a missing account is refused`() =
        runTest(dispatcher) {
            val refused = investments.saveHolding(holding().copy(accountId = "account:gone"))

            assertEquals(Err(AppError.NotFound), refused)
        }

    /** Input: a lot on a holding that does not exist. Output: asserts `NotFound`. */
    @Test
    fun `a lot on a missing holding is refused`() =
        runTest(dispatcher) {
            val refused = investments.saveLot(lot("holding:gone"))

            assertEquals(Err(AppError.NotFound), refused)
        }

    /**
     * Input:  a holding with two lots, then deleted.
     * Output: asserts both the holding and its lots are tombstoned, and neither is erased.
     */
    @Test
    fun `deleting a holding tombstones its lots too, and erases nothing`() =
        runTest(dispatcher) {
            val id = investments.saveHolding(holding()).expectOk()
            investments.saveLot(lot(id)).expectOk()
            investments.saveLot(lot(id, day = "2026-07-01")).expectOk()

            investments.deleteHolding(id).expectOk()

            assertNull("the holding is gone from live reads", investments.find(id).expectOk())
            assertTrue("and so are its lots", investments.lotsOf(id).expectOk().isEmpty())
            assertTrue("no holdings left live", database.investmentHoldingDao().forProfile(REAL_PROFILE).isEmpty())
            assertEquals(
                "but nothing was erased — DB-003",
                2,
                database.archiveDao().investmentLots(REAL_PROFILE).size,
            )
        }

    /** Input: one lot of two, deleted. Output: asserts only that lot leaves the live read. */
    @Test
    fun `deleting one lot leaves the rest of the holding alone`() =
        runTest(dispatcher) {
            val id = investments.saveHolding(holding()).expectOk()
            val first = investments.saveLot(lot(id)).expectOk()
            investments.saveLot(lot(id, day = "2026-07-01")).expectOk()

            investments.deleteLot(first).expectOk()

            assertEquals(1, investments.lotsOf(id).expectOk().size)
            assertEquals("2026-07-01", investments.lotsOf(id).expectOk().single().transactedOnIsoDate)
        }

    // --- allocation (issue 6.4, FR-INV-002, ADR-0029) -------------------------------------------

    /**
     * Input:  a broker account with two priced holdings.
     * Output: asserts the split reaches the caller with both classes and the right denominator.
     *
     * The engine's arithmetic is proven above SQLite already; what is unproven is that the
     * repository hands it the right *positions*. Holdings must arrive one position each rather than
     * one per account, or `RULE-CONC-15-70`'s single-holding test would be measuring accounts.
     */
    @Test
    fun `a broker account contributes one position per holding, not one per account`() =
        runTest(dispatcher) {
            val equity = investments.saveHolding(holding(name = "Flexi Cap")).expectOk()
            investments.saveLot(lot(equity, units = 100, minor = 750_000)).expectOk()
            val debt = investments.saveHolding(holding(name = "Gilt Fund", assetClass = AssetClass.DEBT)).expectOk()
            investments.saveLot(lot(debt, units = 100, minor = 750_000)).expectOk()

            val allocation = investments.observeAllocation().first()

            assertNull("both holdings are priced", allocation.unavailable)
            assertEquals("100 units at ₹82.50 each, twice", Money(1_650_000), allocation.total)
            assertEquals(2, allocation.slices.size)
            assertEquals(2, allocation.valuedCount)
            assertEquals(
                "each holding is its own position, so each is half the portfolio",
                listOf(5_000, 5_000),
                allocation.slices.map { it.shareBps },
            )
        }

    /**
     * Input:  a gold account with a balance and no holdings.
     * Output: asserts it is counted whole, classed by [AssetClass.defaultFor].
     *
     * This is the case that would silently vanish. A gold account the user tracks as one number has
     * no holding rows to price, so a denominator built only from holdings would omit it — and
     * `RULE-GOLD-CAP` would then never fire for the users most likely to trip it.
     */
    @Test
    fun `an account with no holdings is counted whole, at its balance`() =
        runTest(dispatcher) {
            val equity = investments.saveHolding(holding()).expectOk()
            investments.saveLot(lot(equity, units = 100, minor = 750_000)).expectOk()
            accounts.create(
                AccountDraft(
                    name = "SBI Gold Deposit",
                    type = AccountType.GOLD,
                    openingBalance = Money(1_650_000),
                    currencyCode = "INR",
                ),
            ).expectOk()

            val allocation = investments.observeAllocation().first()

            assertEquals("₹8,250 of equity plus the ₹16,500 gold balance", Money(2_475_000), allocation.total)
            assertEquals(
                "the gold account is a position even though it holds no instruments",
                setOf(AssetClass.EQUITY, AssetClass.GOLD),
                allocation.slices.map { it.assetClass }.toSet(),
            )
            assertTrue(
                "and at two thirds of the portfolio it is far past its 10% cap",
                allocation.flags.any { it.assetClass == AssetClass.GOLD && it.citation.ruleId == "RULE-GOLD-CAP" },
            )
        }

    /**
     * Input:  a savings account holding more than the whole portfolio.
     * Output: asserts it is outside the denominator entirely (ADR-0029).
     *
     * The decision this pins is the one most likely to be quietly reversed by someone "fixing" the
     * allocation to match net worth. A portfolio is the money being invested; counting an emergency
     * fund would put nearly every user permanently past the 70% single-class line.
     */
    @Test
    fun `a savings balance is not part of the portfolio`() =
        runTest(dispatcher) {
            val equity = investments.saveHolding(holding()).expectOk()
            investments.saveLot(lot(equity, units = 100, minor = 750_000)).expectOk()
            accounts.create(
                AccountDraft(
                    name = "HDFC Savings",
                    type = AccountType.BANK,
                    openingBalance = Money(50_000_000),
                    currencyCode = "INR",
                ),
            ).expectOk()

            val allocation = investments.observeAllocation().first()

            assertEquals("only the ₹8,250 of equity", Money(825_000), allocation.total)
            assertEquals(listOf(AssetClass.EQUITY), allocation.slices.map { it.assetClass })
        }

    /**
     * Input:  an account the user excluded from net worth.
     * Output: asserts it is excluded from the portfolio too — it is not the user's to count.
     */
    @Test
    fun `an account excluded from net worth is excluded from the portfolio`() =
        runTest(dispatcher) {
            val equity = investments.saveHolding(holding()).expectOk()
            investments.saveLot(lot(equity, units = 100, minor = 750_000)).expectOk()
            val theirs =
                accounts.create(
                    AccountDraft(
                        name = "Held for a parent",
                        type = AccountType.GOLD,
                        openingBalance = Money(9_000_000),
                        currencyCode = "INR",
                    ),
                ).expectOk()
            val notMine =
                AccountDraft(
                    name = theirs.name,
                    type = theirs.type,
                    openingBalance = theirs.openingBalance,
                    currencyCode = "INR",
                    includeInNetWorth = false,
                )
            accounts.update(theirs.id, notMine).expectOk()

            val allocation = investments.observeAllocation().first()

            assertEquals(Money(825_000), allocation.total)
            assertEquals(listOf(AssetClass.EQUITY), allocation.slices.map { it.assetClass })
        }

    /**
     * Input:  a holding with lots but no price.
     * Output: asserts it is counted as unvalued rather than as ₹0 (P-03).
     */
    @Test
    fun `an unpriced holding is reported as unvalued rather than counted at zero`() =
        runTest(dispatcher) {
            val priced = investments.saveHolding(holding()).expectOk()
            investments.saveLot(lot(priced, units = 100, minor = 750_000)).expectOk()
            val unpriced =
                investments.saveHolding(
                    holding(name = "SGB 2030", assetClass = AssetClass.GOLD, unitPrice = null, pricedOn = null),
                ).expectOk()
            investments.saveLot(lot(unpriced, units = 10, minor = 500_000)).expectOk()

            val allocation = investments.observeAllocation().first()

            assertEquals(1, allocation.valuedCount)
            assertEquals(1, allocation.unvaluedCount)
            assertEquals("the unpriced gold is not in the denominator", Money(825_000), allocation.total)
            assertEquals(
                "and gold does not appear as a 0% slice",
                listOf(AssetClass.EQUITY),
                allocation.slices.map { it.assetClass },
            )
            assertEquals("half the positions could be seen", 5_000, allocation.provenance.confidenceBps)
        }

    /**
     * Input:  a profile with no investable account at all.
     * Output: asserts a reason comes back rather than an empty split the screen cannot explain.
     */
    @Test
    fun `a profile with nothing invested reports why`() =
        runTest(dispatcher) {
            val allocation = investments.observeAllocation().first()

            assertEquals(AllocationUnavailable.NO_POSITIONS, allocation.unavailable)
            assertTrue(allocation.slices.isEmpty())
            assertTrue(
                "but the rules it would have applied are still named",
                allocation.provenance.evidence.isNotEmpty(),
            )
        }

    /**
     * Unwraps an `Ok`, failing the test on an `Err`.
     *
     * Matched on the type rather than `(this as? Ok)?.value ?: error(...)`, because that shorter
     * form treats a perfectly good `Ok(null)` as a failure — and `find` returning `Ok(null)` for a
     * holding that was deleted is one of the behaviours under test here.
     */
    private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> error("expected Ok, was $this")
        }

    private companion object {
        const val REAL_PROFILE = "profile:real"
    }
}
