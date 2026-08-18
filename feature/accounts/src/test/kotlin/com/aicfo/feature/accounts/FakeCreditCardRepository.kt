package com.aicfo.feature.accounts

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.CreditCard
import com.aicfo.data.repository.CardAlertForAccount
import com.aicfo.data.repository.CreditCardRepository
import com.aicfo.domain.engines.card.CardAlert
import com.aicfo.domain.engines.card.CardStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An in-memory [CreditCardRepository] for this module's tests (issue 6.1).
 *
 * Why:  the accounts ViewModel and the editor both took a second repository at 6.1, and every test
 *       in this module needs *something* to pass for it — most of them do not care what. A shared
 *       fake means the seven tests that are about archiving and reconciliation stay about archiving
 *       and reconciliation rather than growing a card stub each.
 *
 *       **Writes are visible to reads**, unlike a stub that returns `Ok(Unit)` and forgets: the
 *       editor's card path is a round trip — it saves terms and a later load must show them — and a
 *       fake that dropped the write would let a ViewModel that never saved pass every assertion.
 * What: a `MutableStateFlow` of statuses the test sets, and a map of saved terms.
 * Result: the card dependency is satisfiable without Room.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * Input:  [initialStatuses] — what `observeCardStatuses` starts with. Output: a controllable fake.
 */
internal class FakeCreditCardRepository(
    initialStatuses: Map<String, CardStatus> = emptyMap(),
) : CreditCardRepository {
    private val statuses = MutableStateFlow(initialStatuses)

    /** Every card saved, keyed by account id, so a test can assert the write happened. */
    val saved: MutableMap<String, CreditCard> = mutableMapOf()

    override fun observeCardStatuses(): Flow<Map<String, CardStatus>> = statuses

    override suspend fun find(accountId: String): Result<CreditCard?, AppError> = Ok(saved[accountId])

    override suspend fun save(card: CreditCard): Result<Unit, AppError> {
        saved[card.accountId] = card
        return Ok(Unit)
    }

    override suspend fun pendingAlerts(): Result<List<CardAlertForAccount>, AppError> = Ok(emptyList())

    override suspend fun markNotified(alert: CardAlert): Result<Boolean, AppError> = Ok(true)
}
