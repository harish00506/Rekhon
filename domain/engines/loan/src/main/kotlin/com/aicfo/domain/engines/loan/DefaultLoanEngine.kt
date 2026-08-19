package com.aicfo.domain.engines.loan

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Loan
import com.aicfo.core.model.sum

/**
 * The production [LoanEngine] (issue 6.2; FR-ACC-003, §5.8).
 *
 * Why:  two small pure calculators, and this class exists to name them in one place, attach
 *       provenance, and hold the one decision neither of them can make: which instalment to use.
 *       `Loan`'s `init` has already refused every unusable set of terms, so exactly one failure is
 *       left — an instalment too small to cover the first month's interest — and it is checked once,
 *       here, rather than in each calculator.
 * What: delegates to [Emi] and [Amortisation].
 * Result: `Ok` unless the loan cannot amortise or an instalment is asked for that does not exist.
 * Changelog: 2026-08-19 — Created for issue 6.2.
 *
 * `internal` — callers hold the [LoanEngine] interface, built by [LoanEngineFactory] (ARC-003).
 */
internal class DefaultLoanEngine : LoanEngine {
    override fun emi(input: LoanTermsInput): Result<LoanEmi, AppError> = resolveEmi(input.loan, input.nowUtcMillis)

    /**
     * Builds the whole schedule and totals what the loan costs.
     *
     * Result: `Ok(AmortisationSchedule)`, or the failure [resolveEmi] found. `totalInterest` is
     *         summed from the rows rather than computed as `emi × n − principal`, which would be
     *         wrong on two counts: the last instalment is not the EMI, and a loan cleared early has
     *         fewer instalments than its tenure.
     * Input:  [input]. Output: `Result<AmortisationSchedule, AppError>`.
     */
    override fun schedule(input: LoanTermsInput): Result<AmortisationSchedule, AppError> {
        val emi = resolveEmi(input.loan, input.nowUtcMillis)
        if (emi is Err) return emi
        val instalment = (emi as Ok).value
        val rows = Amortisation.rows(input.loan, instalment.amount)
        return Ok(
            AmortisationSchedule(
                rows = rows,
                emi = instalment,
                totalInterest = rows.map { it.interest }.sum(),
                provenance = provenance(input.nowUtcMillis),
            ),
        )
    }

    /**
     * Reads one instalment without keeping the ones before it.
     *
     * Result: `Ok(AmortisationRow)`; `Err(AppError.Validation)` for a number outside the tenure, and
     *         `Err(AppError.NotFound)` for one inside the tenure that the loan never reaches because
     *         a lender-stated EMI cleared it early. The two are different answers — the first is a
     *         caller asking for something that cannot exist, the second is a loan that finished.
     * Input:  [input]. Output: `Result<AmortisationRow, AppError>`.
     */
    override fun instalment(input: LoanInstalmentInput): Result<AmortisationRow, AppError> {
        if (input.number !in 1..input.loan.tenureMonths) return Err(AppError.Validation("number"))
        val emi = resolveEmi(input.loan, input.nowUtcMillis)
        if (emi is Err) return emi
        val row = Amortisation.row(input.loan, (emi as Ok).value.amount, input.number)
        return if (row == null) Err(AppError.NotFound) else Ok(row)
    }

    /**
     * Decides which instalment the schedule is built from.
     * Why:    the user's lender wins. Banks round the closed form their own way, and a schedule that
     *         disagrees with the borrower's own statement by ₹2 a month is a schedule they stop
     *         trusting — so an entered EMI replaces the derived one outright rather than being
     *         averaged with it or warned about. The derived figure is still carried on [LoanEmi] so
     *         the editor can show both (P-02).
     * Result: `Ok(LoanEmi)`, or `Err(AppError.Validation)` naming the field at fault — the entered
     *         EMI when there is one, the rate when the derived figure is the one that cannot
     *         amortise (which only a rate high enough to outrun its own formula produces).
     * Input:  [loan]; [nowUtcMillis] — for provenance only. Output: `Result<LoanEmi, AppError>`.
     */
    private fun resolveEmi(
        loan: Loan,
        nowUtcMillis: Long,
    ): Result<LoanEmi, AppError> {
        val derived = Emi.derive(loan)
        val chosen = loan.emiOverride ?: derived
        if (!Emi.amortises(loan, chosen)) {
            return Err(AppError.Validation(if (loan.emiOverride == null) "annualRateBps" else "emiOverride"))
        }
        return Ok(
            LoanEmi(
                amount = chosen,
                basis = if (loan.emiOverride == null) EmiBasis.DERIVED else EmiBasis.LENDER_STATED,
                derived = derived,
                provenance = provenance(nowUtcMillis),
            ),
        )
    }

    /**
     * Stamps a result with where it came from (AI-ARC-003, AI-ARC-006).
     * Why:    every engine result carries one, and this engine's is the same three fields every
     *         time. **No `evidence`**: there is no rulebook row behind amortisation, and citing one
     *         would be a false claim about the source of the number — see [LoanEngine]'s note.
     * Result: the provenance. Input: [nowUtcMillis]. Output: [EngineProvenance].
     */
    private fun provenance(nowUtcMillis: Long): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = nowUtcMillis,
        )

    private companion object {
        const val ENGINE_ID = "loan-amortiser"

        /**
         * Bumped whenever the formula or its pinned precision changes (AI-ARC-006). `Emi.PRECISION`
         * is part of this contract: altering it changes every historical answer.
         */
        const val ENGINE_VERSION = "1.0"
    }
}
