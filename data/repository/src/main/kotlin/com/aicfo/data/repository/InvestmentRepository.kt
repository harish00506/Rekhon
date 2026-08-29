package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.dao.AccountWithBalance
import com.aicfo.core.database.entity.InvestmentHoldingEntity
import com.aicfo.core.database.entity.InvestmentLotEntity
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.InvestmentHolding
import com.aicfo.core.model.InvestmentLot
import com.aicfo.core.model.LotKind
import com.aicfo.core.model.Money
import com.aicfo.core.model.PriceKey
import com.aicfo.core.model.Quantity
import com.aicfo.domain.engines.investment.AllocationInput
import com.aicfo.domain.engines.investment.HoldingInput
import com.aicfo.domain.engines.investment.HoldingPerformance
import com.aicfo.domain.engines.investment.InvestmentEngine
import com.aicfo.domain.engines.investment.PortfolioAllocation
import com.aicfo.domain.engines.investment.PortfolioPosition
import com.aicfo.domain.engines.investment.PriceFreshness
import com.aicfo.domain.engines.investment.PriceFreshnessInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * The only class that reads or writes holdings and their lots (issue 6.3; §11, ARC-005).
 *
 * Why:  the investment engine is pure — it takes a holding, a list of lots, and answers. Something
 *       has to turn stored rows into those arguments and the answer back into something a screen
 *       can hold, and this is it. Two things happen here and nowhere else:
 *
 *       **The clock read.** The engine deliberately has no clock and no `asOf` parameter: a
 *       holding's terminal cash flow is dated by the day its price was observed, so the return does
 *       not change because the day did (P-08). The clock is still read here, for the `created_at` /
 *       `updated_at` stamps and for provenance (TIM-001) — but nothing it returns reaches the
 *       arithmetic.
 *
 *       **The type check.** A holding hung off a savings account would give a bank balance an asset
 *       class and an XIRR computed from lots it has not got. That is a data-integrity rule, not a UI
 *       concern, so it lives here — the argument `LoanRepository.save` makes for loans.
 * What: observe every holding's performance for the accounts list and for one account's screen,
 *       write holdings and lots, and soft-delete either.
 * Result: [HoldingPerformance] values the UI can use without knowing Room exists.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * **Nothing here caches a value, a cost or a return.** All three are derived on every read from the
 * lots and the unit price (ADR-0027); a cached figure is a copy that can disagree with the rows
 * that produced it — the argument ADR-0007 makes for balances and ADR-0026 for schedules.
 */
interface InvestmentRepository {
    /**
     * Watches every holding the active profile has, grouped by the account it sits in.
     * Why:    the accounts list shows a value and a return under each investment account, and it
     *         needs all of them at once. Grouping by account rather than by holding is what lets the
     *         screen render one row per account without a second query per row.
     * Result: a map keyed by account id, re-emitted whenever a holding, a price or a lot changes.
     *         An account with no holdings is **absent from the map**, not present with a zero — the
     *         convention the `cards` and `loans` maps already keep, because "not set up yet" and
     *         "worth nothing" are different things (P-03).
     * Input:  none. Output: `Flow<Map<String, List<PricedHolding>>>`.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    fun observeByAccount(): Flow<Map<String, List<PricedHolding>>>

    /**
     * Watches the holdings inside one account.
     * Why:    the holdings screen is per-account. Filtering [observeByAccount] in the ViewModel
     *         would re-render this screen whenever any *other* account changed.
     * Result: that account's holdings with their figures, name-ordered, re-emitted on every write.
     * Input:  [accountId]. Output: `Flow<List<PricedHolding>>`.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    fun observeForAccount(accountId: String): Flow<List<PricedHolding>>

    /**
     * Watches how the whole portfolio is spread, and what about that is worth mentioning.
     * Why:    allocation spans accounts, so it cannot be assembled from [observeForAccount] without
     *         the caller doing the joining — and the caller is a ViewModel, which is not allowed to
     *         know that a gold account with no holdings still counts (ARC-005). Three streams are
     *         combined here rather than two because an account's *balance* is part of the answer:
     *         a gold account the user tracks as one number has no holdings to price and would
     *         otherwise vanish from the split.
     *
     *         **What counts as the portfolio is decided here, not in the engine** (ADR-0029):
     *         investment, gold and crypto accounts that are not archived and that the user counts
     *         towards net worth. Savings balances and property are deliberately outside it — a
     *         "portfolio" that included an emergency fund would put nearly every user permanently
     *         past `RULE-CONC-15-70`'s 70% line, which turns a warning into noise.
     * Result: a [PortfolioAllocation] on every change to an account, a holding or a lot. Always a
     *         value, never null: an empty portfolio is reported as
     *         [PortfolioAllocation.unavailable] rather than as an absent result, so the screen
     *         always has something to explain (P-02).
     * Input:  none — the active profile is the scope.
     * Output: `Flow<PortfolioAllocation>`.
     * Changelog: 2026-08-28 — Created for issue 6.4 (FR-INV-002).
     */
    fun observeAllocation(): Flow<PortfolioAllocation>

    /**
     * Reads one holding's stored facts, for the editor.
     * Result: the holding, or `null` when it does not exist or was deleted.
     * Input:  [id]. Output: `Result<InvestmentHolding?, AppError>`.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    suspend fun find(id: String): Result<InvestmentHolding?, AppError>

    /**
     * Reads one holding's lots, for the editor.
     * Result: the lots, oldest first; empty when the holding has none yet.
     * Input:  [holdingId]. Output: `Result<List<InvestmentLot>, AppError>`.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    suspend fun lotsOf(holdingId: String): Result<List<InvestmentLot>, AppError>

    /**
     * Creates a holding, or updates the one [id] names.
     * Why:    a draft rather than an [InvestmentHolding], the pattern [AccountDraft] set: the domain
     *         model requires a real id, because a holding without identity is not a holding — so
     *         "the user has filled in a form but nothing has been written yet" needs its own type
     *         rather than a model with a blank field waved through. The id is minted here from the
     *         injected [IdGenerator], never `UUID.randomUUID()`, so the write is reproducible under
     *         test (P-08).
     * Result: [Ok] with the id written, [Err] with [AppError.NotFound] when the account is gone, or
     *         `Validation("account.notInvestable")` when the account cannot hold instruments.
     * Input:  [draft] — what the user entered; [id] — `null` to create, otherwise the row to edit.
     * Output: `Result<String, AppError>` — the id written.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    suspend fun saveHolding(
        draft: HoldingDraft,
        id: String? = null,
    ): Result<String, AppError>

    /**
     * Creates a lot, or updates the one [id] names.
     * Result: [Ok] with the id written, or [AppError.NotFound] when its holding does not exist.
     * Input:  [draft] — what the user entered; [id] — `null` to create, otherwise the row to edit.
     * Output: `Result<String, AppError>`.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    suspend fun saveLot(
        draft: LotDraft,
        id: String? = null,
    ): Result<String, AppError>

    /**
     * Soft-deletes a holding and every lot beneath it.
     * Why:    both, in one call, because a holding whose lots stayed live would leave the engine
     *         summing cash flows for something the user removed. There is no foreign key to cascade
     *         through — this schema has none by convention — so the cascade is written out here.
     * Result: [Ok] once both are tombstoned; nothing is erased (DB-003).
     * Input:  [id]. Output: `Result<Unit, AppError>`.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    suspend fun deleteHolding(id: String): Result<Unit, AppError>

    /**
     * Soft-deletes one lot.
     * Result: [Ok] once tombstoned. Input: [id]. Output: `Result<Unit, AppError>`.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    suspend fun deleteLot(id: String): Result<Unit, AppError>

    companion object {
        /** The [IdGenerator] prefix for holding ids, so a database dump reads as itself. */
        const val HOLDING_ID_PREFIX = "holding"

        /** The [IdGenerator] prefix for lot ids. */
        const val LOT_ID_PREFIX = "lot"
    }
}

/**
 * What the user entered for a holding, before it has an identity (issue 6.3).
 *
 * Why:  [InvestmentHolding] requires a non-blank id, because a holding without identity is not a
 *       holding — and a form the user is still filling in has no id yet. Rather than weaken that
 *       invariant so an unsaved holding can borrow the type, the unsaved state gets its own, exactly
 *       as [AccountDraft] does for accounts.
 * What: every field the user supplies; the id, the profile and the timestamps are the repository's.
 * Result: the argument [InvestmentRepository.saveHolding] takes.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * @property accountId the account it will sit in.
 * @property name the user's label for it.
 * @property assetClass what kind of thing it is; the editor defaults this from the account's type
 *   via `AssetClass.defaultFor`.
 * @property unitPrice paise per unit as last observed, or `null` when not yet priced (P-03).
 * @property pricedOnIsoDate the day [unitPrice] was observed; both-or-neither with it.
 */
data class HoldingDraft(
    val accountId: String,
    val name: String,
    val assetClass: AssetClass,
    val unitPrice: Money? = null,
    val pricedOnIsoDate: String? = null,
    /** The instrument a market-data proxy resolves, or `null` to keep pricing this by hand (6.5). */
    val priceKey: PriceKey? = null,
)

/**
 * What the user entered for a lot, before it has an identity (issue 6.3).
 *
 * Why:  the reason [HoldingDraft] exists, applied to lots.
 * What: the movement's holding, kind, day, units and cash.
 * Result: the argument [InvestmentRepository.saveLot] takes.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * @property holdingId the holding the movement belongs to.
 * @property kind what it was; the only source of its cash direction.
 * @property transactedOnIsoDate the day the money moved (TIM-002).
 * @property quantity units moved, as a magnitude; zero for an income lot.
 * @property amount cash moved in paise, as a magnitude, charges included (MNY-001).
 */
data class LotDraft(
    val holdingId: String,
    val kind: LotKind,
    val transactedOnIsoDate: String,
    val quantity: Quantity,
    val amount: Money,
)

/**
 * The Room-backed [InvestmentRepository].
 * Why:    takes the whole [database] because a save spans `account`, `investment_holding` and
 *         `investment_lot`; and takes the [engine] rather than constructing one so the figure and
 *         the code that produced it stay assembled in the DI graph (ARC-003, P-03).
 * Result: the implementation injected into the accounts screen.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 *
 * Input:  [database]; [engine]; [clock] — stamps and provenance only, never the arithmetic
 *         (TIM-001); [ids] — holdings and lots mint their own keys, unlike a loan; [dispatchers];
 *         [activeProfileId] — so the demo profile gets its own holdings.
 * Output: a working repository.
 */
internal class RoomInvestmentRepository(
    private val database: CfoDatabase,
    private val engine: InvestmentEngine,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : InvestmentRepository {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeByAccount(): Flow<Map<String, List<PricedHolding>>> =
        activeProfileId
            .flatMapLatest { profileId ->
                // Both streams, combined, because a lot edited under one holding changes that
                // holding's figures and nothing else's. Two separate subscriptions per holding would
                // be one query per row on a screen that already has a row per account.
                combine(
                    database.investmentHoldingDao().observeForProfile(profileId),
                    database.investmentLotDao().observeForProfile(profileId),
                ) { holdings, lots ->
                    price(holdings, lots).groupBy { it.performance.accountId }
                }
            }.flowOn(dispatchers.io)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeForAccount(accountId: String): Flow<List<PricedHolding>> =
        activeProfileId
            .flatMapLatest { profileId ->
                // The profile's lots, not the account's: there is no "lots of an account" query and
                // adding one would mean a join, where [price] already groups by holding id and
                // ignores whatever does not belong to the holdings it was handed.
                combine(
                    database.investmentHoldingDao().observeForAccount(accountId),
                    database.investmentLotDao().observeForProfile(profileId),
                ) { holdings, lots ->
                    price(holdings, lots)
                }
            }.flowOn(dispatchers.io)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAllocation(): Flow<PortfolioAllocation> =
        activeProfileId
            .flatMapLatest { profileId ->
                // Read once, outside the combine, so every re-emission of this flow describes the
                // same day. Reading it inside would let a balance computed just before midnight sit
                // beside one computed just after (TIM-001).
                val asOfIsoDate = clock.today().toString()
                combine(
                    database.accountDao().observeWithBalances(profileId, includeArchived = false, asOfIsoDate),
                    database.investmentHoldingDao().observeForProfile(profileId),
                    database.investmentLotDao().observeForProfile(profileId),
                ) { accounts, holdings, lots ->
                    val priced = price(holdings, lots).map { it.performance }
                    val input = AllocationInput(positions(accounts, priced), clock.nowUtcMillis())
                    // The engine is total over this input — it answers with a reason rather than an
                    // error — so an Err here would be a programming mistake, not a user state.
                    (engine.allocation(input) as Ok).value
                }
            }.flowOn(dispatchers.io)

    override suspend fun find(id: String): Result<InvestmentHolding?, AppError> =
        withContext(dispatchers.io) {
            Ok(database.investmentHoldingDao().find(id)?.toHolding())
        }

    override suspend fun lotsOf(holdingId: String): Result<List<InvestmentLot>, AppError> =
        withContext(dispatchers.io) {
            Ok(database.investmentLotDao().observeForHolding(holdingId).first().mapNotNull { it.toLot() })
        }

    override suspend fun saveHolding(
        draft: HoldingDraft,
        id: String?,
    ): Result<String, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            val account = database.accountDao().findWithBalance(draft.accountId, clock.today().toString())
            val type = account?.let { AccountType.fromStored(it.account.type) }
            when {
                account == null -> Err(AppError.NotFound)
                type !in INVESTABLE -> Err(AppError.Validation("account.notInvestable"))
                else -> {
                    val now = clock.nowUtcMillis()
                    val rowId = id ?: ids.newId(InvestmentRepository.HOLDING_ID_PREFIX)
                    val existing = database.investmentHoldingDao().find(rowId)
                    database.investmentHoldingDao().upsert(
                        InvestmentHoldingEntity(
                            id = rowId,
                            profileId = profileId,
                            accountId = draft.accountId,
                            name = draft.name,
                            assetClass = draft.assetClass.storedValue,
                            unitPriceMinor = draft.unitPrice?.minor,
                            pricedOnIsoDate = draft.pricedOnIsoDate,
                            priceKey = draft.priceKey?.value,
                            // Cleared whenever the price itself changed. A number the user just
                            // typed must not inherit the provenance of one that was fetched, or the
                            // screen would say "fetched an hour ago" about their own typing.
                            priceFetchedAtUtcMillis =
                                existing?.priceFetchedAtUtcMillis
                                    ?.takeIf { existing.unitPriceMinor == draft.unitPrice?.minor },
                            // Preserved across an edit, so "when did I start tracking this?" stays
                            // answerable — as every other row keeps its created stamp.
                            createdAtUtcMillis = existing?.createdAtUtcMillis ?: now,
                            updatedAtUtcMillis = now,
                        ),
                    )
                    Ok(rowId)
                }
            }
        }

    override suspend fun saveLot(
        draft: LotDraft,
        id: String?,
    ): Result<String, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            val holding = database.investmentHoldingDao().find(draft.holdingId)
            if (holding == null) {
                Err(AppError.NotFound)
            } else {
                val now = clock.nowUtcMillis()
                val rowId = id ?: ids.newId(InvestmentRepository.LOT_ID_PREFIX)
                val existing = database.investmentLotDao().findRow(rowId)
                database.investmentLotDao().upsert(
                    InvestmentLotEntity(
                        id = rowId,
                        profileId = profileId,
                        holdingId = draft.holdingId,
                        kind = draft.kind.storedValue,
                        transactedOnIsoDate = draft.transactedOnIsoDate,
                        quantityNano = draft.quantity.nano,
                        amountMinor = draft.amount.minor,
                        createdAtUtcMillis = existing?.createdAtUtcMillis ?: now,
                        updatedAtUtcMillis = now,
                    ),
                )
                Ok(rowId)
            }
        }

    override suspend fun deleteHolding(id: String): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            val now = clock.nowUtcMillis()
            database.investmentLotDao().softDeleteForHolding(id, now)
            database.investmentHoldingDao().softDelete(id, now)
            Ok(Unit)
        }

    override suspend fun deleteLot(id: String): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            database.investmentLotDao().softDelete(id, clock.nowUtcMillis())
            Ok(Unit)
        }

    /**
     * Runs every holding through the engine with the lots that belong to it.
     * Why:    one pass, one grouping. Asking the engine per holding with a filtered list built
     *         inside the loop would be quadratic in the number of lots, which is fine for ten
     *         holdings and not for a portfolio somebody has been adding to for a decade.
     * Result: one [HoldingPerformance] per holding, in the order the DAO returned them.
     * Input:  [holdings]; [lots] — a superset, grouped here by holding id.
     * Output: the list.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    private fun price(
        holdings: List<InvestmentHoldingEntity>,
        lots: List<InvestmentLotEntity>,
    ): List<PricedHolding> {
        val now = clock.nowUtcMillis()
        // Read once for the whole batch, so every holding on one screen is judged against the same
        // day. Reading it per row would let two prices dated identically disagree about their age
        // across a midnight boundary (TIM-001).
        val today = clock.today().toString()
        val byHolding = lots.groupBy { it.holdingId }
        return holdings.mapNotNull { entity ->
            val holding = entity.toHolding() ?: return@mapNotNull null
            val its = byHolding[entity.id].orEmpty().mapNotNull { it.toLot() }
            val performance = (engine.holding(HoldingInput(holding, its, now)) as? Ok)?.value
            performance?.let { PricedHolding(it, freshness(engine, holding, today, now)) }
        }
    }
}

/**
 * The account types an instrument can hang off, and the whole of the portfolio (issue 6.4).
 *
 * `PROPERTY` and `VEHICLE` are deliberately absent: they are valued, not lot-tracked. This comment
 * used to add "and issue 6.4 counts them through their account balance"; issue 6.4 decided
 * otherwise and does not (ADR-0029). A portfolio is the money the user is investing, not everything
 * they own — counting a house would put almost every user permanently past `RULE-CONC-15-70`'s 70%
 * single-class line, which turns a warning into noise.
 *
 * File-scope rather than a companion member since issue 6.4, because [positions] reads it too and
 * that had to move out of the class: `RoomInvestmentRepository` was one function past detekt's
 * ceiling, and neither helper touched `this`.
 */
private val INVESTABLE = setOf(AccountType.INVESTMENT, AccountType.GOLD, AccountType.CRYPTO)

/**
 * Turns a stored holding row into the domain model.
 * Why:    a row written by a newer build can carry an `asset_class` this one does not know. Skipping
 *         it — the `null` the caller drops — is the forward-compatibility contract
 *         `AccountType.fromStored` already keeps, and better than crashing the list.
 * Result: the model, or `null` when the class is unrecognised.
 * Input:  none (receiver). Output: [InvestmentHolding]?.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
private fun InvestmentHoldingEntity.toHolding(): InvestmentHolding? {
    val assetClass = AssetClass.fromStored(assetClass) ?: return null
    return InvestmentHolding(
        id = id,
        accountId = accountId,
        name = name,
        assetClass = assetClass,
        unitPrice = unitPriceMinor?.let { Money(it) },
        pricedOnIsoDate = pricedOnIsoDate,
        // An unparseable key is dropped rather than defaulted, the same forward-compatibility
        // contract the asset class keeps: a row written by a newer build must not take this one down.
        priceKey = priceKey?.let { key -> runCatching { PriceKey(key) }.getOrNull() },
        priceFetchedAtUtcMillis = priceFetchedAtUtcMillis,
    )
}

/**
 * Turns a stored lot row into the domain model.
 * Why:    an unrecognised `kind` is **dropped, never defaulted**. Falling back to [LotKind.BUY]
 *         would invent a purchase the user never made and quietly change their cost basis and their
 *         return; skipping the row leaves both figures visibly short instead, which is the honest
 *         failure and the same forward-compatibility contract `AccountType.fromStored` keeps.
 * Result: the model, or `null` when the kind is one this build does not know.
 * Input:  none (receiver). Output: [InvestmentLot]?.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
private fun InvestmentLotEntity.toLot(): InvestmentLot? {
    val lotKind = LotKind.fromStored(kind) ?: return null
    return InvestmentLot(
        id = id,
        holdingId = holdingId,
        kind = lotKind,
        transactedOnIsoDate = transactedOnIsoDate,
        quantity = Quantity(quantityNano),
        amount = Money(amountMinor),
    )
}

/**
 * Decides what the portfolio is made of (issue 6.4; ADR-0029).
 * Why:    two kinds of thing count, and conflating them is the bug this guards. An account with
 *         holdings contributes **its holdings** — a broker account is not one position, it is
 *         however many funds are inside it, and `RULE-CONC-15-70`'s single-holding test is
 *         meaningless otherwise. An account without holdings contributes **itself**, valued at
 *         its balance and classed by [AssetClass.defaultFor], because a gold account the user
 *         tracks as one number is still gold and dropping it would understate the class the
 *         rulebook caps.
 * Result: one position per holding, or one per un-held account, and nothing for an account that
 *         holds nothing at all — an empty account is not an unpriced one, and counting it as
 *         unpriced would report missing data the user has not actually got.
 * Input:  [accounts] — the profile's live accounts with balances; [performances] — every
 *         holding, already priced by the engine.
 * Output: the positions to allocate over.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 */
private fun positions(
    accounts: List<AccountWithBalance>,
    performances: List<HoldingPerformance>,
): List<PortfolioPosition> {
    val byAccount = performances.groupBy { it.accountId }
    return accounts
        .mapNotNull { it.toAccount() }
        .filter { it.type in INVESTABLE && it.includeInNetWorth }
        .flatMap { account ->
            val holdings = byAccount[account.id].orEmpty()
            if (holdings.isNotEmpty()) {
                holdings.map { holding ->
                    PortfolioPosition(
                        holdingId = holding.holdingId,
                        accountId = account.id,
                        name = holding.name,
                        assetClass = holding.assetClass,
                        value = holding.currentValue,
                    )
                }
            } else {
                accountAsPosition(account)
            }
        }
}

/**
 * Counts an account that holds no instruments as one position (issue 6.4).
 * Why:    split out of [positions] to keep it under the 40-line limit (§21.6), and because the
 *         two exclusions here are worth stating on their own. A balance at or below zero
 *         contributes nothing and is dropped rather than carried as unpriced — reporting "one
 *         holding has no price" about an empty account would send the user looking for data
 *         that does not exist. A type with no asset class is dropped for the reason
 *         `AssetClass.defaultFor` gives: a debt filed under `OTHER` would understate every
 *         other class's share.
 * Result: a single-element list, or an empty one.
 * Input:  [account] — an investable account with no holdings.
 * Output: the position, if there is one.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 */
private fun accountAsPosition(account: Account): List<PortfolioPosition> {
    val assetClass = AssetClass.defaultFor(account.type) ?: return emptyList()
    if (account.balance <= Money.ZERO) return emptyList()
    return listOf(
        PortfolioPosition(
            holdingId = null,
            accountId = account.id,
            name = account.name,
            assetClass = assetClass,
            value = account.balance,
        ),
    )
}

/**
 * Asks the engine how old this holding's price is.
 * Why:    the clock lives here and not in the engine (TIM-001), so today is resolved once by
 *         the caller and handed down. The engine is total over this input — "never priced" is a
 *         verdict — so an `Err` would be a programming mistake rather than a user state, and
 *         the `as Ok` says so.
 * Result: the verdict the screen renders beneath the value.
 *
 * File scope rather than a member because it touches no instance state, and
 * `RoomInvestmentRepository` sits exactly on detekt's eleven-function ceiling — the same pressure
 * that moved `positions` and `accountAsPosition` out in issue 6.4.
 * Input:  [engine]; [holding] — for its dates and class; [today]; [now]. Output: [PriceFreshness].
 * Changelog: 2026-08-29 — Created for issue 6.5.
 */
private fun freshness(
    engine: InvestmentEngine,
    holding: InvestmentHolding,
    today: String,
    now: Long,
): PriceFreshness =
    (
        engine.priceFreshness(
            PriceFreshnessInput(
                assetClass = holding.assetClass,
                pricedOnIsoDate = holding.pricedOnIsoDate,
                fetchedAtUtcMillis = holding.priceFetchedAtUtcMillis,
                todayIsoDate = today,
                nowUtcMillis = now,
            ),
        ) as Ok
    ).value
