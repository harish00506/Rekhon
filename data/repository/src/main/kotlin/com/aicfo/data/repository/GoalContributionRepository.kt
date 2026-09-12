package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.GoalContributionEntity
import com.aicfo.core.database.entity.GoalFundingAccountEntity
import com.aicfo.core.model.Account
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The two link tables, and the only thing allowed to read them (issue 7.4; §15, FR-GOAL-004,
 * FR-GOAL-002, ARC-005).
 *
 * Why:  §15 says goal progress is transaction-evidenced, and until this issue `goal.saved_minor` was
 *       a number the user typed. Linking is how the evidence arrives. It is a repository of its own
 *       rather than five more methods on [GoalRepository] because it owns different tables — ARC-005
 *       asks that exactly one class touch a DAO, not that one class touch every DAO — and because
 *       the two concerns read very differently: [GoalRepository] answers "how are my goals doing",
 *       this one answers "what is this goal made of".
 * What: watch one goal's links, offer the movements that could become links, and make or reverse
 *       one.
 * Result: a ViewModel sees domain values and never a Room type.
 * Changelog: 2026-09-06 — Created for issue 7.4.
 *
 * **Nothing here stores an amount.** A contribution *is* the linked transaction, so its value is
 * read from the ledger at query time — see [GoalContribution] and
 * `GoalDao.observeEvidenced`, which is where the two link kinds are summed.
 */
interface GoalContributionRepository {
    /**
     * Watches the movements linked to one goal.
     * Why:    the goal detail screen lists them, newest link first, so the user can see what their
     *         progress is actually made of (P-02) and take any of it back.
     * Result: emits on every link and unlink. Empty for a goal nobody has linked anything to, which
     *         is a state and not an error.
     * Input:  [goalId]. Output: `Flow<List<GoalContribution>>`.
     */
    fun observeContributions(goalId: String): Flow<List<GoalContribution>>

    /**
     * Watches the accounts dedicated to one goal (FR-GOAL-002).
     * Result: emits on every dedication and release; empty when none. Input: [goalId].
     * Output: `Flow<List<GoalFundingAccount>>`.
     */
    fun observeFundingAccounts(goalId: String): Flow<List<GoalFundingAccount>>

    /**
     * Watches the movements the user could link to one goal.
     * Why:    a picker over the recent ledger. Already-linked movements are excluded, because
     *         offering one twice invites the user to try an action that would change nothing.
     * Result: newest first, at most [LINK_PICKER_LIMIT] rows, future-dated and soft-deleted rows
     *         already excluded by [TransactionRepository.observeRecent].
     *
     * **A transfer is offered once, never as both legs.** The pair moves one sum of money between
     * two of the user's own accounts (ADR-0008), so linking both would count it twice; the leg kept
     * is the **inflow**, because that is the side that arrives in the pot the goal is saving into.
     *
     * Input:  [goalId] — whose existing links to exclude. Output: `Flow<List<Transaction>>`.
     */
    fun observeLinkable(goalId: String): Flow<List<Transaction>>

    /**
     * Links one movement to one goal.
     * Result: `Ok(Unit)`, including when the pair is already linked — linking twice is not an error,
     *         and a previously unlinked pair is **revived** rather than duplicated.
     * Input:  [goalId]; [transactionId]. Output: `Result<Unit, AppError>`.
     */
    suspend fun link(
        goalId: String,
        transactionId: String,
    ): Result<Unit, AppError>

    /**
     * Takes one movement back off a goal.
     * Why:    the acceptance criterion asks for reversal **and** for the provenance to survive it, so
     *         this is a soft delete: the progress drops by exactly what it rose by, and *when this
     *         was linked* is still on the row.
     * Result: `Ok(Unit)` even when it was not linked; unlinking twice is not an error.
     * Input:  [goalId]; [transactionId]. Output: `Result<Unit, AppError>`.
     */
    suspend fun unlink(
        goalId: String,
        transactionId: String,
    ): Result<Unit, AppError>

    /**
     * Dedicates one account to one goal (FR-GOAL-002).
     *
     * Why:    the standing half of FR-GOAL-004. Everything that lands in the account counts, without
     *         the user linking each movement — including movements that have not happened yet.
     * Result: `Ok(Unit)`; an existing dedication is revived and re-dated rather than duplicated.
     * Input:  [goalId]; [accountId]; [countHistory] — false to count only from today, true to count
     *   everything the account has ever held. The choice is stored as a date rather than applied as
     *   a policy, so a reader can see which one the user picked.
     * Output: `Result<Unit, AppError>`.
     */
    suspend fun linkAccount(
        goalId: String,
        accountId: String,
        countHistory: Boolean,
    ): Result<Unit, AppError>

    /**
     * Releases one account from one goal.
     * Result: `Ok(Unit)` even when it was not dedicated. Input: [goalId]; [accountId].
     * Output: `Result<Unit, AppError>`.
     */
    suspend fun unlinkAccount(
        goalId: String,
        accountId: String,
    ): Result<Unit, AppError>

    companion object {
        /** Prefix for minted link ids, matching the convention every other table uses. */
        const val CONTRIBUTION_ID_PREFIX = "goalcontrib"

        /** Prefix for minted funding-account ids. */
        const val FUNDING_ID_PREFIX = "goalfund"

        /**
         * How many recent movements the picker offers.
         *
         * Not a financial threshold — nothing about the advice changes at the boundary. It is the
         * same bounded-by-count choice `observeRecent` documents: the full ledger stays one tap away
         * on the Transactions screen, so a bounded picker strands nothing.
         */
        const val LINK_PICKER_LIMIT = 100
    }
}

/**
 * One movement linked to a goal (issue 7.4; FR-GOAL-004).
 *
 * Why:  the detail screen shows what a goal's progress is made of, which needs the movement itself
 *       and not just its id (P-02).
 * What: the transaction, and what it contributes.
 * Result: one row of the contributions list.
 * Changelog: 2026-09-06 — Created for issue 7.4.
 *
 * @property transaction the linked movement, exactly as the ledger holds it. **Not a copy of its
 *   amount** — the value below is derived from it on every read, so an edited or deleted transaction
 *   can never leave a stale figure behind.
 * @property amount what this movement contributes: the **magnitude** of the transaction's amount.
 *   A SIP debit is stored negative and still funds the goal; the direction is what the user asserted
 *   by linking it. Dedicated funding accounts sum the *signed* amount instead, for the reason
 *   `GoalFundingAccountEntity` records.
 * @property linkedAtUtcMillis when the user made the link, kept so an unlink does not erase the fact
 *   that it happened.
 */
data class GoalContribution(
    val transaction: Transaction,
    val amount: Money,
    val linkedAtUtcMillis: Long,
)

/**
 * One account dedicated to a goal (issue 7.4; FR-GOAL-002).
 *
 * Changelog: 2026-09-06 — Created for issue 7.4.
 *
 * @property account the dedicated account, so the screen can name it rather than show an id.
 * @property linkedFromIsoDate the first day whose movements count (TIM-002).
 * @property countsWholeHistory whether [linkedFromIsoDate] is the sentinel meaning "everything this
 *   account has ever held" rather than a day the user would recognise.
 *
 *   **Found by running it.** The screen rendered the stored date, so a dedication made with
 *   "count everything in it" read back as *"counting from 0001-01-01"* — a true statement about the
 *   database and a meaningless one about the user's money. The screen must not compare a date to a
 *   magic string to work that out, so the repository that wrote the sentinel is what reports it.
 * @property linkedAtUtcMillis when the dedication was made.
 */
data class GoalFundingAccount(
    val account: Account,
    val linkedFromIsoDate: String,
    val countsWholeHistory: Boolean,
    val linkedAtUtcMillis: Long,
)

/**
 * [GoalContributionRepository] over Room (issue 7.4).
 *
 * Changelog: 2026-09-06 — Created for issue 7.4.
 */
@Suppress("LongParameterList") // Seven collaborators, each a distinct binding — as RoomGoalRepository.
internal class RoomGoalContributionRepository(
    private val database: CfoDatabase,
    private val transactions: TransactionRepository,
    private val accounts: AccountRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : GoalContributionRepository {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeContributions(goalId: String): Flow<List<GoalContribution>> =
        database.goalContributionDao().observeForGoal(goalId)
            .flatMapLatest { links ->
                // The links are the index; the ledger is the truth. Resolving through the
                // transaction rows on every emission is what keeps an edited amount from leaving a
                // stale contribution behind.
                database.transactionDao().observeByIds(links.map { it.transactionId })
                    .map { rows ->
                        val byId = rows.mapNotNull { it.toTransaction() }.associateBy { it.id }
                        links.mapNotNull { link ->
                            byId[link.transactionId]?.let { transaction ->
                                GoalContribution(
                                    transaction = transaction,
                                    amount = Money(Math.abs(transaction.amount.minor)),
                                    linkedAtUtcMillis = link.createdAtUtcMillis,
                                )
                            }
                        }
                    }
            }.flowOn(dispatchers.io)

    override fun observeFundingAccounts(goalId: String): Flow<List<GoalFundingAccount>> =
        combine(
            database.goalFundingAccountDao().observeForGoal(goalId),
            accounts.observeAccounts(includeArchived = true),
        ) { links, accountList ->
            val byId = accountList.associateBy { it.id }
            links.mapNotNull { link ->
                byId[link.accountId]?.let { account ->
                    GoalFundingAccount(
                        account = account,
                        linkedFromIsoDate = link.linkedFromIsoDate,
                        countsWholeHistory = link.linkedFromIsoDate == EPOCH_ISO_DATE,
                        linkedAtUtcMillis = link.createdAtUtcMillis,
                    )
                }
            }
        }.flowOn(dispatchers.io)

    override fun observeLinkable(goalId: String): Flow<List<Transaction>> =
        combine(
            transactions.observeRecent(GoalContributionRepository.LINK_PICKER_LIMIT),
            database.goalContributionDao().observeForGoal(goalId),
        ) { recent, linked ->
            val alreadyLinked = linked.map { it.transactionId }.toSet()
            recent.map { it.transaction }
                .filterNot { it.id in alreadyLinked }
                .filterNot { it.isRedundantTransferLeg() }
        }.flowOn(dispatchers.io)

    override suspend fun link(
        goalId: String,
        transactionId: String,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            val now = clock.nowUtcMillis()
            val existing = database.goalContributionDao().findIncludingDeleted(goalId, transactionId)
            database.goalContributionDao().upsert(
                GoalContributionEntity(
                    // Revive rather than mint: the unique index forbids a second row for the pair,
                    // and re-linking something the user once unlinked is an ordinary action, not a
                    // failure. Keeping the original id also keeps the original created stamp below.
                    id = existing?.id ?: ids.newId(GoalContributionRepository.CONTRIBUTION_ID_PREFIX),
                    profileId = profileId,
                    goalId = goalId,
                    transactionId = transactionId,
                    createdAtUtcMillis = existing?.createdAtUtcMillis ?: now,
                    updatedAtUtcMillis = now,
                    // The clearing of this is the revival.
                    deletedAtUtcMillis = null,
                ),
            )
            Ok(Unit)
        }

    override suspend fun unlink(
        goalId: String,
        transactionId: String,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            database.goalContributionDao().softDelete(goalId, transactionId, clock.nowUtcMillis())
            Ok(Unit)
        }

    override suspend fun linkAccount(
        goalId: String,
        accountId: String,
        countHistory: Boolean,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            val now = clock.nowUtcMillis()
            val existing = database.goalFundingAccountDao().findIncludingDeleted(goalId, accountId)
            database.goalFundingAccountDao().upsert(
                GoalFundingAccountEntity(
                    id = existing?.id ?: ids.newId(GoalContributionRepository.FUNDING_ID_PREFIX),
                    profileId = profileId,
                    goalId = goalId,
                    accountId = accountId,
                    // "Count everything" is stored as a date the ledger cannot predate rather than
                    // as a null meaning "no bound": one comparison in SQL, one shape to reason
                    // about, and a reader can see which choice the user made.
                    linkedFromIsoDate = if (countHistory) EPOCH_ISO_DATE else clock.today().toString(),
                    createdAtUtcMillis = existing?.createdAtUtcMillis ?: now,
                    updatedAtUtcMillis = now,
                    deletedAtUtcMillis = null,
                ),
            )
            Ok(Unit)
        }

    override suspend fun unlinkAccount(
        goalId: String,
        accountId: String,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            database.goalFundingAccountDao().softDelete(goalId, accountId, clock.nowUtcMillis())
            Ok(Unit)
        }

    /**
     * Whether this row is the leg of a transfer that the picker should not offer.
     *
     * Why:    a transfer is one movement stored as two rows (ADR-0008), and the picker reads a list
     *         that contains both. Offering both invites the user to link the same money twice.
     * What:   true for the **outflow** leg of a transfer.
     * Result: the inflow leg is the one offered, because that is the side that arrives in the pot
     *         the goal is saving into — and for a plain expense, income or adjustment, nothing is
     *         filtered at all.
     * Input:  the receiver. Output: [Boolean].
     */
    private fun Transaction.isRedundantTransferLeg(): Boolean =
        transferId != null && type == TransactionType.TRANSFER_OUT

    companion object {
        /**
         * The date "count everything in this account" stores.
         *
         * Not a threshold: it is a bound no `booked_on_iso_date` can fall below, chosen so the
         * funding-account sum needs one comparison rather than a nullable column and two branches.
         */
        const val EPOCH_ISO_DATE = "0001-01-01"
    }
}
