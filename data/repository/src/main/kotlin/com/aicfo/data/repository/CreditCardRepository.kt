package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.CardAlertEntity
import com.aicfo.core.database.entity.CreditCardEntity
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.CreditCard
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.card.CardAlert
import com.aicfo.domain.engines.card.CardAlertInput
import com.aicfo.domain.engines.card.CardEngine
import com.aicfo.domain.engines.card.CardStatus
import com.aicfo.domain.engines.card.CardStatusInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * The only class that reads or writes a credit card's terms (issue 6.1; FR-ACC-002, ARC-005).
 *
 * Why:  the card engine is pure — it takes a date and two amounts. Something has to turn the
 *       ledger's stored rows into those arguments, and this is it. Three translations happen here
 *       and nowhere else, because each of them is the kind of thing that goes wrong once and then
 *       goes wrong everywhere:
 *
 *       **The sign flip.** A card's balance is stored *negative*, because `AccountType.isLiability`
 *       says a card is money owed. Every ratio in the engine wants a magnitude. Flipping it once,
 *       here, is why no calculator downstream has to reason about a sign.
 *
 *       **The clock read.** `clock.today()` is called exactly once per read, in this class, and
 *       handed down (TIM-001). The engine has no clock and the build fails if it grows one.
 *
 *       **The claim.** `markNotified` writes *before* the notification is sent — see its own doc.
 * What: observe every card's status, save a card's terms, and the alert claim pair the worker uses.
 * Result: `CardStatus` and `CardAlert` values that the UI and the worker can use without knowing
 *       Room exists.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
interface CreditCardRepository {
    /**
     * Watches every credit card the active profile has, with its figures.
     * Why:    the accounts list draws a utilisation bar per card and the editor shows one card's
     *         detail; both want the same computed value, so there is one read rather than two.
     * Result: a map keyed by account id, re-emitted whenever a card, an account or a transaction
     *         changes. An account of type `CREDIT_CARD` with no `credit_card` row yet is **absent**
     *         from the map rather than present with zeros — the ordinary state of a card the user
     *         has not filled in, and the screen renders a prompt rather than a false 0%.
     * Input:  none. Output: `Flow<Map<String, CardStatus>>`.
     */
    fun observeCardStatuses(): Flow<Map<String, CardStatus>>

    /**
     * Reads one card's stored terms.
     * Result: `Ok(CreditCard)`, or `Ok(null)` when the account has no card detail yet — which is
     *         not an error, so the editor can open on an empty form.
     * Input:  [accountId]. Output: `Result<CreditCard?, AppError>`.
     */
    suspend fun find(accountId: String): Result<CreditCard?, AppError>

    /**
     * Writes a card's terms.
     * Why:    an upsert rather than create/update, because a card's terms are current facts about
     *         an account rather than events — there is no history here to preserve.
     * Result: `Ok(Unit)`, or `Err(Validation)` when the account is not a credit card. That check is
     *         here rather than in the UI because it is a data-integrity rule: a `credit_card` row
     *         against a savings account would give it a utilisation bar and a payment reminder.
     * Input:  [card] — the terms; its `accountId` must name an existing `CREDIT_CARD` account.
     * Output: `Result<Unit, AppError>`.
     */
    suspend fun save(card: CreditCard): Result<Unit, AppError>

    /**
     * Every alert that is due and has not yet been sent.
     * Why:    the engine answers "what is true about this card today"; this subtracts what the user
     *         has already been told, which lives in `card_alert`. Keeping the subtraction here and
     *         not in the worker means the in-app surface can show the *unfiltered* truth — a card
     *         over its limit stays over its limit whether or not a notification went out (P-02).
     * Result: `Ok(list)`, empty on an ordinary day. `Ok(emptyList())` rather than `Err` when the
     *         engine declines, so the worker does not back off for ever against a card it can never
     *         say anything about.
     * Input:  none. Output: `Result<List<CardAlert>, AppError>`.
     */
    suspend fun pendingAlerts(): Result<List<CardAlertForAccount>, AppError>

    /**
     * Claims one alert, so it is never sent twice.
     * Why:    **claim before you send, not after** — the rule `BudgetRepository.markNotified`
     *         records, and the reason `card_alert`'s unique index exists. With the order reversed, a
     *         crash between the two would re-notify on the next run, and this is the channel §17.1
     *         calls Critical: the one a user must not learn to mute. The cost of claiming first is
     *         the opposite failure — a claim written and then a post that fails — which loses one
     *         reminder rather than training the user to ignore all of them. That is the right way
     *         round to be wrong.
     * Result: `Ok(true)` when **this** call won the claim and the caller should notify; `Ok(false)`
     *         when the row already existed, whoever wrote it.
     * Input:  [alert]. Output: `Result<Boolean, AppError>`.
     */
    suspend fun markNotified(alert: CardAlert): Result<Boolean, AppError>

    companion object {
        /** Mirrors `AccountRepository.ID_PREFIX`'s convention for the claim rows. */
        const val ALERT_ID_PREFIX = "card-alert"

        /** `insertIfNew` returns this when the unique index refused the row. */
        internal const val INSERT_IGNORED = -1L
    }
}

/**
 * One pending alert, with the card's name attached (issue 6.1).
 *
 * Why:  `CardAlert` knows an account id, because the engine is pure and has never seen an account
 *       row. A notification has to say *which card*, and "HDFC Card payment due in 3 days" is the
 *       whole value of the message. Joining the name on here rather than inside the notifier keeps
 *       the notifier free of DAOs (ARC-005) — the shape `CategoryBudgetAlert` already uses for
 *       budgets.
 * Result: everything the notifier needs and nothing it does not.
 * Input:  [accountName] — the user's own name for the card; [alert] — the engine's decision.
 * Output: an immutable value.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
data class CardAlertForAccount(
    val accountName: String,
    val alert: CardAlert,
)

/**
 * The Room-backed [CreditCardRepository].
 * Why:    takes the whole [database] because one read spans `account`, `credit_card` and (through
 *         the account DAO's correlated subquery) `transactions`; and takes the [engine] rather than
 *         constructing one so the figure and the code that produced it stay assembled in the DI
 *         graph (ARC-003, P-03).
 * Result: the implementation injected into the accounts screen and the alert worker.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * Input:  [database]; [engine]; [clock] — the single clock read on this path (TIM-001); [ids];
 *         [dispatchers]; [activeProfileId] — so the demo profile gets its own cards.
 * Output: a working repository.
 */
internal class RoomCreditCardRepository(
    private val database: CfoDatabase,
    private val engine: CardEngine,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : CreditCardRepository {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCardStatuses(): Flow<Map<String, CardStatus>> =
        activeProfileId
            .flatMapLatest { profileId ->
                val today = clock.today()
                combine(
                    database.accountDao().observeWithBalances(
                        profileId,
                        includeArchived = false,
                        asOfIsoDate = today.toString(),
                    ),
                    database.creditCardDao().observeForProfile(profileId),
                ) { balances, cards ->
                    val outstandingByAccount = balances.associate { it.account.id to it.account }
                    cards.mapNotNull { entity ->
                        val account = outstandingByAccount[entity.accountId] ?: return@mapNotNull null
                        if (AccountType.fromStored(account.type) != AccountType.CREDIT_CARD) return@mapNotNull null
                        val status =
                            engine.status(
                                CardStatusInput(
                                    card = entity.toCreditCard(),
                                    today = today,
                                    outstanding = outstandingOf(account.currentBalanceMinor),
                                    nowUtcMillis = clock.nowUtcMillis(),
                                ),
                            )
                        (status as? Ok)?.value?.let { entity.accountId to it }
                    }.toMap()
                }
            }.flowOn(dispatchers.io)

    override suspend fun find(accountId: String): Result<CreditCard?, AppError> =
        withContext(dispatchers.io) {
            Ok(database.creditCardDao().find(accountId)?.toCreditCard())
        }

    override suspend fun save(card: CreditCard): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            val account = database.accountDao().findWithBalance(card.accountId, clock.today().toString())
            when {
                account == null -> Err(AppError.NotFound)
                AccountType.fromStored(account.account.type) != AccountType.CREDIT_CARD ->
                    // Not a UI concern: a card row against a savings account would give it a
                    // utilisation bar and a payment reminder, both computed from a limit it has not
                    // got. The type is the integrity rule, so the type is checked here.
                    Err(AppError.Validation("account.notACreditCard"))
                else -> {
                    val now = clock.nowUtcMillis()
                    val existing = database.creditCardDao().find(card.accountId)
                    database.creditCardDao().upsert(
                        CreditCardEntity(
                            accountId = card.accountId,
                            profileId = profileId,
                            creditLimitMinor = card.creditLimit.minor,
                            statementDay = card.statementDay,
                            dueDay = card.dueDay,
                            lastStatementMinor = card.lastStatement?.minor,
                            lastStatementIsoDate = card.lastStatementIsoDate,
                            minimumDueMinor = card.minimumDue?.minor,
                            aprBps = card.aprBps,
                            // Preserved across an edit, so "when did this card start?" stays
                            // answerable — the same reason every other row keeps its created stamp.
                            createdAtUtcMillis = existing?.createdAtUtcMillis ?: now,
                            updatedAtUtcMillis = now,
                        ),
                    )
                    Ok(Unit)
                }
            }
        }

    override suspend fun pendingAlerts(): Result<List<CardAlertForAccount>, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            val today = clock.today()
            val now = clock.nowUtcMillis()
            val alreadySent =
                database.cardAlertDao().forProfile(profileId)
                    .map { Triple(it.accountId, it.cycleStartIsoDate, it.kind) }
                    .toSet()

            val cards = database.creditCardDao().forProfile(profileId)
            val pending =
                cards.flatMap { entity ->
                    val account = database.accountDao().findWithBalance(entity.accountId, today.toString())
                    if (account == null) {
                        emptyList()
                    } else {
                        val result =
                            engine.alert(
                                CardAlertInput(
                                    card = entity.toCreditCard(),
                                    today = today,
                                    outstanding = outstandingOf(account.account.currentBalanceMinor),
                                    nowUtcMillis = now,
                                ),
                            )
                        (result as? Ok)?.value.orEmpty().map { CardAlertForAccount(account.account.name, it) }
                    }
                }.filterNot {
                    Triple(it.alert.accountId, it.alert.cycleStartIsoDate, it.alert.kind.name) in alreadySent
                }

            Ok(pending)
        }

    override suspend fun markNotified(alert: CardAlert): Result<Boolean, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            val citation = alert.provenance.evidence.first()
            val inserted =
                database.cardAlertDao().insertIfNew(
                    CardAlertEntity(
                        id = ids.newId(CreditCardRepository.ALERT_ID_PREFIX),
                        profileId = profileId,
                        accountId = alert.accountId,
                        cycleStartIsoDate = alert.cycleStartIsoDate,
                        kind = alert.kind.name,
                        ruleId = citation.ruleId,
                        ruleVersion = citation.ruleVersion,
                        notifiedAtUtcMillis = clock.nowUtcMillis(),
                    ),
                )
            Ok(inserted != CreditCardRepository.INSERT_IGNORED)
        }

    /**
     * Turns the ledger's signed balance into the magnitude the engine wants.
     * Why:    a card is a liability, so `current_balance_minor` is negative when money is owed
     *         (`AccountType.isLiability`). Every ratio downstream divides by a limit and would
     *         produce a negative utilisation from a negative balance. Flipping once here is why no
     *         calculator in the engine has a sign branch in it.
     * Result: what is owed, floored at zero — a card in credit owes nothing, it does not owe a
     *         negative amount.
     * Input:  [balanceMinor] — signed paise from the ledger. Output: [Money].
     */
    private fun outstandingOf(balanceMinor: Long): Money = (Money.ZERO - Money(balanceMinor)).coerceAtLeast(Money.ZERO)
}

/**
 * Maps a stored row to the domain model.
 * Why:    one conversion, so the nullable-means-absent rule is written once. Rooms rows are
 *         `internal` to `:core:database`; nothing above this line sees one (ARC-005).
 * Result: a [CreditCard]. Input: the receiver. Output: [CreditCard].
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
private fun CreditCardEntity.toCreditCard(): CreditCard =
    CreditCard(
        accountId = accountId,
        creditLimit = Money(creditLimitMinor),
        statementDay = statementDay,
        dueDay = dueDay,
        lastStatement = lastStatementMinor?.let(::Money),
        lastStatementIsoDate = lastStatementIsoDate,
        minimumDue = minimumDueMinor?.let(::Money),
        aprBps = aprBps,
    )
