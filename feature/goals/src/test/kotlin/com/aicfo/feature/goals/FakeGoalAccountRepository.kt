package com.aicfo.feature.goals

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Account
import com.aicfo.core.model.Money
import com.aicfo.core.model.Reconciliation
import com.aicfo.data.repository.AccountDraft
import com.aicfo.data.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A read-only [AccountRepository] for the goals feature tests (issue 7.4).
 *
 * Why:  the funding-account chooser needs accounts to offer, and nothing in `:feature:goals` writes
 *       one. Every write path throws rather than returning something plausible — the reasoning
 *       `:feature:transactions`' own fake records: a test that starts to depend on a write here
 *       should fail loudly instead of quietly proving something `:feature:accounts` owns.
 * What: a mutable list behind `observeAccounts`.
 * Result: the chooser's states — none, one, several, all already dedicated — are all reachable.
 * Changelog: 2026-09-06 — Created for issue 7.4.
 */
internal class FakeGoalAccountRepository : AccountRepository {
    private val accounts = MutableStateFlow<List<Account>>(emptyList())

    /** Seeds the chooser. Input: [seed] — the accounts to offer. Output: none. */
    fun setAccounts(vararg seed: Account) {
        accounts.value = seed.toList()
    }

    override fun observeAccounts(includeArchived: Boolean): Flow<List<Account>> =
        accounts.map { list -> list.filter { includeArchived || !it.isArchived } }

    override fun observeAccounts(
        profileId: String,
        includeArchived: Boolean,
    ): Flow<List<Account>> = observeAccounts(includeArchived)

    override suspend fun find(id: String): Result<Account, AppError> =
        accounts.value.firstOrNull { it.id == id }?.let { Ok(it) } ?: Err(AppError.NotFound)

    override suspend fun create(draft: AccountDraft): Result<Account, AppError> = unsupported()

    override suspend fun update(
        id: String,
        draft: AccountDraft,
    ): Result<Account, AppError> = unsupported()

    override suspend fun setArchived(
        id: String,
        archived: Boolean,
    ): Result<Unit, AppError> = unsupported()

    override suspend fun delete(id: String): Result<Unit, AppError> = unsupported()

    override suspend fun reconcile(
        accountId: String,
        statementBalance: Money,
    ): Result<Reconciliation, AppError> = unsupported()

    override suspend fun refreshCachedBalances(): Result<Int, AppError> = unsupported()

    /** Result: never returns. Input: none. Output: nothing — it throws. */
    private fun unsupported(): Nothing =
        error("`:feature:goals` does not write accounts; a test that needs one is testing the wrong module")
}
