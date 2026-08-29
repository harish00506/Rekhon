package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.FakeIdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.Money
import com.aicfo.core.model.PriceKey
import com.aicfo.core.network.MarketDataApi
import com.aicfo.core.network.MarketQuote
import com.aicfo.domain.engines.investment.InvestmentEngineFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

/**
 * The refresh path — issue 6.5 (SRS §16.1, FR-INV-004, P-01, P-04, EXT-003).
 *
 * Why:  the network layer's own suite already proves what happens to a response. **What is unproven
 *       here is everything on either side of the request**, and each failure is silent:
 *
 *       - the **consent gate**. A refresh that ran with MARKET_DATA off would be a P-01 violation
 *         that no screen displays and no log records. It is proved rather than asserted: the fake
 *         api *throws* if it is called at all, so a gate that stopped working fails the test rather
 *         than being described by it.
 *       - the **request payload**. EXT-003 says only instrument identifiers leave the device; the
 *         captured argument is asserted to be exactly the price keys, so a later edit that widened
 *         it to carry a quantity or an account id would be caught here.
 *       - the **anti-clobber write**. A refresh must move four columns and nothing else. A
 *         read-modify-write would silently revert a rename that landed in between, and the user
 *         would see their holding's name change back with no explanation.
 *       - the **opt-in switch**. A holding with no price key is hand-priced, and a refresh that
 *         overwrote it would replace a number the user typed with one they did not.
 *       - the **TTL gate**. Without it every emission of a Flow that re-renders on every lot edit
 *         becomes a request, which looks like a server problem rather than a client bug.
 * What: every gate, the payload, the write, and the two failure modes.
 * Result: the claim "this app makes no market-data request you did not allow" is checkable.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 *
 * Unencrypted in-memory Room and the **real** engine, following `InvestmentRepositoryTest`: the TTL
 * decision is the engine's, and a stub could not make it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MarketPriceRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var investments: InvestmentRepository
    private lateinit var prices: MarketPriceRepository
    private lateinit var accountId: String

    private val clock = FakeClock(initialMillis = NOW)
    private val consents = FakeConsents()
    private val api = FakeMarketDataApi()
    private val dispatcher = UnconfinedTestDispatcher()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh database, both repositories, one broker account, consent on. */
    @Before
    fun setUp() =
        runTest(dispatcher) {
            database =
                Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    CfoDatabase::class.java,
                ).allowMainThreadQueries().build()
            val dispatchers = TestDispatchers(dispatcher)
            val engine = InvestmentEngineFactory.create()
            val accounts = RepositoryFactory.accounts(database, clock, FakeIdGenerator(), dispatchers, activeProfileId)
            investments =
                RepositoryFactory.investments(
                    database, engine, clock, FakeIdGenerator(), dispatchers, activeProfileId,
                )
            prices =
                RepositoryFactory.marketPrice(
                    database, api, engine, consents, clock, dispatchers, activeProfileId,
                )
            consents.set(ConsentFeature.MARKET_DATA, granted = true)
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

    // --- the gates ------------------------------------------------------------------------------

    /**
     * Input:  a keyed, stale holding, with MARKET_DATA revoked.
     * Output: asserts nothing was asked. The fake throws when called, so this proves the gate holds
     *         rather than asserting that it did.
     */
    @Test
    fun `with consent revoked nothing is asked`() =
        runTest(dispatcher) {
            saveKeyed()
            consents.set(ConsentFeature.MARKET_DATA, granted = false)
            api.failIfCalled = true

            assertEquals(0, prices.refresh().expectOk())
        }

    /**
     * Input:  a consent store that cannot be read at all.
     * Output: asserts an unreadable store reads as *not granted*, never as permission (P-01).
     */
    @Test
    fun `an unreadable consent store is not a grant`() =
        runTest(dispatcher) {
            saveKeyed()
            consents.readable = false
            api.failIfCalled = true

            assertEquals(0, prices.refresh().expectOk())
        }

    /**
     * Input:  a holding with no price key — the hand-priced default.
     * Output: asserts no request is built, so a profile that never opts in never reaches a socket.
     */
    @Test
    fun `a holding with no price key is never fetched for`() =
        runTest(dispatcher) {
            investments.saveHolding(holding()).expectOk()
            api.failIfCalled = true

            assertEquals(0, prices.refresh().expectOk())
        }

    /**
     * Input:  a holding fetched a minute ago, against gold's daily interval.
     * Output: asserts the TTL gate stops the second call. Without it, a screen that re-renders on
     *         every edit would fetch on every render.
     */
    @Test
    fun `a price fetched a minute ago is not fetched again`() =
        runTest(dispatcher) {
            saveKeyed()
            api.quotes = listOf(quote())
            prices.refresh().expectOk()

            clock.advanceBy(Duration.ofMinutes(1))
            api.failIfCalled = true

            assertEquals(0, prices.refresh().expectOk())
        }

    /**
     * Input:  the same holding, a day and a bit later.
     * Output: asserts the interval expiring reopens the gate — the TTL delays a fetch, it does not
     *         cancel it.
     */
    @Test
    fun `a price older than the interval is fetched again`() =
        runTest(dispatcher) {
            saveKeyed()
            api.quotes = listOf(quote())
            prices.refresh().expectOk()

            clock.advanceBy(Duration.ofHours(25))
            api.quotes = listOf(quote(minor = 800_000, asOf = "2027-02-02"))

            assertEquals(1, prices.refresh().expectOk())
            assertEquals(Money(800_000), stored().unitPrice)
        }

    // --- the payload ----------------------------------------------------------------------------

    /**
     * Input:  two holdings of the same instrument plus one of another, all due.
     * Output: asserts the request carries exactly the distinct price keys — no name, no quantity, no
     *         account id, and no duplicate (EXT-003).
     */
    @Test
    fun `the request carries exactly the distinct price keys`() =
        runTest(dispatcher) {
            saveKeyed(name = "SGB 2030")
            saveKeyed(name = "Gold coins")
            saveKeyed(name = "Bitcoin", assetClass = AssetClass.CRYPTO, key = CRYPTO)

            prices.refresh().expectOk()

            assertEquals(listOf(setOf(PriceKey(GOLD), PriceKey(CRYPTO))), api.asked)
        }

    // --- the write ------------------------------------------------------------------------------

    /**
     * Input:  a quote for a holding the user renamed after it was keyed.
     * Output: asserts the four price columns moved and the name and class did not. This is the
     *         guarantee `updatePriceByKey` exists for: a read-modify-write would have reverted the
     *         rename to whatever the refresh had read.
     */
    @Test
    fun `a refresh moves the price and nothing else`() =
        runTest(dispatcher) {
            val id = saveKeyed(name = "SGB 2030")
            api.quotes = listOf(quote())

            assertEquals(1, prices.refresh().expectOk())

            val after = stored(id)
            assertEquals(Money(783_412), after.unitPrice)
            assertEquals("2027-01-15", after.pricedOnIsoDate)
            assertEquals(NOW, after.priceFetchedAtUtcMillis)
            assertEquals("SGB 2030", after.name)
            assertEquals(AssetClass.GOLD, after.assetClass)
            assertEquals(accountId, after.accountId)
        }

    /**
     * Input:  two holdings sharing one price key.
     * Output: asserts one quote updates both. They are the same instrument, so one answer is right
     *         for both — and the count the caller sees is rows, not quotes.
     */
    @Test
    fun `one quote prices every holding that shares its key`() =
        runTest(dispatcher) {
            saveKeyed(name = "SGB 2030")
            saveKeyed(name = "Gold coins")
            api.quotes = listOf(quote())

            assertEquals(2, prices.refresh().expectOk())
        }

    /**
     * Input:  one keyed holding and one hand-priced one, and a quote for the key.
     * Output: asserts the hand-typed price is untouched. A null key is the opt-in switch, and
     *         overwriting a number the user typed with one they did not is the worst outcome here.
     */
    @Test
    fun `a hand-priced holding is not overwritten`() =
        runTest(dispatcher) {
            saveKeyed(name = "SGB 2030")
            val typed = investments.saveHolding(holding(name = "Physical gold", unitPrice = Money(700_000))).expectOk()
            api.quotes = listOf(quote())

            assertEquals(1, prices.refresh().expectOk())
            assertEquals(Money(700_000), stored(typed).unitPrice)
        }

    /**
     * Input:  a keyed holding in the real profile while the demo profile is active.
     * Output: asserts the write is profile-scoped. Price keys are global, so without the profile in
     *         the `WHERE` clause a refresh inside the demo would reach into the real portfolio
     *         (ADR-0006).
     */
    @Test
    fun `a refresh cannot reach another profile's rows`() =
        runTest(dispatcher) {
            val id = saveKeyed()
            activeProfileId.value = DEMO_PROFILE
            api.quotes = listOf(quote())

            assertEquals(0, prices.refresh().expectOk())
            activeProfileId.value = REAL_PROFILE
            assertNull(stored(id).unitPrice)
        }

    // --- failure (P-04) -------------------------------------------------------------------------

    /**
     * Input:  a stored price, then a proxy that fails.
     * Output: asserts the stored price survives and no error reaches the caller. A price that could
     *         not be refreshed is still the best price the app has, and the screen already says how
     *         old it is.
     */
    @Test
    fun `a failed fetch leaves the stored price alone`() =
        runTest(dispatcher) {
            saveKeyed()
            api.quotes = listOf(quote())
            prices.refresh().expectOk()
            clock.advanceBy(Duration.ofHours(25))
            api.failure = AppError.Network(retryable = true)

            assertEquals(0, prices.refresh().expectOk())
            assertEquals(Money(783_412), stored().unitPrice)
        }

    /**
     * Input:  a due holding and a proxy that answers about nothing.
     * Output: asserts an empty answer is not an error and writes nothing.
     */
    @Test
    fun `an empty answer writes nothing`() =
        runTest(dispatcher) {
            saveKeyed()
            api.quotes = emptyList()

            assertEquals(0, prices.refresh().expectOk())
            assertNull(stored().unitPrice)
        }

    /**
     * Input:  a quote whose key matches no holding — the user deleted it mid-flight.
     * Output: asserts zero rows updated, and no failure. A race with a delete is not an error.
     */
    @Test
    fun `a quote for a holding that has gone updates nothing`() =
        runTest(dispatcher) {
            val id = saveKeyed()
            api.quotes = listOf(quote())
            investments.deleteHolding(id).expectOk()

            assertEquals(0, prices.refresh().expectOk())
        }

    // --- helpers --------------------------------------------------------------------------------

    /** Result: a keyed holding, saved. Input: the fields that vary. Output: its id. */
    private suspend fun saveKeyed(
        name: String = "SGB 2030",
        assetClass: AssetClass = AssetClass.GOLD,
        key: String = GOLD,
    ): String =
        investments.saveHolding(
            holding(name = name, assetClass = assetClass).copy(priceKey = PriceKey(key)),
        ).expectOk()

    /** Result: an unkeyed draft. Input: the fields that vary. Output: [HoldingDraft]. */
    private fun holding(
        name: String = "SGB 2030",
        assetClass: AssetClass = AssetClass.GOLD,
        unitPrice: Money? = null,
    ) = HoldingDraft(
        accountId = accountId,
        name = name,
        assetClass = assetClass,
        unitPrice = unitPrice,
        pricedOnIsoDate = if (unitPrice == null) null else "2027-01-01",
    )

    /** Result: one quote for the gold key. Input: [minor], [asOf]. Output: [MarketQuote]. */
    private fun quote(
        minor: Long = 783_412,
        asOf: String = "2027-01-15",
    ) = MarketQuote(priceKey = PriceKey(GOLD), unitPrice = Money(minor), asOfIsoDate = asOf)

    /**
     * Reads a holding back, failing the test if it has gone.
     * Result: the stored holding. Input: [id] — defaults to the profile's only one.
     * Output: [InvestmentHolding].
     */
    private suspend fun stored(id: String? = null) =
        requireNotNull(
            investments.find(id ?: database.investmentHoldingDao().forProfile(REAL_PROFILE).first().id).expectOk(),
        )

    private companion object {
        /** The instant every test starts at — a Friday, so no weekend arithmetic is implied. */
        val NOW = Instant.parse("2027-02-01T06:00:00Z").toEpochMilli()

        const val REAL_PROFILE = "local"
        const val DEMO_PROFILE = "demo"

        const val GOLD = "gold:inr.gram.24k"
        const val CRYPTO = "crypto:btc.inr"
    }
}

/**
 * A market-data client a test can arm, silence, or forbid (issue 6.5).
 * Why:    the throwing mode is the point. A gate asserted with `assertEquals(0, calls)` still passes if
 *         the gate is removed and the api happens to return nothing; a gate proved with a fake that
 *         throws cannot. Every consent and opt-in test here sets [failIfCalled].
 * What: records what it was asked, answers with what the test set.
 * Result: a [MarketDataApi] with no network in it.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 */
private class FakeMarketDataApi : MarketDataApi {
    /** Every key set this was asked about, in order — the EXT-003 assertion reads this. */
    val asked = mutableListOf<Set<PriceKey>>()

    /** What to answer with when no [failure] is set. */
    var quotes: List<MarketQuote> = emptyList()

    /** When set, the answer is this error instead of [quotes]. */
    var failure: AppError? = null

    /** When true, being called at all fails the test. */
    var failIfCalled: Boolean = false

    override suspend fun quotes(keys: Set<PriceKey>): Result<List<MarketQuote>, AppError> {
        check(!failIfCalled) {
            "The market-data api was called, and this test asserts that it cannot be. A gate above " +
                "it — consent, price keys, or the refresh interval — has stopped holding"
        }
        asked += keys
        return failure?.let { error -> Err(error) } ?: Ok(quotes)
    }
}

/**
 * A consent ledger a test can flip or break (issue 6.5).
 * Why:    the same shape `SmsRepositoryTest` uses, plus [readable]: P-01's rule is that an
 *         unreadable store reads as *not granted*, and the only way to test that is to have one.
 * Result: a [ConsentStore] over a `MutableStateFlow`.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 */
private class FakeConsents : ConsentStore {
    private val state = MutableStateFlow<Map<ConsentFeature, ConsentState>>(emptyMap())

    /** When false, every read fails — a corrupt or locked DataStore. */
    var readable: Boolean = true

    /** Result: sets one feature's state. Input: [feature], [granted]. Output: none. */
    fun set(
        feature: ConsentFeature,
        granted: Boolean,
    ) {
        state.value = state.value + (feature to ConsentState(granted = granted))
    }

    override fun observe(feature: ConsentFeature): Flow<Result<ConsentState, AppError>> =
        state.map { current ->
            if (!readable) Err(AppError.Storage("IOException")) else Ok(current[feature] ?: ConsentState.NOT_GRANTED)
        }

    override fun observeAll(): Flow<Result<Map<ConsentFeature, ConsentState>, AppError>> =
        state.map { current -> Ok(ConsentFeature.entries.associateWith { current[it] ?: ConsentState.NOT_GRANTED }) }

    override suspend fun grant(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit).also { set(feature, true) }

    override suspend fun revoke(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit).also { set(feature, false) }
}

/**
 * Unwraps a [Result], failing the test on [Err].
 * Why:    the file-local helper every suite in this module carries — an `as Ok` cast reports a
 *         ClassCastException, which says nothing about which call failed or why.
 * Result: the value. Input: the receiver. Output: [T].
 * Changelog: 2026-08-29 — Created for issue 6.5.
 */
private fun <T> Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        else -> throw AssertionError("expected Ok, got $this")
    }
