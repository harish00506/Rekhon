package com.aicfo.feature.accounts

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Loan
import com.aicfo.data.repository.LoanRepository
import com.aicfo.domain.engines.loan.AmortisationRow
import com.aicfo.domain.engines.loan.AmortisationSchedule
import com.aicfo.domain.engines.loan.LoanEngineFactory
import com.aicfo.domain.engines.loan.LoanTermsInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An in-memory [LoanRepository] for this module's tests (issue 6.2).
 *
 * Why:  both ViewModels took a third repository at 6.2, and most tests in this module do not care
 *       what it returns — a shared fake keeps the ten tests about archiving and reconciliation
 *       about archiving and reconciliation, exactly as [FakeCreditCardRepository] does for 6.1.
 *
 *       **Writes are visible to reads**, and [save] runs the terms through the **real** engine
 *       before accepting them. Both matter: the editor's loan path is a round trip, and a fake that
 *       accepted anything would let a ViewModel that skipped validation pass every assertion — the
 *       refusal of terms that cannot amortise is behaviour under test, not an implementation detail
 *       of Room.
 * What: a `MutableStateFlow` of next instalments the test sets, and a map of saved terms.
 * Result: the loan dependency is satisfiable without Room.
 * Changelog: 2026-08-20 — Created for issue 6.2.
 *
 * Input:  [initialInstalments] — what `observeNextInstalments` starts with. Output: a controllable
 *         fake.
 */
internal class FakeLoanRepository(
    initialInstalments: Map<String, AmortisationRow> = emptyMap(),
) : LoanRepository {
    private val instalments = MutableStateFlow(initialInstalments)
    private val engine = LoanEngineFactory.create()

    /** Every loan saved, keyed by account id, so a test can assert the write happened. */
    val saved: MutableMap<String, Loan> = mutableMapOf()

    override fun observeNextInstalments(): Flow<Map<String, AmortisationRow>> = instalments

    override suspend fun find(accountId: String): Result<Loan?, AppError> = Ok(saved[accountId])

    override suspend fun save(loan: Loan): Result<Unit, AppError> {
        // The real repository refuses terms the engine cannot amortise before writing them, and a
        // test that could save an impossible loan here would prove the wrong thing.
        val emi = engine.emi(LoanTermsInput(loan))
        if (emi is Err) return emi
        saved[loan.accountId] = loan
        return Ok(Unit)
    }

    override suspend fun schedule(accountId: String): Result<AmortisationSchedule, AppError> {
        val loan = saved[accountId] ?: return Err(AppError.NotFound)
        return engine.schedule(LoanTermsInput(loan))
    }
}
