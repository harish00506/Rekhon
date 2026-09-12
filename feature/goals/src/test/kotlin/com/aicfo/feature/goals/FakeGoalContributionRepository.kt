package com.aicfo.feature.goals

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Account
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.data.repository.GoalContribution
import com.aicfo.data.repository.GoalContributionRepository
import com.aicfo.data.repository.GoalFundingAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [GoalContributionRepository] for the feature tests (issue 7.4).
 *
 * Why:  a fake rather than a mock, the convention this repo keeps. Unlike [FakeGoalRepository] it
 *       runs **no engine**: there is no arithmetic here to get wrong, only a set of links — the sum
 *       they produce lives in SQL and is proven by `GoalContributionRepositoryTest` against a real
 *       database, which is the only place it can be.
 * What: two mutable link sets, a fixed picker list, and a switch for the failure path.
 * Result: the ViewModel's routing is exercised without a database.
 * Changelog: 2026-09-06 — Created for issue 7.4.
 */
internal class FakeGoalContributionRepository : GoalContributionRepository {
    /** The movements the picker offers. Set by a test before the ViewModel subscribes. */
    val linkable = MutableStateFlow<List<Transaction>>(emptyList())

    /** The accounts a dedication resolves against, so `observeFundingAccounts` can name them. */
    val knownAccounts = MutableStateFlow<List<Account>>(emptyList())

    private val contributions = MutableStateFlow<List<GoalContribution>>(emptyList())
    private val funding = MutableStateFlow<List<Pair<String, Boolean>>>(emptyList())

    /** Set to fail the next write, so the error path is reachable. */
    var failOnWrite: AppError? = null

    /** Every link asked for, so a test can assert what actually reached the repository. */
    val linked: MutableList<String> = mutableListOf()

    /** Every unlink asked for. */
    val unlinked: MutableList<String> = mutableListOf()

    /** Every dedication asked for, as `accountId to countHistory`. */
    val dedicated: MutableList<Pair<String, Boolean>> = mutableListOf()

    override fun observeContributions(goalId: String): Flow<List<GoalContribution>> = contributions

    override fun observeFundingAccounts(goalId: String): Flow<List<GoalFundingAccount>> =
        funding.map { links ->
            val byId = knownAccounts.value.associateBy { it.id }
            links.mapNotNull { (accountId, countHistory) ->
                byId[accountId]?.let {
                    GoalFundingAccount(
                        account = it,
                        linkedFromIsoDate = if (countHistory) EPOCH else "2026-08-30",
                        countsWholeHistory = countHistory,
                        linkedAtUtcMillis = 0L,
                    )
                }
            }
        }

    override fun observeLinkable(goalId: String): Flow<List<Transaction>> =
        linkable.map { list -> list.filterNot { it.id in contributions.value.map { c -> c.transaction.id } } }

    override suspend fun link(
        goalId: String,
        transactionId: String,
    ): Result<Unit, AppError> {
        failOnWrite?.let { return Err(it) }
        linked += transactionId
        linkable.value.firstOrNull { it.id == transactionId }?.let { transaction ->
            contributions.value =
                contributions.value.filterNot { it.transaction.id == transactionId } +
                GoalContribution(
                    transaction = transaction,
                    amount = Money(Math.abs(transaction.amount.minor)),
                    linkedAtUtcMillis = 0L,
                )
        }
        return Ok(Unit)
    }

    override suspend fun unlink(
        goalId: String,
        transactionId: String,
    ): Result<Unit, AppError> {
        failOnWrite?.let { return Err(it) }
        unlinked += transactionId
        contributions.value = contributions.value.filterNot { it.transaction.id == transactionId }
        return Ok(Unit)
    }

    override suspend fun linkAccount(
        goalId: String,
        accountId: String,
        countHistory: Boolean,
    ): Result<Unit, AppError> {
        failOnWrite?.let { return Err(it) }
        dedicated += accountId to countHistory
        funding.value = funding.value.filterNot { it.first == accountId } + (accountId to countHistory)
        return Ok(Unit)
    }

    override suspend fun unlinkAccount(
        goalId: String,
        accountId: String,
    ): Result<Unit, AppError> {
        failOnWrite?.let { return Err(it) }
        funding.value = funding.value.filterNot { it.first == accountId }
        return Ok(Unit)
    }

    private companion object {
        /** The sentinel the real repository stores for "count everything in this account". */
        const val EPOCH = "0001-01-01"
    }
}
